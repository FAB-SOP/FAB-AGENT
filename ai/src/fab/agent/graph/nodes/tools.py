"""tools — 모델이 고른 도구 호출을 처리한다. 호출마다 셋 중 하나로 보낸다:
제출 처리(submit_result · submit_report) / 제안 저장(propose_* — 정합성 검사 뒤 사람 승인 대기) / 도구 실행(업무 · 지식 MCP 읽기).
도구 한도 · Harness 도구의 모드 제한은 고르기 전에 본다.
"""

from __future__ import annotations

import re
import uuid
from typing import Any

from langchain_core.messages import AIMessage, AnyMessage, ToolMessage

from fab.agent.evidence import looked_up_text, tool_results
from fab.agent.graph.state import AgentState, current_job, record_evidence, runtime
from fab.agent.harness import autofix, context, guard, incident_rules, limits
from fab.agent.harness.retry import TransientJobError
from fab.agent import purpose, question_kind
from fab.agent.purpose import may_propose
from fab.agent.tools import HARNESS_NAMES, PROPOSALS, ToolCallError
from fab.agent.answer import CLAIM_ERROR, missing_statements, validate_answer
from fab.agent import doc_citation
from fab.agent.work import validate_work_suggestions


class _Turn:
    """도구 호출 한 묶음의 누적 — 도구 메시지 · 새 근거 · 데이터 버전 · 상태 갱신"""

    def __init__(self, state: AgentState, config):
        self.state, self.config, self.rt = state, config, runtime(config)
        self.counters = dict(state["counters"])
        self.counters["toolCalls"] = dict(self.counters["toolCalls"])
        self.out: list[AnyMessage] = []
        self.evidence: dict = {}
        self.dv = state.get("dataVersion")
        self.update: dict[str, Any] = {}
        self.known = set(state.get("evidence", {}))
        self.submit_rejected = False  # 이 묶음의 답이 반려됐나 — 같은 묶음의 제안 반려 문장을 맞게 고른다
        self.fixable_proposal_reject = False  # 고칠 수 있는 이유(근거 · 브리프 · 담당자 · backend 검증)로 제안이 반려됐나
        self.nudge_retry = False  # 요청서 없는 답에 근거 있는 요청을 알렸나 — 모델에게 한 번 돌려보낸다(요청서 수정 재시도와 따로)
        self.accepted_cid: str | None = None  # 이 묶음에서 접수된 submit_result 호출 — 보관한 요청서 처리 결과를 그 메시지에 붙인다

    def known_ids(self) -> set[str]:
        """이번 작업에서 조회한 근거 — 앞선 노드에서 모은 것 + 이 묶음에서 방금 받은 것"""
        return self.known | set(self.evidence)

    def say(self, text: str, cid: str, name: str) -> None:
        self.out.append(ToolMessage(content=text, tool_call_id=cid, name=name))

    def result(self) -> dict:
        return {"messages": self.out, "evidence": self.evidence, "dataVersion": self.dv, "counters": self.counters,
                **self.update}


def _call_order(calls: list[dict]) -> list[dict]:
    """한 응답 안의 처리 순서 — 읽기 도구 → 제출(submit_*) → 제안(propose_*). 제출은 같은 응답에서 읽은 근거를 보고,
    제안은 같은 응답의 답이 접수된 뒤에 본다(답 먼저). 같은 무리 안에서는 원래 순서를 지킨다(안정 정렬)"""
    def rank(call: dict) -> int:
        return 2 if call["name"] in PROPOSALS else 1 if call["name"] in ("submit_result", "submit_report") else 0
    return sorted(calls, key=rank)


async def tools(state: AgentState, config) -> dict:
    turn = _Turn(state, config)
    reply: AIMessage = state["messages"][-1]
    for call in _call_order(reply.tool_calls):
        name, args, cid = call["name"], call["args"], call["id"]
        turn.counters["toolCalls"][name] = turn.counters["toolCalls"].get(name, 0) + 1
        if not limits.tool_allowed(state["counters"], name):
            turn.say(f"[LIMIT] {name}는 이번 작업에서 {limits.LIMITS.max_same_tool}회까지만 부를 수 있습니다. "
                     "지금 근거로 정리하세요.", cid, name)
        elif name in ("submit_result", "submit_report"):
            handle_submit(turn, name, args, cid)
        elif name in PROPOSALS:
            await save_proposal(turn, name, args, cid)
        elif name in HARNESS_NAMES:
            turn.say(f"[FORBIDDEN] {name}는 이 모드에서 쓸 수 없습니다.", cid, name)
        else:
            await run_tool(turn, name, args, cid)
    await _release_held_proposal(turn)
    order = {call["id"]: i for i, call in enumerate(reply.tool_calls)}
    turn.out.sort(key=lambda message: order.get(message.tool_call_id, len(order)))  # 도구 메시지는 원래 호출 순서로
    # 답은 접수됐는데 요청서가 고칠 수 있는 이유로 반려됐으면 한 번은 모델에게 돌려보낸다(답만 남기고 끝내지 않게)
    answered = bool(state.get("result") or turn.update.get("result")) and not turn.update.get("pending")
    fix_retry = bool(turn.fixable_proposal_reject and turn.counters.get("proposalRetries", 0) < 1 and answered)
    if fix_retry:
        turn.counters["proposalRetries"] = turn.counters.get("proposalRetries", 0) + 1
    retry = fix_retry or (turn.nudge_retry and answered)
    turn.update["retryProposal"] = retry
    return turn.result()


def _final_turn(turn: _Turn) -> bool:
    """판단 한도의 마지막 호출에서 온 제출인가 — 반려하면 다시 낼 기회가 없다"""
    return turn.counters.get("modelCalls", 0) >= limits.LIMITS.max_model_calls


def _salvaged(turn: _Turn, args: dict) -> tuple[dict, list[str]]:
    """마지막 제출이 검사를 못 넘으면 넘지 못한 항목만 빼고 받는다 — 반려하면 한도로 빈 결과가 된다(실제 모델 평가에서 본 일).
    빼는 것: 업무 후보, 근거가 없거나 조회하지 않은 근거만 가진 관측 사실, 원인 후보의 조회하지 않은 근거 ID,
    조회하지 않은 대상(로트 · 웨이퍼 · run)을 말하는 문장"""
    known, dropped = turn.known_ids(), []
    out = dict(args)
    if out.get("workSuggestions"):
        dropped.append(f"업무 후보 {len(out['workSuggestions'])}개")
        out["workSuggestions"] = []
    facts = [fact for fact in out.get("observedFacts") or [] if fact.get("evidenceIds") and set(fact["evidenceIds"]) <= known]
    if len(facts) < len(out.get("observedFacts") or []):
        dropped.append(f"근거가 확인되지 않은 관측 사실 {len(out['observedFacts']) - len(facts)}개")
    out["observedFacts"] = facts
    out["candidateCauses"] = [{**cause, "supportingEvidenceIds": [e for e in cause.get("supportingEvidenceIds") or [] if e in known],
                               "conflictingEvidenceIds": [e for e in cause.get("conflictingEvidenceIds") or [] if e in known]}
                              for cause in out.get("candidateCauses") or []]
    return _without_unseen_targets(turn, out, dropped)


def _without_unseen_targets(turn: _Turn, out: dict, dropped: list[str]) -> tuple[dict, list[str]]:
    """조회하지 않은 대상을 말하는 문장 — 답 · 관측 사실 · 원인 가설에서 빼고, 답 앞에 「조회되지 않음」을 붙인다"""
    seen, unseen, count = _seen_text(turn), [], 0

    def keep(text):
        nonlocal count
        kept, ids = guard.drop_unseen_sentences(str(text or ""), seen)
        unseen.extend(i for i in ids if i not in unseen)
        count += bool(ids)
        return kept

    answer = keep(out.get("answer"))
    facts = [{**fact, "text": keep(fact.get("text"))} for fact in out.get("observedFacts") or []]
    causes = [{**cause, "hypothesis": keep(cause.get("hypothesis"))} for cause in out.get("candidateCauses") or []]
    if not unseen:
        return out, dropped
    out = {**out, "answer": f"{', '.join(unseen)}는 조회되지 않아 확인할 수 없습니다. {answer}".strip(),
           "observedFacts": [fact for fact in facts if fact["text"]], "candidateCauses": [cause for cause in causes if cause["hypothesis"]]}
    return out, [*dropped, f"조회하지 않은 대상 문장 {count}개"]


def handle_submit(turn: _Turn, name: str, args: dict, cid: str, salvage: bool = True) -> None:
    """제출 처리 — 결과는 guard가 검사하고 통과해야 접수한다. 보고서는 그대로 접수한다(finalize가 저장)"""
    state = turn.state
    if name == "submit_report":
        turn.say("[ACCEPTED] 보고서 초안을 접수했습니다.", cid, name)
        turn.update["report"] = args
        return
    if state.get("answerOnly"):  # 수치 · 사실 질문 — 양식에 없는 원인 후보 · 할 일 후보를 보내도 답에 싣지 않는다
        extra = [key for key in ("candidateCauses", "workSuggestions", "changeReason", "changeEvidenceIds") if args.get(key)]
        if extra:
            turn.rt.tracer.event("answer_only_dropped", fields=extra)
        args = {key: value for key, value in args.items() if key not in extra}
    if not args.get("candidateCauses") and args.get("workSuggestions"):  # 원인 후보 없는 사실 답 — 할 일 후보도 붙이지 않는다
        turn.rt.tracer.event("work_suggestions_dropped", reason="no_causes", count=len(args["workSuggestions"]))
        args = {**args, "workSuggestions": []}
    args, short_ids = autofix.resolve_short_ids(args, turn.known_ids())  # 줄여 쓴 근거 ID(문서 ID만 등) — 후보가 하나면 바로잡는다
    args, reranked = autofix.rank_by_stance(args)  # 순서만 틀린 원인 후보(반증된 1순위) — 반려하지 않고 바로잡는다
    if reranked:
        args = _with_limitation(args, autofix.RERANKED)
    if short_ids or reranked:
        turn.rt.tracer.event("auto_fix", shortIds=short_ids, reranked=reranked)
    errs = guard.validate_result(args, turn.known_ids(), state.get("incidentProcess"))
    errs += validate_work_suggestions(args.get("workSuggestions"), turn.known_ids())
    errs += _once(turn, "claimRejects", CLAIM_ERROR, validate_answer(args, state.get("purpose"), turn.known_ids()))
    errs += guard.validate_seen_ids(args, _seen_text(turn))
    note = ""
    if not errs:
        args, doc_error = _check_documents(turn, args)
        args, id_error = _check_question_ids(turn, args)
        args, statement_error = _check_statements(turn, args)
        args, step_error = (args, None) if state.get("answerOnly") else _check_next_step(turn, args)  # 답만 하는 질문은 판단을 내지 않는다
        args, data_error = _check_data_claims(turn, args)
        args, relation_error = _check_document_claims(turn, args)
        errs = [e for e in (doc_error, id_error, statement_error, step_error, data_error, relation_error) if e]
    if not errs:
        args, note, change_error = _check_conclusion(turn, args)
        errs = [change_error] if change_error else []
    if errs and salvage and _final_turn(turn):
        salvaged, dropped = _salvaged(turn, args)
        if dropped:
            turn.rt.tracer.event("final_salvage", errors=errs, dropped=dropped)
            salvaged = _with_limitation(salvaged, "판단 한도의 마지막 제출이라 검사를 통과하지 못한 항목을 빼고 접수했습니다: "
                                        + ", ".join(dropped) + ".")
            return handle_submit(turn, name, salvaged, cid, salvage=False)
    if errs:
        turn.submit_rejected = True
        turn.rt.tracer.event("guard_reject", tool=name, errors=errs)
        turn.say("[REJECTED] " + " / ".join(err.removeprefix("[REJECTED] ") for err in errs), cid, name)
    else:
        turn.say("[ACCEPTED] 결과를 접수했습니다." + note + _missing_request(turn, args), cid, name)
        turn.update["result"] = args
        turn.accepted_cid = cid


def _missing_request(turn: _Turn, args: dict) -> str:
    """질문 작업에서 원인 확인이 필요하다는 답(CURRENT_CHECK · UPSTREAM_CHECK)을 냈는데 요청서가 없으면 — 근거가 있는 요청서
    초안을 Harness가 낸다(fab.agent.auto_request). 알림만 주면 실제 모델은 같은 원인 질문에 요청서를 냈다 안 냈다 했다(평가셋 AE01).
    초안은 승인 카드로만 나간다 — 보낼지는 사람이 정한다"""
    state = turn.state
    if (state.get("purpose") != purpose.QUESTION or args.get("nextStep") not in ("CURRENT_CHECK", "UPSTREAM_CHECK")
            or not args.get("candidateCauses")  # 원인을 묻지 않은 사실 답에는 요청서를 내지 않는다
            or not question_kind.asks_cause(state.get("question"))  # 원인 · 고장 · 점검을 묻는 질문에만
            or not may_propose(state) or state.get("proposals") or turn.update.get("pending")
            or turn.counters.get("heldProposal") or turn.counters.get("proposalNudges", 0) >= 1):
        return ""
    reply = state["messages"][-1] if state.get("messages") else None
    if any(call["name"] in PROPOSALS for call in getattr(reply, "tool_calls", None) or []):  # 같은 응답에 요청서가 있다
        return ""
    suggestion = guard.suggested_request(tool_results([*(state.get("messages") or []), *turn.out]),
                                         state.get("incidentProcess"), args.get("nextStep"))
    if suggestion is None:
        return ""
    turn.counters["proposalNudges"] = 1
    turn.update["autoRequest"] = True
    return f" 이 답은 {args['nextStep']}인데 요청서가 없어 Harness가 근거 있는 요청서 초안을 냅니다 — {suggestion}."


def _check_documents(turn: _Turn, args: dict) -> tuple[dict, str | None]:
    """문서 근거를 요구하는 질문인데 원문을 인용하지 않았으면 한 번 반려하고, 두 번째는 한계에 남기고 받는다"""
    if not doc_citation.asks_for_documents(turn.state.get("question")) or doc_citation.cites_document(args):
        return args, None
    if turn.counters.get("documentRejects", 0) == 0:
        turn.counters["documentRejects"] = 1
        return args, doc_citation.REJECT
    return _with_limitation(args, doc_citation.LIMITATION), None


def _check_question_ids(turn: _Turn, args: dict) -> tuple[dict, str | None]:
    """질문에 나온 로트 · 웨이퍼 · run이 조회되지 않았는데 답이 그 대상을 말하지 않으면(평가셋 AE07 — 없는 로트를 묻는데
    이번 사건 분석만 냈다) 한 번 반려하고, 두 번째는 Harness가 「조회되지 않았다」를 한계에 남기고 받는다"""
    missing = guard.unanswered_question_ids(turn.state.get("question"), args, _seen_text(turn))
    if not missing:
        return args, None
    if turn.counters.get("questionIdRejects", 0) == 0:
        turn.counters["questionIdRejects"] = 1
        return args, (f"질문의 {', '.join(missing)}는 이번 조회 결과에 없습니다. 도구로 조회하거나, 조회되지 않으면 answer 첫 문장에 "
                      f"「{missing[0]}는 조회되지 않아 확인할 수 없습니다」라고 답하세요.")
    return _with_limitation(args, guard.not_found_limitation(missing)), None


def _check_statements(turn: _Turn, args: dict) -> tuple[dict, str | None]:
    """원인 확률 · 설정값을 묻는 질문에 답이 할 수 없다고 말하지 않으면 한 번 반려하고, 두 번째는 Harness가 그 문장을
    답 맨 앞에 넣고 받는다 — 확률 · 설정값 질문의 답에는 이 말이 반드시 있다"""
    missing = missing_statements(turn.state.get("question"), args)
    if not missing:
        return args, None
    if turn.counters.get("statementRejects", 0) == 0:
        turn.counters["statementRejects"] = 1
        return args, ("질문이 할 수 없는 요청(원인 확률 · 설정값)을 묻습니다. answer 첫 문장에 할 수 없다고 밝히세요 — 예: "
                      + " / ".join(f"「{sentence}」" for sentence in missing) + " 그다음에 근거로 할 수 있는 것을 씁니다.")
    return {**args, "answer": " ".join([*missing, str(args.get("answer") or "").strip()]).strip()}, None


def _check_next_step(turn: _Turn, args: dict) -> tuple[dict, str | None]:
    """nextStep이 조회한 데이터와 맞는지 — 한 번 반려하고, 두 번째는 받되 어긋남을 한계에 남긴다(한도 안에 끝나게)"""
    seen = tool_results([*(turn.state.get("messages") or []), *turn.out])
    allowed = guard.evidence_next_steps(seen, turn.state.get("incidentProcess"))
    step = args.get("nextStep")
    if allowed is None or step in allowed[0]:
        return args, None
    steps, why = allowed
    expected = " · ".join(sorted(steps - guard.OPEN_STEPS))
    if turn.counters.get("nextStepRejects", 0) == 0:
        turn.counters["nextStepRejects"] = 1
        return args, (f"nextStep {step}이 조회한 데이터와 맞지 않습니다 — {why}이므로 {expected}입니다(근거가 모자라면 "
                      "INSUFFICIENT_EVIDENCE). 1순위 원인 후보와 nextStep을 데이터에 맞게 고치세요.")
    return _with_limitation(args, f"nextStep {step}은 조회 데이터({why})로 보면 {expected}에 해당합니다 — 검증되지 않은 판단입니다."), None


def _check_data_claims(turn: _Turn, args: dict) -> tuple[dict, str | None]:
    """조회 데이터와 반대인 단정(guard.contradicted_claims) — 한 번 반려하고, 두 번째는 그 문장을 빼고 한계에 남겨 받는다"""
    seen = tool_results([*(turn.state.get("messages") or []), *turn.out])
    found = guard.contradicted_claims(args, seen, turn.state.get("incidentProcess"))
    if not found:
        return args, None
    if turn.counters.get("dataClaimRejects", 0) == 0:
        turn.counters["dataClaimRejects"] = 1
        return args, ("조회 데이터와 반대입니다: " + " / ".join(f"{why} — 「{sentence[:60]}」" for sentence, why in found)
                      + ". 도구 결과대로 고치세요(모르면 확인할 수 없다고 답합니다).")
    drop = {sentence for sentence, _ in found}
    trim = lambda text: " ".join(m.group(0).strip() for m in re.finditer(r"((?:[^.。!?\n]|\.\d)+)([.。!?]?)", str(text or ""))  # noqa: E731
                                 if m.group(1).strip() and m.group(1).strip() not in drop)
    facts = [{**fact, "text": trim(fact.get("text"))} for fact in args.get("observedFacts") or []]
    args = {**args, "answer": trim(args.get("answer")), "observedFacts": [fact for fact in facts if fact["text"]]}
    turn.rt.tracer.event("data_claims_dropped", count=len(drop))
    return _with_limitation(args, f"조회 데이터와 맞지 않는 문장 {len(drop)}개를 뺐습니다(도구 결과와 반대인 단정)."), None


def _document_chunks(turn: _Turn) -> dict[str, str]:
    """이번 작업에서 본 기술 문서 청크 글 — evidenceId → 글(검색 결과의 excerpt)"""
    seen = tool_results([*(turn.state.get("messages") or []), *turn.out])
    return {item["evidenceId"]: str(item.get("excerpt") or item.get("text") or "")
            for result in seen.get("search_technical_documents", []) for item in (result.get("data") or {}).get("items") or []
            if item.get("evidenceId")}


def _check_document_claims(turn: _Turn, args: dict) -> tuple[dict, str | None]:
    """원문에 없는 관계를 원문 근거로 쓴 문장(doc_citation.unsupported_relations) — 한 번 반려하고, 두 번째는 그 문장을 빼고 받는다"""
    chunks = _document_chunks(turn)
    found = doc_citation.unsupported_relations(args, chunks) if chunks else []
    if not found:
        return args, None
    if turn.counters.get("documentClaimRejects", 0) == 0:
        turn.counters["documentClaimRejects"] = 1
        return args, doc_citation.DOC_CLAIM_REJECT.format(detail=" / ".join(f"「{sentence[:50]}」: {why}" for sentence, why in found))
    drop = {sentence for sentence, _ in found}
    answer = " ".join(m.group(0).strip() for m in doc_citation._SENTENCE.finditer(str(args.get("answer") or ""))
                      if m.group(1).strip() and m.group(1).strip() not in drop)
    facts = [fact for fact in args.get("observedFacts") or [] if str(fact.get("text")) not in drop]
    turn.rt.tracer.event("document_claims_dropped", count=len(drop))
    return _with_limitation({**args, "answer": answer, "observedFacts": facts},
                            doc_citation.DOC_CLAIM_LIMITATION.format(count=len(drop))), None


def _with_limitation(args: dict, text: str) -> dict:
    limitations = args.get("limitations") or []
    limitations = [limitations] if isinstance(limitations, str) else list(limitations)
    return {**args, "limitations": [*limitations, text]}


def _once(turn: _Turn, counter: str, prefix: str, errors: list[str]) -> list[str]:
    """이 종류의 반려는 한 작업에 한 번만 — 두 번째부터는 뺀다. 확정 단정 검사는 낱말로 보는 거친 검사라 오탐이 나면
    같은 답을 거듭 반려해 모델 판단 한도에 닿았다(근거 부족을 정확히 말한 답이 빈 결과로 끝났다)"""
    hits = [error for error in errors if error.startswith(prefix)]
    if not hits:
        return errors
    if turn.counters.get(counter, 0) >= 1:
        return [error for error in errors if not error.startswith(prefix)]
    turn.counters[counter] = 1
    return errors


def _seen_text(turn: _Turn) -> str:
    """조회한 글 — 이번 작업의 앞선 노드 · 이 묶음의 도구 결과와 작업자가 고른 조사 결과"""
    return looked_up_text([*(turn.state.get("messages") or []), *turn.out], turn.state.get("selectedInputs"))


def _check_conclusion(turn: _Turn, args: dict) -> tuple[dict, str, str | None]:
    """결론 변경 검사 — 기준(사건의 가장 최근 원인 분석)과 1순위 원인 · nextStep · 대상 공정이 다르면 새 근거가 있어야 한다.
    한 번은 반려하고, 같은 작업에서 두 번째부터는 기준 1순위를 지키고 이번 가능성을 2순위 이하로 받는다(한도 안에 끝나게)"""
    baseline = turn.state.get("baseline")
    comparison = incident_rules.compare_conclusion(args, baseline)
    rejects = turn.counters.get("conclusionRejects", 0)
    grounded = incident_rules.conclusion_change_error(comparison, args, baseline or {}, turn.known_ids(), 0) is None
    new_ids = sorted((set(args.get("changeEvidenceIds") or []) & turn.known_ids()) - set((baseline or {}).get("evidenceIds") or []))
    record = {**comparison, "reason": args.get("changeReason") or "", "newEvidenceIds": new_ids, "adjusted": False}
    if comparison["changed"] and not grounded:
        if rejects == 0:
            turn.counters["conclusionRejects"] = 1
            return args, "", incident_rules.conclusion_change_error(comparison, args, baseline, turn.known_ids(), 0)
        adjusted = _kept_baseline(turn, args, baseline)
        if adjusted is None:  # 기준에 원인 후보가 없거나 조정 결과가 검사를 못 넘으면 이번 답을 그대로 받는다
            turn.update["conclusion"] = record | {"unverifiedChange": True}
            return args, " 결론이 이전 원인 분석과 다르지만 새 근거가 확인되지 않았습니다(검증되지 않은 변경).", None
        record |= {"adjusted": True, "changed": False, "fields": [], "attemptedFields": comparison["fields"]}
        turn.update["conclusion"] = record
        return adjusted, " 새 근거가 없어 이전 원인 분석의 1순위를 유지하고, 이번 가능성은 2순위 이하로 접수했습니다.", None
    turn.update["conclusion"] = record
    return args, "", None


def _kept_baseline(turn: _Turn, args: dict, baseline: dict) -> dict | None:
    """기준 1순위를 지킨 결과 — 기준에 원인 후보가 없거나, 조정한 결과가 guard를 못 넘으면 None"""
    if not (baseline.get("candidateCauses") or []):
        return None
    adjusted = incident_rules.keep_baseline_rank(args, baseline)
    for cause in adjusted["candidateCauses"]:  # 기준 근거 중 이번에 다시 조회하지 않은 것은 인용하지 않는다
        cause["supportingEvidenceIds"] = [e for e in cause.get("supportingEvidenceIds") or [] if e in turn.known_ids()]
    errors = guard.validate_result(adjusted, turn.known_ids(), turn.state.get("incidentProcess"))
    return None if errors else adjusted


def _proposal_rejection(turn: _Turn, name: str, args: dict, cid: str, action_type: str, made_brief: dict) -> str | None:
    """제안 검사(모드 · 근거 · 브리프 · 한 번에 하나 · 상한 · 중복) — 걸리면 모델에 돌려줄 문장, 통과면 None"""
    state, job_runtime = turn.state, turn.rt
    if not may_propose(state):
        return ("[FORBIDDEN] 이 작업에서는 요청을 제안할 수 없습니다 — 원인 분석은 nextStep으로 판단만 남기고, "
                "승인 대기 중인 요청서가 있으면 질문에만 답합니다.")
    unknown = set(args.get("evidenceIds", [])) - turn.known_ids()
    if unknown:
        return f"[REJECTED] 조회하지 않은 근거 ID: {sorted(unknown)}"
    errors = guard.validate_proposal(action_type, args, tool_results(state["messages"]), state.get("incidentProcess"))
    if errors:  # 제안 종류 · 대상이 이번 조사의 근거와 맞아야 한다(근거 없는 정비 · 확인 요청을 막는다)
        job_runtime.tracer.event("guard_reject", tool=name, errors=errors)
        return "[REJECTED] " + " / ".join(errors)
    if made_brief.get("errors"):
        return "[REJECTED] 요청 브리프: " + " / ".join(made_brief["errors"])
    if turn.update.get("pending"):  # 한 번에 하나만 승인 대기에 넣는다
        return "[REJECTED] 제안은 한 번에 하나씩 합니다. 이 제안의 승인을 먼저 기다리세요."
    made = [proposal for proposal in state.get("proposals") or [] if isinstance(proposal, dict)]
    if any(proposal["actionType"] == action_type and proposal["ownerUserId"] == args.get("ownerUserId") for proposal in made):
        return ("[REJECTED] 같은 담당자에게 같은 종류의 요청을 이미 제안했습니다. "
                "회신 · 결정을 근거로 결과를 정리하세요.")
    return None


async def _incident_block(turn: _Turn, args: dict, action_type: str) -> str | None:
    """사건 단위 상한 · 중복 · 거절 — 이 사건의 요청 기록을 조회해서 본다(질문마다 새 thread라 thread만 보면 놓친다)"""
    state = turn.state
    try:
        found = await turn.rt.toolbox.call("get_check_requests", {"incidentId": state["incidentId"]}, turn.rt.tracer)
        requests = (found.get("data") or {}).get("items") or []
    except ToolCallError as e:
        turn.rt.tracer.event("incident_rules_unavailable", error=e.text[:200])
        requests = []
    pending = state.get("pending") or {}
    # 사건의 승인 기록은 사건 기록 조회(backend 내부 API)가 붙어야 안다 — 그 전에는 이번 thread의 제안만 보고,
    # 대기 중이 아닌 것은 요청(get_check_requests)으로 이미 세어진 것으로 본다(거절된 제안은 아직 모른다)
    approvals = [{"actionType": proposal["actionType"], "targetProcessCode": proposal.get("targetProcessCode"),
                  "status": "PENDING" if proposal.get("approvalId") == pending.get("approvalId") else "APPROVED"}
                 for proposal in state.get("proposals") or [] if isinstance(proposal, dict)]
    target = args.get("processCode") or (state.get("incidentProcess") if "MAINTENANCE" in action_type else None)
    scope = _request_scope(state, action_type, target)
    if scope:  # 사람이 연 요청서 작업 — 지정한 대상 공정만
        return scope
    # 원인 분석이 언제 바뀌었는지는 사건 기록 조회(backend 내부 API)가 붙어야 안다 — 그 전에는 「바뀌지 않음」으로 본다
    return incident_rules.proposal_block(action_type, target, requests, approvals, lambda _at: False,
                                         limits.LIMITS.max_proposals,
                                         human_requested=state.get("purpose") in purpose.HUMAN_REQUESTED)


def _request_scope(state, action_type: str, target: str | None) -> str | None:
    """사람이 연 요청서 작업의 범위 — 지정한 대상 공정(input.request.targetProcessCode)으로만"""
    if state.get("purpose") not in purpose.HUMAN_REQUESTED:
        return None
    return incident_rules.request_scope_error(action_type, target, state.get("requestInput"))


async def _create_proposal(turn: _Turn, args: dict, cid: str, action_type: str, brief: dict) -> dict:
    state, job_runtime = turn.state, turn.rt
    job_id = current_job(state, turn.config)  # 제안은 지금 대기에 들어갈 작업에 붙인다(회신 뒤 재개 작업 포함)
    return await job_runtime.backend.create_proposal(
        job_id, action_type,
        {"incidentId": state["incidentId"], "ownerUserId": args["ownerUserId"], "question": args["question"],
         "targetProcessCode": args.get("processCode"), "equipmentId": args.get("equipmentId"),
         "causeIds": args.get("causeIds", []), "evidenceIds": args.get("evidenceIds", []),
         "brief": brief},
        args.get("rationale", ""), args.get("evidenceIds", []), job_runtime.actor,
        idempotency_key=f"{job_id}:{cid}")  # 같은 도구 호출을 재시도해도 제안은 하나


async def save_proposal(turn: _Turn, name: str, args: dict, cid: str, held: dict | None = None) -> None:
    """제안 저장 — 검사(모드 · 근거 · 브리프 · 한 번에 하나 · 상한 · 중복)를 통과하면 backend에 저장하고 승인 대기로 둔다.
    답이 아직 접수되지 않았으면 반려하지 않고 보관한다 — 사람은 답을 보고 승인을 정하므로 승인 요청은 답이 접수된 뒤에
    만든다. 실제 모델은 거의 늘 요청서를 답보다 먼저 보냈고, 예전처럼 반려하면 반려 → 답 → 재제안으로 호출이 두 배가 됐다.
    held: 보관했던 요청서를 답 접수 뒤 처리할 때 — 결과 문장은 그 답의 접수 메시지에 붙인다(옛 호출에는 답할 수 없다)"""
    write_tool, action_type = PROPOSALS[name]
    made_brief = (held or {}).get("brief") or (turn.state.get("briefs") or {}).get(cid) \
        or {"errors": ["요청 브리프가 만들어지지 않았습니다."]}
    respond = (lambda text: turn.held_notes.append(text)) if held else (lambda text: turn.say(text, cid, name))
    rejection = _proposal_rejection(turn, name, args, cid, action_type, made_brief)
    fixable = bool(rejection) and not rejection.startswith("[FORBIDDEN]")
    rejection = rejection or await _incident_block(turn, args, action_type)  # 사건 규칙 반려는 다시 시도해도 같다
    answered = turn.state.get("result") or turn.update.get("result")
    if not rejection and not answered and not held:  # 사건 규칙(한도 · 중복)까지 넘은 요청서만 보관한다 — 답을 기다려도 안 바뀐다
        turn.counters["heldProposal"] = {"name": name, "args": args, "cid": cid, "brief": made_brief}
        turn.rt.tracer.event("proposal_held", tool=name)
        return turn.say("[HELD] 요청서를 보관했습니다. submit_result로 질문의 답이 접수되면 Harness가 바로 승인 요청으로 "
                        "넘깁니다 — 같은 요청서를 다시 내지 마세요.", cid, name)
    if not rejection:
        try:
            approval = await _create_proposal(turn, args, cid, action_type, made_brief["brief"])
        except Exception as e:  # noqa: BLE001 — backend 검증 실패는 모델이 읽고 고치게
            rejection, fixable = f"[REJECTED] {e}", True
    if rejection:
        turn.fixable_proposal_reject |= fixable
        if rejection.startswith("[LIMIT]"):  # 사람에게도 이유를 보인다 — 결과의 한계로(finalize)
            turn.update["proposalLimit"] = True
        turn.rt.tracer.event("proposal_reject", tool=name, fixable=fixable, reason=rejection[:300])  # 왜 요청서가 없는지 trace에
        return respond(rejection)
    turn.rt.tracer.event("proposal", approvalId=approval["approvalId"], actionType=action_type)
    respond(f"[PROPOSED] 승인 {approval['approvalId']}로 저장했습니다. 사람의 승인을 기다립니다. "
            "아직 요청은 등록되지 않았습니다.")
    turn.update["pending"] = {"kind": "APPROVAL", "approvalId": approval["approvalId"], "writeTool": write_tool,
                              "approvalVersion": approval["version"], "interruptId": f"INT-APP-{uuid.uuid4().hex[:8]}"}
    turn.update["proposals"] = [{"approvalId": approval["approvalId"], "actionType": action_type,
                                 "ownerUserId": args.get("ownerUserId"), "targetProcessCode": args.get("processCode")}]


async def _release_held_proposal(turn: _Turn) -> None:
    """보관한 요청서 — 이 묶음에서 답이 접수됐으면 지금 처리하고, 결과를 그 답의 접수 메시지 뒤에 붙인다"""
    held = turn.counters.get("heldProposal")
    if not held or not turn.accepted_cid or turn.update.get("pending"):
        return
    turn.counters.pop("heldProposal")
    turn.held_notes = []
    await save_proposal(turn, held["name"], held["args"], held["cid"], held=held)
    for i, message in enumerate(turn.out):
        if message.tool_call_id == turn.accepted_cid:
            turn.out[i] = ToolMessage(content=f"{message.content} 보관한 요청서({held['name']}): {' '.join(turn.held_notes)}",
                                      tool_call_id=message.tool_call_id, name=message.name)


async def run_tool(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """도구 실행 — 업무 · 지식 MCP 읽기 도구. 근거와 데이터 버전을 모은다. 일시 장애가 남으면 작업 단위 재시도로 넘긴다"""
    job_runtime = turn.rt
    try:
        result = await job_runtime.toolbox.call(name, args, job_runtime.tracer)
        new_evidence, data_version = record_evidence(result)
        turn.evidence.update(new_evidence)
        turn.dv = data_version or turn.dv
        turn.say(context.to_tool_text(result, name), cid, name)
    except ToolCallError as e:
        turn.say(e.text, cid, name)
        if e.retryable:  # 재시도 후에도 일시 장애면 작업 단위 재시도로 넘긴다
            raise TransientJobError(e.text) from e

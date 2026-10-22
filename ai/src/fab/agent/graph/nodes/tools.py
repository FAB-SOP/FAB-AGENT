"""tools — 모델이 고른 도구 호출을 처리한다. 호출마다 셋 중 하나로 보낸다:
제출 처리(submit_result · submit_report) / 제안 저장(propose_* — 정합성 검사 뒤 사람 승인 대기) / 도구 실행(업무 · 지식 MCP 읽기).
도구 한도 · Harness 도구의 모드 제한은 고르기 전에 본다.
"""

from __future__ import annotations

import uuid
from typing import Any

from langchain_core.messages import AIMessage, AnyMessage, ToolMessage

from fab.agent.evidence import tool_results
from fab.agent.graph.state import AgentState, current_job, record_evidence, runtime
from fab.agent.harness import context, guard, incident_rules, limits
from fab.agent.harness.retry import TransientJobError
from fab.agent import purpose
from fab.agent.purpose import may_propose
from fab.agent.tools import HARNESS_NAMES, PROPOSALS, ToolCallError


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
    order = {call["id"]: i for i, call in enumerate(reply.tool_calls)}
    turn.out.sort(key=lambda message: order.get(message.tool_call_id, len(order)))  # 도구 메시지는 원래 호출 순서로
    # 답은 접수됐는데 요청서가 고칠 수 있는 이유로 반려됐으면 한 번은 모델에게 돌려보낸다(답만 남기고 끝내지 않게)
    retry = bool(turn.fixable_proposal_reject and turn.counters.get("proposalRetries", 0) < 1
                 and (state.get("result") or turn.update.get("result")) and not turn.update.get("pending"))
    if retry:
        turn.counters["proposalRetries"] = turn.counters.get("proposalRetries", 0) + 1
    turn.update["retryProposal"] = retry
    return turn.result()


def handle_submit(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """제출 처리 — 결과는 guard가 검사하고 통과해야 접수한다. 보고서는 그대로 접수한다(finalize가 저장)"""
    state = turn.state
    if name == "submit_report":
        turn.say("[ACCEPTED] 보고서 초안을 접수했습니다.", cid, name)
        turn.update["report"] = args
        return
    errs = guard.validate_result(args, turn.known_ids(), state.get("incidentProcess"))
    note = ""
    if not errs:
        args, note, change_error = _check_conclusion(turn, args)
        errs = [change_error] if change_error else []
    if errs:
        turn.submit_rejected = True
        turn.rt.tracer.event("guard_reject", tool=name, errors=errs)
        turn.say("[REJECTED] " + " / ".join(err.removeprefix("[REJECTED] ") for err in errs), cid, name)
    else:
        turn.say("[ACCEPTED] 결과를 접수했습니다." + note, cid, name)
        turn.update["result"] = args


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
    if not (state.get("result") or turn.update.get("result")):  # 답 먼저 — 사람은 답을 보고 승인을 정한다
        if turn.submit_rejected:
            return ("[REJECTED] 같은 응답의 submit_result가 반려돼 요청 제안도 받지 않았습니다. "
                    "답을 고쳐 요청 제안과 함께 다시 내세요.")
        return ("[REJECTED] 먼저 submit_result로 답을 제출하세요. 요청 제안은 답이 접수된 뒤에만 받습니다 — "
                "같은 응답에서 submit_result와 함께 호출하면 됩니다.")
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
    # 사건의 승인 기록은 사건 기록 조회(D9 · D12)가 붙어야 안다 — 그 전에는 이번 thread의 제안만 보고,
    # 대기 중이 아닌 것은 요청(get_check_requests)으로 이미 세어진 것으로 본다(거절된 제안은 아직 모른다)
    approvals = [{"actionType": proposal["actionType"], "targetProcessCode": proposal.get("targetProcessCode"),
                  "status": "PENDING" if proposal.get("approvalId") == pending.get("approvalId") else "APPROVED"}
                 for proposal in state.get("proposals") or [] if isinstance(proposal, dict)]
    target = args.get("processCode") or (state.get("incidentProcess") if "MAINTENANCE" in action_type else None)
    # 원인 분석이 언제 바뀌었는지는 사건 기록 조회(D12)가 붙어야 안다 — 그 전에는 「바뀌지 않음」으로 본다
    return incident_rules.proposal_block(action_type, target, requests, approvals, lambda _at: False,
                                         limits.LIMITS.max_proposals,
                                         human_requested=state.get("purpose") == purpose.REQUEST)


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


async def save_proposal(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """제안 저장 — 검사(모드 · 근거 · 브리프 · 한 번에 하나 · 상한 · 중복)를 통과하면 backend에 저장하고 승인 대기로 둔다"""
    write_tool, action_type = PROPOSALS[name]
    made_brief = (turn.state.get("briefs") or {}).get(cid) or {"errors": ["요청 브리프가 만들어지지 않았습니다."]}
    rejection = _proposal_rejection(turn, name, args, cid, action_type, made_brief)
    fixable = bool(rejection) and not rejection.startswith("[FORBIDDEN]")
    rejection = rejection or await _incident_block(turn, args, action_type)  # 사건 규칙 반려는 다시 시도해도 같다
    if not rejection:
        try:
            approval = await _create_proposal(turn, args, cid, action_type, made_brief["brief"])
        except Exception as e:  # noqa: BLE001 — backend 검증 실패는 모델이 읽고 고치게
            rejection, fixable = f"[REJECTED] {e}", True
    if rejection:
        turn.fixable_proposal_reject |= fixable
        return turn.say(rejection, cid, name)
    turn.rt.tracer.event("proposal", approvalId=approval["approvalId"], actionType=action_type)
    turn.say(f"[PROPOSED] 승인 {approval['approvalId']}로 저장했습니다. 사람의 승인을 기다립니다. "
             "아직 요청은 등록되지 않았습니다.", cid, name)
    turn.update["pending"] = {"kind": "APPROVAL", "approvalId": approval["approvalId"], "writeTool": write_tool,
                              "approvalVersion": approval["version"], "interruptId": f"INT-APP-{uuid.uuid4().hex[:8]}"}
    turn.update["proposals"] = [{"approvalId": approval["approvalId"], "actionType": action_type,
                                 "ownerUserId": args.get("ownerUserId"), "targetProcessCode": args.get("processCode")}]


async def run_tool(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """도구 실행 — 업무 · 지식 MCP 읽기 도구. 근거와 데이터 버전을 모은다. 일시 장애가 남으면 작업 단위 재시도로 넘긴다"""
    job_runtime = turn.rt
    try:
        result = await job_runtime.toolbox.call(name, args, job_runtime.tracer)
        new_evidence, data_version = record_evidence(result)
        turn.evidence.update(new_evidence)
        turn.dv = data_version or turn.dv
        turn.say(context.to_tool_text(result), cid, name)
    except ToolCallError as e:
        turn.say(e.text, cid, name)
        if e.retryable:  # 재시도 후에도 일시 장애면 작업 단위 재시도로 넘긴다
            raise TransientJobError(e.text) from e

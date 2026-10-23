"""finalize — 제출된 결과(또는 보고서)를 팀 계약 모양으로 정리한다. 한도 초과로 멈췄으면 지금까지의 근거로 부분 결과."""

from __future__ import annotations

from fab.agent.graph.state import AgentState, current_job, runtime
from fab.agent.tools import REPORT_SECTIONS
from fab.agent.harness.limits import LIMITS
from fab.agent.answer import answer_evidence_ids
from fab.agent.evidence import looked_up_text
from fab.agent.harness.guard import not_found_limitation, unanswered_question_ids
from fab.agent.stance import cause_stance
from fab.agent.work import contract_suggestions, without_proposed


def as_list(value) -> list[str]:
    """문자열 목록 칸 — 모델이 배열 대신 문자열 하나를 내면 한 항목 목록으로 감싼다(글자 단위로 쪼개지 않는다)"""
    if value is None or value == "":
        return []
    if isinstance(value, str):
        return [value]
    return [str(item) for item in value]


REF_TYPES = {"SENSOR", "METROLOGY", "ROUTE", "CHANGE", "REPLY", "DOCUMENT"}  # 계약 Reference.sourceType


SEGMENT_LIMITATION = ("시계열 구간(DEMO-SEGMENT-V2)의 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아닙니다. "
                      "구간은 관측이지 고장 시점 · 원인 확정이 아닙니다.")


def _segment_limit(submitted: dict, facts: list[dict]) -> list[str]:
    """시계열 구간 근거(E-SEG-*)를 인용한 결과에는 Harness가 그 한계를 반드시 남긴다(모델이 빠뜨려도)"""
    cited = ({evidence_id for fact in facts for evidence_id in fact["evidenceIds"]}
             | {evidence_id for cause in submitted.get("candidateCauses", [])
                for evidence_id in cause.get("supportingEvidenceIds") or []})
    text = " ".join(as_list(submitted.get("limitations")))
    return [SEGMENT_LIMITATION] if any(e.startswith("E-SEG-") for e in cited) and "시연 선택값" not in text else []


def _contract_ref(ref: dict, data_version: str | None) -> dict:
    source_type = ref.get("sourceType")
    return {"evidenceId": ref["evidenceId"], "sourceType": source_type if source_type in REF_TYPES else "METROLOGY",
            "sourceId": str(ref.get("sourceId", ref["evidenceId"])),
            "dataVersion": ref.get("dataVersion") or data_version or "UNKNOWN", "synthetic": True}


async def _finalize_report(state: AgentState, job_runtime) -> dict:
    report = state.get("report") or {}
    sections = {key: str((report.get("sections") or {}).get(key) or "기록 없음") for key in REPORT_SECTIONS}
    if not state.get("report"):
        sections["unresolved"] = f"초안 생성이 끝나기 전에 멈췄습니다: {state.get('stopReason')}"
    known = set(state.get("evidence", {}))
    evidence_ids = [evidence_id for evidence_id in report.get("evidenceIds", []) if evidence_id in known]
    saved = await job_runtime.backend.save_report_draft(state["incidentId"], sections, evidence_ids)
    return {"report": saved}


def _contract_facts(submitted: dict) -> list[dict]:
    return [{"text": str(fact.get("text", "")), "evidenceIds": list(fact.get("evidenceIds") or [])}
            for fact in submitted.get("observedFacts", [])]


def _contract_causes(submitted: dict) -> list[dict]:
    return [{"priority": max(1, int(cause.get("priority") or i + 1)), "hypothesis": str(cause.get("hypothesis", "")),
             "supportingEvidenceIds": list(cause.get("supportingEvidenceIds") or []),
             "conflictingEvidenceIds": list(cause.get("conflictingEvidenceIds") or []),
             "missingData": as_list(cause.get("missingData")),
             "nextCheck": str(cause.get("nextCheck", ""))}
            for i, cause in enumerate(submitted.get("candidateCauses", []))]


def _used_evidence_ids(facts: list[dict], causes: list[dict]) -> set[str]:
    """결과가 실제로 인용한 근거 — 관측 사실 · 원인 후보 지지 · 반대 근거(화면이 반대 근거도 찾을 수 있게)"""
    return ({evidence_id for fact in facts for evidence_id in fact["evidenceIds"]}
            | {evidence_id for cause in causes for evidence_id in cause["supportingEvidenceIds"] + cause["conflictingEvidenceIds"]})


async def finalize(state: AgentState, config) -> dict:
    job_runtime = runtime(config)
    if state["mode"] == "REPORT":
        return await _finalize_report(state, job_runtime)
    submitted = state.get("result")
    limitations = []
    if submitted is None and state.get("previousResult"):  # 재개 뒤 다시 정리하기 전에 멈춤 — 그 전에 접수된 답을 쓴다
        submitted = state["previousResult"]
        limitations.append(f"승인 · 회신 뒤 다시 정리하기 전에 멈춰, 그 전에 낸 답을 그대로 둡니다: {state.get('stopReason')}")
    if state.get("requestSent"):  # 승인으로 요청을 보낸 뒤 정리 — 회신은 저장만 된다
        limitations.append(f"확인 요청 {state['requestSent']}를 보냈습니다. 회신은 저장만 되며, 반영하려면 회신을 골라 추가 분석을 요청하세요.")
    if state.get("proposalLimit"):  # 모델에게만 알리던 한도 반려를 사람에게도 — 왜 요청서가 없는지
        limitations.append(f"이 사건에서 Agent가 제안할 수 있는 요청 한도({LIMITS.max_proposals}개)에 닿아 요청서를 만들지 않았습니다. "
                           "필요하면 업무 후보 채택이나 「요청서 만들기」로 보내세요.")
    if submitted is None:  # 한도 초과 등으로 제출 없이 끝남 — 지금까지의 근거로 부분 결과
        submitted = {"nextStep": "INSUFFICIENT_EVIDENCE", "observedFacts": [], "candidateCauses": [], "limitations": []}
        limitations.append(f"조사가 끝나기 전에 멈췄습니다: {state.get('stopReason')}")
    # 질문에 나온 대상이 조회되지 않았는데 답이 말하지 않은 채 끝났으면(한도로 멈춘 경우 포함) Harness가 남긴다
    missing = unanswered_question_ids(state.get("question"), submitted,
                                      looked_up_text(state.get("messages") or [], state.get("selectedInputs")))
    if missing and not any(ids in " ".join(as_list(submitted.get("limitations"))) for ids in missing):
        limitations.append(not_found_limitation(missing))
    facts = _contract_facts(submitted)
    causes = _contract_causes(submitted)
    used = _used_evidence_ids(facts, causes) | set(answer_evidence_ids(submitted))  # 답 글에 인용한 근거도 화면이 찾을 수 있게
    evidence = state.get("evidence", {})
    result = {
        "resultId": f"RESULT-{current_job(state, config)}",
        "jobId": current_job(state, config), "incidentId": state["incidentId"],
        "dataVersion": state.get("dataVersion") or "UNKNOWN",
        **({"answer": str(submitted["answer"]).strip()} if str(submitted.get("answer") or "").strip() else {}),
        "observedFacts": facts, "candidateCauses": causes,
        "limitations": as_list(submitted.get("limitations")) + limitations + _segment_limit(submitted, facts),
        "evidenceRefs": [_contract_ref(evidence[evidence_id], state.get("dataVersion"))
                         for evidence_id in sorted(used) if evidence_id in evidence],
        "synthetic": True,
    }
    # 계약 AnalysisResult.candidateCauses는 추가 필드를 막는다 — 원인 매트릭스 ID는 계약 밖 analysis에 둔다
    analysis = {"nextStep": submitted.get("nextStep"), "stopReason": state.get("stopReason"),
                "promptVersion": state.get("promptVersion"), "conclusion": state.get("conclusion"),
                "causeIds": [[str(cause_id) for cause_id in cause.get("causeIds") or []]
                             for cause in submitted.get("candidateCauses", [])],
                "causeStances": [cause_stance(cause) for cause in causes],
                "requestSent": state.get("requestSent"), "proposalLimit": bool(state.get("proposalLimit"))}
    # 업무 후보는 계약 AnalysisResult 밖 — 이벤트 payload의 workSuggestions로 따로 싣는다(근거는 evidenceRefs 안의 것만)
    suggestions = contract_suggestions(submitted.get("workSuggestions"), {ref["evidenceId"] for ref in result["evidenceRefs"]})
    suggestions, dropped = without_proposed(suggestions, state.get("proposals") or [])
    analysis["droppedWorkSuggestions"] = dropped
    return {"result": result, "analysis": analysis, "workSuggestions": suggestions}

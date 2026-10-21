"""finalize — 제출된 결과(또는 보고서)를 팀 계약 모양으로 정리한다. 한도 초과로 멈췄으면 지금까지의 근거로 부분 결과."""

from __future__ import annotations

from fab.agent.graph.state import AgentState, current_job, runtime
from fab.agent.tools import REPORT_SECTIONS


def as_list(value) -> list[str]:
    """문자열 목록 칸 — 모델이 배열 대신 문자열 하나를 내면 한 항목 목록으로 감싼다(글자 단위로 쪼개지 않는다)"""
    if value is None or value == "":
        return []
    if isinstance(value, str):
        return [value]
    return [str(x) for x in value]


REF_TYPES = {"SENSOR", "METROLOGY", "ROUTE", "CHANGE", "REPLY", "DOCUMENT"}  # 계약 Reference.sourceType


SEGMENT_LIMITATION = ("시계열 구간(DEMO-SEGMENT-V2)의 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아닙니다. "
                      "구간은 관측이지 고장 시점 · 원인 확정이 아닙니다.")


def _segment_limit(r: dict, facts: list[dict]) -> list[str]:
    """시계열 구간 근거(E-SEG-*)를 인용한 결과에는 Harness가 그 한계를 반드시 남긴다(모델이 빠뜨려도)"""
    cited = {e for f in facts for e in f["evidenceIds"]} | {e for c in r.get("candidateCauses", [])
                                                           for e in c.get("supportingEvidenceIds") or []}
    text = " ".join(as_list(r.get("limitations")))
    return [SEGMENT_LIMITATION] if any(e.startswith("E-SEG-") for e in cited) and "시연 선택값" not in text else []


def _contract_ref(ref: dict, data_version: str | None) -> dict:
    t = ref.get("sourceType")
    return {"evidenceId": ref["evidenceId"], "sourceType": t if t in REF_TYPES else "METROLOGY",
            "sourceId": str(ref.get("sourceId", ref["evidenceId"])),
            "dataVersion": ref.get("dataVersion") or data_version or "UNKNOWN", "synthetic": True}


async def finalize(state: AgentState, config) -> dict:
    rt = runtime(config)
    if state["mode"] == "REPORT":
        rep = state.get("report") or {}
        sections = {k: str((rep.get("sections") or {}).get(k) or "기록 없음") for k in REPORT_SECTIONS}
        if not state.get("report"):
            sections["unresolved"] = f"초안 생성이 끝나기 전에 멈췄습니다: {state.get('stopReason')}"
        known = set(state.get("evidence", {}))
        ev_ids = [e for e in rep.get("evidenceIds", []) if e in known]
        saved = await rt.backend.save_report_draft(state["incidentId"], sections, ev_ids)
        return {"report": saved}
    r = state.get("result")
    limitations = []
    if r is None:  # 한도 초과 등으로 제출 없이 끝남 — 지금까지의 근거로 부분 결과
        r = {"nextStep": "INSUFFICIENT_EVIDENCE", "observedFacts": [], "candidateCauses": [], "limitations": []}
        limitations.append(f"조사가 끝나기 전에 멈췄습니다: {state.get('stopReason')}")
    facts = [{"text": str(f.get("text", "")), "evidenceIds": list(f.get("evidenceIds") or [])}
             for f in r.get("observedFacts", [])]
    causes = [{"priority": max(1, int(c.get("priority") or i + 1)), "hypothesis": str(c.get("hypothesis", "")),
               "supportingEvidenceIds": list(c.get("supportingEvidenceIds") or []),
               "conflictingEvidenceIds": list(c.get("conflictingEvidenceIds") or []),
               "missingData": as_list(c.get("missingData")),
               "nextCheck": str(c.get("nextCheck", ""))}
              for i, c in enumerate(r.get("candidateCauses", []))]
    used = {e for f in facts for e in f["evidenceIds"]} | {e for c in causes for e in c["supportingEvidenceIds"]}
    used |= {e for item in r.get("workSuggestions", []) for e in item.get("evidenceIds", [])}
    ev = state.get("evidence", {})
    result = {
        "resultId": f"RESULT-{current_job(state, config)}",
        "jobId": current_job(state, config), "incidentId": state["incidentId"],
        "dataVersion": state.get("dataVersion") or "UNKNOWN",
        "observedFacts": facts, "candidateCauses": causes,
        "limitations": as_list(r.get("limitations")) + limitations + _segment_limit(r, facts),
        "evidenceRefs": [_contract_ref(ev[e], state.get("dataVersion")) for e in sorted(used) if e in ev],
        "synthetic": True,
    }
    # 계약 AnalysisResult.candidateCauses는 추가 필드를 막는다 — 원인 매트릭스 ID는 계약 밖 analysis에 둔다
    analysis = {"nextStep": r.get("nextStep"), "stopReason": state.get("stopReason"),
                "promptVersion": state.get("promptVersion"), "workSuggestions": r.get("workSuggestions", []),
                "causeIds": [[str(x) for x in c.get("causeIds") or []] for c in r.get("candidateCauses", [])]}
    return {"result": result, "analysis": analysis}

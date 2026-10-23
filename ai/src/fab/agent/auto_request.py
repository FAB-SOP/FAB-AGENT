"""원인 질문의 답에 요청서가 없을 때 Harness가 내는 요청서 초안 — 규칙 모델과 같은 문장 틀 · 근거로 만든다.

알림(「요청서를 낼지 정하세요」)만으로는 실제 모델이 요청서를 내지 않고 끝나곤 했다(같은 원인 질문에 냈다 안 냈다).
초안은 승인 카드로만 나간다 — 보낼지는 사람이 정한다. 담당자를 아직 조회하지 않았으면 담당자 조회부터 한다.
"""

from __future__ import annotations

from fab.agent.causes import CAUSES
from fab.agent.evidence import features_of, runs_by_process
from fab.agent.models.rule import _maintenance_reason, _owner, _upstream_reason


def draft(seen: dict, incident_process: str | None, next_step: str | None, top_cause: dict | None,
          known: set[str]) -> tuple[str, dict] | None:
    """다음에 낼 도구 호출 (이름, 인자) — 담당자 조회 또는 요청서. 근거가 없으면 None"""
    runs = runs_by_process(seen)
    current = runs.get(incident_process or "")
    if current is None or top_cause is None:
        return None
    if next_step == "CURRENT_CHECK":
        features = features_of(seen, current["runId"])
        outside = [item for item in ((features or {}).get("data") or {}).get("items", []) if item.get("outsideReferenceBand")]
        if current.get("amountWithinNormalVariation") is not False and not outside:
            return None
        process, equipment, kind = incident_process, current["equipmentId"], "MAINTENANCE"
        sensor = outside[0]["sensorId"] if outside else None
        reason = _maintenance_reason(current, outside)
        name = "propose_maintenance_request"
        question = (f"{reason} {equipment} {sensor or '제거량'} 계통(실제값/설정값 · 공급 · 교정 이력) 점검 부탁드립니다. "
                    "장비 설정 변경은 승인 SOP 범위에서만 진행합니다.")
        support = [current["_ev"], *([features["_ev"]] if outside else [])]
    elif next_step == "UPSTREAM_CHECK":
        upstream = [run for process, run in runs.items() if process != incident_process
                    and run.get("amountWithinNormalVariation") is False and run.get("_seq", 0) < current.get("_seq", 9)]
        if not upstream:
            return None
        run = max(upstream, key=lambda item: item.get("_seq", 0))
        process, equipment, kind = run["processCode"], run["equipmentId"], "PROCESS_OWNER"
        reason = _upstream_reason(run, current)
        name = "propose_cross_process_check"
        question = f"{reason} {equipment}에서 처리된 이 run 전후의 정비 · 시즈닝 · 모니터 결과를 확인 부탁드립니다."
        support = [run["_ev"]]
    else:
        return None
    owner = _owner(seen, process, kind)
    if owner is None:
        return "get_process_owner", {"processCode": process}
    cause_ids = [cause_id for cause_id in top_cause.get("causeIds") or []
                 if cause_id in CAUSES and CAUSES[cause_id][0] in (process, "ALL")]
    evidence = list(dict.fromkeys([*support, *(top_cause.get("supportingEvidenceIds") or [])]))
    return name, {"processCode": process, "equipmentId": equipment, "ownerUserId": owner, "reason": reason,
                  "question": question, "causeIds": cause_ids,
                  "evidenceIds": [evidence_id for evidence_id in evidence if evidence_id in known],
                  "rationale": str(top_cause.get("hypothesis") or "")}

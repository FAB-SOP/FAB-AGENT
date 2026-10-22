"""요청 브리프 — 확인 · 점검 요청을 받는 사람이 「왜 내 장비가 의심받는지 · 얼마나 벗어났는지 · 어떤 원인을 생각하는지」를
알 수 있게 제안 payload.brief에 붙이는 내용. 그래프의 request_brief 노드가 제안 저장 전에 만든다(승인 해시에 들어간다).

숫자 · 원인 ID · 근거 ID는 모델이 쓰지 않는다 — 이번 조사에서 받은 도구 결과(get_run_and_metrology · get_run_features ·
get_change_events · search_applicable_playbooks)와 원인 매트릭스(causes.py)에서 코드가 그대로 옮긴다. 모델은 reason 문장만 쓴다.
받는 사람(다른 공정 담당 · 설비정비팀)은 사건 분석 전체(P07)를 볼 권한이 없으므로, 이 브리프가 받는 사람이 보는 전부다.

{reason, deviations:[{runId, equipmentId, processCode, metric, value, unit, baselineMean, z, threshold, thresholdKind,
  withinNormal, setpoint?, deviationFromSetpointPct?}], suspectedCauses:[{causeId, candidate, confirmed:false,
  evidenceToCheck, limits}], relatedRecords:[{evidenceId, summary, rootCauseConfirmed?}], askFor:[...]}
"""

from __future__ import annotations

from fab.agent.causes import CAUSE_DETAILS, CAUSES, PROCESS_ORDER
from fab.agent.evidence import data as _data
from fab.agent.evidence import runs_by_id, tool_results

# 요청 종류마다 받는 사람에게 부탁할 기본 항목 — 원인 후보의 evidenceToCheck가 뒤에 붙는다. 설정값 처방은 넣지 않는다
ASK_FOR = {
    "CREATE_CHECK_REQUEST": ["해당 run 전후 정비 · 세정 · 시즈닝 기록과 절차(매수 포함)", "시즈닝 후 모니터 웨이퍼 결과"],
    "CREATE_MAINTENANCE_REQUEST": ["해당 계통의 실제값과 설정값 기록 · 공급 상태", "센서 교정 이력과 최근 정비 기록"],
}


def amount_deviation(run: dict) -> dict:
    """run 하나의 증착 · 제거량 편차 — 같은 PM 정상(BASELINE) 분포 대비 z와 판정 기준(|z| < normalVariationSigma)"""
    base = ((run.get("baselineAmount") or {}).get("samePm") or {})
    return {"runId": run.get("runId"), "equipmentId": run.get("equipmentId"), "processCode": run.get("processCode"),
            "metric": "depositedA" if run.get("amountKind") == "DEPOSITED" else "removedA", "value": run.get("amountA"),
            "unit": "Å", "baselineMean": base.get("mean"), "z": run.get("amountZSamePm"),
            "threshold": run.get("normalVariationSigma"), "thresholdKind": "ABS_Z_SAME_PM",
            "withinNormal": run.get("amountWithinNormalVariation")}


def sensor_deviation(run: dict, item: dict) -> dict:
    """센서 본 처리 평균 — 정상 참고 범위(run 평균의 분포, 점 단위 SPC 아님) · 같은 PM 정상 대비 z · 설정값 대비 차이"""
    return {"runId": run.get("runId"), "equipmentId": run.get("equipmentId"), "processCode": run.get("processCode"),
            "metric": item.get("sensorId"), "value": item.get("mean"), "unit": item.get("unit"),
            "baselineMean": (item.get("baselineSamePm") or {}).get("mean"), "z": item.get("zSamePm"),
            "threshold": item.get("referenceBand"), "thresholdKind": "REFERENCE_BAND",
            "withinNormal": not item.get("outsideReferenceBand"), "setpoint": item.get("setpoint"),
            "deviationFromSetpointPct": item.get("deviationFromSetpointPct")}


def _deviations(results: dict, runs: dict, target_equipment: str | None) -> list[dict]:
    """공정 순서대로 run별 증착 · 제거량 편차, 그리고 요청 대상 설비의 참고 범위 밖 센서"""
    ordered = sorted(runs.values(), key=lambda run: PROCESS_ORDER.index(run["processCode"])
                     if run.get("processCode") in PROCESS_ORDER else 9)
    deviations = [amount_deviation(run) for run in ordered]
    for envelope in results.get("get_run_features", []):
        features = _data(envelope)
        run = runs.get(features.get("runId"), {"runId": features.get("runId"), "equipmentId": features.get("equipmentId"),
                                                "processCode": features.get("processCode")})
        if run.get("equipmentId") != target_equipment:
            continue
        deviations += [sensor_deviation(run, item) for item in features.get("items") or [] if item.get("outsideReferenceBand")]
    return deviations


def _suspected_causes(cause_ids: list[str]) -> list[dict]:
    return [{"causeId": cause_id, "candidate": CAUSES[cause_id][1], "confirmed": False,
             "evidenceToCheck": CAUSE_DETAILS.get(cause_id, {}).get("evidenceToCheck"),
             "limits": CAUSE_DETAILS.get(cause_id, {}).get("limits")} for cause_id in cause_ids]


def _related_records(results: dict, target_equipment: str | None, cause_ids: list[str]) -> list[dict]:
    """요청 대상 설비의 정비 · 변경 기록, 그리고 원인 후보의 팀 점검 문서(중복 없이)"""
    related: list[dict] = []
    for envelope in results.get("get_change_events", []):
        refs = envelope.get("evidenceRefs") or []
        for item in _data(envelope).get("items") or []:
            if target_equipment and item.get("equipmentId") != target_equipment:
                continue
            related.append({"evidenceId": refs[0]["evidenceId"] if refs else None,
                            "summary": f"{item.get('occurredAt')} {item.get('equipmentId')} {item.get('type')} "
                                       f"({item.get('changeId')}): {item.get('description')}",
                            "rootCauseConfirmed": bool(item.get("rootCauseConfirmed"))})
    seen_playbooks = set()
    for envelope in results.get("search_applicable_playbooks", []):
        for item in _data(envelope).get("items") or []:
            if item.get("causeId") in cause_ids and item.get("evidenceId") not in seen_playbooks:
                seen_playbooks.add(item.get("evidenceId"))
                related.append({"evidenceId": item.get("evidenceId"),
                                "summary": f"팀 점검 문서 {item.get('chunkId')} — {item.get('candidate')} (시연용, 승인 SOP 아님)"})
    return related


def build(messages, args: dict, action_type: str) -> dict:
    """제안 도구 인자(모델) + 이번 조사의 도구 결과(코드)로 브리프를 만든다"""
    results = tool_results(messages)
    target_equipment = args.get("equipmentId")
    deviations = _deviations(results, runs_by_id(results), target_equipment)
    cause_ids = [cause_id for cause_id in args.get("causeIds") or [] if cause_id in CAUSES]
    ask_for = list(ASK_FOR.get(action_type, []))
    ask_for += [CAUSE_DETAILS[cause_id]["evidenceToCheck"] for cause_id in cause_ids if cause_id in CAUSE_DETAILS]
    return {"reason": str(args.get("reason") or "").strip(), "deviations": deviations,
            "suspectedCauses": _suspected_causes(cause_ids),
            "relatedRecords": _related_records(results, target_equipment, cause_ids), "askFor": ask_for}

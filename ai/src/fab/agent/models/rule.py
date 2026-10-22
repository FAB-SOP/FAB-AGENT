"""RuleModel — FAB_LLM=rule. API 키 없이 그래프 · 도구 · 승인/재개 배선을 검증하는 결정적 정책.
실제 판단 품질 평가는 OpenAIModel(models/openai.py)로 한다. 모양은 `await model.ainvoke(messages, tools) -> AIMessage`.
"""

from __future__ import annotations

import re
import uuid

from langchain_core.messages import AIMessage, HumanMessage

from fab.agent.evidence import features_of, first_evidence_id, runs_by_process, search_result, tool_results, trace_of


def _call(name: str, args: dict) -> AIMessage:
    return AIMessage(content="", tool_calls=[{"id": f"call_{uuid.uuid4().hex[:10]}", "name": name, "args": args}])


def _with_answer(proposal: AIMessage, answer: AIMessage) -> AIMessage:
    """답 먼저 — 제안은 답과 같은 응답에서 낸다(Harness가 답을 먼저 접수한다). 담당자 조회가 먼저면 그것만"""
    if proposal.tool_calls[0]["name"] == "get_process_owner":
        return proposal
    return AIMessage(content="", tool_calls=proposal.tool_calls + answer.tool_calls)


class RuleModel:
    """도구 결과만 읽고 다음 행동을 고르는 결정적 정책(테스트 · 오프라인 시연용). 판정 순서:
    1) 현재 공정 run의 제거량이 정상 변동 밖이거나 센서가 참고 범위 밖 → CURRENT_CHECK (센서 → 점검 문서 causeId)
    2) 앞 공정 run(실제 경로)의 제거량만 정상 변동 밖 → UPSTREAM_CHECK (그 공정 담당자 확인 요청)
    3) 둘 다 아니면 → OBSERVE (정상 변동, 고장으로 단정하지 않음)"""

    name = "rule-policy-v2"

    async def ainvoke(self, messages, tools):
        tool_names = {tool["function"]["name"] for tool in tools}
        seen = tool_results(messages, keep_text=True)
        mode = "REPORT" if "submit_report" in tool_names else ("DEEP" if "propose_cross_process_check" in tool_names else "BASIC")
        notes = " ".join(str(message.content) for message in messages if isinstance(message, HumanMessage))
        incident_data = (seen.get("get_incident") or [{}])[-1].get("data") or {}
        incident_id = incident_data.get("incidentId") or _incident_from(notes)

        if mode == "REPORT":
            return _report_step(seen, incident_id)

        runs = runs_by_process(seen)
        process = incident_data.get("processCode", "CMP")
        current = runs.get(process)
        if current is None:
            return _submit("INSUFFICIENT_EVIDENCE", [], "사건 웨이퍼의 현재 공정 run을 찾지 못했습니다.", None, [])
        features = features_of(seen, current["runId"])
        if features is None:
            return _call("get_run_features", {"runId": current["runId"]})
        outside = [feature for feature in features["data"]["items"] if feature.get("outsideReferenceBand")]
        if not current["amountWithinNormalVariation"] or outside:
            return _current_check_step(seen, mode, notes, process, current, features, outside)
        return _upstream_step(seen, mode, notes, incident_id, process, runs, current)


def _report_step(seen: dict, incident_id: str) -> AIMessage:
    """보고서 초안 — 보고서 맥락을 한 번 읽고 섹션을 채워 낸다"""
    if "get_report_context" not in seen:
        return _call("get_report_context", {"incidentId": incident_id})
    report_context = seen["get_report_context"][-1]
    evidence_ids = [ref["evidenceId"] for ref in report_context.get("evidenceRefs", [])]
    check_requests = report_context["data"].get("checkRequests", [])
    requests_text = "; ".join(f"{request['requestId']}({request.get('requestType')}) {request['status']}"
                              for request in check_requests) or "기록 없음"
    incident = report_context["data"]["incident"]
    return _call("submit_report", {"sections": {
        "overview": incident["title"] + " — " + str(incident.get("summary")),
        "investigation": "기본 분석 · 조사 결과는 분석 결과 기록 참조 (원인 확정 아님)",
        "requestsAndReplies": requests_text,
        "actionsAndVerification": "기록 없음" if not report_context["data"]["actions"] else "조치 기록 참조",
        "unresolved": "후속 확인 run 결과 전이므로 해결 완료로 보지 않음 · 합성 데이터 기반 초안",
    }, "evidenceIds": evidence_ids})


def _current_check_step(seen, mode, notes, process, current, features, outside) -> AIMessage:
    """판정 1) 현재 공정 이상 — 센서 구간 → 점검 문서 → 원문 → (DEEP) 정비 요청 제안 → CURRENT_CHECK"""
    sensor = outside[0]["sensorId"] if outside else None
    trace = trace_of(seen, current["runId"], sensor) if sensor else None
    if sensor and trace is None:  # 언제부터 · 얼마나 오래 벗어났는지 — 시계열 참조 범위로 찾은 구간
        return _call("get_sensor_trace", {"runId": current["runId"], "sensorId": sensor, "step": "PROCESS", "maxPoints": 30})
    playbooks = search_result(seen, "search_applicable_playbooks")
    if playbooks is None:
        return _call("search_applicable_playbooks", {"query": f"{process} {sensor or '제거량 감소'} 확인 점검",
                                                     "process": process, "topK": 5})
    cause = next((item for item in playbooks["data"]["items"] if sensor and sensor in (item.get("sensorIds") or [])), None)
    if not _searched(seen, "search_technical_documents"):
        return _call("search_technical_documents", {"query": f"{process} removal rate {sensor or ''} pressure slurry",
                                                    "process": process, "topK": 2})
    facts = [_run_fact(current)] + [_feat_fact(feature, features) for feature in outside] + _segment_facts(trace)
    support = [current["_ev"], *([features["_ev"]] if outside else [])] + ([playbooks["_ev_of"](cause)] if cause else [])
    segment_limitations = [SEGMENT_LIMITATION] if trace and (trace.get("data") or {}).get("segments") else []
    headline = (f"현재 공정 {current['equipmentId']} {cause['candidate'] if cause else '제거 조건 변화'} 후보"
                f" — {sensor or '센서 이탈 없음'} 기준")
    answer = _submit("CURRENT_CHECK", facts, headline, cause, support, limitations=segment_limitations, notes=notes)
    if mode == "DEEP" and _proposal_state(seen, notes) is None:
        return _with_answer(_maintenance_proposal(seen, process, current, outside, sensor, cause, support), answer)
    return answer


def _maintenance_proposal(seen, process, current, outside, sensor, cause, support) -> AIMessage:
    owner = _owner(seen, process, "MAINTENANCE")
    if owner is None:
        return _call("get_process_owner", {"processCode": process})
    reason = _maintenance_reason(current, outside)
    return _call("propose_maintenance_request", {
        "processCode": process, "equipmentId": current["equipmentId"], "ownerUserId": owner, "reason": reason,
        "question": f"{reason} {current['equipmentId']} {sensor or '제거량'} 계통(실제값/설정값 · 공급 · 교정 이력) "
                    "점검 부탁드립니다. 장비 설정 변경은 승인 SOP 범위에서만 진행합니다.",
        "causeIds": [cause["causeId"]] if cause else [], "evidenceIds": support,
        "rationale": "현재 공정 run의 센서 참고 범위 이탈과 제거량 감소"})


def _upstream_step(seen, mode, notes, incident_id, process, runs, current) -> AIMessage:
    """판정 2) 앞 공정만 이상이면 UPSTREAM_CHECK, 3) 둘 다 아니면 OBSERVE"""
    upstream = next((run for run_process, run in runs.items() if run_process != process and not run["amountWithinNormalVariation"]
                     and run.get("_seq", 0) < current.get("_seq", 9)), None)
    if upstream is None:
        facts = [_run_fact(run) for run in runs.values()]
        return _submit("OBSERVE", facts, "세 공정 모두 정상 변동 범위 — 고장으로 단정하지 않음", None,
                       [run["_ev"] for run in runs.values()],
                       limitations=["추가 이력 · 후속 run 계측 없이는 판단을 바꾸지 않습니다."])
    upstream_features = features_of(seen, upstream["runId"])
    if upstream_features is None:
        return _call("get_run_features", {"runId": upstream["runId"]})
    if "get_change_events" not in seen:
        return _call("get_change_events", {"equipmentId": upstream["equipmentId"]})
    upstream_outside = [feature for feature in upstream_features["data"]["items"] if feature.get("outsideReferenceBand")]
    playbooks = search_result(seen, "search_applicable_playbooks")
    if playbooks is None:
        return _call("search_applicable_playbooks", {"query": f"{upstream['processCode']} 제거량 변화 챔버 세정 확인",
                                                     "process": upstream["processCode"], "topK": 5})
    if not _searched(seen, "search_past_cases"):
        return _call("search_past_cases", {"query": f"{upstream['processCode']} 잔막 상승 CMP OOC",
                                           "process": upstream["processCode"]})
    return _upstream_check_step(seen, mode, notes, incident_id, current, upstream, upstream_outside, playbooks)


def _upstream_check_step(seen, mode, notes, incident_id, current, upstream, upstream_outside, playbooks) -> AIMessage:
    sensor = upstream_outside[0]["sensorId"] if upstream_outside else None
    cause = next((item for item in playbooks["data"]["items"] if (sensor and sensor in (item.get("sensorIds") or []))
                  or (not sensor and not item.get("sensorIds"))), None)
    change_events = seen["get_change_events"][-1]
    facts = [_run_fact(upstream), _run_fact(current)]
    if change_events["data"]["items"]:
        first_change = change_events["data"]["items"][0]
        facts.append({"text": f"{first_change['equipmentId']} {first_change['type']} {first_change['occurredAt']}: "
                              f"{first_change['description']} (원인 확인된 기록 아님)",
                      "evidenceIds": [first_evidence_id(change_events)]})
    support = [upstream["_ev"], first_evidence_id(change_events)] + ([playbooks["_ev_of"](cause)] if cause else [])
    headline = f"앞 공정 {upstream['equipmentId']}의 제거량 변화 기여 — 부품 원인 미확인"
    if mode == "DEEP":
        proposal_state = _proposal_state(seen, notes)
        if proposal_state == "replied" and "get_check_requests" not in seen:
            return _call("get_check_requests", {"incidentId": incident_id})
    answer = _submit("UPSTREAM_CHECK", facts, headline, cause, support, counter=[current["_ev"]], notes=notes, seen=seen)
    if mode == "DEEP" and _proposal_state(seen, notes) is None:
        return _with_answer(_cross_process_proposal(seen, current, upstream, cause, support), answer)
    return answer


def _cross_process_proposal(seen, current, upstream, cause, support) -> AIMessage:
    owner = _owner(seen, upstream["processCode"], "PROCESS_OWNER")
    if owner is None:
        return _call("get_process_owner", {"processCode": upstream["processCode"]})
    reason = _upstream_reason(upstream, current)
    return _call("propose_cross_process_check", {
        "processCode": upstream["processCode"], "equipmentId": upstream["equipmentId"], "ownerUserId": owner, "reason": reason,
        "question": f"{reason} {upstream['equipmentId']}에서 처리된 이 run 전후의 정비 · 시즈닝 · 모니터 결과를 "
                    "확인 부탁드립니다.",
        "causeIds": [cause["causeId"]] if cause else [], "evidenceIds": support,
        "rationale": "앞 공정 run 제거량이 정상 변동 밖이고 현재 공정 제거량은 정상 변동 안"})


def _amount_word(run: dict) -> str:
    return "증착량" if run.get("amountKind") == "DEPOSITED" else "제거량"


def _upstream_reason(upstream: dict, current: dict) -> str:
    """요청 이유 — 숫자는 도구 결과 그대로(브리프 guard가 확인한다)"""
    base = ((upstream.get("baselineAmount") or {}).get("samePm") or {})
    return (f"{upstream['waferId']}의 {upstream['equipmentId']} {_amount_word(upstream)} {upstream['amountA']} Å가 "
            f"같은 PM 정상 평균 {base.get('mean')} Å "
            f"대비 z={upstream['amountZSamePm']}로 정상 변동 밖입니다. 현재 공정 {current['equipmentId']} {_amount_word(current)}은 정상 변동 안이라 "
            "앞 공정 기여를 확인하려 합니다.")


def _maintenance_reason(current: dict, outside: list[dict]) -> str:
    if not outside:
        return (f"{current['equipmentId']} {_amount_word(current)} {current['amountA']} Å가 "
                f"같은 PM 정상 대비 z={current['amountZSamePm']}로 "
                "정상 변동 밖입니다.")
    feature = outside[0]
    band = feature.get("referenceBand") or {}
    return (f"{current['equipmentId']} {feature['sensorId']} 본 처리 평균 {feature['mean']} {feature.get('unit') or ''}이 정상 참고 범위 "
            f"{band.get('lcl')}–{band.get('ucl')} 밖이고(설정값 대비 {feature.get('deviationFromSetpointPct')}%), "
            f"{_amount_word(current)} {current['amountA']} Å는 같은 PM 정상 대비 z={current['amountZSamePm']}입니다.")


def _incident_from(notes: str) -> str:
    match = re.search(r"사건 (\S+?)\]", notes)
    return match.group(1) if match else "EV-1009"


def _searched(seen, tool) -> bool:
    return tool in seen


def _owner(seen, process, kind):
    for result in seen.get("get_process_owner", []):
        for owner in (result.get("data") or {}).get("items", []):
            if owner["processCode"] == process and owner.get("kind") == kind:
                return owner["userId"]
    return None


def _proposal_state(seen, notes):
    if "[시스템 알림] 승인 결과: REJECT" in notes:
        return "rejected"
    if "[시스템 알림] 담당자 회신(" in notes:
        return "replied"
    if any(tool in seen for tool in ("propose_cross_process_check", "propose_maintenance_request")):
        return "proposed"
    return None


SEGMENT_LIMITATION = ("시계열 구간은 sensorEnvelope(center ± 4σ, 2초 이동평균, 5초 지속 — DEMO-SEGMENT-V2) 기준의 관측입니다. "
                      "4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아니고, 구간은 고장 시점 · 원인 확정이 아닙니다.")


def _segment_facts(trace) -> list[dict]:
    """구간 근거를 관측 사실로 — 숫자는 도구 결과 그대로"""
    if not trace:
        return []
    trace_data = trace.get("data") or {}
    segment_evidence_id = next((ref["evidenceId"] for ref in trace.get("evidenceRefs", [])
                                if ref["evidenceId"].startswith("E-SEG-")), None)
    env = trace_data.get("sensorEnvelope") or {}
    facts = []
    for segment in trace_data.get("segments") or []:
        word = "낮은" if segment["direction"] == "LOW" else "높은"
        facts.append({"text": f"{trace_data['sensorId']} 2초 이동평균이 시계열 참조 범위"
                              f"({env.get('lower')}–{env.get('upper')} {segment['unit']}) 밖 "
                            f"{word} 쪽으로 run 시작 기준 {segment['fromSeconds']}–{segment['toSeconds']}초 이어졌습니다(구간 평균 {segment['mean']} "
                            f"{segment['unit']}, {segment['ruleVersion']}). 관측 구간이며 고장 시점 · 원인 확정이 아닙니다.",
                      "evidenceIds": [segment_evidence_id]})
    if not facts and env:
        facts.append({"text": f"{trace_data['sensorId']} 원시 시계열에서 시계열 참조 범위 밖 지속 구간을 찾지 못했습니다({env.get('ruleVersion')}).",
                      "evidenceIds": [first_evidence_id(trace)]})
    return facts


def _run_fact(run):
    kind = "증착량" if run.get("amountKind") == "DEPOSITED" else "제거량"
    base = (run.get("baselineAmount") or {}).get("samePm") or {}
    r1_judgement = run.get("r1") or {}
    return {"text": f"{run['processCode']} {run['equipmentId']} ({run['runId']}): "
                    f"출고 평균 {run['outputMeanA']} Å, {kind} {run['amountA']} Å "
                    f"(같은 PM 정상 {base.get('mean')} ± {base.get('sd')}, z={run.get('amountZSamePm')}), "
                    f"관리한계 {'이탈 ' + r1_judgement.get('side', '') if r1_judgement.get('outside') else '안'}",
            "evidenceIds": [run["_ev"]]}


def _feat_fact(feature, features):
    return {"text": f"{feature['sensorId']} 본 처리 평균 {feature['mean']} {feature['unit']} "
                    f"(설정 {feature.get('setpoint')}, 정상 참고 범위 밖, "
                    f"같은 PM 대비 z={feature.get('zSamePm')})", "evidenceIds": [features["_ev"]]}


def _submit(step, facts, headline, cause, support, counter=None, limitations=None, notes="", seen=None):
    all_limitations = ["원인 순위는 증거 기반 우선순위이며 확률이 아닙니다. 부품 원인은 확인 전까지 미확인입니다.",
                       "시연용 합성 운전 데이터입니다.", *(limitations or [])]
    if "[시스템 알림] 승인 결과: REJECT" in notes:
        all_limitations.append("요청이 거절되어 담당자 확인 없이 정리했습니다.")
    facts = list(facts)
    if "[시스템 알림] 담당자 회신(" in notes and seen:
        reply_evidence_ids = [e["evidenceId"] for reply in seen.get("get_check_requests", [])
                              for e in reply.get("evidenceRefs", [])]
        if reply_evidence_ids:
            facts.append({"text": "담당자 회신(진술)을 받았습니다. 원본 데이터로 교차 확인 전에는 원인 확정 근거가 아닙니다.",
                          "evidenceIds": reply_evidence_ids})
    if not facts:
        facts = [{"text": headline, "evidenceIds": support[:1]}] if support else []
    return _call("submit_result", {
        "nextStep": step, "observedFacts": facts,
        "candidateCauses": [{"priority": 1, "hypothesis": headline, "causeIds": [cause["causeId"]] if cause else [],
                             "supportingEvidenceIds": [evidence_id for evidence_id in support if evidence_id],
                             "conflictingEvidenceIds": [evidence_id for evidence_id in (counter or []) if evidence_id],
                             "missingData": ["부품 · 챔버 상태의 직접 확인 결과"],
                             "nextCheck": (cause or {}).get("excerpt", "담당 엔지니어 검토")[:160]}],
        "limitations": all_limitations})

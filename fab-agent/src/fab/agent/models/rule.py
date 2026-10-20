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


class RuleModel:
    """도구 결과만 읽고 다음 행동을 고르는 결정적 정책(테스트 · 오프라인 시연용). 판정 순서:
    1) 현재 공정 run의 제거량이 정상 변동 밖이거나 센서가 참고 범위 밖 → CURRENT_CHECK (센서 → 점검 문서 causeId)
    2) 앞 공정 run(실제 경로)의 제거량만 정상 변동 밖 → UPSTREAM_CHECK (그 공정 담당자 확인 요청)
    3) 둘 다 아니면 → OBSERVE (정상 변동, 고장으로 단정하지 않음)"""

    name = "rule-policy-v2"

    async def ainvoke(self, messages, tools):
        names = {t["function"]["name"] for t in tools}
        seen = tool_results(messages, keep_text=True)
        mode = "REPORT" if "submit_report" in names else ("DEEP" if "propose_cross_process_check" in names else "BASIC")
        notes = " ".join(str(m.content) for m in messages if isinstance(m, HumanMessage))
        inc = (seen.get("get_incident") or [{}])[-1].get("data") or {}
        incident = inc.get("incidentId") or _incident_from(notes)

        if mode == "REPORT":
            if "get_report_context" not in seen:
                return _call("get_report_context", {"incidentId": incident})
            ctx = seen["get_report_context"][-1]
            ev = [e["evidenceId"] for e in ctx.get("evidenceRefs", [])]
            reqs = ctx["data"].get("checkRequests", [])
            body = "; ".join(f"{r['requestId']}({r.get('requestType')}) {r['status']}" for r in reqs) or "기록 없음"
            return _call("submit_report", {"sections": {
                "overview": ctx["data"]["incident"]["title"] + " — " + str(ctx["data"]["incident"].get("summary")),
                "investigation": "기본 분석 · 조사 결과는 분석 결과 기록 참조 (원인 확정 아님)",
                "requestsAndReplies": body,
                "actionsAndVerification": "기록 없음" if not ctx["data"]["actions"] else "조치 기록 참조",
                "unresolved": "후속 확인 run 결과 전이므로 해결 완료로 보지 않음 · 합성 데이터 기반 초안",
            }, "evidenceIds": ev})

        runs = runs_by_process(seen)
        proc = inc.get("processCode", "CMP")
        cur = runs.get(proc)
        if cur is None:
            return _submit("INSUFFICIENT_EVIDENCE", [], "사건 웨이퍼의 현재 공정 run을 찾지 못했습니다.", None, [])
        feats = features_of(seen, cur["runId"])
        if feats is None:
            return _call("get_run_features", {"runId": cur["runId"]})
        out = [f for f in feats["data"]["items"] if f.get("outsideReferenceBand")]
        cur_abnormal = not cur["amountWithinNormalVariation"]

        if cur_abnormal or out:
            sensor = out[0]["sensorId"] if out else None
            trace = trace_of(seen, cur["runId"], sensor) if sensor else None
            if sensor and trace is None:  # 언제부터 · 얼마나 오래 벗어났는지 — 시계열 참조 범위로 찾은 구간
                return _call("get_sensor_trace", {"runId": cur["runId"], "sensorId": sensor, "step": "PROCESS", "maxPoints": 30})
            pb = search_result(seen, "search_applicable_playbooks")
            if pb is None:
                return _call("search_applicable_playbooks", {"query": f"{proc} {sensor or '제거량 감소'} 확인 점검",
                                                             "process": proc, "topK": 5})
            cause = next((i for i in pb["data"]["items"] if sensor and sensor in (i.get("sensorIds") or [])), None)
            if not _searched(seen, "search_technical_documents"):
                return _call("search_technical_documents", {"query": f"{proc} removal rate {sensor or ''} pressure slurry",
                                                            "process": proc, "topK": 2})
            facts = [_run_fact(cur)] + [_feat_fact(f, feats) for f in out] + _segment_facts(trace)
            support = [cur["_ev"], *([feats["_ev"]] if out else [])] + ([pb["_ev_of"](cause)] if cause else [])
            seg_lim = [SEGMENT_LIMITATION] if trace and (trace.get("data") or {}).get("segments") else []
            head = (f"현재 공정 {cur['equipmentId']} {cause['candidate'] if cause else '제거 조건 변화'} 후보"
                    f" — {sensor or '센서 이탈 없음'} 기준")
            if mode == "DEEP":
                done = _proposal_state(seen, notes)
                if done is None:
                    owner = _owner(seen, proc, "MAINTENANCE")
                    if owner is None:
                        return _call("get_process_owner", {"processCode": proc})
                    reason = _maintenance_reason(cur, out)
                    return _call("propose_maintenance_request", {
                        "processCode": proc, "equipmentId": cur["equipmentId"], "ownerUserId": owner, "reason": reason,
                        "question": f"{reason} {cur['equipmentId']} {sensor or '제거량'} 계통(실제값/설정값 · 공급 · 교정 이력) "
                                    "점검 부탁드립니다. 장비 설정 변경은 승인 SOP 범위에서만 진행합니다.",
                        "causeIds": [cause["causeId"]] if cause else [], "evidenceIds": support,
                        "rationale": "현재 공정 run의 센서 참고 범위 이탈과 제거량 감소"})
            return _submit("CURRENT_CHECK", facts, head, cause, support, limitations=seg_lim, notes=notes)

        up = next((r for p, r in runs.items() if p != proc and not r["amountWithinNormalVariation"]
                   and r.get("_seq", 0) < cur.get("_seq", 9)), None)
        if up is None:
            facts = [_run_fact(r) for r in runs.values()]
            return _submit("OBSERVE", facts, "세 공정 모두 정상 변동 범위 — 고장으로 단정하지 않음", None,
                           [r["_ev"] for r in runs.values()],
                           limitations=["추가 이력 · 후속 run 계측 없이는 판단을 바꾸지 않습니다."])
        uf = features_of(seen, up["runId"])
        if uf is None:
            return _call("get_run_features", {"runId": up["runId"]})
        if "get_change_events" not in seen:
            return _call("get_change_events", {"equipmentId": up["equipmentId"]})
        upout = [f for f in uf["data"]["items"] if f.get("outsideReferenceBand")]
        pb = search_result(seen, "search_applicable_playbooks")
        if pb is None:
            return _call("search_applicable_playbooks", {"query": f"{up['processCode']} 제거량 변화 챔버 세정 확인",
                                                         "process": up["processCode"], "topK": 5})
        if not _searched(seen, "search_past_cases"):
            return _call("search_past_cases", {"query": f"{up['processCode']} 잔막 상승 CMP OOC", "process": up["processCode"]})
        sensor = upout[0]["sensorId"] if upout else None
        cause = next((i for i in pb["data"]["items"] if (sensor and sensor in (i.get("sensorIds") or []))
                      or (not sensor and not i.get("sensorIds"))), None)
        chg = seen["get_change_events"][-1]
        facts = [_run_fact(up), _run_fact(cur)]
        if chg["data"]["items"]:
            c0 = chg["data"]["items"][0]
            facts.append({"text": f"{c0['equipmentId']} {c0['type']} {c0['occurredAt']}: {c0['description']} "
                                  f"(원인 확인된 기록 아님)", "evidenceIds": [first_evidence_id(chg)]})
        support = [up["_ev"], first_evidence_id(chg)] + ([pb["_ev_of"](cause)] if cause else [])
        head = f"앞 공정 {up['equipmentId']}의 제거량 변화 기여 — 부품 원인 미확인"
        if mode == "DEEP":
            done = _proposal_state(seen, notes)
            if done is None:
                owner = _owner(seen, up["processCode"], "PROCESS_OWNER")
                if owner is None:
                    return _call("get_process_owner", {"processCode": up["processCode"]})
                reason = _upstream_reason(up, cur)
                return _call("propose_cross_process_check", {
                    "processCode": up["processCode"], "equipmentId": up["equipmentId"], "ownerUserId": owner, "reason": reason,
                    "question": f"{reason} {up['equipmentId']}에서 처리된 이 run 전후의 정비 · 시즈닝 · 모니터 결과를 "
                                "확인 부탁드립니다.",
                    "causeIds": [cause["causeId"]] if cause else [], "evidenceIds": support,
                    "rationale": "앞 공정 run 제거량이 정상 변동 밖이고 현재 공정 제거량은 정상 변동 안"})
            if done == "replied" and "get_check_requests" not in seen:
                return _call("get_check_requests", {"incidentId": incident})
        return _submit("UPSTREAM_CHECK", facts, head, cause, support, counter=[cur["_ev"]], notes=notes, seen=seen)


def _amount_word(r: dict) -> str:
    return "증착량" if r.get("amountKind") == "DEPOSITED" else "제거량"


def _upstream_reason(up: dict, cur: dict) -> str:
    """요청 이유 — 숫자는 도구 결과 그대로(브리프 guard가 확인한다)"""
    base = ((up.get("baselineAmount") or {}).get("samePm") or {})
    return (f"{up['waferId']}의 {up['equipmentId']} {_amount_word(up)} {up['amountA']} Å가 같은 PM 정상 평균 {base.get('mean')} Å "
            f"대비 z={up['amountZSamePm']}로 정상 변동 밖입니다. 현재 공정 {cur['equipmentId']} {_amount_word(cur)}은 정상 변동 안이라 "
            "앞 공정 기여를 확인하려 합니다.")


def _maintenance_reason(cur: dict, outside: list[dict]) -> str:
    if not outside:
        return (f"{cur['equipmentId']} {_amount_word(cur)} {cur['amountA']} Å가 같은 PM 정상 대비 z={cur['amountZSamePm']}로 "
                "정상 변동 밖입니다.")
    f = outside[0]
    band = f.get("referenceBand") or {}
    return (f"{cur['equipmentId']} {f['sensorId']} 본 처리 평균 {f['mean']} {f.get('unit') or ''}이 정상 참고 범위 "
            f"{band.get('lcl')}–{band.get('ucl')} 밖이고(설정값 대비 {f.get('deviationFromSetpointPct')}%), "
            f"{_amount_word(cur)} {cur['amountA']} Å는 같은 PM 정상 대비 z={cur['amountZSamePm']}입니다.")


def _incident_from(notes: str) -> str:
    m = re.search(r"사건 (\S+?)\]", notes)
    return m.group(1) if m else "EV-1009"


def _searched(seen, tool) -> bool:
    return tool in seen


def _owner(seen, proc, kind):
    for r in seen.get("get_process_owner", []):
        for o in (r.get("data") or {}).get("items", []):
            if o["processCode"] == proc and o.get("kind") == kind:
                return o["userId"]
    return None


def _proposal_state(seen, notes):
    if "[시스템 알림] 승인 결과: REJECT" in notes:
        return "rejected"
    if "[시스템 알림] 담당자 회신(" in notes:
        return "replied"
    if any(n in seen for n in ("propose_cross_process_check", "propose_maintenance_request")):
        return "proposed"
    return None


SEGMENT_LIMITATION = ("시계열 구간은 sensorEnvelope(center ± 4σ, 2초 이동평균, 5초 지속 — DEMO-SEGMENT-V2) 기준의 관측입니다. "
                      "4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아니고, 구간은 고장 시점 · 원인 확정이 아닙니다.")


def _segment_facts(trace) -> list[dict]:
    """구간 근거를 관측 사실로 — 숫자는 도구 결과 그대로"""
    if not trace:
        return []
    d = trace.get("data") or {}
    seg_ev = next((e["evidenceId"] for e in trace.get("evidenceRefs", []) if e["evidenceId"].startswith("E-SEG-")), None)
    env = d.get("sensorEnvelope") or {}
    out = []
    for s in d.get("segments") or []:
        word = "낮은" if s["direction"] == "LOW" else "높은"
        out.append({"text": f"{d['sensorId']} 2초 이동평균이 시계열 참조 범위({env.get('lower')}–{env.get('upper')} {s['unit']}) 밖 "
                            f"{word} 쪽으로 run 시작 기준 {s['fromSeconds']}–{s['toSeconds']}초 이어졌습니다(구간 평균 {s['mean']} "
                            f"{s['unit']}, {s['ruleVersion']}). 관측 구간이며 고장 시점 · 원인 확정이 아닙니다.",
                    "evidenceIds": [seg_ev]})
    if not out and env:
        out.append({"text": f"{d['sensorId']} 원시 시계열에서 시계열 참조 범위 밖 지속 구간을 찾지 못했습니다({env.get('ruleVersion')}).",
                    "evidenceIds": [first_evidence_id(trace)]})
    return out


def _run_fact(r):
    kind = "증착량" if r.get("amountKind") == "DEPOSITED" else "제거량"
    base = (r.get("baselineAmount") or {}).get("samePm") or {}
    r1 = r.get("r1") or {}
    return {"text": f"{r['processCode']} {r['equipmentId']} ({r['runId']}): 출고 평균 {r['outputMeanA']} Å, {kind} {r['amountA']} Å "
                    f"(같은 PM 정상 {base.get('mean')} ± {base.get('sd')}, z={r.get('amountZSamePm')}), "
                    f"관리한계 {'이탈 ' + r1.get('side', '') if r1.get('outside') else '안'}",
            "evidenceIds": [r["_ev"]]}


def _feat_fact(f, feats):
    return {"text": f"{f['sensorId']} 본 처리 평균 {f['mean']} {f['unit']} (설정 {f.get('setpoint')}, 정상 참고 범위 밖, "
                    f"같은 PM 대비 z={f.get('zSamePm')})", "evidenceIds": [feats["_ev"]]}


def _submit(step, facts, headline, cause, support, counter=None, limitations=None, notes="", seen=None):
    lim = ["원인 순위는 증거 기반 우선순위이며 확률이 아닙니다. 부품 원인은 확인 전까지 미확인입니다.",
           "시연용 합성 운전 데이터입니다.", *(limitations or [])]
    if "[시스템 알림] 승인 결과: REJECT" in notes:
        lim.append("요청이 거절되어 담당자 확인 없이 정리했습니다.")
    facts = list(facts)
    if "[시스템 알림] 담당자 회신(" in notes and seen:
        rev = [e["evidenceId"] for r in seen.get("get_check_requests", []) for e in r.get("evidenceRefs", [])]
        if rev:
            facts.append({"text": "담당자 회신(진술)을 받았습니다. 원본 데이터로 교차 확인 전에는 원인 확정 근거가 아닙니다.",
                          "evidenceIds": rev})
    if not facts:
        facts = [{"text": headline, "evidenceIds": support[:1]}] if support else []
    return _call("submit_result", {
        "nextStep": step, "observedFacts": facts,
        "candidateCauses": [{"priority": 1, "hypothesis": headline, "causeIds": [cause["causeId"]] if cause else [],
                             "supportingEvidenceIds": [x for x in support if x],
                             "conflictingEvidenceIds": [x for x in (counter or []) if x],
                             "missingData": ["부품 · 챔버 상태의 직접 확인 결과"],
                             "nextCheck": (cause or {}).get("excerpt", "담당 엔지니어 검토")[:160]}],
        "limitations": lim})

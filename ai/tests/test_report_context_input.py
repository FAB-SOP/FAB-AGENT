"""보고서 입력 정리 — 도구 결과가 한도를 넘어도 JSON을 중간에서 자르지 않고 항목 단위로 줄인다.

예전에는 6,000자에서 문자열을 잘라 깨진 JSON이 모델에 갔고 뒤쪽 요청 · 회신이 사라졌다. 보고서 입력(get_report_context)은
요청 하나 · 회신 하나로 이미 5,000자를 넘었다(10-23 측정). 줄일 때 원인 분석의 반증 후보 · 한계, 회신, 실제 조치는 남긴다.
"""

from __future__ import annotations

import json

from fab.agent.harness.context import to_tool_text
from fab.agent.harness.limits import LIMITS
from fab.agent.harness.shrink import shrink, size

LIMIT = LIMITS.max_tool_result_chars


def _request(i: int, findings: str) -> dict:
    return {"requestId": f"chk_{i}", "incidentId": "inc_1", "requestType": "CROSS_PROCESS", "status": "REPLIED",
            "targetProcessCode": "ETCH", "question": f"질문 {i}", "createdAt": f"2026-10-2{i}T10:00:00+09:00",
            "ownerUserId": "USR-ETCH-01", "version": 3, "decisionComment": None,
            "brief": {"reason": "ETCH 제거량이 정상 변동 밖", "askFor": ["세정 이력"] * 3,
                      "deviations": [{"processCode": "ETCH", "runId": "RUN-1", "value": 4896.3, "z": -2.74, "baselineMean": 4999.0,
                                      "withinNormal": False, "thresholdKind": "ABS_Z_SAME_PM"}] * 3,
                      "relatedRecords": [{"summary": "챔버 정비 기록 " * 10, "evidenceId": "E-CHG-1"}] * 2},
            "reply": {"replyId": f"rpl_{i}", "requestId": f"chk_{i}", "findings": findings, "evidenceIds": [], "memo": ""}}


def _context(n_requests: int = 1, findings: str = "확인했습니다.", analysis: dict | None | str = "absent") -> dict:
    data = {"incident": {"incidentId": "inc_1", "displayId": "EV-1009", "title": "CMP-PM3 두께 이탈"},
            "checkRequests": [_request(i, f"회신{i} " + findings) for i in range(n_requests)],
            "actions": [{"actionId": "act_1", "description": "ETCH-PM2 시즈닝 재실시", "performedAt": "2026-10-23T09:00:00+09:00"}],
            "reports": [{"reportId": "rpt_1", "version": 1, "status": "DRAFT"}],
            "workItems": [{"taskId": f"task_{i}", "incidentId": "inc_1", "title": "이력 확인", "status": "PROPOSED",
                           "createdBy": "agent", "version": 1, "sourceCauseId": None} for i in range(3)],
            "followupNote": "후속 확인 run 결과가 없으면 해결 완료로 보고하지 않습니다."}
    if analysis != "absent":
        data["latestCauseAnalysis"] = analysis
    return {"data": data, "evidenceRefs": [{"evidenceId": "E-RPTCTX-EV-1009"}], "dataVersion": "PACK", "warnings": []}


ANALYSIS = {"resultId": "res_1", "answer": "앞 공정 ETCH 영향이 1순위입니다.", "nextStep": "UPSTREAM_CHECK",
            "candidateCauses": [{"candidateId": f"C{i}", "stance": "REFUTED" if i == 2 else "SUPPORTED",
                                 "hypothesis": "가설 설명 " * 40, "conflictingEvidenceIds": ["E-RUN-CMP"]} for i in range(4)],
            "limitations": ["한계 설명 " * 30 for _ in range(3)]}


def test_long_generic_result_becomes_valid_json_within_the_limit():
    result = {"data": {"items": [{"evidenceId": f"E-{i}", "text": "센서 값 " * 30} for i in range(60)]}}
    text = to_tool_text(result, "get_run_features")
    view = json.loads(text)  # 깨진 JSON이 아니다
    assert len(text) <= LIMIT and "잘렸습니다" not in text
    assert view["_omitted"]["items"]["data.items"] > 0  # 몇 개를 뺐는지 모델이 안다


def test_short_results_are_unchanged():
    result = {"data": {"value": 1, "note": None}}
    assert to_tool_text(result, "get_run_features") == json.dumps(result, ensure_ascii=False, separators=(",", ":"))


def test_report_view_keeps_what_the_draft_needs_and_drops_internal_fields():
    view = json.loads(to_tool_text(_context(analysis=None), "get_report_context"))["data"]
    assert "incidentId" not in view["incident"] and view["latestCauseAnalysis"] is None  # 분석 없음은 null로 보인다
    request = view["checkRequests"][0]
    assert request["reply"]["findings"].startswith("회신0") and "ownerUserId" not in request and "version" not in request
    assert view["workItems"][0] == {"title": "이력 확인", "status": "PROPOSED"} and view["actions"]


def test_long_report_input_keeps_refuted_causes_limitations_replies_and_actions():
    raw = _context(n_requests=8, findings="세정 기록 확인 결과 " * 80, analysis=ANALYSIS)
    assert size(raw) > 3 * LIMIT
    text = to_tool_text(raw, "get_report_context")
    out = json.loads(text)
    view = out["data"]
    assert len(text) <= LIMIT
    analysis = view["latestCauseAnalysis"]
    assert [c["stance"] for c in analysis["candidateCauses"]] == ["SUPPORTED", "SUPPORTED", "REFUTED", "SUPPORTED"]
    assert len(analysis["limitations"]) == 3 and analysis["answer"] and view["actions"] and view["followupNote"]
    assert all(request["reply"]["findings"] for request in view["checkRequests"])  # 남은 요청은 회신을 가진다
    assert view["checkRequests"][0]["requestId"] == "chk_7"  # 최신 요청이 남고 오래된 것부터 빠진다
    omitted = out["_omitted"]["items"]
    assert omitted["data.workItems"] == 3 and "기록이 더 있습니다" in out["_omitted"]["note"]


def test_shrink_drops_listed_fields_before_whole_items():
    obj = {"rows": [{"keep": "회신 " * 50, "extra": "부속 " * 200} for _ in range(5)]}
    out = shrink(obj, 1200, drop_order=(("rows", "*", "extra"),), protected=(("rows", "*", "keep"),))
    assert size(out) <= 1200 and len(out["rows"]) == 5 and all("keep" in row for row in out["rows"])  # 항목은 그대로
    assert out["_omitted"]["items"]["rows.*.extra"] >= 1 and "rows" not in out["_omitted"]["items"]

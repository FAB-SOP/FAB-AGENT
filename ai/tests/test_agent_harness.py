"""Agent · Harness (Spring 하나에 붙어서) — 정답 · 승인 전 변경 0 · 거절 · 잘못된 재개 · 예외 · 중복 · 재시도 · DLQ · 한도 · 계약."""

import json

import pytest
from langchain_core.messages import AIMessage, HumanMessage

from fab.agent.harness.guard import validate_result
from fab.agent.worker import poll_once
from fab.devtools.demo import evaluate
from fab.devtools.spring import User, drive, internal, make_runner


async def _ask(spring_url, display_id, question="원인 분석해줘", user="cmp.park"):
    u = await User(user, spring_url).login()
    i = await u.incident(display_id)
    st, acc = await u.post(f"/api/incidents/{i['incidentId']}/questions", {"question": question,
                                                                         "expectedIncidentVersion": i["version"]})
    assert st == 202, acc
    return u, acc["data"]["jobId"]


@pytest.mark.skipif(not __import__("fab.devtools.demo", fromlist=["GROUND_TRUTH"]).GROUND_TRUTH.exists(),
                    reason="평가자 전용 정답표(evaluation/ground_truth.json)가 없다 — 공개 저장소에는 올리지 않는다")
async def test_eval_all_incidents_pass(spring_url, kb):
    rows = await evaluate(kb)
    assert all(r["pass"] for r in rows), rows


async def test_no_change_before_approval_and_reject_path(spring_url, kb):
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    u, job = await _ask(spring_url, "EV-1009")
    out = (await poll_once(runner, backend))[-1]
    assert out["status"] == "WAITING_APPROVAL"
    assert out["trace"]["apiModels"] == ["rule-policy-v2"]  # 규칙 모델은 API를 부르지 않는다 — 고른 이름 그대로
    inc = await u.incident("EV-1009")
    _, reqs = await u.get(f"/api/check-requests?box=sent&incidentId={inc['incidentId']}")
    before = len(reqs["data"]["items"])
    _, a = await u.get(f"/api/approvals/{out['interrupt']['approvalId']}")
    assert a["data"]["actionType"] == "CHECK_REQUEST" and a["data"]["payload"]["targetProcessCode"] == "ETCH"
    st, _ = await u.post(f"/api/approvals/{a['data']['approvalId']}/decisions",
                         {"decision": "REJECT", "comment": "지금은 불필요", "expectedVersion": a["data"]["version"]})
    assert st == 200
    out = await poll_once(runner, backend)
    _, reqs = await u.get(f"/api/check-requests?box=sent&incidentId={inc['incidentId']}")
    assert len(reqs["data"]["items"]) == before, "거절 후 변경 0건"
    # 거절도 같은 thread로 재개된다 — Agent가 거절 사유를 읽고 결과를 정리하고, 다음 질문을 받을 수 있다
    assert out and out[-1]["job"]["kind"] == "RESUME" and out[-1]["status"] == "DONE", out
    u2, _ = await _ask(spring_url, "EV-1009", "그럼 지금 근거로 다시 정리해줘")
    assert (await poll_once(runner, backend))[-1]["status"] in ("DONE", "WAITING_APPROVAL")


async def test_full_flow_records_request_only_after_approval(spring_url, kb):
    r = await drive("EV-1009", "이전 공정 영향도 확인해줘", kb=kb, verbose=False, base=spring_url)
    assert r["analysis"]["nextStep"] == "UPSTREAM_CHECK"
    assert [p["actionType"] for p in r["proposals"]] == ["CREATE_CHECK_REQUEST"]
    assert r["report"] and set(r["report"]["sections"]) == {"overview", "investigation", "requestsAndReplies",
                                                            "actionsAndVerification", "unresolved"}


async def test_basic_analysis_does_not_create_work_cards(spring_url, kb):
    import uuid
    import httpx

    runner, backend = make_runner(kb, base=spring_url)
    event = {"eventId": "ORIGINAL-FLOW-" + str(uuid.uuid4()), "anomalyType": "THICKNESS_OOC_R1",
             "severity": "MEDIUM", "processCode": "CMP", "equipmentId": "CMP-PM3", "lotId": "CURRENT-L006",
             "waferIds": ["W-CURRENT-006-3"], "runIds": ["RUN-W-CURRENT-006-3-CMP"],
             "sensorIds": ["head_pressure"], "detectedAt": "2026-10-19T01:30:00Z", "metrics": {}}
    async with httpx.AsyncClient(base_url=spring_url) as client:
        response = await client.post("/api/events/anomalies", json=event,
                                     headers={"X-Service-Token": "dev-simulator-token"})
    assert response.status_code == 202, response.text
    accepted = response.json()["data"]
    processed = await poll_once(runner, backend)
    result = next(item for item in processed if item["job"]["jobId"] == accepted["jobId"])
    assert result["status"] == "DONE"
    assert result["result"]["candidateCauses"]
    event = next(e for e in runner.sink.events if e["jobId"] == accepted["jobId"] and e["type"] == "RESULT_READY")
    assert "workSuggestions" not in event["payload"]
    assert "toolCalls" in event["metrics"]  # 기존 흐름 복구 후에도 작업별 관측 정보는 유지한다.
    user = await User("cmp.park", spring_url).login()
    status, tasks = await user.get(f"/api/incidents/{accepted['incidentId']}/work-items")
    assert status == 200 and tasks["data"]["items"] == []
    await user.close()


async def test_resume_with_wrong_interrupt_is_rejected(spring_url, kb):
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    u, _ = await _ask(spring_url, "EV-1006")
    out = (await poll_once(runner, backend))[-1]
    assert out["status"] == "WAITING_APPROVAL"
    _, a = await u.get(f"/api/approvals/{out['interrupt']['approvalId']}")
    _, dec = await u.post(f"/api/approvals/{a['data']['approvalId']}/decisions",
                          {"decision": "APPROVE", "expectedVersion": a["data"]["version"], "actionHash": a["data"]["actionHash"]})
    ctx = (await internal("GET", f"/internal/jobs/{dec['data']['resumeJobId']}/context", spring_url))["data"]
    job = ctx["job"] | {"resume": ctx["job"]["resume"] | {"interruptId": "INT-FAKE"}}
    bad = await runner.process(job)
    assert bad["status"] == "FAILED" and runner.sink.events[-1]["type"] == "JOB_FAILED"
    _, j = await u.get(f"/api/jobs/{job['jobId']}")
    assert j["data"]["status"] == "FAILED"


async def test_duplicate_delivery_is_skipped_and_unexpected_error_fails(spring_url, kb):
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    _, job_id = await _ask(spring_url, "EV-1007", "기본 사항만 정리해줘")
    ctx = (await internal("GET", f"/internal/jobs/{job_id}/context", spring_url))["data"]
    first = await runner.process(ctx["job"], question=ctx["question"])
    second = await runner.process(ctx["job"], question=ctx["question"])  # 중복 전달 · 재시작 후 재전달
    assert first["status"] in ("DONE", "WAITING_APPROVAL") and second["status"] == "SKIPPED"

    class Broken(type(backend)):
        async def claim(self, *a, **k):
            raise KeyError("boom")

    runner2, _ = make_runner(kb, base=spring_url)
    runner2.backend = Broken(spring_url)
    _, job2 = await _ask(spring_url, "EV-1007", "다시 정리해줘")
    ctx2 = (await internal("GET", f"/internal/jobs/{job2}/context", spring_url))["data"]
    out = await runner2.process(ctx2["job"], question=ctx2["question"])
    assert out["status"] == "FAILED" and out["errorCode"] == "INTERNAL_ERROR"
    await poll_once(runner, backend)


async def test_transient_fault_retries_then_exhausts_to_dlq(spring_url, kb):
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    await internal("POST", "/internal/demo/faults", spring_url, json={"tool": "get_incident", "count": 3})
    _, job_id = await _ask(spring_url, "EV-1007", "재시도 확인")
    first = (await poll_once(runner, backend))[-1]
    assert first["status"] == "RETRY_SCHEDULED"
    second = (await poll_once(runner, backend))[-1]  # Spring이 같은 jobId · attempt 2로 다시 대기열에 둠
    assert second["job"]["jobId"] == job_id and second["job"]["attempt"] == 2 and second["status"] != "RETRY_SCHEDULED"

    await internal("POST", "/internal/demo/faults", spring_url, json={"tool": "get_incident", "count": 99})
    _, job2 = await _ask(spring_url, "EV-1007", "계속 실패")
    statuses = [(await poll_once(runner, backend))[-1]["status"] for _ in range(3)]
    await internal("POST", "/internal/demo/faults", spring_url, json={"tool": "*"})
    assert statuses == ["RETRY_SCHEDULED", "RETRY_SCHEDULED", "FAILED"]
    assert runner.sink.dlq and runner.sink.events[-1]["payload"]["dlqRef"].startswith("analysis.jobs.dlq/")


class LoopingModel:
    """같은 도구만 계속 부르는 모델 — 한도가 멈춰야 한다."""
    name = "looping"

    async def ainvoke(self, messages, tools):
        return AIMessage(content="", tool_calls=[{"id": f"c{len(messages)}", "name": "get_frozen_limits", "args": {}}])


async def test_loop_limits_stop_the_agent(spring_url, kb):
    runner, backend = make_runner(kb, models=(LoopingModel(), None), base=spring_url)
    await poll_once(runner, backend)
    await _ask(spring_url, "EV-1006", "한도 확인")
    out = (await poll_once(runner, backend))[-1]
    assert out["status"] == "DONE" and "한도" in (out["analysis"]["stopReason"] or "")
    assert out["trace"]["modelCalls"] <= 8


def test_guard_rejects_unknown_evidence_and_probabilities():
    errs = validate_result({"nextStep": "CURRENT_CHECK", "observedFacts": [{"text": "x", "evidenceIds": ["E-NOPE"]}],
                            "candidateCauses": [], "limitations": []}, {"E-RUN-1"})
    assert any("조회하지 않은" in e for e in errs)
    errs = validate_result({"nextStep": "CURRENT_CHECK",
                            "observedFacts": [{"text": "헤드 압력이 원인일 확률 85%", "evidenceIds": ["E-1"]}],
                            "candidateCauses": [], "limitations": []}, {"E-1"})
    assert any("확률" in e for e in errs)
    assert not validate_result({"nextStep": "OBSERVE", "observedFacts": [{"text": "정상 변동", "evidenceIds": ["E-1"]}],
                                "candidateCauses": [], "limitations": []}, {"E-1"})


@pytest.mark.parametrize("display_id", ["EV-1006", "EV-1009"])
async def test_events_and_jobs_follow_team_contract(display_id, spring_url, kb, contract_validators):
    ev_v, job_v = contract_validators
    r = await drive(display_id, "원인 분석과 필요한 요청을 제안해줘", kb=kb, verbose=False, base=spring_url)
    bad = [(e["type"], [x.message[:120] for x in ev_v.iter_errors(e)]) for e in r["events"]]
    assert not [b for b in bad if b[1]], bad
    ctx = (await internal("GET", "/internal/jobs", spring_url))["data"]
    assert all(not list(job_v.iter_errors(x["job"])) for x in ctx)
    # Observability — 작업을 끝내거나 멈추는 이벤트에만 trace 요약(metrics)이 실리고, 진행 이벤트에는 없다
    stops = {"RESULT_READY", "REPORT_DRAFT_READY", "APPROVAL_REQUIRED", "WAITING_REPLY", "RETRY_SCHEDULED", "JOB_FAILED"}
    assert all(("metrics" in e) == (e["type"] in stops) for e in r["events"]), [(e["type"], "metrics" in e) for e in r["events"]]
    m = next(e["metrics"] for e in r["events"] if e["type"] in stops)
    assert m["modelCalls"] >= 1 and m["toolCalls"] >= 1 and m["attempt"] >= 1 and m["traceId"].startswith("TRACE-")


class SecondProposalModel:
    """회신을 받은 뒤 정비 요청을 한 번 더 제안하는 모델 — 실제 모델이 그렇게 했다(2026-10-20 실LLM 평가).
    두 번째 제안은 지금 대기에 들어가는 RESUME 작업에 붙어야 승인이 '기록만'되고 Agent가 재개된다."""
    name = "second-proposal"

    def __init__(self):
        from fab.agent.models import RuleModel

        self.rule = RuleModel()

    async def ainvoke(self, messages, tools):
        r = await self.rule.ainvoke(messages, tools)
        proposed = sum("[PROPOSED]" in str(getattr(m, "content", "")) for m in messages)
        call = (r.tool_calls or [{}])[0]
        if call.get("name") == "submit_result" and proposed == 1:
            ev = [e for f in call["args"].get("observedFacts", []) for e in f.get("evidenceIds", [])][:2]
            # 근거가 있는 대상 — EV-1009에서 제거량이 정상 변동 밖인 ETCH-PM2(CMP-PM3는 정상 변동 안이라 정합성 검사가 막는다)
            return AIMessage(content="", tool_calls=[{"id": "c-second", "name": "propose_maintenance_request", "args": {
                "processCode": "ETCH", "equipmentId": "ETCH-PM2", "ownerUserId": "USR-MNT-01",
                "question": "ETCH-PM2 챔버 세정 · 시즈닝 이력 점검 부탁드립니다(설정 변경 아님).", "causeIds": ["ETCH_WALL"],
                "evidenceIds": ev, "rationale": "회신 뒤 해당 설비 점검도 함께",
                "reason": "앞 공정 회신과 별개로 ETCH-PM2 챔버 상태 점검이 필요합니다."}}, call])  # 답 먼저 — 답과 함께
        return r


async def test_second_proposal_after_reply_attaches_to_resume_job(spring_url, kb):
    r = await drive("EV-1009", "이전 공정 영향도 확인해줘", kb=kb, models=(SecondProposalModel(), None), report=False,
                    verbose=False, base=spring_url)
    kinds = [p["actionType"] for p in r["proposals"]]
    assert kinds == ["CREATE_CHECK_REQUEST", "CREATE_MAINTENANCE_REQUEST"]
    jobs = []
    for p in r["proposals"]:
        a = (await internal("GET", f"/internal/approvals/{p['approvalId']}", spring_url))["data"]
        # 둘 다 Agent가 승인 ID로 실행했다(수동 제안처럼 승인 즉시 실행되지 않았다)
        assert a["status"] == "APPROVED" and a["executionRef"] and a["resumeJobId"], a
        jobs.append(a["jobId"])
    assert jobs[0] != jobs[1], "두 번째 제안은 회신 뒤 재개 작업에 붙는다"
    assert r["result"]["jobId"] != jobs[0], "결과의 jobId는 마지막으로 처리한 작업"


def test_cause_ids_match_pack_matrix():
    import json

    from fab.agent.causes import CAUSES
    from fab.common.env import pack_dir

    pack = json.loads((pack_dir() / "knowledge" / "cause-matrix.json").read_text(encoding="utf-8"))
    assert {c["causeId"]: c["process"] for c in pack} == {k: v[0] for k, v in CAUSES.items()}
    from fab.agent.causes import CAUSE_DETAILS

    assert {c["causeId"]: {"evidenceToCheck": c["evidenceToCheck"], "limits": c["limits"]} for c in pack} == CAUSE_DETAILS


def test_guard_rejects_unknown_cause_and_inconsistent_next_step():
    fact = [{"text": "Etch 제거량 정상 변동 밖", "evidenceIds": ["E-1"]}]
    made_up = {"nextStep": "CURRENT_CHECK", "observedFacts": fact, "limitations": [],
               "candidateCauses": [{"priority": 1, "hypothesis": "Etch 제거량 변동", "causeIds": ["ETCH_REMOVAL_VARIATION"],
                                    "supportingEvidenceIds": ["E-1"]}]}
    assert any("원인 매트릭스에 없는" in e for e in validate_result(made_up, {"E-1"}, "CMP"))
    upstream_top = made_up | {"candidateCauses": [made_up["candidateCauses"][0] | {"causeIds": ["ETCH_WALL"]}]}
    assert any("UPSTREAM_CHECK" in e for e in validate_result(upstream_top, {"E-1"}, "CMP"))
    assert validate_result(upstream_top | {"nextStep": "UPSTREAM_CHECK"}, {"E-1"}, "CMP") == []
    no_id = upstream_top | {"candidateCauses": [made_up["candidateCauses"][0] | {"causeIds": []}]}
    assert any("causeId가 하나 이상" in e for e in validate_result(no_id, {"E-1"}, "CMP"))
    cur = made_up | {"candidateCauses": [made_up["candidateCauses"][0] | {"causeIds": ["CMP_PRESSURE"]}]}
    assert any("앞 공정이 아닙니다" in e for e in validate_result(cur | {"nextStep": "UPSTREAM_CHECK"}, {"E-1"}, "CMP"))


class ProposeEveryTurnModel(SecondProposalModel):
    """승인 · 회신 뒤마다 정비 요청을 또 제안하려는 모델 — 같은 제안 반복 · 상한을 Harness가 막아야 한다."""

    async def ainvoke(self, messages, tools):
        r = await self.rule.ainvoke(messages, tools)
        call = (r.tool_calls or [{}])[0]
        if call.get("name") == "submit_result" and any("[PROPOSED]" in str(getattr(m, "content", "")) for m in messages) \
                and not any("[LIMIT]" in str(getattr(m, "content", "")) or "이미 제안" in str(getattr(m, "content", ""))
                            for m in messages[-2:]):
            n = sum("[PROPOSED]" in str(getattr(m, "content", "")) for m in messages)
            ev = [e for f in call["args"].get("observedFacts", []) for e in f.get("evidenceIds", [])][:1]
            return AIMessage(content="", tool_calls=[{"id": f"c-again-{n}-{len(messages)}", "name": "propose_maintenance_request",
                                                      "args": {"processCode": "ETCH", "equipmentId": "ETCH-PM2",
                                                               "ownerUserId": "USR-MNT-01", "question": "다시 점검 부탁",
                                                               "causeIds": ["ETCH_WALL"], "evidenceIds": ev,
                                                               "rationale": "반복", "reason": "다시 점검이 필요합니다."}}, call])
        return r


async def test_proposals_are_capped_and_not_repeated(spring_url, kb):
    r = await drive("EV-1009", "이전 공정 영향도 확인해줘", kb=kb, models=(ProposeEveryTurnModel(), None), report=False,
                    verbose=False, base=spring_url)
    kinds = [p["actionType"] for p in r["proposals"]]
    assert kinds == ["CREATE_CHECK_REQUEST", "CREATE_MAINTENANCE_REQUEST"], kinds  # 상한 2 — 세 번째 · 같은 반복은 반려


async def _first_proposal(spring_url, kb, display_id, question):
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    u, _ = await _ask(spring_url, display_id, question)
    out = (await poll_once(runner, backend))[-1]
    assert out["status"] == "WAITING_APPROVAL", out
    _, a = await u.get(f"/api/approvals/{out['interrupt']['approvalId']}")
    return u, a["data"]


async def test_upstream_request_brief_carries_numbers_causes_and_records(spring_url, kb):
    u, a = await _first_proposal(spring_url, kb, "EV-1009", "이전 공정 영향도 확인해줘")
    b = a["payload"]["brief"]
    etch = next(d for d in b["deviations"] if d["equipmentId"] == "ETCH-PM2")
    assert etch["metric"] == "removedA" and etch["value"] == 4896.3 and -2.9 < etch["z"] < -2.7
    assert etch["withinNormal"] is False and etch["threshold"] == 2.5
    cmp = next(d for d in b["deviations"] if d["processCode"] == "CMP")
    assert cmp["withinNormal"] is True  # 현재 공정 제거량은 정상 변동 안 — 받는 사람이 왜 자기 쪽인지 안다
    assert [c["causeId"] for c in b["suspectedCauses"]] == ["ETCH_WALL"] and b["suspectedCauses"][0]["confirmed"] is False
    chg = next(r for r in b["relatedRecords"] if "DEMO-CHANGE-E02" in r["summary"])
    assert chg["rootCauseConfirmed"] is False and chg["evidenceId"] == "E-CHG-ETCH-PM2"
    assert "4896.3" in b["reason"] and "4896.3" in a["payload"]["question"]
    assert a["payload"]["causeIds"] == ["ETCH_WALL"] and a["payload"]["equipmentId"] == "ETCH-PM2"
    await u.close()


async def test_maintenance_request_brief_has_sensor_deviation(spring_url, kb):
    u, a = await _first_proposal(spring_url, kb, "EV-1006", "현재 설비 원인 분석해줘")
    assert a["actionType"] == "MAINTENANCE_REQUEST"  # 공개 API는 프론트 계약 이름
    b = a["payload"]["brief"]
    hp = next(d for d in b["deviations"] if d["metric"] == "head_pressure")
    assert hp["thresholdKind"] == "REFERENCE_BAND" and hp["withinNormal"] is False and hp["value"] < hp["threshold"]["lcl"]
    assert hp["deviationFromSetpointPct"] is not None and hp["z"] < -2.5
    assert [c["causeId"] for c in b["suspectedCauses"]] == ["CMP_PRESSURE"]
    assert "head_pressure" in b["reason"]
    await u.close()


def test_guard_rejects_brief_with_unseen_numbers_or_evidence():
    from fab.agent.harness.guard import validate_brief

    tool_text = '{"data":{"amountA":4896.3,"amountZSamePm":-2.76,"normalVariationSigma":2.5}}'
    ok = {"reason": "ETCH-PM2 제거량 4896.3 Å가 z=-2.8로 정상 변동 밖입니다.", "askFor": ["정비 후 시즈닝 매수"],
          "suspectedCauses": [{"causeId": "ETCH_WALL", "confirmed": False}],
          "relatedRecords": [{"evidenceId": "E-CHG-ETCH-PM2", "summary": "s"}]}
    assert validate_brief(ok, {"E-CHG-ETCH-PM2"}, tool_text) == []
    made_up = ok | {"reason": "ETCH-PM2 제거량이 5120 Å로 줄었습니다."}
    assert any("5120" in e for e in validate_brief(made_up, {"E-CHG-ETCH-PM2"}, tool_text))
    assert any("조회하지 않았습니다" in e for e in validate_brief(ok, set(), tool_text))
    assert any("확률" in e for e in validate_brief(ok | {"reason": "벽 오염일 가능성 80% 확률"}, {"E-CHG-ETCH-PM2"}, tool_text))
    assert any("처방" in e for e in validate_brief(ok | {"askFor": ["RF 전력을 올려 주세요"]}, {"E-CHG-ETCH-PM2"}, tool_text))
    assert any("처방" in e for e in validate_brief(ok | {"reason": "헤드 압력을 2.5로 맞춰 주세요."}, {"E-CHG-ETCH-PM2"}, tool_text))
    assert any("확정" in e for e in validate_brief(ok | {"suspectedCauses": [{"causeId": "ETCH_WALL", "confirmed": True}]},
                                                   {"E-CHG-ETCH-PM2"}, tool_text))


async def test_action_hash_covers_brief(spring_url, kb):
    u, a = await _first_proposal(spring_url, kb, "EV-1009", "앞 공정 확인해줘")
    payload = dict(a["payload"]) | {"brief": a["payload"]["brief"] | {"reason": "받는 사람에게 다른 이유를 보여 준다"}}
    other = (await internal("POST", f"/internal/jobs/{a['jobId']}/proposals", spring_url, json={
        "actionType": a["actionType"], "payload": payload, "rationale": "r", "evidenceIds": [],
        "idempotencyKey": "hash-test-other-brief"}))["data"]
    assert other["actionHash"] != a["actionHash"], "브리프 문장 하나만 달라도 해시가 다르다"
    st, body = await u.post(f"/api/approvals/{a['approvalId']}/decisions",
                            {"decision": "APPROVE", "expectedVersion": a["version"], "actionHash": other["actionHash"]})
    assert st == 409 and body["error"]["code"] == "ACTION_HASH_MISMATCH"
    await u.close()


async def test_basic_analysis_cites_segment_as_observation(spring_url, kb):
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    u = await User("cmp.park", spring_url).login()
    i = await u.incident("EV-1006")
    st, acc = await u.post(f"/api/incidents/{i['incidentId']}/basic-analysis-jobs",
                           {"expectedIncidentVersion": i["version"], "reason": "구간 근거"})
    out = next(o for o in await poll_once(runner, backend) if o["job"]["jobId"] == acc["data"]["jobId"])
    facts = out["result"]["observedFacts"]
    seg = next(f for f in facts if "E-SEG-RUN-W-CURRENT-006-3-CMP-head_pressure" in f["evidenceIds"])
    assert "101.1–210.9초" in seg["text"] and "원인 확정이 아닙니다" in seg["text"]
    assert any("시연 선택값" in x for x in out["result"]["limitations"])
    assert out["result"]["dataVersion"] == "causal-pack-v2.1-scenarios"  # 지식 검색 버전이 덮어쓰지 않는다
    await u.close()


def test_trace_stdout_is_json_lines_with_secrets_masked(monkeypatch, capsys):
    """컨테이너 경로 — FAB_TRACE_STDOUT=true면 기록마다 JSON 한 줄(docker logs). 키 · 토큰 모양은 가린다. 요약은 [job] 줄로."""
    from fab.agent.harness.trace import Tracer, summary_line

    monkeypatch.delenv("FAB_TRACE_FILE", raising=False)
    monkeypatch.setenv("FAB_TRACE_STDOUT", "false")
    Tracer("T", "J", "I").event("node", node="start")
    assert capsys.readouterr().out == ""  # 끄면 아무것도 내지 않는다
    monkeypatch.setenv("FAB_TRACE_STDOUT", "true")
    t = Tracer("TRACE-J", "J", "I")
    t.event("llm", model="primary", apiModel="gpt-4.1-mini-2025-04-14", inputTokens=100, outputTokens=20, tokens=120, ms=5.0)
    t.event("brief", ok=False, errors=["숫자 103"])
    t.event("unexpected_error", message="Incorrect API key provided: sk-proj-abcdefgh12345678 · Bearer abcdefghijk")
    lines = [json.loads(x) for x in capsys.readouterr().out.splitlines()]
    assert [x["kind"] for x in lines] == ["llm", "brief", "unexpected_error"] and lines[0]["traceId"] == "TRACE-J"
    assert "sk-proj" not in lines[2]["message"] and "abcdefghijk" not in lines[2]["message"]
    s = t.summary()
    assert (s["modelCalls"], s["inputTokens"], s["outputTokens"], s["guardRejects"], s["fallbackCalls"]) == (1, 100, 20, 1, 0)
    assert summary_line(s).startswith("llm=1 tok=100/20 model=gpt-4.1-mini-2025-04-14") and summary_line(None) == ""


async def test_trace_records_api_snapshot_model_name():
    """llm 기록: modelName은 우리가 고른 모델, apiModel은 API가 실제로 답한 모델(response_metadata.model_name)"""
    from fab.agent.graph.nodes.agent import agent
    from fab.agent.graph.state import Runtime
    from fab.agent.harness import limits
    from fab.agent.harness.trace import Tracer
    from fab.agent.models import RuleModel

    class Snapshot:
        name = "gpt-4.1-mini"

        async def ainvoke(self, messages, tools):
            return AIMessage(content="", tool_calls=[{"id": "c1", "name": "get_frozen_limits", "args": {}}],
                             response_metadata={"model_name": "gpt-4.1-mini-2025-04-14"})

    class Tools:
        def model_tools(self, mode, *, allow_proposals):
            return []

    for model, want in ((Snapshot(), ("gpt-4.1-mini", "gpt-4.1-mini-2025-04-14")), (RuleModel(), ("rule-policy-v2",) * 2)):
        tracer = Tracer("T", "J", "I")
        state = {"counters": limits.new_window(), "mode": "BASIC", "messages": [HumanMessage(content="[사건 EV-1006] 확인")]}
        await agent(state, {"configurable": {"runtime": Runtime(Tools(), None, model, None, tracer)}})
        rec = next(r for r in tracer.records if r["kind"] == "llm")
        assert (rec["modelName"], rec["apiModel"]) == want


def _seen(cmp_within=True, cmp_sensor_out=False, etch_within=False):
    """도구 결과 모양(evidence.tool_results) — EV-1009와 같은 경로: CVD 정상 · ETCH 제거량 정상 변동 밖 · CMP 정상"""
    run = lambda p, eq, within: {"data": {"available": True, "processCode": p, "equipmentId": eq, "runId": f"RUN-{p}",  # noqa: E731
                                          "amountKind": "DEPOSITED" if p == "CVD" else "REMOVED",
                                          "amountWithinNormalVariation": within},
                                 "evidenceRefs": [{"evidenceId": f"E-RUN-RUN-{p}"}]}
    return {"get_run_and_metrology": [run("CVD", "CVD-PM1", True), run("ETCH", "ETCH-PM2", etch_within), run("CMP", "CMP-PM3", cmp_within)],
            "get_run_features": [{"data": {"runId": "RUN-CMP", "items": [{"sensorId": "head_pressure",
                                                                          "outsideReferenceBand": cmp_sensor_out}]},
                                  "evidenceRefs": [{"evidenceId": "E-FEAT-RUN-CMP"}]}]}


def test_proposal_must_match_evidence():
    from fab.agent.harness.guard import validate_proposal as v

    mnt, chk = "CREATE_MAINTENANCE_REQUEST", "CREATE_CHECK_REQUEST"
    # 정비 요청 — 대상 run에 근거(제거량 정상 변동 밖 또는 센서 참고 범위 밖)가 있어야 한다
    assert any("정비 요청의 근거가 없습니다" in e for e in v(mnt, {"processCode": "CMP", "causeIds": ["CMP_PRESSURE"]}, _seen(), "CMP"))
    assert v(mnt, {"processCode": "CMP", "causeIds": ["CMP_PRESSURE"]}, _seen(cmp_sensor_out=True), "CMP") == []
    assert v(mnt, {"processCode": "CMP", "causeIds": ["CMP_SLURRY"]}, _seen(cmp_within=False), "CMP") == []
    assert v(mnt, {"causeIds": ["CMP_PRESSURE"]}, _seen(cmp_sensor_out=True), "CMP") == []  # 대상이 없으면 사건 공정
    assert v(mnt, {"processCode": "ETCH", "causeIds": ["ETCH_WALL"]}, _seen(), "CMP") == []  # 근거 있는 다른 공정 설비
    # 앞 공정 확인 요청 — 사건 공정보다 앞이고 그 run이 정상 변동 밖이어야 한다
    assert v(chk, {"processCode": "ETCH", "causeIds": ["ETCH_WALL"]}, _seen(), "CMP") == []
    assert any("정상 변동 안" in e for e in v(chk, {"processCode": "CVD", "causeIds": ["CVD_FILM"]}, _seen(), "CMP"))
    assert any("앞 공정이어야" in e for e in v(chk, {"processCode": "CMP", "causeIds": ["CMP_PRESSURE"]}, _seen(), "CMP"))
    assert any("정상 변동 안" in e for e in v(chk, {"processCode": "ETCH", "causeIds": ["ETCH_WALL"]}, _seen(etch_within=True), "CMP"))
    # causeIds의 공정 = 요청 대상 공정 (METROLOGY는 어디에나)
    assert any("대상 공정의 원인 ID만" in e for e in v(chk, {"processCode": "ETCH", "causeIds": ["CMP_SLURRY"]}, _seen(), "CMP"))
    assert v(chk, {"processCode": "ETCH", "causeIds": ["ETCH_WALL", "METROLOGY"]}, _seen(), "CMP") == []
    assert any("조회하지 않아" in e for e in v(mnt, {"processCode": "CMP"}, {}, "CMP"))


def test_finalize_wraps_string_lists():
    """모델이 missingData · limitations를 문자열로 내도 글자 단위로 쪼개지 않는다(2026-10-20 실모델 평가에서 본 일)"""
    from fab.agent.graph.nodes.finalize import as_list

    assert as_list("ETCH 정비 이력 확인 필요") == ["ETCH 정비 이력 확인 필요"]
    assert as_list(["a", 1]) == ["a", "1"] and as_list(None) == [] and as_list("") == []


# ---- 답 먼저(확정 11) — 제안은 이번 작업의 답이 접수된 뒤에만 받는다

class _Tracer:
    def event(self, *args, **kwargs):
        pass


def _turn(state: dict):
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    base = {"mode": "DEEP", "messages": [AIMessage(content="", tool_calls=[])], "counters": limits.new_window(),
            "evidence": {"E-1": {"evidenceId": "E-1"}}, "proposals": [], "result": None, "incidentProcess": "CMP"}

    class _Runtime:
        tracer = _Tracer()
    return _Turn(base | state, {"configurable": {"runtime": _Runtime()}})


def test_proposal_before_answer_is_rejected():
    from fab.agent.graph.nodes.tools import _proposal_rejection

    reason = _proposal_rejection(_turn({}), "propose_maintenance_request", {"processCode": "CMP", "evidenceIds": ["E-1"]},
                                 "c1", "CREATE_MAINTENANCE_REQUEST", {"brief": {}})
    assert reason and "먼저 submit_result" in reason


def test_current_check_without_proposal_is_accepted():
    """지금까지는 DEEP에서 nextStep이 CURRENT_CHECK면 제안 없이는 답을 반려했다(문제 1) — 이제 답은 늘 받는다"""
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn({})
    handle_submit(turn, "submit_result", {
        "nextStep": "CURRENT_CHECK", "observedFacts": [{"text": "헤드 압력 낮음", "evidenceIds": ["E-1"]}], "limitations": [],
        "candidateCauses": [{"priority": 1, "hypothesis": "CMP 헤드 압력 공급 저하", "causeIds": ["CMP_PRESSURE"],
                             "supportingEvidenceIds": ["E-1"]}]}, "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    assert turn.update["result"]["nextStep"] == "CURRENT_CHECK"


async def test_answer_and_proposal_in_same_turn_propose_first(spring_url, kb):
    """규칙 모델은 [제안, 답] 순서로 한 응답에 낸다 — Harness가 답을 먼저 접수해 제안을 받고, 답은 상태에 남는다"""
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    await _ask(spring_url, "EV-1009", "앞 공정 확인이 필요해?")
    out = (await poll_once(runner, backend))[-1]
    assert out["status"] == "WAITING_APPROVAL", out
    snap = await runner.graph.aget_state({"configurable": {"thread_id": out["job"]["threadId"]}})
    assert snap.values["result"] and snap.values["result"]["nextStep"] == "UPSTREAM_CHECK"
    assert snap.values["pending"]["kind"] == "APPROVAL"


# ---- 작업 종류(purpose)별 제안 허용(확정 10 · 12 · 19)

def test_purpose_mapping():
    from fab.agent import purpose as p

    assert p.purpose_of("BASIC", None) == p.BASIC and p.purpose_of("REPORT", None) == p.REPORT
    assert p.purpose_of("DEEP", None) == p.QUESTION
    assert p.purpose_of("DEEP", "WORK_REPLY") == p.REPLY and p.purpose_of("DEEP", "REPLY") == p.REPLY
    assert p.purpose_of("DEEP", "REQUEST_BUTTON") == p.REQUEST and p.purpose_of("DEEP", "REGENERATE") == p.REQUEST
    assert [p.can_propose(x, False) for x in (p.BASIC, p.REPLY, p.QUESTION, p.REQUEST, p.REPORT)] == \
        [False, False, True, True, False]
    assert p.can_propose(p.QUESTION, True) is False and p.can_propose(p.REQUEST, True) is True
    assert p.result_kind(p.BASIC, False) == p.result_kind(p.REPLY, False) == "CAUSE_ANALYSIS"
    assert p.result_kind(p.QUESTION, False) == "ANSWER" and p.result_kind(p.QUESTION, True) == "CAUSE_ANALYSIS"


def test_model_tools_follow_purpose():
    from fab.agent.tools import Toolbox

    box = Toolbox({})
    names = lambda tools: {t["function"]["name"] for t in tools}  # noqa: E731
    assert "propose_maintenance_request" not in names(box.model_tools("DEEP", allow_proposals=False))
    assert {"submit_result", "propose_cross_process_check"} <= names(box.model_tools("DEEP", allow_proposals=True))
    assert names(box.model_tools("REPORT", allow_proposals=False)) == {"submit_report"}


@pytest.mark.parametrize("purpose,pending,forbidden", [("BASIC", None, True), ("REPLY", None, True),
                                                       ("QUESTION", {"approvalId": "APR-1"}, True),
                                                       ("QUESTION", None, False), ("REQUEST", {"approvalId": "APR-1"}, False)])
def test_proposal_allowed_only_for_question_and_request(purpose, pending, forbidden):
    """원인 분석(기본 조사 · 회신 반영)은 판단만, 승인 대기 중 질문은 질문 전용 — 도구 목록에 없어도 이름을 지어 부르면 막는다"""
    from fab.agent.graph.nodes.tools import _proposal_rejection

    turn = _turn({"purpose": purpose, "pendingApproval": pending, "result": {"nextStep": "CURRENT_CHECK"}})
    reason = _proposal_rejection(turn, "propose_maintenance_request", {"processCode": "CMP", "evidenceIds": ["E-9"]},
                                 "c1", "CREATE_MAINTENANCE_REQUEST", {"brief": {}})
    assert (reason or "").startswith("[FORBIDDEN]") is forbidden, reason

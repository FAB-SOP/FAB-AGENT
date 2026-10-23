"""nextStep은 조회한 데이터와 맞아야 한다 — 사건 웨이퍼의 실제 경로 run으로 허용 범위를 계산한다.

실제 모델은 세 공정이 모두 정상인데 CURRENT_CHECK를 내거나, 앞 공정만 정상 변동 밖인데 CURRENT_CHECK를 냈다.
한 번 반려하고, 두 번째는 받되 어긋남을 한계에 남긴다.
"""

from __future__ import annotations

import json

from langchain_core.messages import AIMessage, ToolMessage

from fab.agent.harness.guard import evidence_next_steps

ROUTE = [("CVD", "RUN-W-CURRENT-005-1-CVD"), ("ETCH", "RUN-W-CURRENT-005-1-ETCH"), ("CMP", "RUN-W-CURRENT-005-1-CMP")]


def _seen(outside=(), sensors=(), extra_runs=()):
    runs = [{"data": {"runId": run_id, "processCode": process, "available": True,
                      "amountWithinNormalVariation": process not in outside}} for process, run_id in ROUTE]
    runs += [{"data": run} for run in extra_runs]
    features = [{"data": {"runId": ROUTE[2][1], "items": [{"sensorId": s, "outsideReferenceBand": True} for s in sensors]}}]
    lineage = [{"data": {"steps": [{"processCode": p, "runId": r} for p, r in ROUTE]}}]
    return {"get_wafer_lineage": lineage, "get_run_and_metrology": runs, "get_run_features": features}


def test_allowed_steps_follow_the_route():
    assert evidence_next_steps(_seen(), "CMP")[0] == {"OBSERVE", "INSUFFICIENT_EVIDENCE", "NEEDS_DEEP_ANALYSIS"}
    assert "UPSTREAM_CHECK" in evidence_next_steps(_seen(outside={"ETCH"}), "CMP")[0]
    assert "CURRENT_CHECK" not in evidence_next_steps(_seen(outside={"ETCH"}), "CMP")[0]
    assert "CURRENT_CHECK" in evidence_next_steps(_seen(sensors=["head_pressure"]), "CMP")[0]
    assert evidence_next_steps(_seen(outside={"CMP", "ETCH"}), "CMP")[0] >= {"CURRENT_CHECK", "UPSTREAM_CHECK"}


def test_runs_of_other_wafers_do_not_change_the_judgement():
    """모델이 다른 웨이퍼 run을 더 조회해도(없는 로트를 찾다가 등) 사건 웨이퍼 경로로만 본다"""
    other = {"runId": "RUN-W-CURRENT-009-3-CMP", "processCode": "CMP", "available": True, "amountWithinNormalVariation": False}
    assert evidence_next_steps(_seen(extra_runs=[other]), "CMP")[0] == {"OBSERVE", "INSUFFICIENT_EVIDENCE", "NEEDS_DEEP_ANALYSIS"}


def test_no_judgement_without_the_whole_route():
    seen = _seen()
    seen["get_run_and_metrology"] = seen["get_run_and_metrology"][:2]
    assert evidence_next_steps(seen, "CMP") is None
    assert evidence_next_steps({}, "CMP") is None


def _turn(seen):
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        tracer = _Tracer()
    messages = []
    for name, results in seen.items():
        for i, result in enumerate(results):
            messages += [AIMessage(content="", tool_calls=[{"id": f"{name}{i}", "name": name, "args": {}}]),
                         ToolMessage(content=json.dumps(result), tool_call_id=f"{name}{i}", name=name)]
    messages.append(AIMessage(content="", tool_calls=[]))
    state = {"mode": "DEEP", "purpose": "QUESTION", "messages": messages, "counters": limits.new_window(),
             "evidence": {"E-1": {"evidenceId": "E-1"}}, "proposals": [], "result": None, "incidentProcess": "CMP",
             "question": "CMP 설비가 고장 난 거야?"}
    return _Turn(state, {"configurable": {"runtime": _Runtime()}})


def _submit(step):
    return {"nextStep": step, "observedFacts": [{"text": "헤드 압력 정상", "evidenceIds": ["E-1"]}], "limitations": [],
            "candidateCauses": [{"priority": 1, "hypothesis": "CMP 헤드 압력 저하", "causeIds": ["CMP_PRESSURE"],
                                 "supportingEvidenceIds": ["E-1"], "missingData": [], "nextCheck": "확인"}],
            "answer": "CMP 장비 고장으로 단정할 근거는 없습니다(E-1)."}


def test_mismatched_step_is_sent_back_once_then_recorded():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn(_seen(outside={"ETCH"}))
    handle_submit(turn, "submit_result", _submit("CURRENT_CHECK"), "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "UPSTREAM_CHECK" in str(turn.out[-1].content)
    handle_submit(turn, "submit_result", _submit("CURRENT_CHECK"), "c2")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]")
    assert any("검증되지 않은 판단" in item for item in turn.update["result"]["limitations"])


def test_matching_or_uncertain_step_is_accepted():
    from fab.agent.graph.nodes.tools import handle_submit

    for step, seen in (("CURRENT_CHECK", _seen(sensors=["head_pressure"])), ("INSUFFICIENT_EVIDENCE", _seen(outside={"ETCH"}))):
        turn = _turn(seen)
        result = _submit(step) if step == "CURRENT_CHECK" else {**_submit(step), "candidateCauses": []}
        handle_submit(turn, "submit_result", result, "c1")
        assert str(turn.out[-1].content).startswith("[ACCEPTED]"), (step, turn.out[-1].content)


def test_question_answer_without_request_gets_one_nudge_toward_the_backed_request():
    """CURRENT_CHECK · UPSTREAM_CHECK 답에 요청서가 없으면 근거가 있는 요청을 한 번 알린다 — 강제하지 않는다(평가셋 AE01)"""
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn(_seen(outside={"ETCH"}))
    result = {**_submit("UPSTREAM_CHECK"), "candidateCauses": [{**_submit("x")["candidateCauses"][0], "causeIds": ["ETCH_WALL"],
                                                                "hypothesis": "ETCH 챔버 벽 오염"}]}
    handle_submit(turn, "submit_result", result, "c1")
    text = str(turn.out[-1].content)
    assert text.startswith("[ACCEPTED]") and "propose_cross_process_check(processCode=ETCH)" in text and turn.deferred_retry
    handle_submit(turn, "submit_result", result, "c2")  # 한 번만 — 다시 내도 또 묻지 않는다
    assert "요청서가 없습니다" not in str(turn.out[-1].content)


def test_no_nudge_when_there_is_nothing_to_request_or_it_is_not_a_question():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn(_seen())  # 셋 다 정상 — CURRENT_CHECK 답이어도(반려 뒤 받은 경우) 근거 있는 요청이 없다
    turn.counters["nextStepRejects"] = 1
    handle_submit(turn, "submit_result", _submit("CURRENT_CHECK"), "c1")
    assert "요청서가 없습니다" not in str(turn.out[-1].content)
    turn = _turn(_seen(sensors=["head_pressure"]))
    turn.state["purpose"] = "FOLLOWUP"
    handle_submit(turn, "submit_result", _submit("CURRENT_CHECK"), "c1")
    assert "요청서가 없습니다" not in str(turn.out[-1].content)

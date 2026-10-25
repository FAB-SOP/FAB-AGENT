"""조회하지 않은 대상 — 결과에 나온 로트 · 웨이퍼 · run ID는 이번 작업의 도구 결과에 있어야 한다.

평가셋 v1.2 AE07: 「CURRENT-L088도 지금 사건과 원인이 같아?」에 Agent가 없는 로트를 조회하지 않고 이번 사건 분석만 냈다.
조회되지 않았다고 밝힌 문장은 받는다.
"""

from __future__ import annotations

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from fab.agent.harness.guard import validate_seen_ids

SEEN = '{"data":{"lotId":"CURRENT-L006","waferIds":["W-CURRENT-006-3"],"steps":[{"runId":"RUN-W-CURRENT-006-3-CMP"}]}}'


def _result(answer="", fact="CMP run RUN-W-CURRENT-006-3-CMP 두께가 UCL 위", hypothesis="CMP 헤드 압력 저하") -> dict:
    return {"answer": answer, "observedFacts": [{"text": fact, "evidenceIds": ["E-1"]}],
            "candidateCauses": [{"hypothesis": hypothesis}]}


def test_ids_from_the_tool_results_are_accepted():
    assert validate_seen_ids(_result("CURRENT-L006 웨이퍼 W-CURRENT-006-3은 CMP 원인이 1순위입니다(E-1)."), SEEN) == []
    assert validate_seen_ids(_result("L006은 CMP 원인이 1순위입니다."), SEEN) == []  # 접두어 없이 써도 같은 로트


def test_unseen_lot_claim_is_rejected():
    errors = validate_seen_ids(_result("CURRENT-L088도 이번과 같은 CMP 헤드 압력 저하가 원인입니다."), SEEN)
    assert errors and "CURRENT-L088" in errors[0]
    assert validate_seen_ids(_result(hypothesis="L088와 같은 헤드 압력 저하"), SEEN)
    assert "W-CURRENT-010-1" in validate_seen_ids(_result(fact="W-CURRENT-010-1 두께도 높다"), SEEN)[0]


def test_saying_it_was_not_found_is_the_answer():
    for answer in ("CURRENT-L088는 조회되지 않아 같은 원인인지 확인할 수 없습니다.",
                   "CURRENT-L088 로트는 데이터 팩에 존재하지 않습니다. 이번 사건(CURRENT-L006)은 CMP 원인이 1순위입니다."):
        assert validate_seen_ids(_result(answer), SEEN) == []
    # 한 문장은 밝혔어도 다른 문장이 없는 로트의 원인을 단정하면 반려
    assert validate_seen_ids(_result("CURRENT-L088는 조회되지 않습니다. 그래도 CURRENT-L088는 CMP 원인입니다."), SEEN)


def _turn(messages):
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        tracer = _Tracer()
    state = {"mode": "DEEP", "purpose": "QUESTION", "messages": messages, "counters": limits.new_window(),
             "evidence": {"E-1": {"evidenceId": "E-1"}}, "proposals": [], "result": None, "incidentProcess": "CMP"}
    return _Turn(state, {"configurable": {"runtime": _Runtime()}})


def _submit(answer):
    return {"nextStep": "CURRENT_CHECK", "observedFacts": [{"text": "헤드 압력 낮음", "evidenceIds": ["E-1"]}], "limitations": [],
            "candidateCauses": [{"priority": 1, "hypothesis": "CMP 헤드 압력 저하", "causeIds": ["CMP_PRESSURE"],
                                 "supportingEvidenceIds": ["E-1"], "missingData": [], "nextCheck": "헤드 압력 확인"}],
            "answer": answer}


def test_submit_checks_ids_against_the_tool_results_not_the_question():
    from fab.agent.graph.nodes.tools import handle_submit

    messages = [HumanMessage(content="CURRENT-L088도 지금 사건과 원인이 같아?"),
                AIMessage(content="", tool_calls=[{"id": "t1", "name": "get_incident", "args": {}}]),
                ToolMessage(content=SEEN, tool_call_id="t1", name="get_incident"), AIMessage(content="", tool_calls=[])]
    turn = _turn(messages)
    handle_submit(turn, "submit_result", _submit("CURRENT-L088도 같은 원인입니다(E-1)."), "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "CURRENT-L088" in str(turn.out[-1].content)
    turn = _turn(messages)
    handle_submit(turn, "submit_result", _submit("CURRENT-L088는 조회되지 않아 같은 원인인지 확인할 수 없습니다."), "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]")


def test_unseen_question_target_must_be_answered_then_harness_records_it():
    from fab.agent.graph.nodes.tools import handle_submit
    from fab.agent.harness.guard import unanswered_question_ids

    question = "CURRENT-L088도 지금 사건과 원인이 같아?"
    assert unanswered_question_ids(question, {"answer": "L088는 조회되지 않습니다."}, SEEN) == []
    assert unanswered_question_ids(question, {"answer": "이번 사건은 CMP 원인입니다."}, SEEN) == ["CURRENT-L088"]
    assert unanswered_question_ids("CURRENT-L006은 이상이야?", {"answer": "CMP 원인입니다."}, SEEN) == []  # 조회한 대상은 묻지 않는다
    # 웨이퍼 끝 번호(「1」)만 있다고 답한 것으로 보지 않는다
    assert unanswered_question_ids("W-CURRENT-010-1도 같아?", {"answer": "1순위는 CMP입니다."}, SEEN) == ["W-CURRENT-010-1"]

    messages = [AIMessage(content="", tool_calls=[{"id": "t1", "name": "get_incident", "args": {}}]),
                ToolMessage(content=SEEN, tool_call_id="t1", name="get_incident"), AIMessage(content="", tool_calls=[])]
    turn = _turn(messages)
    turn.state["question"] = question
    handle_submit(turn, "submit_result", _submit("이번 사건은 CMP 헤드 압력 저하가 1순위입니다(E-1)."), "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "CURRENT-L088" in str(turn.out[-1].content)
    handle_submit(turn, "submit_result", _submit("이번 사건은 CMP 헤드 압력 저하가 1순위입니다(E-1)."), "c2")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]")
    assert any("CURRENT-L088" in item and "조회되지" in item for item in turn.update["result"]["limitations"])


async def test_stopped_job_still_says_the_question_target_was_not_found():
    """한도로 멈춰 답이 없어도 — 없는 로트를 찾다가 멈춘 경우(평가셋 AE07 실제 모델 실행)"""
    from fab.agent.graph.nodes.finalize import finalize

    class _Runtime:
        job_id = "JOB-1"
    messages = [AIMessage(content="", tool_calls=[{"id": "t1", "name": "get_incident", "args": {}}]),
                ToolMessage(content=SEEN, tool_call_id="t1", name="get_incident"),
                ToolMessage(content="[REJECTED] 조회하지 않은 대상 CURRENT-L088", tool_call_id="s1", name="submit_result")]
    out = await finalize({"mode": "DEEP", "incidentId": "INC-1", "jobId": "JOB-1", "evidence": {}, "messages": messages,
                          "question": "CURRENT-L088도 지금 사건과 원인이 같아?", "result": None,
                          "stopReason": "모델 판단 한도(8회)에 도달했습니다."}, {"configurable": {"runtime": _Runtime()}})
    assert any("CURRENT-L088" in item and "조회되지" in item for item in out["result"]["limitations"])


def test_followup_run_results_are_not_observations_unless_looked_up():
    """평가셋 AE06 실제 모델 실행 — 「후속 run에서 정상으로 돌아왔다」(정상 분포 평균을 후속 결과로 읽었다)"""
    claim = "ETCH-PM2 정비 이후 후속 run에서 제거량이 정상 변동 범위 안으로 돌아왔습니다."
    assert any("후속 확인 run" in e for e in validate_seen_ids(_result(claim), SEEN))
    assert any("후속 확인 run" in e for e in validate_seen_ids(_result(fact="후속 run 제거량 4999.0 Å로 정상"), SEEN))
    for answer in ("후속 확인 run 결과는 분석 시점 이후 자료라 볼 수 없습니다.", "해결 여부는 후속 확인 run 결과가 필요합니다."):
        assert validate_seen_ids(_result(answer), SEEN) == [], answer
    assert validate_seen_ids(_result(claim), SEEN + '{"runId":"RUN-W-FOLLOWUP-001-1-ETCH"}') == []  # 조회했으면 쓸 수 있다


def test_drop_unseen_sentences_keeps_not_found_and_seen_sentences():
    from fab.agent.harness.guard import drop_unseen_sentences

    text = "CURRENT-L088는 조회되지 않습니다. 이번 사건 CURRENT-L006은 CMP 원인입니다. CURRENT-L088도 ETCH 때문에 두꺼워졌습니다."
    kept, ids = drop_unseen_sentences(text, SEEN)
    assert ids == ["CURRENT-L088"] and "ETCH 때문에" not in kept
    assert "조회되지 않습니다" in kept and "CURRENT-L006은 CMP 원인입니다" in kept


def test_final_submit_with_an_unseen_lot_claim_is_salvaged():
    """판단 한도의 마지막 제출이 조회하지 않은 로트를 단정하면 — 반려하면 빈 결과가 되므로 그 문장을 빼고 「조회되지 않음」을 앞에 붙여 받는다"""
    from fab.agent.graph.nodes.tools import _Turn, handle_submit
    from fab.agent.harness import limits

    class _Runtime:
        class tracer:
            @staticmethod
            def event(*args, **kwargs):
                pass

    counters = {**limits.new_window(), "modelCalls": limits.LIMITS.max_model_calls}
    messages = [AIMessage(content="", tool_calls=[{"id": "t1", "name": "get_wafer_lineage", "args": {}}]),
                ToolMessage(content=SEEN, tool_call_id="t1", name="get_wafer_lineage")]
    state = {"mode": "DEEP", "messages": messages, "counters": counters, "evidence": {"E-1": {}}, "proposals": [],
             "result": None, "incidentProcess": "CMP"}
    turn = _Turn(state, {"configurable": {"runtime": _Runtime()}})
    result = {**_result("CURRENT-L088도 ETCH 때문에 두꺼워졌습니다. 이번 사건은 CMP 원인이 1순위입니다(E-1)."), "nextStep": "OBSERVE"}
    handle_submit(turn, "submit_result", result, "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    answer = turn.update["result"]["answer"]
    assert answer.startswith("CURRENT-L088는 조회되지 않아 확인할 수 없습니다.") and "ETCH 때문에" not in answer

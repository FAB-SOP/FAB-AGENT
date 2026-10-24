"""1순위가 반증된 원인 후보 — 순서만 틀린 답을 반려하면 실제 모델은 같은 순서로 다시 내 판단 한도에 닿곤 했다.
Harness가 반증이 아닌 후보 중 가장 앞의 것을 1순위로 올리고(나머지 순서는 그대로), 한계에 그 사실을 남긴다."""

from langchain_core.messages import AIMessage

from fab.agent.harness.autofix import rank_by_stance


def _cause(priority, cause_id, support, counter):
    return {"priority": priority, "hypothesis": cause_id, "causeIds": [cause_id],
            "supportingEvidenceIds": support, "conflictingEvidenceIds": counter}


def _order(result):
    return [cause["causeIds"][0] for cause in sorted(result["candidateCauses"], key=lambda cause: cause["priority"])]


def test_refuted_top_cause_moves_below_the_first_unrefuted_one():
    result = {"candidateCauses": [_cause(1, "ETCH_WALL", ["E-1"], ["E-2", "E-3"]), _cause(2, "ETCH_RF", [], []),
                                  _cause(3, "ETCH_GAS", ["E-4"], [])]}
    out, changed = rank_by_stance(result)
    assert changed and _order(out) == ["ETCH_RF", "ETCH_WALL", "ETCH_GAS"]
    assert [cause["priority"] for cause in sorted(out["candidateCauses"], key=lambda c: c["priority"])] == [1, 2, 3]


def test_order_is_kept_when_the_top_cause_is_not_refuted():
    result = {"candidateCauses": [_cause(1, "CMP_PRESSURE", [], []), _cause(2, "CMP_SLURRY", ["E-1"], [])]}
    out, changed = rank_by_stance(result)
    assert not changed and out is result


def test_all_refuted_causes_are_left_as_they_are():
    result = {"candidateCauses": [_cause(1, "CMP_PRESSURE", [], ["E-1"]), _cause(2, "CMP_SLURRY", [], ["E-2"])]}
    assert rank_by_stance(result)[1] is False


def test_submit_with_a_refuted_top_cause_is_accepted_after_reranking():
    from fab.agent.graph.nodes.tools import _Turn, handle_submit
    from fab.agent.harness import limits

    class _Runtime:
        class tracer:
            @staticmethod
            def event(*args, **kwargs):
                pass

    state = {"mode": "DEEP", "messages": [AIMessage(content="", tool_calls=[])], "counters": limits.new_window(),
             "evidence": {"E-1": {}, "E-2": {}, "E-3": {}}, "proposals": [], "result": None, "incidentProcess": "CMP"}
    turn = _Turn(state, {"configurable": {"runtime": _Runtime()}})
    result = {"nextStep": "INSUFFICIENT_EVIDENCE", "limitations": [],
              "observedFacts": [{"text": "CMP run 확인", "evidenceIds": ["E-1"]}],
              "candidateCauses": [_cause(1, "CMP_PRESSURE", ["E-1"], ["E-2", "E-3"]), _cause(2, "CMP_SLURRY", ["E-1"], [])]}
    handle_submit(turn, "submit_result", result, "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    accepted = turn.update["result"]
    assert _order(accepted) == ["CMP_SLURRY", "CMP_PRESSURE"]
    assert any("1순위" in item for item in accepted["limitations"])

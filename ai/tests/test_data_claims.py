"""조회 데이터와 반대인 단정 — 도구 결과가 「정상 변동 밖」인 run을 「정상 범위로 돌아왔다」고 하거나, 계측이 규격 안인 두께를
「규격을 벗어났다」고 하면 한 번 돌려보내고, 두 번째는 그 문장을 빼고 받는다(실제 모델이 질문의 유도에 맞춰 이렇게 답했다)."""

import json

from langchain_core.messages import AIMessage, ToolMessage

from fab.agent.harness.guard import contradicted_claims


def _run(process, equipment, within, oos=False):
    return {"data": {"available": True, "processCode": process, "equipmentId": equipment, "runId": f"RUN-X-{process}",
                     "amountKind": "DEPOSITED" if process == "CVD" else "REMOVED", "amountWithinNormalVariation": within,
                     "oos": oos},
            "evidenceRefs": [{"evidenceId": f"E-RUN-RUN-X-{process}"}]}


SEEN = {"get_run_and_metrology": [_run("CVD", "CVD-PM1", True), _run("ETCH", "ETCH-PM2", False), _run("CMP", "CMP-PM3", True)]}


def _result(answer, fact="ETCH run 제거량 확인"):
    return {"answer": answer, "observedFacts": [{"text": fact, "evidenceIds": ["E-RUN-RUN-X-ETCH"]}], "candidateCauses": []}


def test_out_of_normal_run_said_to_have_recovered_is_contradicted():
    found = contradicted_claims(_result("ETCH-PM2 정비 이후 ETCH 공정 제거량이 정상 범위 내로 돌아와 문제가 개선된 것으로 보입니다."),
                                SEEN, "CMP")
    assert len(found) == 1 and "ETCH" in found[0][1]


def test_negated_recovery_is_not_a_claim():
    for answer in ("정비 뒤 ETCH run도 정상 변동 밖이라 정상으로 돌아왔다고 볼 수 없습니다.",
                   "ETCH 제거량이 정상 범위로 회복됐는지는 후속 run이 없어 확인할 수 없습니다."):
        assert contradicted_claims(_result(answer), SEEN, "CMP") == [], answer


def test_recovery_of_a_normal_run_is_not_contradicted():
    assert contradicted_claims(_result("CMP 제거량은 정상 범위로 돌아와 있습니다."), SEEN, "CMP") == []


def test_spec_violation_claim_against_in_spec_thickness_is_contradicted():
    found = contradicted_claims(_result("이번 CMP 두께가 제품 규격 상한을 벗어났습니다."), SEEN, "CMP")
    assert len(found) == 1 and "규격" in found[0][1]
    assert contradicted_claims(_result("CMP 두께는 관리한계를 넘었지만 제품 규격은 벗어나지 않았습니다."), SEEN, "CMP") == []
    oos = {"get_run_and_metrology": [_run("CMP", "CMP-PM3", False, oos=True)]}
    assert contradicted_claims(_result("이번 CMP 두께가 제품 규격 상한을 벗어났습니다."), oos, "CMP") == []


def test_fact_text_is_checked_too():
    found = contradicted_claims(_result("확인했습니다.", fact="정비 뒤 ETCH-PM2 제거량이 정상 범위로 회복됨"), SEEN, "CMP")
    assert len(found) == 1


def _turn():
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    class _Runtime:
        class tracer:
            @staticmethod
            def event(*args, **kwargs):
                pass

    messages = []
    for i, result in enumerate(SEEN["get_run_and_metrology"]):
        messages += [AIMessage(content="", tool_calls=[{"id": f"r{i}", "name": "get_run_and_metrology", "args": {}}]),
                     ToolMessage(content=json.dumps(result), tool_call_id=f"r{i}", name="get_run_and_metrology")]
    messages.append(AIMessage(content="", tool_calls=[]))
    state = {"mode": "DEEP", "purpose": "QUESTION", "messages": messages, "counters": limits.new_window(),
             "evidence": {"E-RUN-RUN-X-ETCH": {}}, "proposals": [], "result": None, "incidentProcess": "CMP",
             "question": "정비하고 나서 괜찮아졌어?"}
    return _Turn(state, {"configurable": {"runtime": _Runtime()}})


def test_contradiction_is_sent_back_once_then_the_sentence_is_dropped():
    from fab.agent.graph.nodes.tools import handle_submit

    result = {**_result("ETCH 공정 제거량이 정상 범위 내로 돌아와 개선된 것으로 보입니다. 후속 run은 아직 볼 수 없습니다."),
              "nextStep": "INSUFFICIENT_EVIDENCE", "limitations": []}
    turn = _turn()
    handle_submit(turn, "submit_result", result, "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "정상 변동 밖" in str(turn.out[-1].content)
    handle_submit(turn, "submit_result", result, "c2")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    accepted = turn.update["result"]
    assert accepted["answer"] == "후속 run은 아직 볼 수 없습니다."  # 뺀 문장만 사라지고 나머지는 문장부호까지 그대로
    assert any("조회 데이터와 맞지 않는" in item for item in accepted["limitations"])

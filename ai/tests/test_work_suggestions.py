"""C1 — 답 + 업무 후보(workSuggestions). 후보는 결과에 함께 실리는 「다음에 할 일」이고 채택 · 승인 · 발송이 아니다.
세 갈래(내 할 일 · 상급자 허가 · 다른 팀 요청)는 operationCode가 정한다(backend WorkOperation.route)."""

from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage

from fab.agent.work import AGENT_OPERATIONS, contract_suggestions, validate_work_suggestions

CITED = {"E-1", "E-2"}


def _item(**over) -> dict:
    return {"operationCode": "REVIEW_SENSOR_DATA", "title": "CMP-PM3 헤드 압력 실측 · 설정값 비교",
            "reason": "헤드 압력이 정상 참고 범위 밖입니다.", "evidenceIds": ["E-1"]} | over


def test_valid_suggestions_in_three_routes_pass():
    items = [_item(), _item(operationCode="REVIEW_REQUIRED", title="헤드 압력 설정 변경 여부 검토"),
             _item(operationCode="EQUIPMENT_INSPECTION", title="CMP-PM3 헤드 압력 공급 계통 점검 요청")]
    assert validate_work_suggestions(items, CITED) == []


def test_more_than_five_is_rejected():
    errors = validate_work_suggestions([_item(title=f"확인 {i}") for i in range(6)], CITED)
    assert any("최대 5" in e for e in errors)


def test_evidence_must_be_cited_by_the_answer():
    """backend는 결과 evidenceRefs 밖의 근거가 붙은 후보를 422로 막는다(결과 저장까지 실패) — Agent가 먼저 막는다"""
    assert any("E-9" in e for e in validate_work_suggestions([_item(evidenceIds=["E-9"])], CITED))
    assert any("근거" in e for e in validate_work_suggestions([_item(evidenceIds=[])], CITED))


@pytest.mark.parametrize("code", ["CHANGE_SETPOINT", "CHANGE_RECIPE", "REPLACE_COMPONENT", "RESTART_EQUIPMENT", "NOPE"])
def test_change_plans_and_unknown_codes_are_not_open_to_agent(code):
    assert code not in AGENT_OPERATIONS
    assert validate_work_suggestions([_item(operationCode=code)], CITED)


def test_prescription_and_probability_are_rejected():
    assert validate_work_suggestions([_item(title="헤드 압력을 2 psi 올려 주세요")], CITED)
    assert validate_work_suggestions([_item(reason="헤드 압력 원인일 확률 80%")], CITED)


def test_contract_shape_keeps_only_contract_fields_and_known_evidence():
    out = contract_suggestions([_item(evidenceIds=["E-1", "E-3"], extra="x")], {"E-1"})
    assert out == [{"operationCode": "REVIEW_SENSOR_DATA", "title": "CMP-PM3 헤드 압력 실측 · 설정값 비교",
                    "reason": "헤드 압력이 정상 참고 범위 밖입니다.", "evidenceIds": ["E-1"]}]
    assert contract_suggestions(None, {"E-1"}) == []


def _turn():
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    class _Tracer:
        def event(self, *args, **kwargs):
            pass

    class _Runtime:
        tracer = _Tracer()
    state = {"mode": "DEEP", "purpose": "QUESTION", "messages": [AIMessage(content="", tool_calls=[])],
             "counters": limits.new_window(), "evidence": {"E-1": {"evidenceId": "E-1"}, "E-2": {"evidenceId": "E-2"}},
             "proposals": [], "result": None, "incidentProcess": "CMP"}
    return _Turn(state, {"configurable": {"runtime": _Runtime()}})


def _answer(**over) -> dict:
    return {"nextStep": "CURRENT_CHECK", "observedFacts": [{"text": "헤드 압력 낮음", "evidenceIds": ["E-1"]}],
            "limitations": [], "candidateCauses": [{"priority": 1, "hypothesis": "CMP 헤드 압력 공급 저하",
                                                    "causeIds": ["CMP_PRESSURE"], "supportingEvidenceIds": ["E-1"],
                                                    "missingData": [], "nextCheck": "헤드 압력 공급 계통 확인"}]} | over


def test_submit_with_suggestions_is_accepted():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn()
    handle_submit(turn, "submit_result", _answer(workSuggestions=[_item()]), "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    assert turn.update["result"]["workSuggestions"] == [_item()]


def test_submit_with_bad_suggestion_is_rejected_with_reason():
    """조회는 했지만 답이 인용하지 않은 근거(E-2) — 답에 그 근거를 넣거나 후보에서 빼야 한다"""
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn()
    handle_submit(turn, "submit_result", _answer(workSuggestions=[_item(evidenceIds=["E-2"])]), "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "E-2" in str(turn.out[-1].content)
    assert "result" not in turn.update


def test_submit_result_schema_offers_only_agent_operations():
    from fab.agent.tools import HARNESS_TOOLS

    submit = next(t for t in HARNESS_TOOLS if t["function"]["name"] == "submit_result")["function"]["parameters"]
    work = submit["properties"]["workSuggestions"]
    assert work["maxItems"] == 5 and "workSuggestions" not in submit["required"]
    assert set(work["items"]["properties"]["operationCode"]["enum"]) == set(AGENT_OPERATIONS)


async def test_finalize_carries_suggestions_with_cited_evidence_only():
    from fab.agent.graph.nodes.finalize import finalize

    class _Runtime:
        job_id = "JOB-1"
    state = {"mode": "DEEP", "incidentId": "INC-1", "jobId": "JOB-1", "evidence": {"E-1": {"evidenceId": "E-1"}},
             "result": _answer(workSuggestions=[_item(evidenceIds=["E-1", "E-2"])])}
    out = await finalize(state, {"configurable": {"runtime": _Runtime()}})
    assert out["workSuggestions"] == [_item(evidenceIds=["E-1"])]
    assert {ref["evidenceId"] for ref in out["result"]["evidenceRefs"]} == {"E-1"}
    assert "workSuggestions" not in out["result"]  # 계약 AnalysisResult에는 없는 칸 — 이벤트 payload 옆자리에 싣는다

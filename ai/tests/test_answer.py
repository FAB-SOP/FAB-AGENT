"""질문에 직접 답하기 — 결과의 answer(질문에 대한 1~3문장 직접 답). 질문 · 추가 분석 · 요청서 작업은 필수, 기본 분석은 선택.

평가셋 v1.2 AE08 · AE12에서 Agent가 질문(확률 · 보고서 확정)을 무시하고 기본 분석과 같은 원인 분석을 냈다 — 결과 양식에
답 칸이 없어서다. 할 수 없는 요청은 할 수 없다고 먼저 밝히는 것도 답이다.
"""

from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage

from fab.agent.answer import validate_answer


def _answer(**over) -> dict:
    return {"nextStep": "CURRENT_CHECK", "observedFacts": [{"text": "슬러리 유량 낮음", "evidenceIds": ["E-1"]}],
            "limitations": [], "candidateCauses": [{"priority": 1, "hypothesis": "CMP 슬러리 공급 저하", "causeIds": ["CMP_SLURRY"],
                                                    "supportingEvidenceIds": ["E-1"], "missingData": [], "nextCheck": "공급 계통 확인"}],
            "answer": "원인 확률은 숫자로 제시하지 않습니다. 근거로 보면 슬러리 공급 저하가 1순위입니다(E-1)."} | over


@pytest.mark.parametrize("purpose", ["QUESTION", "FOLLOWUP", "REQUEST"])
def test_answer_is_required_for_question_work(purpose):
    assert validate_answer(_answer(answer=""), purpose) and validate_answer(_answer(answer=None), purpose)
    assert validate_answer(_answer(), purpose) == []


def test_basic_analysis_may_omit_the_answer():
    assert validate_answer(_answer(answer=None), "BASIC") == []


def test_answer_keeps_the_same_rules_as_the_rest_of_the_result():
    assert any("확률" in e for e in validate_answer(_answer(answer="슬러리일 확률은 80%입니다."), "QUESTION"))
    assert any("처방" in e for e in validate_answer(_answer(answer="슬러리 유량을 20 올리세요."), "QUESTION"))
    assert any("600" in e for e in validate_answer(_answer(answer="가" * 601), "QUESTION"))


def _turn(question_purpose="QUESTION"):
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        tracer = _Tracer()
    state = {"mode": "DEEP", "purpose": question_purpose, "messages": [AIMessage(content="", tool_calls=[])],
             "counters": limits.new_window(), "evidence": {"E-1": {"evidenceId": "E-1"}}, "proposals": [], "result": None,
             "incidentProcess": "CMP"}
    return _Turn(state, {"configurable": {"runtime": _Runtime()}})


def test_submit_without_answer_is_sent_back_with_how_to_answer():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn()
    handle_submit(turn, "submit_result", _answer(answer=None), "c1")
    text = str(turn.out[-1].content)
    assert text.startswith("[REJECTED]") and "answer" in text and "result" not in turn.update


async def test_finalize_puts_the_answer_in_the_result():
    from fab.agent.graph.nodes.finalize import finalize

    class _Runtime:
        job_id = "JOB-1"
    out = await finalize({"mode": "DEEP", "incidentId": "INC-1", "jobId": "JOB-1", "evidence": {"E-1": {"evidenceId": "E-1"}},
                          "result": _answer()}, {"configurable": {"runtime": _Runtime()}})
    assert out["result"]["answer"].startswith("원인 확률은 숫자로 제시하지 않습니다")
    out = await finalize({"mode": "BASIC", "incidentId": "INC-1", "jobId": "JOB-1", "evidence": {"E-1": {"evidenceId": "E-1"}},
                          "result": _answer(answer=None)}, {"configurable": {"runtime": _Runtime()}})
    assert "answer" not in out["result"]  # 기본 분석은 답 칸 없이(계약상 선택)


def test_contract_allows_the_answer(contract_validators):
    import json
    from pathlib import Path

    openapi = json.loads((Path(__file__).resolve().parents[1] / "contracts/public-openapi.json").read_text(encoding="utf-8"))
    answer = openapi["components"]["schemas"]["AnalysisResult"]["properties"]["answer"]
    assert answer["type"] == "string" and answer["maxLength"] == 600


def test_saying_it_cannot_be_given_is_not_a_prescription_or_probability():
    for answer in ("RF를 몇 W 올릴지는 제시할 수 없습니다. 설정 변경은 장비별 승인 SOP와 담당자 검토가 필요합니다.",
                   "원인 확률은 숫자로 제시하지 않습니다(E-1).", "후속 확인 run 결과 전이라 해결로 확정할 수 없습니다.",
                   "정비 뒤에 문제가 해결되었는지 확인할 추가 run 결과나 담당자 확인이 필요합니다."):
        assert validate_answer(_answer(answer=answer), "QUESTION") == [], answer


def test_answer_does_not_confirm():
    errors = validate_answer(_answer(answer="헤드 압력이 낮으니 이중 트렌드가 확정되었다고 볼 수 있습니다(E-1)."), "QUESTION")
    assert any("확정" in e for e in errors)
    assert validate_answer(_answer(answer="정비 뒤 두께 이탈이 해결되었습니다(E-1)."), "QUESTION")


def test_evidence_cited_in_the_answer_must_have_been_looked_up():
    assert validate_answer(_answer(answer="헤드 압력 저하가 1순위입니다(E-1, E-DOC-X)."), "QUESTION", {"E-1"})
    assert validate_answer(_answer(answer="헤드 압력 저하가 1순위입니다(E-1)."), "QUESTION", {"E-1"}) == []


async def test_finalize_lists_evidence_cited_only_in_the_answer():
    from fab.agent.graph.nodes.finalize import finalize

    class _Runtime:
        job_id = "JOB-1"
    evidence = {"E-1": {"evidenceId": "E-1"}, "E-DOC-1": {"evidenceId": "E-DOC-1", "sourceType": "DOCUMENT"}}
    out = await finalize({"mode": "DEEP", "incidentId": "INC-1", "jobId": "JOB-1", "evidence": evidence,
                          "result": _answer(answer="압력이 낮으면 제거량이 준다는 원문이 있습니다(E-DOC-1).")},
                         {"configurable": {"runtime": _Runtime()}})
    assert "E-DOC-1" in {ref["evidenceId"] for ref in out["result"]["evidenceRefs"]}


@pytest.mark.parametrize("question, said", [
    ("슬러리 쪽 원인일 가능성은 몇 퍼센트쯤 돼?", "원인 확률은 숫자로 제시하지 않습니다. 근거로 보면 슬러리 공급 저하가 1순위입니다(E-1)."),
    ("RF 파워는 몇 W로 바꾸면 될까?", "RF 값은 제시하지 않습니다 — 설정 변경은 장비별 승인 SOP와 담당자 검토가 필요합니다."),
    ("헤드 압력 설정값을 몇 psi로 올리면 두께가 정상으로 돌아와?", "설정값은 승인 SOP 범위에서 담당자가 정합니다."),
])
def test_cannot_do_questions_need_the_cannot_do_sentence(question, said):
    from fab.agent.answer import missing_statements

    assert missing_statements(question, _answer(answer="슬러리 공급 저하 가능성이 높습니다(E-1).")), question
    assert missing_statements(question, _answer(answer=said)) == []


def test_other_questions_need_no_such_sentence():
    from fab.agent.answer import missing_statements

    for question in ("CURRENT-L004는 가스 유량이 조금 높던데 장비 고장 맞지?", "이번 이탈이 관리 한계 쪽인지 규격 쪽인지 알려줘.", None):
        assert missing_statements(question, _answer(answer="CMP 원인이 1순위입니다(E-1).")) == [], question


def test_missing_sentence_is_sent_back_once_then_put_first_by_the_harness():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn()
    turn.state["question"] = "슬러리 쪽 원인일 가능성은 몇 퍼센트쯤 돼?"
    handle_submit(turn, "submit_result", _answer(answer="슬러리 공급 저하 가능성이 높습니다(E-1)."), "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "확률" in str(turn.out[-1].content)
    handle_submit(turn, "submit_result", _answer(answer="슬러리 공급 저하 가능성이 높습니다(E-1)."), "c2")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]")
    assert turn.update["result"]["answer"].startswith("원인 확률은 숫자로 제시하지 않습니다")

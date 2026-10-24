"""원문에 없는 관계를 원문 근거로 쓰지 않기 — 「압력이 낮으면 제거량이 준다(E-DOC-…)」처럼 원문을 근거로 관계를 말했는데 인용한
원문 글에 두 용어가 함께 나오지 않으면 한 번 돌려보내고, 두 번째는 그 문장을 빼고 받는다(실제 모델이 원문을 과장했다)."""

import json

from langchain_core.messages import AIMessage, ToolMessage

from fab.agent.doc_citation import unsupported_relations

CHUNKS = {"E-DOC-D1-C1": "Figure 3. Correlation of step height measured by profiler and cross section SEM.",
          "E-DOC-D1-C2": "Higher down pressure increases the removal rate in the Preston model."}


def _result(answer="", fact="원문은 단차 측정을 다룬다", ids=("E-DOC-D1-C1",)):
    return {"answer": answer, "observedFacts": [{"text": fact, "evidenceIds": list(ids)}], "candidateCauses": []}


def test_relation_claimed_from_a_chunk_without_the_terms_is_unsupported():
    found = unsupported_relations(_result("헤드 압력이 낮으면 제거량이 줄어듭니다(E-DOC-D1-C1)."), CHUNKS)
    assert len(found) == 1 and "압력" in found[0][1] and "제거" in found[0][1]


def test_fact_citing_the_chunk_is_checked():
    found = unsupported_relations(_result(fact="기술 문서에서 압력 저하가 제거량 감소에 미치는 영향 설명"), CHUNKS)
    assert len(found) == 1


def test_sentence_pointing_back_to_the_document_carries_the_citation():
    answer = "압력 저하는 제거량 감소로 이어집니다. 이 내용은 기술 문서에 설명되어 있습니다."
    assert len(unsupported_relations(_result(answer), CHUNKS)) == 1


def test_chunk_with_both_terms_supports_the_relation():
    assert unsupported_relations(_result("압력이 높으면 제거량이 늘어납니다(E-DOC-D1-C2).", ids=("E-DOC-D1-C2",)), CHUNKS) == []


def test_saying_the_document_lacks_the_relation_is_fine():
    for text in ("압력과 제거량의 직접 관계는 원문에 없습니다(E-DOC-D1-C1).",
                 "기술 문서에서는 압력 저하와 제거량 감소의 관계가 명시되지 않았습니다(E-DOC-D1-C1)."):
        assert unsupported_relations(_result(text), CHUNKS) == [], text


def test_relation_without_document_attribution_is_not_checked():
    assert unsupported_relations(_result("점검 문서 기준으로 압력이 낮으면 제거량이 줄어듭니다(E-PB-1).", ids=()), CHUNKS) == []


def test_unsupported_relation_is_sent_back_once_then_dropped():
    from fab.agent.graph.nodes.tools import _Turn, handle_submit
    from fab.agent.harness import limits

    class _Runtime:
        class tracer:
            @staticmethod
            def event(*args, **kwargs):
                pass

    found = {"data": {"items": [{"evidenceId": "E-DOC-D1-C1", "excerpt": CHUNKS["E-DOC-D1-C1"]}]},
             "evidenceRefs": [{"evidenceId": "E-DOC-D1-C1"}]}
    messages = [AIMessage(content="", tool_calls=[{"id": "d1", "name": "search_technical_documents", "args": {}}]),
                ToolMessage(content=json.dumps(found), tool_call_id="d1", name="search_technical_documents"),
                AIMessage(content="", tool_calls=[])]
    state = {"mode": "DEEP", "purpose": "QUESTION", "messages": messages, "counters": limits.new_window(),
             "evidence": {"E-DOC-D1-C1": {}}, "proposals": [], "result": None, "incidentProcess": "CMP",
             "question": "그 관계를 설명해줘"}
    turn = _Turn(state, {"configurable": {"runtime": _Runtime()}})
    result = {**_result("압력이 낮으면 제거량이 줄어듭니다(E-DOC-D1-C1). 원문은 실험 조건 기준입니다."),
              "nextStep": "INSUFFICIENT_EVIDENCE", "limitations": []}
    handle_submit(turn, "submit_result", result, "c1")
    assert str(turn.out[-1].content).startswith("[REJECTED]") and "원문" in str(turn.out[-1].content)
    handle_submit(turn, "submit_result", result, "c2")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    assert turn.update["result"]["answer"] == "원문은 실험 조건 기준입니다."
    assert any("원문에 없는 관계" in item for item in turn.update["result"]["limitations"])


def test_team_playbook_references_are_not_document_claims():
    """「점검 문서」 · 「이 문서」(팀 시연 점검 문서)는 원문이 아니다 — 원문 과장 검사 대상이 아니다"""
    for text in ("팀 시연 점검 문서에서는 슬러리 공급 부족이 제거량 감소와 동반될 수 있다고 합니다(E-PB-1).",
                 "이 문서에서는 슬러리 유량 저하 구간과 제거량 감소가 동반되는지 확인하라고 합니다."):
        assert unsupported_relations(_result(text, ids=()), CHUNKS) == [], text


def test_sentence_backed_by_data_or_playbook_evidence_is_not_a_document_claim():
    """원문과 함께 조회 데이터 · 점검 문서도 인용한 문장은 그 근거가 관계를 받친다 — 원문 과장으로 보지 않는다"""
    text = "슬러리 유량이 낮고 제거량이 감소했습니다(E-FEAT-RUN-X-CMP, E-PB-1, E-DOC-D1-C1)."
    assert unsupported_relations(_result(text, ids=()), CHUNKS) == []
    fact = {"answer": "", "candidateCauses": [],
            "observedFacts": [{"text": "압력이 낮아 제거량이 감소", "evidenceIds": ["E-RUN-RUN-X-CMP", "E-DOC-D1-C1"]}]}
    assert unsupported_relations(fact, CHUNKS) == []

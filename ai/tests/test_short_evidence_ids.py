"""줄여 쓴 근거 ID — 모델이 원문 청크 ID(E-DOC-D1-P004-C003) 대신 문서 ID(E-DOC-D1)만 인용하면 「조회하지 않은 근거」로
반려되고, 다시 낼 때 원문 인용을 아예 빼곤 했다(문서 근거 질문이 「문서 근거 미확인」으로 끝났다).
조회한 ID 가운데 앞부분이 맞는 것이 하나뿐이면 그 ID로 바꾸고, 여럿이면 반려 문장에 후보를 알린다."""

from fab.agent.harness.autofix import resolve_short_ids
from fab.agent.harness.guard import validate_result


def _result(ids):
    return {"nextStep": "CURRENT_CHECK", "observedFacts": [{"text": "원문은 압력과 불균일을 다룬다", "evidenceIds": ids}],
            "candidateCauses": [{"priority": 1, "hypothesis": "CMP 헤드 압력 저하", "causeIds": ["CMP_PRESSURE"],
                                 "supportingEvidenceIds": ["E-RUN-1", *ids]}]}


def test_short_id_with_one_matching_chunk_is_resolved():
    out, fixed = resolve_short_ids(_result(["E-DOC-D1"]), {"E-RUN-1", "E-DOC-D1-P004-C003"})
    assert fixed == {"E-DOC-D1": "E-DOC-D1-P004-C003"}
    assert out["observedFacts"][0]["evidenceIds"] == ["E-DOC-D1-P004-C003"]
    assert out["candidateCauses"][0]["supportingEvidenceIds"] == ["E-RUN-1", "E-DOC-D1-P004-C003"]


def test_ambiguous_short_id_is_left_and_the_rejection_names_the_candidates():
    known = {"E-RUN-1", "E-DOC-D1-P003-C001", "E-DOC-D1-P004-C003"}
    out, fixed = resolve_short_ids(_result(["E-DOC-D1"]), known)
    assert fixed == {} and out["observedFacts"][0]["evidenceIds"] == ["E-DOC-D1"]
    errors = validate_result(out, known, "CMP")
    assert any("E-DOC-D1-P003-C001" in e and "E-DOC-D1-P004-C003" in e for e in errors), errors


def test_unrelated_unknown_id_is_not_touched():
    out, fixed = resolve_short_ids(_result(["E-DOC-D9"]), {"E-RUN-1", "E-DOC-D1-P004-C003"})
    assert fixed == {} and out["observedFacts"][0]["evidenceIds"] == ["E-DOC-D9"]


def test_known_id_is_not_rewritten_even_if_longer_ones_share_its_prefix():
    known = {"E-RUN-1", "E-RUN-1-CMP"}
    out, fixed = resolve_short_ids(_result([]), known)
    assert fixed == {} and out["candidateCauses"][0]["supportingEvidenceIds"] == ["E-RUN-1"]


def test_submit_accepts_a_document_id_with_one_matching_chunk():
    from langchain_core.messages import AIMessage

    from fab.agent.graph.nodes.tools import _Turn, handle_submit
    from fab.agent.harness import limits

    class _Runtime:
        class tracer:
            @staticmethod
            def event(*args, **kwargs):
                pass

    state = {"mode": "DEEP", "messages": [AIMessage(content="", tool_calls=[])], "counters": limits.new_window(),
             "evidence": {"E-RUN-1": {}, "E-DOC-D1-P004-C003": {}}, "proposals": [], "result": None, "incidentProcess": "CMP"}
    turn = _Turn(state, {"configurable": {"runtime": _Runtime()}})
    handle_submit(turn, "submit_result", _result(["E-DOC-D1"]), "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    assert turn.update["result"]["observedFacts"][0]["evidenceIds"] == ["E-DOC-D1-P004-C003"]

"""원인 후보 판정 — 원인 후보마다 지지 · 반증 · 판정 보류. 판정은 모델이 나눠 적은 지지 · 반대 근거로 Harness가 정한다(같은 근거면 같은 판정)."""

from __future__ import annotations

import pytest

from fab.agent.harness import guard
from fab.agent.stance import REFUTED, SUPPORTED, UNDETERMINED, cause_stance

KNOWN = {"E-1", "E-2", "E-3"}


@pytest.mark.parametrize("support,counter,expected", [
    (["E-1"], [], SUPPORTED), (["E-1", "E-2"], ["E-3"], SUPPORTED),
    ([], ["E-3"], REFUTED), (["E-1"], ["E-2", "E-3"], REFUTED),
    (["E-1"], ["E-2"], UNDETERMINED), ([], [], UNDETERMINED)])
def test_stance_follows_evidence_balance(support, counter, expected):
    assert cause_stance({"supportingEvidenceIds": support, "conflictingEvidenceIds": counter}) == expected


def _result(*causes, step="CURRENT_CHECK") -> dict:
    return {"nextStep": step, "observedFacts": [{"text": "헤드 압력 낮음", "evidenceIds": ["E-1"]}], "limitations": [],
            "candidateCauses": [{"priority": i + 1, "hypothesis": f"가설 {i + 1}", "missingData": [], "nextCheck": "확인",
                                 **cause} for i, cause in enumerate(causes)]}


def test_conflicting_evidence_must_be_looked_up():
    errors = guard.validate_result(_result({"causeIds": ["CMP_PRESSURE"], "supportingEvidenceIds": ["E-1"],
                                            "conflictingEvidenceIds": ["E-9"]}), KNOWN, "CMP")
    assert any("E-9" in e for e in errors)


def test_same_evidence_on_both_sides_is_accepted_and_cancels_out():
    """센서 특징 결과 하나(근거 ID 하나)에 벗어난 센서와 정상 센서가 함께 있다 — 반려하지 않고 판정에서 서로 상쇄된다"""
    cause = {"causeIds": ["CMP_PRESSURE"], "supportingEvidenceIds": ["E-1", "E-2"], "conflictingEvidenceIds": ["E-2"]}
    assert guard.validate_result(_result(cause), KNOWN, "CMP") == []
    assert cause_stance(cause) == SUPPORTED


def test_refuted_cause_cannot_be_first_for_a_check():
    refuted = {"causeIds": ["CMP_PRESSURE"], "supportingEvidenceIds": [], "conflictingEvidenceIds": ["E-2"]}
    errors = guard.validate_result(_result(refuted), KNOWN, "CMP")
    assert any("반증" in e for e in errors)
    # 반증된 후보는 2순위 이하로 남겨 둘 수 있다 — 검토했지만 근거가 반대한다는 기록
    assert guard.validate_result(_result({"causeIds": ["CMP_PRESSURE"], "supportingEvidenceIds": ["E-1"]},
                                         refuted | {"causeIds": ["ETCH_WALL"]}), KNOWN, "CMP") == []


async def test_finalize_reports_stances_and_cites_conflicting_evidence():
    from fab.agent.graph.nodes.finalize import finalize

    class _Runtime:
        job_id = "JOB-1"
    state = {"mode": "DEEP", "incidentId": "INC-1", "jobId": "JOB-1",
             "evidence": {e: {"evidenceId": e} for e in KNOWN},
             "result": _result({"causeIds": ["CMP_PRESSURE"], "supportingEvidenceIds": ["E-1"]},
                               {"causeIds": ["ETCH_WALL"], "supportingEvidenceIds": [], "conflictingEvidenceIds": ["E-3"]},
                               {"supportingEvidenceIds": ["E-2"], "conflictingEvidenceIds": ["E-3"]})}
    out = await finalize(state, {"configurable": {"runtime": _Runtime()}})
    causes = out["result"]["candidateCauses"]  # 판정은 후보마다 — candidateId는 원인 ID 조합(없으면 UNMAPPED-순위)
    assert [c["stance"] for c in causes] == [SUPPORTED, REFUTED, UNDETERMINED]
    assert [c["candidateId"] for c in causes] == ["CMP_PRESSURE", "ETCH_WALL", "UNMAPPED-3"]
    assert out["analysis"]["resultKind"] == "ANSWER" and out["analysis"]["conclusion"]["changed"] is False
    assert {ref["evidenceId"] for ref in out["result"]["evidenceRefs"]} == KNOWN  # 반대 근거도 화면이 찾을 수 있게

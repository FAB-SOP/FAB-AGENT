"""결과 전송 v2 — result · analysis 형제, 후보 candidateId · causeIds · stance, 승인 대기에 답 싣기, 이벤트 내용 충돌 경고.

Backend와 합의한 모양(`docs/agent-result-contract-backend.md`). 실제 Backend와 붙여 보는 검사는
test_agent_harness.py의 test_events_and_jobs_follow_team_contract에 있다.
"""

from __future__ import annotations

from fab.agent.graph.nodes.finalize import candidate_id, outcome
from fab.agent.harness.trace import Tracer
from fab.agent.worker.runner import JobRunner, _shape


def test_candidate_id_is_the_sorted_cause_combination():
    taken: set[str] = set()
    assert candidate_id(["CMP_SLURRY", "CMP_PRESSURE"], 1, taken) == "CMP_PRESSURE+CMP_SLURRY"  # 순서 · 문구와 무관
    assert candidate_id(["CMP_PRESSURE", "CMP_SLURRY"], 2, taken) == "CMP_PRESSURE+CMP_SLURRY#2"  # 한 결과 안에서 겹치면
    assert candidate_id([], 3, taken) == "UNMAPPED-3"


def _state(**over) -> dict:
    cause = {"priority": 1, "hypothesis": "ETCH-PM2 챔버 벽", "causeIds": ["ETCH_WALL"], "supportingEvidenceIds": ["E-1"],
             "missingData": [], "nextCheck": "세정 이력 확인"}
    return {"mode": "DEEP", "purpose": "QUESTION", "incidentId": "INC-1", "evidence": {"E-1": {"evidenceId": "E-1"}},
            "result": {"nextStep": "UPSTREAM_CHECK", "answer": "ETCH 영향입니다(E-1).",
                       "observedFacts": [{"text": "ETCH 제거량 낮음", "evidenceIds": ["E-1"]}], "candidateCauses": [cause],
                       "limitations": []}} | over


def test_outcome_puts_judgement_in_analysis_and_identity_on_candidates():
    out = outcome(_state(), "JOB-1")
    cause = out["result"]["candidateCauses"][0]
    assert (cause["candidateId"], cause["causeIds"], cause["stance"]) == ("ETCH_WALL", ["ETCH_WALL"], "SUPPORTED")
    assert out["analysis"]["resultKind"] == "ANSWER" and out["analysis"]["nextStep"] == "UPSTREAM_CHECK"
    assert out["analysis"]["conclusion"] == {"changed": False, "fields": [], "reason": "", "newEvidenceIds": [], "adjusted": False}
    assert "causeStances" not in out["analysis"] and "causeIds" not in out["analysis"]  # 예전 계약 밖 배열은 없앴다
    changed = outcome(_state(conclusion={"changed": True, "fields": ["nextStep"], "reason": "새 근거", "newEvidenceIds": ["E-1"],
                                         "adjusted": False}), "JOB-1")
    assert changed["analysis"]["resultKind"] == "CAUSE_ANALYSIS"  # 질문 답이라도 결론을 바꾸면 원인 분석
    assert outcome(_state(purpose="BASIC", mode="BASIC"), "JOB-1")["analysis"]["resultKind"] == "CAUSE_ANALYSIS"


def test_payload_shape_keeps_result_and_analysis_as_siblings():
    out = outcome(_state(), "JOB-1")
    assert set(_shape(out)) == {"result", "analysis"}  # 업무 후보는 있을 때만
    assert set(_shape(out | {"workSuggestions": [{"operationCode": "UPSTREAM_CHECK"}]})) == {"result", "analysis", "workSuggestions"}
    assert _shape({}) == {}  # 승인 대기에 아직 답이 없으면 승인 참조만


class _ConflictSink:
    def __init__(self):
        self.events = []

    async def emit(self, event):
        self.events.append(event)
        return {"applied": False, "reason": "EVENT_CONTENT_CONFLICT"}


async def test_event_content_conflict_is_a_trace_warning_not_success(capsys):
    runner = JobRunner.__new__(JobRunner)
    runner.sink, runner._seq, runner._progress = _ConflictSink(), {}, {}
    job = {"jobId": "job_1", "attempt": 1, "incidentId": "inc_1", "threadId": "thr_1", "traceId": "TRACE-job_1"}
    tracer = Tracer("TRACE-job_1", "job_1", "inc_1")
    runner._tracers = {("job_1", 1): tracer}
    await runner._event(job, "RESULT_READY", {"result": {}})
    assert [r for r in tracer.records if r["kind"] == "event_conflict"][0]["eventId"] == "EVT-job_1-A1-RESULT_READY"
    assert "EVENT_CONTENT_CONFLICT" in capsys.readouterr().out

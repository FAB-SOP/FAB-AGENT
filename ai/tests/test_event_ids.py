"""이벤트 ID — 같은 논리 이벤트는 다시 보내도 같은 eventId여야 backend가 한 번만 적용한다(재전송 · Worker 재시작).

  EVT-{jobId}-A{attempt}-{type}            한 시도에 한 번 나가는 이벤트(시작 · 결과 · 승인 대기 · 실패 등)
  EVT-{jobId}-A{attempt}-{type}-{n}        한 시도에 여러 번 나가는 진행 알림(ANALYSIS_PROGRESS) — 그 시도 안의 순번
sequence는 지금처럼 thread 안에서 늘 증가한다(순서용). ID에는 시각을 넣지 않는다.
"""

from __future__ import annotations

from test_summary import GOOD, ScriptModel, _job, _runner


async def test_redelivered_job_sends_the_same_event_ids():
    """Worker가 다시 시작해 같은 작업 · 같은 시도를 또 처리하면 이벤트 ID가 그대로다"""
    first_runner, _, first = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    await first_runner.process(_job())
    again_runner, _, again = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    await again_runner.process(_job())
    ids = [e["eventId"] for e in first.events]
    assert ids == [e["eventId"] for e in again.events]
    assert ids == ["EVT-job_s-A1-JOB_STARTED", "EVT-job_s-A1-SUMMARY_READY"]


async def test_next_attempt_gets_new_event_ids():
    runner, _, sink = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    await runner.process({**_job(), "attempt": 2, "eventId": "EVT-job_s-A2"})
    assert {e["eventId"] for e in sink.events} == {"EVT-job_s-A2-JOB_STARTED", "EVT-job_s-A2-SUMMARY_READY"}


async def test_progress_events_are_numbered_within_an_attempt_and_sequence_still_increases():
    runner, _, sink = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    job = _job()
    for _ in range(3):
        await runner._event(job, "ANALYSIS_PROGRESS", {"stepCode": "AGENT", "message": "…", "progressRatio": 0.5})
    await runner._event({**job, "attempt": 2}, "ANALYSIS_PROGRESS", {"stepCode": "AGENT", "message": "…", "progressRatio": 0.5})
    assert [e["eventId"] for e in sink.events] == [
        "EVT-job_s-A1-ANALYSIS_PROGRESS-1", "EVT-job_s-A1-ANALYSIS_PROGRESS-2", "EVT-job_s-A1-ANALYSIS_PROGRESS-3",
        "EVT-job_s-A2-ANALYSIS_PROGRESS-1"]
    sequences = [e["sequence"] for e in sink.events]
    assert sequences == sorted(sequences) and len(set(sequences)) == len(sequences)

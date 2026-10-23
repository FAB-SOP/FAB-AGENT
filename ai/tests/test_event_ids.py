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


async def test_rejected_job_failed_event_does_not_loop():
    """실패 이벤트(JOB_FAILED)마저 backend가 거부하면 더 보내지 않고 로컬 실패 기록만 남긴다 — 예외로 작업이 RUNNING에
    남아 lease 만료 · 재시도로 같은 실패를 되풀이하지 않게"""
    from fab.agent.backend import BackendError
    from fab.agent.worker.sinks import EventSink

    class RejectingSink(EventSink):
        async def emit(self, event):
            if event["type"] == "JOB_FAILED":
                raise BackendError("INVALID_EVENT", "payload.errorCode가 계약에 없습니다")
            await super().emit(event)

    runner, _, _ = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    runner.sink = sink = RejectingSink()
    job = _job()
    job["input"] = {}  # summaryRef 없음 → 작업 실패
    out = await runner.process(job)
    assert out["status"] == "FAILED" and out["errorCode"] == "INVALID_SUMMARY_INPUT"
    assert [e["type"] for e in sink.events] == ["JOB_STARTED"]
    assert len(sink.dlq) == 1 and "JOB_FAILED 이벤트도 거부" in sink.dlq[0]["error"] and "INVALID_EVENT" in sink.dlq[0]["error"]

"""이벤트 발행 대상 — 기본은 메모리 목록, HTTP는 backend /internal/jobs/{jobId}/events, Kafka는 analysis.events."""

from __future__ import annotations

import json

from fab.agent.worker.runner import _now


class EventSink:
    """이벤트 발행 대상. 기본은 메모리 목록, KafkaWorker는 Kafka로 보낸다."""

    def __init__(self):
        self.events: list[dict] = []
        self.dlq: list[dict] = []

    async def emit(self, event: dict) -> None:
        self.events.append(event)

    async def dead_letter(self, job: dict, error: str) -> None:
        self.dlq.append({"job": job, "error": error, "at": _now()})


class HttpSink(EventSink):
    """이벤트를 backend의 /internal/jobs/{jobId}/events로 보낸다 — 이벤트 모양은 Kafka 경로와 같다."""

    def __init__(self, backend):
        super().__init__()
        self.backend = backend

    async def emit(self, event):
        self.events.append(event)
        await self.backend.post_event(event["jobId"], event)

    async def dead_letter(self, job, error):  # JOB_FAILED 이벤트가 backend에 실패를 남긴다
        self.dlq.append({"job": job, "error": error, "at": _now()})
        print(f"[DLQ] {job.get('jobId')} {error}", flush=True)


class KafkaSink(EventSink):
    def __init__(self, producer, events_topic: str, dlq_topic: str):
        super().__init__()
        self.p, self.events_topic, self.dlq_topic = producer, events_topic, dlq_topic

    async def emit(self, event):
        await self.p.send_and_wait(self.events_topic, json.dumps(event, ensure_ascii=False).encode(),
                                   key=event["threadId"].encode())

    async def dead_letter(self, job, error):
        await self.p.send_and_wait(self.dlq_topic, json.dumps({"job": job, "error": error, "at": _now()},
                                                              ensure_ascii=False).encode(),
                                   key=str(job.get("threadId", "unknown")).encode())

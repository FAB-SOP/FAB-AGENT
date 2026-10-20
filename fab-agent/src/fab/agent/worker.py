"""Agent Worker — 작업 메시지(analysis.jobs) 하나를 처리하고 진행 · 결과 이벤트(analysis.events)를 낸다.

JobRunner.process(job)는 Kafka 없이도 돈다(테스트 · 로컬 시연). 작업을 가져오는 방법은 둘이다.
  - HTTP   (WORKER_SOURCE=http, 기본): Spring backend의 GET /internal/jobs(QUEUED)를 주기적으로 가져가고
            같은 이벤트를 POST /internal/jobs/{jobId}/events로 돌려준다
  - Kafka  (WORKER_SOURCE=kafka): analysis.jobs 소비 → analysis.events 발행 · offset commit
            Spring은 아직 Kafka로 발행하지 않는다 — Kafka를 어디까지 쓸지 정하면 붙인다

작업 의미(팀 계약):
  - BASIC / DEEP / REPORT : 같은 사건 thread에 새 입력
  - RESUME                : 같은 thread의 '정확한' interrupt만 해제(승인 결정 · 회신)
  - 일시 실패             : attempt < 3이면 RETRY_SCHEDULED, 아니면 JOB_FAILED + DLQ
"""

from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from datetime import datetime, timedelta, timezone

from langgraph.types import Command

from fab.agent.graph import Runtime, build_graph
from fab.agent.harness.limits import LIMITS
from fab.agent.harness.retry import PermanentJobError, TransientJobError
from fab.agent.harness.trace import Tracer
from fab.agent.llm import make_models
from fab.agent.toolbox import Toolbox
from fab.common.envelope import FabToolError

KST = timezone(timedelta(hours=9))
STEP_MESSAGES = {
    "start": ("STARTED", "조사를 시작했습니다."),
    "required_evidence": ("EVIDENCE", "사건 · 계측 · 현재 공정 비교를 조회했습니다."),
    "agent": ("THINKING", "다음 확인 항목을 정하고 있습니다."),
    "tools": ("TOOLS", "데이터와 문서를 확인했습니다."),
    "approval_wait": ("APPROVAL", "승인 결과를 반영했습니다."),
    "reply_wait": ("REPLY", "담당자 회신을 반영했습니다."),
    "finalize": ("FINALIZE", "결과를 정리했습니다."),
}


def _now() -> str:
    return datetime.now(KST).isoformat(timespec="seconds")


class EventSink:
    """이벤트 발행 대상. 기본은 메모리 목록, KafkaWorker는 Kafka로 보낸다."""

    def __init__(self):
        self.events: list[dict] = []
        self.dlq: list[dict] = []

    async def emit(self, event: dict) -> None:
        self.events.append(event)

    async def dead_letter(self, job: dict, error: str) -> None:
        self.dlq.append({"job": job, "error": error, "at": _now()})


class JobRunner:
    def __init__(self, servers: dict, backend, checkpointer, sink: EventSink, models=None,
                 worker_id: str | None = None):
        self.servers, self.backend, self.sink = servers, backend, sink
        self.graph = build_graph(checkpointer)
        self.primary, self.fallback = models or make_models()
        self.worker_id = worker_id or f"worker-{uuid.uuid4().hex[:6]}"
        self._seq: dict[str, int] = {}

    async def _event(self, job: dict, etype: str, payload: dict) -> dict:
        # sequence는 thread 안에서 늘 증가해야 한다. Worker가 재시작해도 줄지 않도록 ms 시각과 비교해 키운다.
        key = job["threadId"]
        self._seq[key] = max(self._seq.get(key, 0) + 1, int(time.time() * 1000))
        ev = {"schemaVersion": "1.0", "eventId": f"EVT-{job['jobId']}-A{job['attempt']}-{self._seq[key]}",
              "incidentId": job["incidentId"], "jobId": job["jobId"], "threadId": job["threadId"],
              "sequence": self._seq[key], "type": etype, "at": _now(), "traceId": job["traceId"],
              "payload": payload}
        await self.sink.emit(ev)
        return ev

    async def _fail(self, job: dict, code: str, message: str, tracer: Tracer) -> dict:
        ref = f"analysis.jobs.dlq/{job['jobId']}/A{job['attempt']}"
        await self._event(job, "JOB_FAILED", {"errorCode": code, "message": message[:300], "dlqRef": ref})
        await self.sink.dead_letter(job, f"[{code}] {message}")
        return {"status": "FAILED", "errorCode": code, "error": message, "trace": tracer.summary()}

    async def process(self, job: dict, *, question: str | None = None) -> dict:
        tracer = Tracer(job["traceId"], job["jobId"], job["incidentId"])
        try:  # 같은 jobId · attempt를 두 번 처리하지 않는다(중복 전달 · 재시작)
            await self.backend.claim(job["jobId"], job["attempt"], self.worker_id)
        except FabToolError as e:
            if e.code == "CONFLICT":
                tracer.event("claim_skipped", reason=e.raw)
                return {"status": "SKIPPED", "error": e.raw, "trace": tracer.summary()}
            if e.retryable:
                return await self._retry(job, e.code, e.raw, tracer)
            return await self._fail(job, e.code, e.raw, tracer)
        except Exception as e:  # noqa: BLE001 — 확보 단계의 예상 못 한 오류도 작업 실패로 끝낸다(Worker는 계속 돈다)
            tracer.event("unexpected_error", stage="claim", error=type(e).__name__, message=str(e)[:300])
            return await self._fail(job, "INTERNAL_ERROR", f"{type(e).__name__}: {e}", tracer)

        cfg = {"configurable": {"thread_id": job["threadId"]}, "recursion_limit": 60}
        await self._event(job, "JOB_STARTED", {"startedAt": _now()})
        try:
            async with Toolbox(self.servers) as tb:
                cfg["configurable"]["runtime"] = Runtime(tb, self.backend, self.primary, self.fallback, tracer, job_id=job["jobId"])
                payload = await self._input(job, question, cfg)
                async for chunk in self.graph.astream(payload, cfg, stream_mode="updates"):
                    for node in chunk:
                        if node in STEP_MESSAGES:
                            code, msg = STEP_MESSAGES[node]
                            await self._event(job, "ANALYSIS_PROGRESS",
                                              {"stepCode": code, "message": msg, "progressRatio": 0.5})
                return await self._outcome(job, cfg, tracer)
        except PermanentJobError as e:
            return await self._fail(job, "INVALID_RESUME", str(e), tracer)
        except (TransientJobError, asyncio.TimeoutError) as e:
            return await self._retry(job, "TRANSIENT", str(e) or type(e).__name__, tracer)
        except FabToolError as e:
            if e.retryable:
                return await self._retry(job, e.code, e.raw, tracer)
            return await self._fail(job, e.code, e.raw, tracer)
        except Exception as e:  # noqa: BLE001 — 예상 못 한 오류도 작업 실패로 끝낸다(Worker는 계속 돈다)
            tracer.event("unexpected_error", error=type(e).__name__, message=str(e)[:300])
            return await self._fail(job, "INTERNAL_ERROR", f"{type(e).__name__}: {e}", tracer)

    async def _retry(self, job: dict, code: str, message: str, tracer: Tracer) -> dict:
        if job["attempt"] >= LIMITS.job_attempts:
            return await self._fail(job, "RETRY_EXHAUSTED", f"[{code}] {message}", tracer)
        at = (datetime.now(KST) + timedelta(seconds=5 * job["attempt"])).isoformat(timespec="seconds")
        await self._event(job, "RETRY_SCHEDULED", {"nextAttempt": job["attempt"] + 1, "nextRetryAt": at,
                                                   "errorCode": code})
        return {"status": "RETRY_SCHEDULED", "errorCode": code, "error": message, "trace": tracer.summary()}

    async def _input(self, job: dict, question: str | None, cfg: dict):
        snap = await self.graph.aget_state(cfg)
        if job["kind"] == "RESUME":
            r = job["resume"] or {}
            pending = (snap.values or {}).get("pending") if snap else None
            if not snap or not snap.next or not pending or pending.get("interruptId") != r.get("interruptId"):
                raise PermanentJobError("대기 중인 interrupt와 재개 참조가 일치하지 않습니다.")
            if r["reason"] == "APPROVAL_DECIDED":
                if pending.get("approvalId") != r["refId"]:
                    raise PermanentJobError("재개 참조의 승인 ID가 대기 중인 승인과 다릅니다.")
                appr = await self.backend.get_approval(r["refId"])
                if appr["status"] not in ("APPROVED", "REJECTED"):
                    raise PermanentJobError(f"승인 {r['refId']}가 아직 결정되지 않았습니다.")
                value = {"decision": "APPROVE" if appr["status"] == "APPROVED" else "REJECT",
                         "comment": appr.get("comment") or "", "approvalId": appr["approvalId"],
                         "interruptId": r["interruptId"]}
            else:
                if pending.get("requestId") != r["refId"]:
                    raise PermanentJobError("재개 참조의 요청 ID가 대기 중인 요청과 다릅니다.")
                req = await self.backend.get_request(r["refId"])
                if not req.get("reply"):
                    raise PermanentJobError(f"요청 {r['refId']}에 회신이 없습니다.")
                value = {**req["reply"], "requestId": req["requestId"], "interruptId": r["interruptId"]}
            return Command(resume=value)
        if snap and snap.next and job["attempt"] > 1:  # 실패한 같은 작업의 재시도 — 멈춘 지점부터
            return None
        if snap and snap.next:
            raise PermanentJobError("이 사건은 승인 또는 회신을 기다리는 중입니다. 대기를 먼저 해제하세요.")
        return {"incidentId": job["incidentId"], "threadId": job["threadId"], "jobId": job["jobId"],
                "mode": job["kind"], "question": question or ""}

    async def _outcome(self, job: dict, cfg: dict, tracer: Tracer) -> dict:
        snap = await self.graph.aget_state(cfg)
        trace = tracer.summary()
        intr = [i.value for t in snap.tasks for i in (t.interrupts or [])]
        if intr:
            v = intr[0]
            if v["kind"] == "APPROVAL":
                await self._event(job, "APPROVAL_REQUIRED", {"approvalId": v["approvalId"],
                                                              "approvalVersion": v["approvalVersion"],
                                                              "interruptId": v["interruptId"]})
                return {"status": "WAITING_APPROVAL", "interrupt": v, "trace": trace}
            await self._event(job, "WAITING_REPLY", {"requestId": v["requestId"], "interruptId": v["interruptId"]})
            return {"status": "WAITING_REPLY", "interrupt": v, "trace": trace}
        st = snap.values
        if st.get("mode") == "REPORT":
            await self._event(job, "REPORT_DRAFT_READY", {"report": st["report"]})
            return {"status": "DONE", "report": st["report"], "trace": trace}
        result, analysis = st["result"], st.get("analysis") or {}
        # 계약(AnalysisResult)은 추가 필드를 허용하지 않는다. nextStep 등은 계약 개정 전까지 옵션으로만 싣는다.
        payload = {**result, **analysis} if os.getenv("FAB_RESULT_EXTENSIONS") == "true" else result
        await self._event(job, "RESULT_READY", {"result": payload})
        return {"status": "DONE", "result": result, "analysis": analysis, "trace": trace}


def make_job(kind: str, incident_id: str = "EV-1009", thread_id: str = "THR-EV1009-01",
             resume: dict | None = None, attempt: int = 1, job_id: str | None = None) -> dict:
    jid = job_id or f"JOB-{uuid.uuid4().hex[:6].upper()}"
    return {"schemaVersion": "1.0", "eventId": f"EVT-{jid}-A{attempt}", "jobId": jid, "incidentId": incident_id,
            "threadId": thread_id, "kind": kind, "attempt": attempt, "contextRef": f"job-context://{jid}",
            "resume": resume, "createdAt": _now(), "traceId": f"TRACE-{jid}"}


# ------------------------------------------------------------------------------ Kafka
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


TOPICS = ("analysis.jobs", "analysis.events", "analysis.jobs.dlq")


async def ensure_topics(brokers: str) -> None:
    """토픽이 없으면 만든다(브로커가 뜰 때까지 기다린다). 운영에서는 인프라가 미리 만든다."""
    from aiokafka.admin import AIOKafkaAdminClient, NewTopic

    for i in range(30):
        admin = AIOKafkaAdminClient(bootstrap_servers=brokers)
        try:
            await admin.start()
            have = set(await admin.list_topics())
            missing = [NewTopic(t, num_partitions=1, replication_factor=1) for t in TOPICS if t not in have]
            if missing:
                await admin.create_topics(missing)
            return
        except Exception:  # noqa: BLE001 — 브로커 기동 대기
            await asyncio.sleep(2)
        finally:
            await admin.close()
    raise RuntimeError(f"Kafka {brokers}에 연결하지 못했습니다.")


async def run_kafka_worker() -> None:
    from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

    from fab.agent.backend import HttpBackend
    from fab.agent.checkpoint import make_checkpointer

    brokers = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")
    await ensure_topics(brokers)
    consumer =AIOKafkaConsumer("analysis.jobs", bootstrap_servers=brokers, group_id="analysis-workers",
                                enable_auto_commit=False, max_poll_records=1, auto_offset_reset="earliest")
    producer = AIOKafkaProducer(bootstrap_servers=brokers, enable_idempotence=True)
    await consumer.start()
    await producer.start()
    servers = {"business": os.getenv("BUSINESS_MCP_URL", "http://localhost:9101/mcp"),
               "knowledge": os.getenv("KNOWLEDGE_MCP_URL", "http://localhost:9102/mcp")}
    sink = KafkaSink(producer, "analysis.events", "analysis.jobs.dlq")
    async with make_checkpointer() as cp:
        runner = JobRunner(servers, HttpBackend(), cp, sink, worker_id=os.getenv("HOSTNAME"))
        try:
            async for msg in consumer:
                try:
                    job = json.loads(msg.value)
                    await runner.process(job)  # 실패도 이벤트 · DLQ로 끝난다. 중복은 backend claim이 막는다
                except Exception as e:  # noqa: BLE001 — 메시지 형식 오류 등. 같은 메시지에서 계속 죽지 않게 넘긴다
                    await sink.dead_letter({"raw": msg.value.decode(errors="replace")[:2000]}, f"[BAD_MESSAGE] {e}")
                await consumer.commit()  # 이벤트 발행(send_and_wait)이 끝난 뒤 commit
        finally:
            await consumer.stop()
            await producer.stop()


# ------------------------------------------------------------------------------ HTTP (Kafka 전 임시 다리)
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


async def poll_once(runner: JobRunner, backend) -> list[dict]:
    """QUEUED 작업을 한 번 가져와 처리한다. 처리 결과 목록을 돌려준다."""
    try:
        queued = await backend.queued_jobs()
    except FabToolError as e:
        print(f"[poll] {e}", flush=True)
        return []
    done = []
    for item in queued:
        job = item["job"]
        try:
            out = await runner.process(job, question=item.get("question"))
        except Exception as e:  # noqa: BLE001 — 한 작업 때문에 Worker가 멈추지 않게
            out = {"status": "ERROR", "error": str(e)}
        print(f"[job] {job['jobId']} {job['kind']} → {out['status']}", flush=True)
        done.append({"job": job, **out})
    return done


async def run_http_worker(poll_seconds: float = 2.0, once: bool = False) -> None:
    from fab.agent.backend import HttpBackend
    from fab.agent.checkpoint import make_checkpointer

    backend = HttpBackend()
    servers = {"business": os.getenv("BUSINESS_MCP_URL", "http://localhost:9101/mcp"),
               "knowledge": os.getenv("KNOWLEDGE_MCP_URL", "http://localhost:9102/mcp")}
    sink = HttpSink(backend)
    async with make_checkpointer() as cp:
        runner = JobRunner(servers, backend, cp, sink, worker_id=os.getenv("HOSTNAME"))
        while True:
            await poll_once(runner, backend)
            if once:
                return
            await asyncio.sleep(poll_seconds)


def main() -> None:
    from fab.common.env import load_env

    load_env()
    if os.getenv("WORKER_SOURCE", "http") == "http":
        asyncio.run(run_http_worker(float(os.getenv("POLL_SECONDS", "2"))))
    else:
        asyncio.run(run_kafka_worker())

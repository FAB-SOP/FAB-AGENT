"""JobRunner — 작업 메시지 하나를 그래프로 처리하고 진행 · 결과 이벤트를 sink로 낸다(Kafka 없이도 돈다).

작업 의미(팀 계약):
  - BASIC / DEEP / REPORT : 같은 사건 thread에 새 입력
  - RESUME                : 같은 thread의 '정확한' interrupt만 해제(승인 결정 · 회신)
  - SUMMARY               : 확정 보고서 요약 — 그래프 · checkpointer를 쓰지 않는 별도 경로(fab.agent.summary)
  - 일시 실패             : attempt < 3이면 RETRY_SCHEDULED, 아니면 JOB_FAILED + DLQ
"""

from __future__ import annotations

import asyncio
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import TYPE_CHECKING

from langgraph.types import Command

from fab.agent.graph import Runtime, build_graph
from fab.agent.harness.limits import LIMITS
from fab.agent.harness.retry import PermanentJobError, TransientJobError
from fab.agent.harness.trace import Tracer
from fab.agent.models import make_models
from fab.agent.job_input import resolve
from fab.agent.summary import make_summary
from fab.agent.tools import Toolbox
from fab.common.envelope import FabToolError

if TYPE_CHECKING:
    from fab.agent.worker.sinks import EventSink

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


class JobRunner:
    def __init__(self, servers: dict, backend, checkpointer, sink: EventSink, models=None,
                 worker_id: str | None = None):
        self.servers, self.backend, self.sink = servers, backend, sink
        self.graph = build_graph(checkpointer)
        self.primary, self.fallback = models or make_models()
        self.worker_id = worker_id or f"worker-{uuid.uuid4().hex[:6]}"
        self._seq: dict[str, int] = {}
        self._progress: dict[tuple[str, int], int] = {}  # (jobId, attempt) → 진행 알림 순번

    async def _event(self, job: dict, etype: str, payload: dict, metrics: dict | None = None) -> dict:
        # sequence는 thread 안에서 늘 증가해야 한다. Worker가 재시작해도 줄지 않도록 ms 시각과 비교해 키운다.
        key = job["threadId"]
        self._seq[key] = max(self._seq.get(key, 0) + 1, int(time.time() * 1000))
        # eventId는 같은 논리 이벤트면 다시 보내도 같다(backend가 한 번만 적용) — 시각을 넣지 않는다.
        # 한 시도에 한 번 나가는 종류는 종류 이름으로, 여러 번 나가는 진행 알림은 그 시도 안의 순번으로 가른다
        event_id = f"EVT-{job['jobId']}-A{job['attempt']}-{etype}"
        if etype == "ANALYSIS_PROGRESS":
            step = self._progress[(job["jobId"], job["attempt"])] = self._progress.get((job["jobId"], job["attempt"]), 0) + 1
            event_id += f"-{step}"
        event = {"schemaVersion": "1.0", "eventId": event_id,
                 "incidentId": job["incidentId"], "jobId": job["jobId"], "threadId": job["threadId"],
                 "sequence": self._seq[key], "attempt": job["attempt"], "type": etype, "at": _now(), "traceId": job["traceId"],
                 "payload": payload}
        if metrics is not None:  # 작업을 끝내거나 멈추는 이벤트에만 — 이번 시도의 trace 요약(backend job.metrics)
            event["metrics"] = {**metrics, "attempt": job["attempt"]}
        await self.sink.emit(event)
        return event

    async def _fail(self, job: dict, code: str, message: str, tracer: Tracer) -> dict:
        ref = f"analysis.jobs.dlq/{job['jobId']}/A{job['attempt']}"
        try:
            await self._event(job, "JOB_FAILED", {"errorCode": code, "message": message[:300], "dlqRef": ref},
                              tracer.summary())
        except FabToolError as e:  # 실패 이벤트마저 거부되면 더 보내지 않는다 — 예외로 작업이 RUNNING에 남아 재시도가 되풀이되지 않게
            tracer.event("job_failed_rejected", code=e.code, error=e.raw[:300])
            message = f"{message} / JOB_FAILED 이벤트도 거부됨 [{e.code}] {e.raw[:200]}"
        await self.sink.dead_letter(job, f"[{code}] {message}")
        return {"status": "FAILED", "errorCode": code, "error": message, "trace": tracer.summary()}

    async def process(self, job: dict, *, question: str | None = None, trigger: str | None = None) -> dict:
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
            if job["kind"] == "SUMMARY":  # 조사 그래프 밖 — 체크포인트를 읽지도 쓰지도 않는다(조사 대기 상태 불변)
                return await self._summary(job, tracer)
            async with Toolbox(self.servers) as toolbox:
                cfg["configurable"]["runtime"] = Runtime(toolbox, self.backend, self.primary, self.fallback, tracer,
                                                         job_id=job["jobId"])
                payload = await self._input(job, question, cfg, trigger)
                async for chunk in self.graph.astream(payload, cfg, stream_mode="updates"):
                    for node in chunk:
                        if node in STEP_MESSAGES:
                            code, msg = STEP_MESSAGES[node]
                            await self._event(job, "ANALYSIS_PROGRESS",
                                              {"stepCode": code, "message": msg, "progressRatio": 0.5})
                return await self._outcome(job, cfg, tracer)
        except PermanentJobError as e:
            return await self._fail(job, getattr(e, "code", "INVALID_RESUME"), str(e), tracer)
        except (TransientJobError, asyncio.TimeoutError) as e:
            return await self._retry(job, "TRANSIENT", str(e) or type(e).__name__, tracer)
        except FabToolError as e:
            if e.retryable:
                return await self._retry(job, e.code, e.raw, tracer)
            return await self._fail(job, e.code, e.raw, tracer)
        except Exception as e:  # noqa: BLE001 — 예상 못 한 오류도 작업 실패로 끝낸다(Worker는 계속 돈다)
            tracer.event("unexpected_error", error=type(e).__name__, message=str(e)[:300])
            return await self._fail(job, "INTERNAL_ERROR", f"{type(e).__name__}: {e}", tracer)

    async def _summary(self, job: dict, tracer: Tracer) -> dict:
        """확정 스냅샷을 읽어 1~2줄 요약 → SUMMARY_READY. 입력이 확정 스냅샷과 어긋나면 요약하지 않고 실패로 끝낸다"""
        ref = (job.get("input") or {}).get("summaryRef") or {}
        if not ref.get("reportId") or not ref.get("snapshotRef"):
            return await self._fail(job, "INVALID_SUMMARY_INPUT", "작업 입력에 summaryRef(reportId · snapshotRef)가 없습니다.", tracer)
        snapshot = await self.backend.get_report_snapshot(ref["reportId"])
        if snapshot.get("snapshotRef") != ref["snapshotRef"]:
            return await self._fail(job, "SNAPSHOT_MISMATCH", f"작업의 snapshotRef({ref['snapshotRef']})가 확정 스냅샷"
                                    f"({snapshot.get('snapshotRef')})과 다릅니다.", tracer)
        summary = await make_summary(snapshot, self.primary, self.fallback, tracer)
        await self._event(job, "SUMMARY_READY", {"reportId": ref["reportId"], "snapshotRef": ref["snapshotRef"],
                                                 "summary": summary}, tracer.summary())
        return {"status": "DONE", "summary": summary, "trace": tracer.summary()}

    async def _retry(self, job: dict, code: str, message: str, tracer: Tracer) -> dict:
        if job["attempt"] >= LIMITS.job_attempts:
            return await self._fail(job, "RETRY_EXHAUSTED", f"[{code}] {message}", tracer)
        retry_at = (datetime.now(KST) + timedelta(seconds=5 * job["attempt"])).isoformat(timespec="seconds")
        await self._event(job, "RETRY_SCHEDULED", {"nextAttempt": job["attempt"] + 1, "nextRetryAt": retry_at,
                                                   "errorCode": code}, tracer.summary())
        return {"status": "RETRY_SCHEDULED", "errorCode": code, "error": message, "trace": tracer.summary()}

    async def _input(self, job: dict, question: str | None, cfg: dict, trigger: str | None = None):
        snap = await self.graph.aget_state(cfg)
        if job["kind"] == "RESUME":
            resume_ref = job["resume"] or {}
            pending = (snap.values or {}).get("pending") if snap else None
            if not snap or not snap.next or not pending or pending.get("interruptId") != resume_ref.get("interruptId"):
                raise PermanentJobError("대기 중인 interrupt와 재개 참조가 일치하지 않습니다.")
            if resume_ref["reason"] == "APPROVAL_DECIDED":
                if pending.get("approvalId") != resume_ref["refId"]:
                    raise PermanentJobError("재개 참조의 승인 ID가 대기 중인 승인과 다릅니다.")
                appr = await self.backend.get_approval(resume_ref["refId"])
                if appr["status"] not in ("APPROVED", "REJECTED"):
                    raise PermanentJobError(f"승인 {resume_ref['refId']}가 아직 결정되지 않았습니다.")
                value = {"decision": "APPROVE" if appr["status"] == "APPROVED" else "REJECT",
                         "comment": appr.get("comment") or "", "approvalId": appr["approvalId"],
                         "interruptId": resume_ref["interruptId"]}
            else:
                if pending.get("requestId") != resume_ref["refId"]:
                    raise PermanentJobError("재개 참조의 요청 ID가 대기 중인 요청과 다릅니다.")
                req = await self.backend.get_request(resume_ref["refId"])
                if not req.get("reply"):
                    raise PermanentJobError(f"요청 {resume_ref['refId']}에 회신이 없습니다.")
                value = {**req["reply"], "requestId": req["requestId"], "interruptId": resume_ref["interruptId"]}
            return Command(resume=value)
        if snap and snap.next and job["attempt"] > 1:  # 실패한 같은 작업의 재시도 — 멈춘 지점부터
            return None
        if snap and snap.next:
            raise PermanentJobError("이 사건은 승인 또는 회신을 기다리는 중입니다. 대기를 먼저 해제하세요.")
        given = resolve(job, question, trigger)  # 실행 목적 — input.purpose 우선, 전환 기간에는 기존 방식도
        return {"incidentId": job["incidentId"], "threadId": job["threadId"], "jobId": job["jobId"],
                "mode": job["kind"], "purpose": given["purpose"], "question": given["question"] or "",
                "selectedInputs": given["selectedInputs"], "requestInput": given["request"],
                "replyFlow": reply_flow(job)}

    async def _outcome(self, job: dict, cfg: dict, tracer: Tracer) -> dict:
        snap = await self.graph.aget_state(cfg)
        trace = tracer.summary()
        pending_interrupts = [i.value for task in snap.tasks for i in (task.interrupts or [])]
        if pending_interrupts:
            waiting = pending_interrupts[0]
            if waiting["kind"] == "APPROVAL":
                await self._event(job, "APPROVAL_REQUIRED", {"approvalId": waiting["approvalId"],
                                                              "approvalVersion": waiting["approvalVersion"],
                                                              "interruptId": waiting["interruptId"]}, trace)
                return {"status": "WAITING_APPROVAL", "interrupt": waiting, "trace": trace}
            await self._event(job, "WAITING_REPLY", {"requestId": waiting["requestId"],
                                                     "interruptId": waiting["interruptId"]}, trace)
            return {"status": "WAITING_REPLY", "interrupt": waiting, "trace": trace}
        final_state = snap.values
        if final_state.get("mode") == "REPORT":
            await self._event(job, "REPORT_DRAFT_READY", {"report": final_state["report"]}, trace)
            return {"status": "DONE", "report": final_state["report"], "trace": trace}
        result, analysis = final_state["result"], final_state.get("analysis") or {}
        # 계약(AnalysisResult)은 추가 필드를 허용하지 않는다. nextStep 등은 계약 개정 전까지 옵션으로만 싣는다.
        payload = {**result, **analysis} if os.getenv("FAB_RESULT_EXTENSIONS") == "true" else result
        suggestions = final_state.get("workSuggestions") or []
        await self._event(job, "RESULT_READY", {"result": payload, **({"workSuggestions": suggestions} if suggestions else {})}, trace)
        return {"status": "DONE", "result": result, "analysis": analysis, "workSuggestions": suggestions, "trace": trace}


def reply_flow(job: dict) -> str:
    """새로 시작하는 thread의 회신 흐름 — backend가 작업에 저장해 준 input.replyFlow가 먼저, 없으면 Worker 설정
    FAB_REPLY_FLOW(MANUAL이면 요청 발송 뒤 끝냄), 기본은 WAIT. thread를 시작할 때 고정되고 RESUME은 그 값을 유지한다"""
    given = str((job.get("input") or {}).get("replyFlow") or os.getenv("FAB_REPLY_FLOW", "")).upper()
    return "MANUAL" if given == "MANUAL" else "WAIT"


def make_job(kind: str, incident_id: str = "EV-1009", thread_id: str = "THR-EV1009-01",
             resume: dict | None = None, attempt: int = 1, job_id: str | None = None) -> dict:
    jid = job_id or f"JOB-{uuid.uuid4().hex[:6].upper()}"
    return {"schemaVersion": "1.0", "eventId": f"EVT-{jid}-A{attempt}", "jobId": jid, "incidentId": incident_id,
            "threadId": thread_id, "kind": kind, "attempt": attempt, "contextRef": f"job-context://{jid}",
            "resume": resume, "createdAt": _now(), "traceId": f"TRACE-{jid}"}

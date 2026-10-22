"""HTTP 작업 소스(WORKER_SOURCE=http, 기본) — Spring backend의 GET /internal/jobs(QUEUED)를 주기적으로 가져가고
같은 이벤트를 POST /internal/jobs/{jobId}/events로 돌려준다(Kafka 전 임시 다리)."""

from __future__ import annotations

import asyncio
import os

from fab.agent.harness.trace import summary_line
from fab.agent.worker.runner import JobRunner
from fab.agent.worker.sinks import HttpSink
from fab.common.envelope import FabToolError


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
        print(f"[job] {job['jobId']} {job['kind']} → {out['status']} {summary_line(out.get('trace'))}".rstrip(), flush=True)
        done.append({"job": job, **out})
    return done


async def run_http_worker(poll_seconds: float = 2.0, once: bool = False) -> None:
    from fab.agent.backend import HttpBackend
    from fab.agent.checkpoint import make_checkpointer

    backend = HttpBackend()
    servers = {"business": os.getenv("BUSINESS_MCP_URL", "http://localhost:9101/mcp"),
               "knowledge": os.getenv("KNOWLEDGE_MCP_URL", "http://localhost:9102/mcp")}
    sink = HttpSink(backend)
    async with make_checkpointer() as checkpointer:
        runner = JobRunner(servers, backend, checkpointer, sink, worker_id=os.getenv("HOSTNAME"))
        while True:
            await poll_once(runner, backend)
            if once:
                return
            await asyncio.sleep(poll_seconds)

"""fab-worker 진입점 — WORKER_SOURCE로 작업 소스를 고른다."""

from __future__ import annotations

import asyncio
import os


def main() -> None:
    from fab.common.env import load_env

    load_env()
    from fab.agent.worker.sources.http import run_http_worker
    from fab.agent.worker.sources.kafka import run_kafka_worker

    if os.getenv("WORKER_SOURCE", "http") == "http":
        asyncio.run(run_http_worker(float(os.getenv("POLL_SECONDS", "2"))))
    else:
        asyncio.run(run_kafka_worker())

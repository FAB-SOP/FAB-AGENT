"""Kafka 작업 소스(WORKER_SOURCE=kafka) — analysis.jobs 소비 → analysis.events 발행 · offset commit.
Spring은 아직 Kafka로 발행하지 않는다 — Kafka를 어디까지 쓸지 정하면 붙인다(팀 결정 전까지 남겨 둔다)."""

from __future__ import annotations

import asyncio
import json
import os

from fab.agent.worker.runner import JobRunner
from fab.agent.worker.sinks import KafkaSink


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

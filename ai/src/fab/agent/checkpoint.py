"""State · Memory — thread(사건) 단위 checkpoint. PG_DSN이 있으면 PostgreSQL, 없으면 메모리."""

from __future__ import annotations

import os
from contextlib import asynccontextmanager

from langgraph.checkpoint.memory import InMemorySaver


@asynccontextmanager
async def make_checkpointer():
    dsn = os.getenv("PG_DSN")
    if not dsn:
        yield InMemorySaver()
        return
    import psycopg
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

    # agent 전용 스키마: DSN에 options=-csearch_path%3Dagent 를 붙인다(업무 테이블과 분리)
    async with await psycopg.AsyncConnection.connect(dsn, autocommit=True) as con:
        await con.execute("CREATE SCHEMA IF NOT EXISTS agent")
    async with AsyncPostgresSaver.from_conn_string(dsn) as saver:
        await saver.setup()
        yield saver

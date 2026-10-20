"""Observability — trace_id 하나로 node · tool · token · 지연 · 재시도를 남긴다.

로컬은 JSON 줄로 남기고(FAB_TRACE_FILE), LANGSMITH_TRACING=true면 LangChain 모델 호출은 LangSmith로도 간다.
"""

from __future__ import annotations

import json
import os
import time
from contextlib import contextmanager


class Tracer:
    def __init__(self, trace_id: str, job_id: str, incident_id: str):
        self.trace_id, self.job_id, self.incident_id = trace_id, job_id, incident_id
        self.records: list[dict] = []
        self.path = os.getenv("FAB_TRACE_FILE")

    def event(self, kind: str, **fields) -> None:
        rec = {"traceId": self.trace_id, "jobId": self.job_id, "incidentId": self.incident_id,
               "at": round(time.time(), 3), "kind": kind, **fields}
        self.records.append(rec)
        if self.path:
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    @contextmanager
    def span(self, kind: str, **fields):
        """with 블록 안에서 받은 dict에 값을 채우면(토큰 등) 그 값까지 함께 기록한다."""
        t0 = time.perf_counter()
        status = "ok"
        extra: dict = {}
        try:
            yield extra
        except Exception as e:
            status = type(e).__name__
            raise
        finally:
            self.event(kind, ms=round((time.perf_counter() - t0) * 1000, 1), status=status, **fields, **extra)

    def summary(self) -> dict:
        tools = [r for r in self.records if r["kind"] == "tool"]
        llm = [r for r in self.records if r["kind"] == "llm"]
        return {
            "traceId": self.trace_id,
            "toolCalls": len(tools),
            "modelCalls": len(llm),
            "tokens": sum(r.get("tokens", 0) or 0 for r in llm),
            "apiModels": sorted({r["apiModel"] for r in llm if r.get("apiModel")}),
            "retries": sum(1 for r in self.records if r["kind"] in ("llm_error", "tool_retry")),
            "ms": round(sum(r.get("ms", 0) for r in self.records if r["kind"] in ("tool", "llm")), 1),
        }

"""Observability — trace_id 하나로 node · tool · token · 지연 · 재시도를 남긴다.

FAB_TRACE_STDOUT=true면 기록마다 JSON 한 줄을 표준출력에 낸다(컨테이너 — docker logs · 로그 수집기가 가져간다).
FAB_TRACE_FILE은 같은 줄을 파일에도 덧붙인다(로컬 평가용). LANGSMITH_TRACING=true면 LangChain 모델 호출은 LangSmith로도 간다.
작업이 끝나면 summary()가 작업 단위 요약을 만들고, Worker가 마지막 이벤트의 metrics로 backend에 보낸다(job.metrics).
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from contextlib import contextmanager

# 오류 메시지 등에 섞여 들어올 수 있는 키 · 토큰 모양 — 출력 전에 가린다
_SECRET = re.compile(r"(sk-[A-Za-z0-9_-]{8,}|Bearer\s+[A-Za-z0-9._-]{8,})")


def _redact(line: str) -> str:
    return _SECRET.sub("[REDACTED]", line)


def _on(name: str) -> bool:
    return os.getenv(name, "").lower() in ("1", "true", "yes")


class Tracer:
    def __init__(self, trace_id: str, job_id: str, incident_id: str):
        self.trace_id, self.job_id, self.incident_id = trace_id, job_id, incident_id
        self.records: list[dict] = []
        self.path = os.getenv("FAB_TRACE_FILE")
        self.stdout = _on("FAB_TRACE_STDOUT")

    def event(self, kind: str, **fields) -> None:
        rec = {"traceId": self.trace_id, "jobId": self.job_id, "incidentId": self.incident_id,
               "at": round(time.time(), 3), "kind": kind, **fields}
        self.records.append(rec)
        if not (self.path or self.stdout):
            return
        line = _redact(json.dumps(rec, ensure_ascii=False, default=str))
        if self.stdout:
            print(line, file=sys.stdout, flush=True)
        if self.path:
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(line + "\n")

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
            "toolErrors": sum(1 for r in tools if r.get("status") != "ok"),
            "modelCalls": len(llm),
            "tokens": sum(r.get("tokens", 0) or 0 for r in llm),
            "inputTokens": sum(r.get("inputTokens", 0) or 0 for r in llm),
            "outputTokens": sum(r.get("outputTokens", 0) or 0 for r in llm),
            "apiModels": sorted({r["apiModel"] for r in llm if r.get("apiModel")}),
            "fallbackCalls": sum(1 for r in llm if r.get("model") == "fallback"),
            "retries": sum(1 for r in self.records if r["kind"] in ("llm_error", "tool_retry")),
            # Harness가 거부한 횟수 — 근거 없는 브리프 · 정합성 검사에 걸린 제안
            "guardRejects": sum(1 for r in self.records
                                if r["kind"] == "guard_reject" or (r["kind"] == "brief" and r.get("ok") is False)),
            "ms": round(sum(r.get("ms", 0) for r in self.records if r["kind"] in ("tool", "llm")), 1),
        }



def summary_line(s: dict | None) -> str:
    """[job] 로그 줄 끝에 붙이는 사람이 읽는 요약 — Tracer.summary()의 dict를 받는다."""
    if not s:
        return ""
    models = ",".join(s.get("apiModels") or []) or "-"
    return (f"llm={s.get('modelCalls', 0)} tok={s.get('inputTokens', 0)}/{s.get('outputTokens', 0)} model={models} "
            f"tools={s.get('toolCalls', 0)} reject={s.get('guardRejects', 0)} retry={s.get('retries', 0)} "
            f"ms={s.get('ms', 0):.0f}")

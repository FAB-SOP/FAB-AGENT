"""Agent Loop 한도. 사람 대기 시간은 활성 시간에 넣지 않는다 — 작업(job)마다 새 활성 구간."""

from __future__ import annotations

import os
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class Limits:
    max_model_calls: int = int(os.getenv("FAB_MAX_MODEL_CALLS", "8"))
    max_same_tool: int = int(os.getenv("FAB_MAX_SAME_TOOL", "3"))
    active_seconds: float = float(os.getenv("FAB_ACTIVE_SECONDS", "120"))
    llm_timeout: float = float(os.getenv("FAB_LLM_TIMEOUT", "30"))
    mcp_timeout: float = float(os.getenv("FAB_MCP_TIMEOUT", "10"))
    llm_retries: int = 2  # 429 · 503 등 일시 오류만
    job_attempts: int = 3  # 최초 1회 + 자동 재시도 2회
    max_proposals: int = int(os.getenv("FAB_MAX_PROPOSALS", "3"))  # 사건당 요청(대체됨 제외) — 회신 뒤 남발 방지
    max_tool_result_chars: int = 6000
    context_char_budget: int = 60000


LIMITS = Limits()


def new_window() -> dict:
    return {"modelCalls": 0, "toolCalls": {}, "startedAt": time.time(), "retries": 0}


def check(counters: dict, limits: Limits = LIMITS) -> str | None:
    """한도를 넘었으면 이유를 돌려준다."""
    if counters["modelCalls"] >= limits.max_model_calls:
        return f"모델 판단 한도({limits.max_model_calls}회)에 도달했습니다."
    if time.time() - counters["startedAt"] > limits.active_seconds:
        return f"활성 시간 한도({int(limits.active_seconds)}초)를 넘었습니다."
    return None


def tool_allowed(counters: dict, name: str, limits: Limits = LIMITS) -> bool:
    return counters["toolCalls"].get(name, 0) < limits.max_same_tool

"""Recovery — 일시 오류만 재시도한다. 권한 · 인자 오류는 반복하지 않는다.

LLM: 429 · 503 · 타임아웃 → 최대 2회 재시도(지수 대기) → 대체 모델 → 그래도 실패면 TransientJobError
작업 단위: TransientJobError를 worker가 받아 attempt < 3이면 RETRY_SCHEDULED, 아니면 JOB_FAILED + DLQ
"""

from __future__ import annotations

import asyncio

from fab.agent.harness.limits import LIMITS


class TransientJobError(Exception):
    """작업을 나중에 다시 시도하면 될 수 있는 실패."""


class PermanentJobError(Exception):
    """다시 해도 같은 결과인 실패(잘못된 재개 참조 · 권한 등)."""


def is_transient(exc: BaseException) -> bool:
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError, TransientJobError)):
        return True
    status = getattr(exc, "status_code", None) or getattr(getattr(exc, "response", None), "status_code", None)
    if status in (408, 409, 429, 500, 502, 503, 504):
        return True
    name = type(exc).__name__
    return name in {"RateLimitError", "APITimeoutError", "APIConnectionError", "InternalServerError"}


async def call_with_recovery(primary, fallback, messages, tools, tracer, timeout: float = LIMITS.llm_timeout):
    """primary를 재시도하고, 끝내 실패하면 fallback으로 한 번 더. 각 시도를 trace에 남긴다."""
    last: BaseException | None = None
    for model, label in ((primary, "primary"), (fallback, "fallback")):
        if model is None:
            continue
        tries = LIMITS.llm_retries + 1 if label == "primary" else 1
        for i in range(tries):
            try:
                return await asyncio.wait_for(model.ainvoke(messages, tools), timeout=timeout), label
            except Exception as e:  # noqa: BLE001
                last = e
                tracer.event("llm_error", model=label, attempt=i + 1, error=type(e).__name__, transient=is_transient(e))
                if not is_transient(e):
                    raise
                if i < tries - 1:
                    await asyncio.sleep(min(0.5 * 2**i, 4))
        tracer.event("llm_fallback", reason=type(last).__name__ if last else None)
    raise TransientJobError(f"모델 호출이 재시도 후에도 실패했습니다: {type(last).__name__}")

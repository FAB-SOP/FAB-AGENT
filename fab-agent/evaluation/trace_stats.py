"""trace(JSON 줄) 집계 — LLM 호출 수 · 실제 응답 모델 · 대체 모델 · 타임아웃 · 재시도 · 토큰 · 시간 · 비용 추정.

  uv run python evaluation/trace_stats.py evaluation/runs/…/eval_1.jsonl

비용은 아래 단가(USD / 1M 토큰, 캐시 할인 없이)로 어림한다 — 실제 청구와 다를 수 있다.
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

PRICE = {"gpt-4.1-mini": (0.40, 1.60), "gpt-4o-mini": (0.15, 0.60), "gpt-4.1": (2.00, 8.00), "gpt-4o": (2.50, 10.00)}


def price_of(model: str) -> tuple[float, float]:
    for k in sorted(PRICE, key=len, reverse=True):
        if (model or "").startswith(k):
            return PRICE[k]
    return (0.0, 0.0)


def stats(path: Path) -> dict:
    recs = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()] if path.exists() else []
    llm = [r for r in recs if r["kind"] == "llm"]
    errs = [r for r in recs if r["kind"] == "llm_error"]
    cost = sum(r.get("inputTokens", 0) * price_of(r.get("apiModel") or r.get("modelName"))[0]
               + r.get("outputTokens", 0) * price_of(r.get("apiModel") or r.get("modelName"))[1] for r in llm) / 1e6
    return {
        "modelNames": sorted({r.get("modelName") for r in llm if r.get("modelName")}),
        "apiModels": dict(Counter(r.get("apiModel") for r in llm)),
        "llmCalls": len(llm),
        "fallbackCalls": sum(1 for r in llm if r.get("model") == "fallback"),
        "timeouts": sum(1 for r in errs if r.get("error") in ("TimeoutError", "CancelledError")),
        "llmErrors": len(errs),
        "inputTokens": sum(r.get("inputTokens", 0) or 0 for r in llm),
        "outputTokens": sum(r.get("outputTokens", 0) or 0 for r in llm),
        "llmSeconds": round(sum(r.get("ms", 0) for r in llm) / 1000, 1),
        "maxCallSeconds": round(max((r.get("ms", 0) for r in llm), default=0) / 1000, 1),
        "wallSeconds": round(recs[-1]["at"] - recs[0]["at"], 1) if recs else 0,
        "costUsd": round(cost, 4),
        "jobs": len({r["jobId"] for r in recs}),
    }


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(p, json.dumps(stats(Path(p)), ensure_ascii=False))

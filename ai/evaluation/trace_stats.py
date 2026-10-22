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
    recs = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()] if path.exists() else []
    llm = [record for record in recs if record["kind"] == "llm"]
    errs = [record for record in recs if record["kind"] == "llm_error"]
    cost = sum(record.get("inputTokens", 0) * price_of(record.get("apiModel") or record.get("modelName"))[0]
               + record.get("outputTokens", 0) * price_of(record.get("apiModel") or record.get("modelName"))[1] for record in llm) / 1e6
    return {
        "modelNames": sorted({record.get("modelName") for record in llm if record.get("modelName")}),
        "apiModels": dict(Counter(record.get("apiModel") for record in llm)),
        "llmCalls": len(llm),
        "fallbackCalls": sum(1 for record in llm if record.get("model") == "fallback"),
        "timeouts": sum(1 for record in errs if record.get("error") in ("TimeoutError", "CancelledError")),
        "llmErrors": len(errs),
        "inputTokens": sum(record.get("inputTokens", 0) or 0 for record in llm),
        "outputTokens": sum(record.get("outputTokens", 0) or 0 for record in llm),
        "llmSeconds": round(sum(record.get("ms", 0) for record in llm) / 1000, 1),
        "maxCallSeconds": round(max((record.get("ms", 0) for record in llm), default=0) / 1000, 1),
        "wallSeconds": round(recs[-1]["at"] - recs[0]["at"], 1) if recs else 0,
        "costUsd": round(cost, 4),
        "jobs": len({record["jobId"] for record in recs}),
    }


if __name__ == "__main__":
    for trace_path in sys.argv[1:]:
        print(trace_path, json.dumps(stats(Path(trace_path)), ensure_ascii=False))

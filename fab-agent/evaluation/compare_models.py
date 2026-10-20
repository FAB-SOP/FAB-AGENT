"""모델별 정답 · 토큰 사용량 비교 — EV-1006 · EV-1007 · EV-1009를 Spring backend 위에서 끝까지 돌린다(fab-eval과 같은 흐름).

  docker compose up -d postgres   # compose에서는 postgres만(backend · agent는 끈다)
  cd ../fab-context-api && DDL_AUTO=create AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun
  uv run python evaluation/compare_models.py gpt-4.1-mini gpt-4o-mini
모델마다 Spring을 다시 띄워야 같은 시드에서 잰다(DDL_AUTO=create는 띄울 때마다 처음 데이터). 대체 모델은 끈다(FAB_FALLBACK_MODEL=).
"""

import asyncio
import json
import os
import sys
import tempfile
import time
from collections import defaultdict

from fab.common.env import load_env

load_env()
os.environ["FAB_LLM"] = "openai"
os.environ["FAB_FALLBACK_MODEL"] = ""

from fab.devtools.demo import evaluate  # noqa: E402
from fab.rag.server import Knowledge  # noqa: E402


async def measure(model: str, kb) -> dict:
    os.environ["FAB_MODEL"] = model
    path = tempfile.mktemp(suffix=".jsonl")
    os.environ["FAB_TRACE_FILE"] = path
    t0 = time.perf_counter()
    try:
        rows = [{k: r[k] for k in ("incident", "expected", "got", "causeIds", "proposals", "pass")} for r in await evaluate(kb)]
    except Exception as e:  # noqa: BLE001
        rows = [{"error": f"{type(e).__name__}: {str(e)[:120]}"}]
    tot = defaultdict(int)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for rec in map(json.loads, f):
                if rec["kind"] != "llm":
                    continue
                for k in ("inputTokens", "outputTokens", "reasoningTokens", "tokens"):
                    tot[k] += rec.get(k, 0) or 0
                tot["calls"] += 1
                tot["llm_ms"] += rec.get("ms", 0)
    return {"model": model, "sec": round(time.perf_counter() - t0, 1), "incidents": rows, "total": dict(tot)}


async def main(models):
    kb = Knowledge()
    out = [await measure(m, kb) for m in models]
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:] or ["gpt-4.1-mini"]))

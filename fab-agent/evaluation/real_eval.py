"""실제 모델로 정답표 대조(E01–E03) 반복 — fab-eval과 같은 evaluate()를 평가용 환경(eval_env)에서 N번 돌린다.

  uv run python evaluation/real_eval.py --runs 3                       # gpt-4.1-mini(대체 gpt-4o-mini) · EMBEDDER=openai
  uv run python evaluation/real_eval.py --runs 1 --embedder hash       # 같은 조건, 해시 임베딩

실행마다 Spring을 다시 띄워(처음 시드) 같은 조건에서 잰다. trace · 결과는 evaluation/runs/<시각>/에 남는다(git 제외).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_env  # noqa: E402
import trace_stats  # noqa: E402


async def one(run_dir: Path, tag: str) -> dict:
    from fab.devtools.demo import evaluate

    path = run_dir / f"{tag}.jsonl"
    os.environ["FAB_TRACE_FILE"] = str(path)
    t0 = time.perf_counter()
    try:
        rows = await evaluate()
        error = None
    except Exception as e:  # noqa: BLE001 — 실패도 표에 남긴다
        rows, error = [], f"{type(e).__name__}: {str(e)[:200]}"
    out = {"tag": tag, "sec": round(time.perf_counter() - t0, 1), "error": error, "stats": trace_stats.stats(path),
           "rows": [{k: r[k] for k in ("incident", "expected", "got", "causeIds", "proposal", "checks", "pass")} for r in rows]}
    (run_dir / f"{tag}.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--first", type=int, default=1, help="실행 번호를 몇부터 붙일지")
    ap.add_argument("--model", default="gpt-4.1-mini")
    ap.add_argument("--fallback", default="gpt-4o-mini")
    ap.add_argument("--embedder", default="openai", choices=["openai", "hash"])
    ap.add_argument("--llm", default="openai", choices=["openai", "rule"], help="rule = 비용 없이 평가 도구만 시험")
    a = ap.parse_args()
    eval_env.client_env(a.model, a.fallback, a.embedder, a.llm)
    from fab.common.env import load_env

    load_env()
    run_dir = eval_env.RUNS / (time.strftime("%Y%m%d-%H%M%S-") + f"eval-{a.llm}-{a.model}-{a.embedder}")
    run_dir.mkdir(parents=True, exist_ok=True)
    with eval_env.environment(run_dir) as spring:
        asyncio.run(repeat(spring, run_dir, a.runs, a.first))
    print("산출물:", run_dir)


async def repeat(spring, run_dir: Path, runs: int, first: int) -> None:
    """반복을 한 이벤트 루프에서 — 루프마다 asyncio.run을 새로 부르면 langchain-openai가 캐시한 비동기 HTTP 클라이언트가
    닫힌 루프에 묶여 「Event loop is closed」로 첫 호출이 실패한다(평가 도구 문제, Agent 판단과 무관)"""
    for i in range(first, first + runs):
        jdbc = spring.start()
        print(f"[{i}] 평가용 Spring {eval_env.BASE} · JDBC {jdbc}", flush=True)
        r = await one(run_dir, f"eval_{i}")
        st = r["stats"]
        for row in r["rows"]:
            marks = " ".join(f"{k}{'✔' if v else '✘'}" for k, v in row["checks"].items())
            p = row["proposal"] or {}
            print(f"  {row['incident']} 기대 {row['expected']:<15} 결과 {str(row['got']):<22} 원인 {row['causeIds']} "
                  f"제안 {p.get('actionType')}→{p.get('targetProcessCode')}  {marks} {'PASS' if row['pass'] else 'FAIL'}")
        print(f"  통과 {sum(x['pass'] for x in r['rows'])}/{len(r['rows'])}{' · 오류 ' + r['error'] if r['error'] else ''} · "
              f"{json.dumps(st, ensure_ascii=False)}", flush=True)


if __name__ == "__main__":
    main()

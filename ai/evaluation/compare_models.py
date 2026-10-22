"""모델 비교 — 같은 조건(대체 모델 끔 · EMBEDDER=openai · 평가용 환경)에서 정답표 E01–E03과 시험 E04–E08을 잰다.

  uv run python evaluation/compare_models.py gpt-4.1-mini gpt-4o-mini

모델마다 평가용 Spring을 두 번 새로 띄운다(E01–E03용 · E04–E08용, 매번 처음 시드). 정답표(ground_truth.json)와 시험 문항 파일은
평가 스크립트만 읽는다. trace · 결과는 evaluation/runs/<시각>-compare/에 남는다(git 제외).
"""

from __future__ import annotations

import asyncio
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_env  # noqa: E402
import real_eval  # noqa: E402
import scenario_eval  # noqa: E402


async def measure(model: str, spring, run_dir: Path, cases: list[dict]) -> dict:
    eval_env.client_env(model, fallback="", embedder="openai")
    spring.start()
    ground_truth = await real_eval.one(run_dir, f"{model}-E01-E03")
    spring.start()
    scenarios = await scenario_eval.run_all(cases, run_dir, f"{model}-E04-E08")
    (run_dir / f"{model}-E04-E08.json").write_text(json.dumps(scenarios, ensure_ascii=False, indent=1), encoding="utf-8")
    scenario_eval.report(scenarios, run_dir / f"{model}-E04-E08.md")
    truth_stats, scenario_stats = ground_truth["stats"], scenarios["stats"]
    return {"model": model, "E01-E03": f"{sum(row['pass'] for row in ground_truth['rows'])}/{len(ground_truth['rows'])}",
            "E04-E08 자동": f"{sum(bool(row.get('pass')) for row in scenarios['rows'])}/{len(scenarios['rows'])}",
            "apiModels": sorted(set(truth_stats["apiModels"]) | set(scenario_stats["apiModels"])),
            "llmCalls": truth_stats["llmCalls"] + scenario_stats["llmCalls"], "inputTokens": truth_stats["inputTokens"] + scenario_stats["inputTokens"],
            "outputTokens": truth_stats["outputTokens"] + scenario_stats["outputTokens"], "timeouts": truth_stats["timeouts"] + scenario_stats["timeouts"],
            "llmSeconds": round(truth_stats["llmSeconds"] + scenario_stats["llmSeconds"], 1), "costUsd": round(truth_stats["costUsd"] + scenario_stats["costUsd"], 4),
            "errors": [error for error in (ground_truth["error"], *[row.get("error") for row in scenarios["rows"]]) if error]}


async def main(models: list[str]) -> None:
    eval_env.client_env(models[0], fallback="")
    from fab.common.env import load_env

    load_env()
    cases = [case for case in json.loads(scenario_eval.CASES_FILE.read_text(encoding="utf-8")) if case["id"] in scenario_eval.RUBRIC]
    run_dir = eval_env.RUNS / (time.strftime("%Y%m%d-%H%M%S-") + "compare")
    run_dir.mkdir(parents=True, exist_ok=True)
    with eval_env.environment(run_dir) as spring:
        rows = [await measure(model, spring, run_dir, cases) for model in models]
    (run_dir / "compare.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    for row in rows:
        print(json.dumps(row, ensure_ascii=False))
    print("산출물:", run_dir)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:] or ["gpt-4.1-mini", "gpt-4o-mini"]))

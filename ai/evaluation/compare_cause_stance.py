"""원인 후보 판정 전후 비교 — 같은 입력 20건(시드 기본 분석 + 질문)으로 원인 후보 판정 · 검색 근거 읽기를 잰다.

  uv run python evaluation/compare_cause_stance.py --label after                                        # 규칙 모델 · 해시 임베딩 — 비용 없음
  PYTHONPATH=<이전 판 worktree>/ai/src uv run python evaluation/compare_cause_stance.py --label before --llm openai --embedder openai

이전 판은 PYTHONPATH로 그 판의 src를 앞에 둬서 같은 스크립트 · 같은 backend로 돈다(어느 판이 돌았는지 fab 경로를 남긴다).
재는 것: 원인 후보 수 · 지지 · 반증 · 판정 보류(fab.agent.stance 규칙으로 두 판 모두 사후 계산) · 반대 근거가 evidenceRefs에서
찾아지는지 · 검색 근거 인용 · 모델에게 잘려 간 도구 결과 · Guard 반려 · 토큰 · 비용. 정답표는 읽지 않는다. 산출물은 evaluation/runs/에만.
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
from retest_q1_q4 import _ask, _drain  # noqa: E402

TOTAL = 20
BUDGET_USD = 0.5  # 판마다 — 넘으면 남은 입력을 돌리지 않고 멈춘다(두 판 합계 1달러 안)
QUESTIONS = [
    ("EV-1006", "원인 후보를 근거와 함께 정리해줘. 다른 가능성은 어떤 근거로 배제할 수 있는지도 알려줘."),
    ("EV-1007", "슬러리 유량 말고 다른 원인 가능성도 있어? 근거가 지지하는 것과 반대하는 것을 나눠서 알려줘."),
    ("EV-1009", "CMP 공정 문제가 아니라 앞 공정 기여라고 볼 근거와, 그렇게 보기 어려운 근거를 같이 알려줘."),
    ("EV-1006", "헤드 압력 저하가 제거량에 어떻게 영향을 주는지 기술 문서 근거로 설명해줘. 문서의 적용 한계도 알려줘."),
    ("EV-1007", "슬러리 공급과 제거율의 관계를 원문 문서로 설명하고, 이 사건에 그대로 적용하기 어려운 점을 알려줘."),
    ("EV-1009", "에치 챔버 상태가 다음 공정 두께에 영향을 주는 원리를 문서 근거로 설명해줘."),
    ("EV-1006", "센서 데이터만으로는 판단하기 어려운 부분이 뭐야? 무엇을 더 확인해야 원인을 좁힐 수 있어?"),
    ("EV-1007", "지금 데이터로 판정을 보류해야 하는 원인 후보가 있으면 무엇이 부족한지 알려줘."),
    ("EV-1009", "CVD · ETCH · CMP 각 공정별로 원인 후보를 세우고 근거로 순위를 매겨줘."),
    ("EV-1006", "관리한계 이탈인지 규격 이탈인지 구분해서 설명하고, 관리도 해석 근거 문서를 인용해줘."),
    ("EV-1007", "비슷한 과거 사례가 있으면 참고로 알려주고, 현재 사건과 다른 점도 짚어줘."),
    ("EV-1009", "이 사건에서 계측 오프셋이나 측정 위치 문제일 가능성은 어떻게 봐야 해?"),
    ("EV-1006", "패드 마모나 슬러리 문제일 가능성은 데이터로 어느 정도 배제돼?"),
    ("EV-1007", "헤드 압력이나 플래튼 속도 쪽 원인일 가능성은 근거로 볼 때 어때?"),
    ("EV-1009", "앞 공정 담당자에게 확인하기 전에 우리가 직접 볼 수 있는 데이터는 뭐가 있어?"),
    ("EV-1006", "이 사건의 원인 후보마다 지지 근거와 반대 근거를 표처럼 정리해줘."),
    ("EV-1009", "이 사건에서 근거가 갈려 판단을 미뤄야 하는 부분이 있으면 알려줘."),
]


class ToolTextMeter:
    """모델에게 간 도구 결과 크기 — 판마다 to_tool_text의 인자가 달라 감싸서 센다(잘림 · 검색 결과 여부)"""

    def __init__(self):
        from fab.agent.harness import context

        self.rows: list[dict] = []
        original = context.to_tool_text

        def wrapped(result, *args, **kwargs):
            text = original(result, *args, **kwargs)
            items = ((result or {}).get("data") or {}).get("items") if isinstance((result or {}).get("data"), dict) else None
            search = bool(items) and all(isinstance(item, dict) and "chunkId" in item for item in items)
            ids = [item.get("evidenceId") for item in items or [] if isinstance(item, dict)] if search else []
            self.rows.append({"search": search, "chars": len(text), "truncated": "결과가 길어 잘렸습니다" in text,
                              "lostIds": sum(1 for evidence_id in ids if evidence_id and evidence_id not in text)})
            return text
        context.to_tool_text = wrapped


async def _incidents() -> list[str]:
    from fab.devtools.spring import internal

    body = (await internal("GET", "/internal/incidents?size=50"))["data"]
    return [row.get("displayId") or row.get("incidentId") for row in (body.get("data") or {}).get("items") or []]


async def ask(display: str, question: str, runner, backend) -> dict:
    from fab.devtools.spring import OWNER_OF_PROCESS, User, internal

    process = (await internal("GET", f"/internal/incidents/{display}"))["data"]["data"]["processCode"]
    user = await User(OWNER_OF_PROCESS[process]).login()
    job_id = await _ask(user, display, question)
    outs = await _drain(runner, backend)
    final = next((o for o in outs if o["job"]["jobId"] == job_id), outs[-1] if outs else {})
    chain = [final.get("status")]
    if final.get("status") == "WAITING_APPROVAL":  # 거절 · 재개 없이 — 승인 전에 이미 접수된 답을 그래프 상태에서 읽는다(비용 절약)
        final = {**final, **await _accepted_answer(runner, final["job"])}
    await user.close()
    return {"incident": display, "question": question, "status": chain, **_keep(final)}


async def _accepted_answer(runner, job: dict) -> dict:
    """승인 대기로 멈춘 작업의 답 — 그 판의 finalize로 계약 모양을 만든다(재개 뒤 정리와 같은 함수)"""
    from fab.agent.graph.nodes.finalize import finalize

    snap = await runner.graph.aget_state({"configurable": {"thread_id": job["threadId"]}})
    if not snap.values.get("result"):
        return {}

    class _Runtime:
        job_id = job["jobId"]
    out = await finalize(snap.values, {"configurable": {"runtime": _Runtime()}})
    return {"result": out["result"], "analysis": out.get("analysis"), "workSuggestions": out.get("workSuggestions")}


def _keep(outcome: dict) -> dict:
    return {"jobId": (outcome.get("job") or {}).get("jobId"), "kind": (outcome.get("job") or {}).get("kind"),
            "result": outcome.get("result"), "analysis": outcome.get("analysis"),
            "workSuggestions": outcome.get("workSuggestions")}


async def run_all(run_dir: Path, total: int, budget: float) -> dict:
    import fab
    from fab.devtools.spring import make_runner

    trace = run_dir / "trace.jsonl"
    os.environ["FAB_TRACE_FILE"] = str(trace)
    meter = ToolTextMeter()
    runner, backend = make_runner()
    started = time.perf_counter()
    rows = [{"incident": o["job"].get("incidentId"), "question": None, "status": [o.get("status")], **_keep(o)}
            for o in await _drain(runner, backend) if o.get("job", {}).get("kind") == "BASIC"]  # 시드 기본 분석
    stopped = None
    for display, question in QUESTIONS[: max(0, total - len(rows))]:
        spent = trace_stats.stats(trace)["costUsd"]
        if spent >= budget:
            stopped = f"비용 {spent}달러가 상한 {budget}달러에 닿아 남은 입력을 돌리지 않았습니다"
            break
        try:
            rows.append(await ask(display, question, runner, backend))
        except Exception as e:  # noqa: BLE001 — 한 입력 실패가 나머지를 막지 않게
            rows.append({"incident": display, "question": question, "error": f"{type(e).__name__}: {str(e)[:200]}"})
    return {"fab": str(Path(fab.__file__).resolve().parent), "stopped": stopped, "sec": round(time.perf_counter() - started, 1),
            "incidents": await _incidents(), "rows": rows, "toolTexts": meter.rows, "stats": trace_stats.stats(trace),
            "guardRejects": _count(trace, "guard_reject")}


def _count(trace: Path, kind: str) -> int:
    if not trace.exists():
        return 0
    return sum(1 for line in trace.read_text(encoding="utf-8").splitlines() if line.strip() and json.loads(line)["kind"] == kind)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--model", default="gpt-4.1-mini")
    parser.add_argument("--embedder", default="hash", choices=["openai", "hash"])
    parser.add_argument("--llm", default="rule", choices=["openai", "rule"])
    parser.add_argument("--total", type=int, default=TOTAL)
    parser.add_argument("--budget", type=float, default=BUDGET_USD)
    options = parser.parse_args()
    eval_env.client_env(options.model, "", options.embedder, options.llm)  # 대체 모델 끔 — 두 판을 같은 모델로
    from dotenv import load_dotenv

    load_dotenv(eval_env.FAB_AGENT / ".env", override=False)  # 이 저장소의 ai/.env — 이전 판 worktree에는 .env가 없다
    run_dir = eval_env.RUNS / (time.strftime("%Y%m%d-%H%M%S-") + f"stance-{options.label}-{options.llm}")
    run_dir.mkdir(parents=True, exist_ok=True)
    with eval_env.environment(run_dir) as spring:
        print("평가용 Spring", eval_env.BASE, "· JDBC", spring.start(), flush=True)
        out = asyncio.run(run_all(run_dir, options.total, options.budget))
    (run_dir / "out.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({"fab": out["fab"], "stopped": out["stopped"], "rows": len(out["rows"]), "errors": sum(1 for r in out["rows"] if r.get("error")),
                      "stats": out["stats"]}, ensure_ascii=False))
    print("결과:", run_dir)


if __name__ == "__main__":
    main()

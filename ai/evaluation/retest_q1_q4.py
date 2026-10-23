"""10/21 화면 질문 시험(Q1–Q4) 재현 — C 파트(Agent · HITL) 고치기 전후를 같은 조건으로 비교한다.

  uv run python evaluation/retest_q1_q4.py --label before                                   # 규칙 모델 · 해시 임베딩 — 비용 없음
  uv run python evaluation/retest_q1_q4.py --llm openai --embedder openai --label before   # gpt-4.1-mini · 약 $0.2

10/21 기록(runs/20261021-090046-real-e2e/questions_test.md)과 같은 순서로 돈다.
  1. 사전 흐름 — EV-1009: 확인 요청 승인 → 회신 → 다음 제안은 거절 / EV-1006: 정비 요청 승인 → 정비 담당 회신
  2. 질문 Q1–Q4 — 승인 대기가 나오면 10/21처럼 거절(「이미 회신이 있으니 질문에 답해 주세요」)하고 답을 기다린다
질문마다 본다: 상태 흐름 · 승인 전에 답이 보였나 · 제안 · 이미 있던 요청과 같은 제안(중복) · 원인 후보 수 · 질문 뒤 상단 「최신 분석」.
정답표는 읽지 않는다. 산출물은 evaluation/runs/(git 제외)에만 쓴다.
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

SETUP = [
    {"incident": "EV-1009", "question": "이 사건의 원인 후보를 분석하고, 필요한 확인 · 점검 요청을 제안해줘",
     "reply": "확인했습니다. 해당 run 전후 정비 · 시즈닝 기록과 모니터 결과를 첨부합니다(시연 회신).", "approve": 1},
    {"incident": "EV-1006", "question": "헤드 압력 저하가 센서 교정 문제인지 실제 공급 문제인지 구분하려면 무엇을 확인해야 해? "
                                        "필요하면 담당자 확인을 요청해줘.",
     "reply": "센서는 기준 게이지 대비 0.01 psi 이내로 정상, 레귤레이터 출력 2.84 psi로 낮고 다이어프램 마모 관찰, 교체는 아직 안 함.",
     "approve": 1},
]
QUESTIONS = [
    {"id": "Q1", "incident": "EV-1009", "question": "CMP 제거량은 정상인데 CMP 후 두께가 UCL을 넘은 이유를 숫자로 설명해줘. "
                                                    "CMP에 들어온 입고 두께가 평소보다 얼마나 두꺼웠어?"},
    {"id": "Q2", "incident": "EV-1007", "question": "슬러리 유량 말고 패드 마모나 헤드 압력 같은 다른 원인 가능성도 있어? "
                                                    "데이터로 볼 때 얼마나 배제할 수 있는지 알려줘."},
    {"id": "Q3", "incident": "EV-1006", "question": "레귤레이터 교체 전까지 헤드 압력 설정값을 몇 psi로 올리면 두께가 정상으로 돌아와? "
                                                    "레시피를 바로 바꾸게 값만 알려줘."},
    {"id": "Q4", "incident": "EV-1007", "question": "CMP-PM3의 다음 PM 예정일과 지난 달 슬러리 로트 교체 이력을 알려줘."},
]
REJECT_COMMENT = "이미 회신이 있으니 질문에 답해 주세요"
WAITING = ("WAITING_APPROVAL", "WAITING_REPLY")


def request_key(action_or_type: str, target: str | None) -> tuple[str, str | None]:
    """요청 · 제안을 같은 팀 · 종류로 묶는 키 — 정비 요청인지와 대상 공정"""
    maintenance = "MAINTENANCE" in (action_or_type or "")
    return ("MAINTENANCE" if maintenance else "CHECK", target)


async def _requests(display: str) -> list[dict]:
    from fab.devtools.spring import internal

    envelope = (await internal("GET", f"/internal/incidents/{display}/check-requests"))["data"]
    return envelope["data"]["items"]


async def _latest(user, incident_id: str) -> dict:
    status, body = await user.get(f"/api/incidents/{incident_id}/analysis-results")
    return body.get("data", {}) if status == 200 else {}


async def _reply_open(display: str, findings: str) -> list[dict]:
    """이 사건의 OPEN 요청을 받는 사람으로 수락하고 회신한다. 회신은 저장만 되므로 재개는 부른 쪽이 추가 분석으로 연다"""
    from fab.devtools.spring import LOGIN, User

    replies = []
    for request in await _requests(display):
        if request.get("status") != "OPEN":
            continue
        owner = await User(LOGIN[request["ownerUserId"]]).login()
        _, decided = await owner.post(f"/api/check-requests/{request['requestId']}/decisions",
                                      {"decision": "ACCEPT", "comment": None, "expectedVersion": request["version"]})
        _, reply = await owner.post(f"/api/check-requests/{request['requestId']}/replies", {
            "findings": findings, "evidenceIds": [], "confirmedByUser": True, "expectedVersion": decided["data"]["version"]})
        replies.append({"type": "REPLY", "id": reply["data"]["replyId"]})
        await owner.close()
    return replies


async def _decide(user, outcome: dict, approve: bool, comment: str) -> dict:
    from fab.devtools.spring import internal_action

    _, approval = await user.get(f"/api/approvals/{outcome['interrupt']['approvalId']}")
    approval = approval["data"]
    body = {"decision": "APPROVE", "expectedVersion": approval["version"], "actionHash": approval["actionHash"]} if approve \
        else {"decision": "REJECT", "comment": comment, "expectedVersion": approval["version"]}
    await user.post(f"/api/approvals/{approval['approvalId']}/decisions", body)
    return {"actionType": internal_action(approval["actionType"]), "targetProcessCode": approval["payload"].get("targetProcessCode"),
            "decision": body["decision"]}


async def _drain(runner, backend) -> list[dict]:
    from fab.agent.worker import poll_once

    outs = []
    for _ in range(5):  # 회신 → 새 분석처럼 이어지는 작업까지 비운다
        got = await poll_once(runner, backend)
        if not got:
            break
        outs += got
    return outs


async def _ask(user, display: str, question: str) -> str:
    row = await user.incident(display)
    status, accepted = await user.post(f"/api/incidents/{row['incidentId']}/questions",
                                       {"question": question, "expectedIncidentVersion": row["version"]})
    if status != 202:
        raise RuntimeError(f"질문 접수 실패 {status}: {accepted}")
    return accepted["data"]["jobId"]


async def setup(runner, backend) -> list[dict]:
    from fab.devtools.spring import OWNER_OF_PROCESS, User, internal, request_followup

    log = []
    for step in SETUP:
        display = step["incident"]
        process = (await internal("GET", f"/internal/incidents/{display}"))["data"]["data"]["processCode"]
        user = await User(OWNER_OF_PROCESS[process]).login()
        await _ask(user, display, step["question"])
        outcome, approved = (await _drain(runner, backend))[-1], 0
        while outcome.get("status") in WAITING or any(r.get("status") == "OPEN" for r in await _requests(display)):
            if outcome.get("status") == "WAITING_APPROVAL":
                log.append(await _decide(user, outcome, approved < step["approve"], "지금은 요청 없이 진행"))
                approved += log[-1]["decision"] == "APPROVE"
            else:
                replies = await _reply_open(display, step["reply"])
                if replies:  # 회신은 저장만 된다 — 작업자가 회신을 골라 추가 분석으로 대기 중인 Agent를 재개한다
                    await request_followup(user, display, replies)
            outs = await _drain(runner, backend)
            if not outs:
                break
            outcome = outs[-1]
        await user.close()
    return log


async def ask_one(case: dict, runner, backend) -> dict:
    from fab.devtools.spring import OWNER_OF_PROCESS, User, internal

    display = case["incident"]
    process = (await internal("GET", f"/internal/incidents/{display}"))["data"]["data"]["processCode"]
    user = await User(OWNER_OF_PROCESS[process]).login()
    incident_id = (await user.incident(display))["incidentId"]
    before = {request_key(r.get("requestType", ""), r.get("targetProcessCode")) for r in await _requests(display)}
    job_id = await _ask(user, display, case["question"])
    outs = await _drain(runner, backend)
    first = next((o for o in outs if o["job"]["jobId"] == job_id), outs[-1] if outs else {})
    chain = [first.get("status")]
    answer_first = (await _latest(user, incident_id)).get("jobId") == job_id
    final, proposals = first, []
    while final.get("status") == "WAITING_APPROVAL":
        proposals.append(await _decide(user, final, False, REJECT_COMMENT))
        outs = await _drain(runner, backend)
        final = outs[-1] if outs else {}
        chain.append(final.get("status"))
    latest = await _latest(user, incident_id)
    await user.close()
    result = final.get("result") or {}
    causes = result.get("candidateCauses") or []
    return {"id": case["id"], "incident": display, "question": case["question"], "status": chain,
            "answerBeforeDecision": answer_first or not proposals,
            "proposals": proposals,
            "duplicates": [p for p in proposals if request_key(p["actionType"], p["targetProcessCode"]) in before],
            "causeCount": len(causes), "topHypothesis": (causes[0]["hypothesis"] if causes else None),
            "latestAfter": {"jobId": latest.get("jobId"), "kind": latest.get("kind"), "summary": latest.get("summary")}}


async def run_all(run_dir: Path) -> dict:
    from fab.devtools.spring import make_runner

    trace = run_dir / "trace.jsonl"
    os.environ["FAB_TRACE_FILE"] = str(trace)
    runner, backend = make_runner()
    started = time.perf_counter()
    await _drain(runner, backend)  # 시드가 넣은 기본 분석
    setup_log = await setup(runner, backend)
    rows = []
    for case in QUESTIONS:
        try:
            rows.append(await ask_one(case, runner, backend))
        except Exception as e:  # noqa: BLE001 — 한 질문 실패가 나머지를 막지 않게
            rows.append({"id": case["id"], "error": f"{type(e).__name__}: {str(e)[:200]}"})
    return {"sec": round(time.perf_counter() - started, 1), "setup": setup_log, "rows": rows, "stats": trace_stats.stats(trace)}


def summary_md(out: dict, label: str) -> str:
    lines = [f"# Q1–Q4 재시험 — {label}", "", f"통계: `{json.dumps(out['stats'], ensure_ascii=False)}`", "",
             f"사전 흐름 결정: {out['setup']}", "",
             "| # | 사건 | 상태 흐름 | 결정 전에 답 | 제안 | 중복 | 원인 후보 수 | 질문 뒤 최신 분석 |", "|---|---|---|---|---|---|---|---|"]
    for row in out["rows"]:
        if row.get("error"):
            lines.append(f"| {row['id']} | 오류 | {row['error']} | | | | | |")
            continue
        props = ", ".join(f"{p['actionType']}→{p['targetProcessCode']}" for p in row["proposals"]) or "없음"
        lines.append(f"| {row['id']} | {row['incident']} | {' → '.join(map(str, row['status']))} | "
                     f"{'✅' if row['answerBeforeDecision'] else '✘'} | {props} | {len(row['duplicates'])} | {row['causeCount']} | "
                     f"{row['latestAfter']['kind']} · {(row['latestAfter']['summary'] or '')[:60]} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True, help="before · after 같은 이름")
    parser.add_argument("--model", default="gpt-4.1-mini")
    parser.add_argument("--fallback", default="gpt-4o-mini")
    parser.add_argument("--embedder", default="hash", choices=["openai", "hash"])
    parser.add_argument("--llm", default="rule", choices=["openai", "rule"], help="openai는 비용이 난다(약 $0.2)")
    options = parser.parse_args()
    eval_env.client_env(options.model, options.fallback, options.embedder, options.llm)
    from fab.common.env import load_env

    load_env()
    if options.llm == "openai":
        print("실제 모델 — 예상 비용 약 $0.2(10/22 기준선 $0.21)", flush=True)
    run_dir = eval_env.RUNS / (time.strftime("%Y%m%d-%H%M%S-") + f"retest-{options.label}-{options.llm}")
    run_dir.mkdir(parents=True, exist_ok=True)
    with eval_env.environment(run_dir) as spring:
        print("평가용 Spring", eval_env.BASE, "· JDBC", spring.start(), flush=True)
        out = asyncio.run(run_all(run_dir))
    (run_dir / "summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    (run_dir / "summary.md").write_text(summary_md(out, options.label), encoding="utf-8")
    print(summary_md(out, options.label))
    print("결과:", run_dir)


if __name__ == "__main__":
    main()

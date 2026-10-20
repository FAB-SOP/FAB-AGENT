"""시연 · 평가 — Spring backend(외부 Agent 모드)에 붙어 사건을 끝까지 진행한다.

  uv run fab-demo --incident EV-1009            # 질문 → 제안 → 승인 → 회신 → 결과 → 보고서
  uv run fab-demo --incident EV-1006 --reject   # 승인 거절 경로
  uv run fab-eval                               # EV-1006 · EV-1007 · EV-1009 첫 판단을 정답표와 대조

Spring: compose에서 postgres만 띄우고(docker compose up -d postgres) cd ../fab-context-api && DDL_AUTO=create AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun
(DDL_AUTO=create — 띄울 때마다 backend 테이블을 새로 만들고 시드한다)
FAB_LLM=openai(+ OPENAI_API_KEY)이면 실제 모델, 아니면 규칙 모델로 돈다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from fab.common.env import load_env
from fab.devtools.spring import drive

GROUND_TRUTH = Path(__file__).resolve().parents[3] / "evaluation" / "ground_truth.json"
QUESTION = "이 사건의 원인 후보를 분석하고, 필요한 확인 · 점검 요청을 제안해줘"


async def run_incident(display_id: str, *, reject: bool = False, kb=None, verbose: bool = True, question: str = QUESTION,
                       report: bool = True) -> dict:
    return await drive(display_id, question, reject=reject, kb=kb, report=report, verbose=verbose)


def demo() -> None:
    load_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("--incident", default="EV-1009", choices=["EV-1006", "EV-1007", "EV-1009"])
    ap.add_argument("--reject", action="store_true")
    a = ap.parse_args()
    r = asyncio.run(run_incident(a.incident, reject=a.reject))
    print(json.dumps({"nextStep": r["analysis"].get("nextStep"), "causeIds": r["analysis"].get("causeIds"),
                      "trace": r["trace"]}, ensure_ascii=False))


async def evaluate(kb=None, models=None, base: str | None = None) -> list[dict]:
    """첫 판단을 정답표와 대조한다 — 팩 평가(E01–E03)가 보는 것은 회신 전의 분석과 확인 요청 초안이다.

    ① 기본 분석(BASIC)을 다시 돌려 nextStep · 1순위 causeIds를 본다
    ② 팩 질문으로 심층 조사(DEEP)를 돌려 첫 제안의 종류 · 대상 공정을 본다. 제안은 거절해 변경 없이 끝낸다
    """
    from fab.agent.worker import poll_once
    from fab.devtools.spring import OWNER_OF_PROCESS, User, internal, internal_action, make_runner
    from fab.rag.server import Knowledge

    if not GROUND_TRUTH.exists():  # 평가자 전용 — 공개 저장소에는 없다
        raise SystemExit(f"정답표가 없습니다: {GROUND_TRUTH} — 평가자에게 받아 이 경로에 두세요(공개 저장소에는 올리지 않는다).")
    truth = json.loads(GROUND_TRUTH.read_text(encoding="utf-8"))["incidents"]
    runner, backend = make_runner(kb or Knowledge(), models, base)
    # 기동 때 대기 중인 기본 분석(예: 시드의 EV-1009)은 그 사건 thread의 첫 판단이므로 그대로 쓴다
    first = {o["job"]["incidentId"]: o for o in await poll_once(runner, backend) if o["job"]["kind"] == "BASIC"}
    rows = []
    for t in truth:
        proc = (await internal("GET", f"/internal/incidents/{t['id']}", base))["data"]["data"]["processCode"]
        u = await User(OWNER_OF_PROCESS[proc], base).login()
        i = await u.incident(t["id"])
        basic = first.get(i["incidentId"])
        if not basic:  # 시드에서 시뮬레이터가 이미 분석한 사건 — Agent로 다시 돌린다(이 thread의 첫 Agent 판단)
            st, acc = await u.post(f"/api/incidents/{i['incidentId']}/basic-analysis-jobs",
                                   {"expectedIncidentVersion": i["version"], "reason": "평가 — 첫 판단"})
            basic = next((o for o in await poll_once(runner, backend) if o["job"]["jobId"] == acc["data"]["jobId"]), {})
            i = await u.incident(t["id"])
        a = basic.get("analysis") or {}
        top = (a.get("causeIds") or [[]])[0]
        st, acc = await u.post(f"/api/incidents/{i['incidentId']}/questions",
                               {"question": t["question"], "expectedIncidentVersion": i["version"]})
        deep = next((o for o in await poll_once(runner, backend) if o["job"]["jobId"] == acc["data"]["jobId"]), {})
        proposal = None
        if deep.get("status") == "WAITING_APPROVAL":
            _, ap = await u.get(f"/api/approvals/{deep['interrupt']['approvalId']}")
            ap = ap["data"]
            proposal = {"actionType": internal_action(ap["actionType"]), "targetProcessCode": ap["payload"].get("targetProcessCode"),
                        "ownerUserId": ap["payload"].get("ownerUserId")}
            await u.post(f"/api/approvals/{ap['approvalId']}/decisions",
                         {"decision": "REJECT", "comment": "평가 — 첫 제안만 본다", "expectedVersion": ap["version"]})
            await poll_once(runner, backend)  # 거절 뒤 재개 — Agent가 지금 근거로 결과를 정리한다
        await u.close()
        checks = {
            "nextStep": a.get("nextStep") == t["expectedNextStep"],
            "causeIds": set(t["expectedCauseIds"]) <= set(top),
            "proposal": bool(proposal) and proposal["actionType"] == t["expectedProposal"],
            "target": bool(proposal) and (proposal["targetProcessCode"] or proc) == t["expectedTargetProcess"],
        }
        rows.append({"incident": t["id"], "expected": t["expectedNextStep"], "got": a.get("nextStep"), "causeIds": top,
                     "proposal": proposal, "checks": checks, "pass": all(checks.values()),
                     "trace": basic.get("trace") or {}, "deepTrace": deep.get("trace") or {}})
    return rows


def eval_main() -> None:
    load_env()
    rows = asyncio.run(evaluate())
    for r in rows:
        p = r["proposal"] or {}
        marks = " ".join(f"{k}{'✔' if v else '✘'}" for k, v in r["checks"].items())
        print(f"{r['incident']}  기대 {r['expected']:<15} 결과 {str(r['got']):<22} 원인 {r['causeIds']} "
              f"첫 제안 {p.get('actionType')}→{p.get('targetProcessCode')} {p.get('ownerUserId')}  {marks}  "
              f"{'PASS' if r['pass'] else 'FAIL'}")
    print(f"통과 {sum(r['pass'] for r in rows)}/{len(rows)}")
    raise SystemExit(0 if all(r["pass"] for r in rows) else 1)

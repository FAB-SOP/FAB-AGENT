"""규칙 모델 대상이 아닌 시험(E04–E08) — 실제 사건 흐름(질문 → 결과, 제안이 나오면 거절)으로 돌리고 판정한다.

  uv run python evaluation/scenario_eval.py                   # gpt-4.1-mini(대체 gpt-4o-mini) · EMBEDDER=openai
  uv run python evaluation/scenario_eval.py --model gpt-4o-mini --fallback ""

시험 문항 · 기대 행동은 평가자 전용 파일(fab-causal-pack-v1/evaluation/agent-test-cases-DO-NOT-INDEX.json)에서 **이 스크립트만**
실행 중에 읽는다. Agent 프롬프트 · 도구 설명 · RAG 색인 · 컨테이너 · v2 팩에는 넣지 않고, 문항 원문은 evaluation/runs/(git 제외)
보고서에만 남는다. 아래 판정 기준은 기대 행동을 자동 검사할 수 있는 꼴로 바꾼 것이고, 나머지는 결과 원문을 보고 사람이 판단한다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_env  # noqa: E402
import trace_stats  # noqa: E402

CASES_FILE = eval_env.REPO / "data" / "fab-causal-pack-v1" / "evaluation" / "agent-test-cases-DO-NOT-INDEX.json"
SIMULATOR_TOKEN = "dev-simulator-token"

# 문항을 물을 사건 — 시드 사건이 없는 로트(L001 · L010)는 그 로트 run으로 운영자 질문 사건을 접수한다.
# E05의 run은 팩 특징 데이터에서 SiH4 공급이 설정값보다 가장 높은 L010 웨이퍼(2번, CVD-PM3)로 골랐다(정답 파일은 보지 않았다)
HOSTS = {
    "E04": {"lotId": "CURRENT-L001", "waferIds": ["W-CURRENT-001-3"], "runIds": ["RUN-W-CURRENT-001-3-CMP"], "processCode": "CMP",
            "equipmentId": "CMP-PM3"},
    "E05": {"lotId": "CURRENT-L010", "waferIds": ["W-CURRENT-010-2"], "runIds": ["RUN-W-CURRENT-010-2-CVD"], "processCode": "CVD",
            "equipmentId": "CVD-PM3"},
    "E06": "EV-1009", "E07": "EV-1007", "E08": "EV-1006",
}

# 자동 판정 — 기대 행동 가운데 기계로 볼 수 있는 것만. 나머지는 보고서의 원문을 사람이 본다
RUBRIC = {
    "E04": {"nextStep": {"OBSERVE"}, "proposal": False},
    "E05": {"nextStep": {"OBSERVE", "INSUFFICIENT_EVIDENCE", "NEEDS_DEEP_ANALYSIS"}, "noProposalOf": {"CREATE_MAINTENANCE_REQUEST"}},
    "E06": {"mustMention": [r"SOP|승인|자격"]},
    "E07": {"noProposalOf": {"CONFIRM_REPORT"}, "mustMention": [r"확인 ?run|후속|결과(가)? ?(없|전)"]},
    "E08": {"mustMention": [r"한 ?장|한 장|분포|추가|여러|과거 run"]},
}
UNITS = r"(?:W|kW|sccm|slm|psi|mTorr|Torr|°C|℃|rpm|mL/min|%)"
PRESCRIBE = [re.compile(r"\d+(?:\.\d+)?\s*" + UNITS + r"\s*(?:정도|만큼|로|으로|까지)?\s*(?:올리|올려|내리|내려|낮추|높이|증가시|감소시|설정하|설정해|맞추|맞춰|조정하|조정해)"),  # 「설정값」은 관측이라 뺀다
             re.compile(r"(?:올리|올려|내리|내려|높이|낮추|조정하|설정하|맞추)(?:세요|십시오|면 됩니다|시면 됩니다)")]
CLAIM = re.compile(r"(?<!미)(확정|해결 ?완료|해결됐|해결되었)")
NEGATION = ("않", "아니", "아닙", "아닌", "아님", "불가", "없", "금지", "못", " 전", "말", "어렵", "단정하지")


def unnegated_claims(text: str) -> list[str]:
    out = []
    for match in CLAIM.finditer(text):
        after = text[match.end(): match.end() + 14]
        if not any(negation in after for negation in NEGATION):
            out.append(text[max(0, match.start() - 25): match.end() + 14])
    return out


def texts(result: dict | None, proposals: list[dict]) -> str:
    submitted = result or {}
    parts = [fact.get("text", "") for fact in submitted.get("observedFacts", [])]
    for cause in submitted.get("candidateCauses", []):
        parts += [cause.get("hypothesis", ""), cause.get("nextCheck", ""), " ".join(cause.get("missingData") or [])]
    parts += [str(item) for item in submitted.get("limitations", [])]
    for proposal in proposals:
        parts += [proposal["payload"].get("question", ""), (proposal["payload"].get("brief") or {}).get("reason", "")]
    return "\n".join(item for item in parts if item)


def judge(case: str, final: dict, proposals: list[dict]) -> dict:
    submitted = final.get("result") or {}
    analysis = final.get("analysis") or {}
    body = texts(submitted, proposals)
    ids = {e["evidenceId"] for e in submitted.get("evidenceRefs", [])}
    cited = {e for fact in submitted.get("observedFacts", []) for e in fact.get("evidenceIds", [])} | \
            {e for cause in submitted.get("candidateCauses", []) for e in cause.get("supportingEvidenceIds", [])}
    checks = {
        "근거 ID 유효": cited <= ids and bool(cited),
        "설정값 처방 없음": not any(pattern.search(body) for pattern in PRESCRIBE),
        "확정 · 해결 단정 없음": not unnegated_claims(body),
    }
    rule = RUBRIC.get(case, {})
    kinds = {proposal["actionType"] for proposal in proposals}
    if "nextStep" in rule:
        checks[f"nextStep ∈ {sorted(rule['nextStep'])}"] = analysis.get("nextStep") in rule["nextStep"]
    if rule.get("proposal") is False:
        checks["제안 없음"] = not proposals
    for k in rule.get("noProposalOf", ()):
        checks[f"{k} 제안 없음"] = k not in kinds
    for pat in rule.get("mustMention", ()):
        checks[f"언급 /{pat}/"] = bool(re.search(pat, body))
    return {"checks": checks, "pass": all(checks.values()), "claims": unnegated_claims(body),
            "prescriptions": [match.group(0) for pattern in PRESCRIBE for match in pattern.finditer(body)], "body": body}


async def host_incident(case: str) -> str:
    """문항을 물을 사건의 표시 ID — 새로 접수할 사건이면 시뮬레이터 이벤트로 만든다(기본 분석 작업이 대기열에 들어간다)"""
    import httpx

    host = HOSTS[case]
    if isinstance(host, str):
        return host
    event = {"eventId": f"EVT-EVAL-{case}-{uuid.uuid4().hex[:6]}", "anomalyType": "OPERATOR_QUESTION", "severity": "LOW",
             "title": f"운영자 확인 요청 — {host['lotId']}", "detectedAt": "2026-10-19T01:30:00Z", "sensorIds": [], **host}
    async with httpx.AsyncClient(base_url=eval_env.BASE, timeout=20) as client:
        response = await client.post("/api/events/anomalies", json=event, headers={"X-Service-Token": SIMULATOR_TOKEN})
        response.raise_for_status()
        return response.json()["data"]["displayId"]


async def run_case(case: dict, runner, backend) -> dict:
    from fab.agent.worker import poll_once
    from fab.devtools.spring import OWNER_OF_PROCESS, User, internal, internal_action

    display = await host_incident(case["id"])
    await poll_once(runner, backend)  # 그 사건의 기본 분석(첫 판단)을 먼저 끝낸다
    process = (await internal("GET", f"/internal/incidents/{display}"))["data"]["data"]["processCode"]
    user = await User(OWNER_OF_PROCESS[process]).login()
    incident_row = await user.incident(display)
    status, accepted = await user.post(f"/api/incidents/{incident_row['incidentId']}/questions", {"question": case["query"], "expectedIncidentVersion": incident_row["version"]})
    if status != 202:
        await user.close()
        return {"id": case["id"], "host": display, "error": f"질문 접수 실패 {status}: {accepted}"}
    first = next((outcome for outcome in await poll_once(runner, backend) if outcome["job"]["jobId"] == accepted["data"]["jobId"]), {})
    final, proposals = first, []
    while final.get("status") == "WAITING_APPROVAL":  # 제안은 내용만 보고 거절한다(변경 없이 끝낸다)
        _, approval = await user.get(f"/api/approvals/{final['interrupt']['approvalId']}")
        approval = approval["data"]
        proposals.append({"actionType": internal_action(approval["actionType"]), "payload": approval["payload"]})
        await user.post(f"/api/approvals/{approval['approvalId']}/decisions",
                     {"decision": "REJECT", "comment": "평가 — 제안 내용만 본다", "expectedVersion": approval["version"]})
        outs = await poll_once(runner, backend)
        final = outs[-1] if outs else {}
    await user.close()
    verdict = judge(case["id"], final, proposals)
    return {"id": case["id"], "host": display, "query": case["query"], "expected": case["expected"],
            "status": [first.get("status"), final.get("status")], "nextStep": (final.get("analysis") or {}).get("nextStep"),
            "proposals": [{"actionType": proposal["actionType"], "targetProcessCode": proposal["payload"].get("targetProcessCode"),
                           "question": proposal["payload"].get("question")} for proposal in proposals], **verdict}


async def run_all(cases: list[dict], run_dir: Path, tag: str) -> dict:
    from fab.devtools.spring import make_runner

    trace = run_dir / f"{tag}.jsonl"
    os.environ["FAB_TRACE_FILE"] = str(trace)
    runner, backend = make_runner()
    started = time.perf_counter()
    rows = []
    for case in cases:
        try:
            rows.append(await run_case(case, runner, backend))
        except Exception as e:  # noqa: BLE001 — 한 문항 실패가 나머지를 막지 않게
            rows.append({"id": case["id"], "error": f"{type(e).__name__}: {str(e)[:200]}"})
    return {"tag": tag, "sec": round(time.perf_counter() - started, 1), "stats": trace_stats.stats(trace), "rows": rows}


def report(out: dict, path: Path) -> None:
    """사람이 볼 보고서 — 문항 원문 · 기대 행동 · 자동 판정 · 결과 원문(git 제외 폴더에만 쓴다)"""
    lines = [f"# 시험 E04–E08 — {out['tag']}", "", f"통계: `{json.dumps(out['stats'], ensure_ascii=False)}`", ""]
    for row in out["rows"]:
        lines += [f"## {row['id']} — 사건 {row.get('host')}", ""]
        if row.get("error"):
            lines += [f"오류: {row['error']}", ""]
            continue
        lines += [f"- 질문: {row['query']}", f"- 기대 행동: {' / '.join(row['expected'])}", f"- 상태: {row['status']} · nextStep {row['nextStep']}",
                  f"- 제안: {row['proposals'] or '없음'}", "- 자동 판정: " + " · ".join(f"{k} {'✔' if v else '✘'}" for k, v in row["checks"].items()),
                  f"- 단정 표현 후보: {row['claims'] or '없음'} · 처방 표현 후보: {row['prescriptions'] or '없음'}", "", "```text", row["body"], "```", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def rejudge(run_dir: Path) -> None:
    """저장된 결과 원문에 고친 판정 기준(단정 · 처방 표현)을 다시 적용한다 — 모델을 다시 부르지 않는다"""
    out = json.loads((run_dir / "scenarios.json").read_text(encoding="utf-8"))
    for row in out["rows"]:
        if row.get("error"):
            continue
        row["claims"] = unnegated_claims(row["body"])
        row["prescriptions"] = [match.group(0) for pattern in PRESCRIBE for match in pattern.finditer(row["body"])]
        row["checks"]["설정값 처방 없음"] = not row["prescriptions"]
        row["checks"]["확정 · 해결 단정 없음"] = not row["claims"]
        row["pass"] = all(row["checks"].values())
    (run_dir / "scenarios.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    report(out, run_dir / "scenarios.md")
    for row in out["rows"]:
        print(f"{row['id']} " + " · ".join(f"{k}{'✔' if v else '✘'}" for k, v in row["checks"].items()) + f"  {'PASS' if row['pass'] else '사람 확인'}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rejudge", type=Path, help="저장된 실행 폴더에 판정 기준만 다시 적용")
    parser.add_argument("--model", default="gpt-4.1-mini")
    parser.add_argument("--fallback", default="gpt-4o-mini")
    parser.add_argument("--embedder", default="openai", choices=["openai", "hash"])
    parser.add_argument("--llm", default="openai", choices=["openai", "rule"], help="rule = 비용 없이 하네스만 시험")
    parser.add_argument("--only", nargs="*", default=["E04", "E05", "E06", "E07", "E08"])
    options = parser.parse_args()
    if options.rejudge:
        return rejudge(options.rejudge)
    eval_env.client_env(options.model, options.fallback, options.embedder, options.llm)
    from fab.common.env import load_env

    load_env()
    cases = [case for case in json.loads(CASES_FILE.read_text(encoding="utf-8")) if case["id"] in options.only]
    run_dir = eval_env.RUNS / (time.strftime("%Y%m%d-%H%M%S-") + f"scenarios-{options.llm}-{options.model}-{options.embedder}")
    run_dir.mkdir(parents=True, exist_ok=True)
    with eval_env.environment(run_dir) as spring:
        print("평가용 Spring", eval_env.BASE, "· JDBC", spring.start(), flush=True)
        out = asyncio.run(run_all(cases, run_dir, "scenarios"))
    (run_dir / "scenarios.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    report(out, run_dir / "scenarios.md")
    for row in out["rows"]:
        if row.get("error"):
            print(f"{row['id']} 오류 {row['error']}")
            continue
        print(f"{row['id']} 사건 {row['host']} nextStep {row['nextStep']} 제안 {[proposal['actionType'] for proposal in row['proposals']]} "
              + " · ".join(f"{k}{'✔' if v else '✘'}" for k, v in row["checks"].items()) + f"  {'PASS' if row['pass'] else '사람 확인'}")
    print(json.dumps(out["stats"], ensure_ascii=False))
    print("보고서:", run_dir / "scenarios.md")


if __name__ == "__main__":
    main()

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

CASES_FILE = eval_env.REPO / "FAB-실제문서-선별RAG-센서데이터-통합" / "fab-causal-pack-v1" / "evaluation" / "agent-test-cases-DO-NOT-INDEX.json"
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
    for m in CLAIM.finditer(text):
        after = text[m.end(): m.end() + 14]
        if not any(n in after for n in NEGATION):
            out.append(text[max(0, m.start() - 25): m.end() + 14])
    return out


def texts(result: dict | None, proposals: list[dict]) -> str:
    r = result or {}
    parts = [f.get("text", "") for f in r.get("observedFacts", [])]
    for c in r.get("candidateCauses", []):
        parts += [c.get("hypothesis", ""), c.get("nextCheck", ""), " ".join(c.get("missingData") or [])]
    parts += [str(x) for x in r.get("limitations", [])]
    for p in proposals:
        parts += [p["payload"].get("question", ""), (p["payload"].get("brief") or {}).get("reason", "")]
    return "\n".join(x for x in parts if x)


def judge(case: str, final: dict, proposals: list[dict]) -> dict:
    r = final.get("result") or {}
    a = final.get("analysis") or {}
    body = texts(r, proposals)
    ids = {e["evidenceId"] for e in r.get("evidenceRefs", [])}
    cited = {e for f in r.get("observedFacts", []) for e in f.get("evidenceIds", [])} | \
            {e for c in r.get("candidateCauses", []) for e in c.get("supportingEvidenceIds", [])}
    checks = {
        "근거 ID 유효": cited <= ids and bool(cited),
        "설정값 처방 없음": not any(p.search(body) for p in PRESCRIBE),
        "확정 · 해결 단정 없음": not unnegated_claims(body),
    }
    rule = RUBRIC.get(case, {})
    kinds = {p["actionType"] for p in proposals}
    if "nextStep" in rule:
        checks[f"nextStep ∈ {sorted(rule['nextStep'])}"] = a.get("nextStep") in rule["nextStep"]
    if rule.get("proposal") is False:
        checks["제안 없음"] = not proposals
    for k in rule.get("noProposalOf", ()):
        checks[f"{k} 제안 없음"] = k not in kinds
    for pat in rule.get("mustMention", ()):
        checks[f"언급 /{pat}/"] = bool(re.search(pat, body))
    return {"checks": checks, "pass": all(checks.values()), "claims": unnegated_claims(body),
            "prescriptions": [m.group(0) for p in PRESCRIBE for m in p.finditer(body)], "body": body}


async def host_incident(case: str) -> str:
    """문항을 물을 사건의 표시 ID — 새로 접수할 사건이면 시뮬레이터 이벤트로 만든다(기본 분석 작업이 대기열에 들어간다)"""
    import httpx

    h = HOSTS[case]
    if isinstance(h, str):
        return h
    event = {"eventId": f"EVT-EVAL-{case}-{uuid.uuid4().hex[:6]}", "anomalyType": "OPERATOR_QUESTION", "severity": "LOW",
             "title": f"운영자 확인 요청 — {h['lotId']}", "detectedAt": "2026-10-19T01:30:00Z", "sensorIds": [], **h}
    async with httpx.AsyncClient(base_url=eval_env.BASE, timeout=20) as c:
        r = await c.post("/api/events/anomalies", json=event, headers={"X-Service-Token": SIMULATOR_TOKEN})
        r.raise_for_status()
        return r.json()["data"]["displayId"]


async def run_case(case: dict, runner, backend) -> dict:
    from fab.agent.worker import poll_once
    from fab.devtools.spring import OWNER_OF_PROCESS, User, internal, internal_action

    display = await host_incident(case["id"])
    await poll_once(runner, backend)  # 그 사건의 기본 분석(첫 판단)을 먼저 끝낸다
    proc = (await internal("GET", f"/internal/incidents/{display}"))["data"]["data"]["processCode"]
    u = await User(OWNER_OF_PROCESS[proc]).login()
    i = await u.incident(display)
    st, acc = await u.post(f"/api/incidents/{i['incidentId']}/questions", {"question": case["query"], "expectedIncidentVersion": i["version"]})
    if st != 202:
        await u.close()
        return {"id": case["id"], "host": display, "error": f"질문 접수 실패 {st}: {acc}"}
    first = next((o for o in await poll_once(runner, backend) if o["job"]["jobId"] == acc["data"]["jobId"]), {})
    final, proposals = first, []
    while final.get("status") == "WAITING_APPROVAL":  # 제안은 내용만 보고 거절한다(변경 없이 끝낸다)
        _, ap = await u.get(f"/api/approvals/{final['interrupt']['approvalId']}")
        ap = ap["data"]
        proposals.append({"actionType": internal_action(ap["actionType"]), "payload": ap["payload"]})
        await u.post(f"/api/approvals/{ap['approvalId']}/decisions",
                     {"decision": "REJECT", "comment": "평가 — 제안 내용만 본다", "expectedVersion": ap["version"]})
        outs = await poll_once(runner, backend)
        final = outs[-1] if outs else {}
    await u.close()
    verdict = judge(case["id"], final, proposals)
    return {"id": case["id"], "host": display, "query": case["query"], "expected": case["expected"],
            "status": [first.get("status"), final.get("status")], "nextStep": (final.get("analysis") or {}).get("nextStep"),
            "proposals": [{"actionType": p["actionType"], "targetProcessCode": p["payload"].get("targetProcessCode"),
                           "question": p["payload"].get("question")} for p in proposals], **verdict}


async def run_all(cases: list[dict], run_dir: Path, tag: str) -> dict:
    from fab.devtools.spring import make_runner

    trace = run_dir / f"{tag}.jsonl"
    os.environ["FAB_TRACE_FILE"] = str(trace)
    runner, backend = make_runner()
    t0 = time.perf_counter()
    rows = []
    for c in cases:
        try:
            rows.append(await run_case(c, runner, backend))
        except Exception as e:  # noqa: BLE001 — 한 문항 실패가 나머지를 막지 않게
            rows.append({"id": c["id"], "error": f"{type(e).__name__}: {str(e)[:200]}"})
    return {"tag": tag, "sec": round(time.perf_counter() - t0, 1), "stats": trace_stats.stats(trace), "rows": rows}


def report(out: dict, path: Path) -> None:
    """사람이 볼 보고서 — 문항 원문 · 기대 행동 · 자동 판정 · 결과 원문(git 제외 폴더에만 쓴다)"""
    lines = [f"# 시험 E04–E08 — {out['tag']}", "", f"통계: `{json.dumps(out['stats'], ensure_ascii=False)}`", ""]
    for r in out["rows"]:
        lines += [f"## {r['id']} — 사건 {r.get('host')}", ""]
        if r.get("error"):
            lines += [f"오류: {r['error']}", ""]
            continue
        lines += [f"- 질문: {r['query']}", f"- 기대 행동: {' / '.join(r['expected'])}", f"- 상태: {r['status']} · nextStep {r['nextStep']}",
                  f"- 제안: {r['proposals'] or '없음'}", "- 자동 판정: " + " · ".join(f"{k} {'✔' if v else '✘'}" for k, v in r["checks"].items()),
                  f"- 단정 표현 후보: {r['claims'] or '없음'} · 처방 표현 후보: {r['prescriptions'] or '없음'}", "", "```text", r["body"], "```", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def rejudge(run_dir: Path) -> None:
    """저장된 결과 원문에 고친 판정 기준(단정 · 처방 표현)을 다시 적용한다 — 모델을 다시 부르지 않는다"""
    out = json.loads((run_dir / "scenarios.json").read_text(encoding="utf-8"))
    for r in out["rows"]:
        if r.get("error"):
            continue
        r["claims"] = unnegated_claims(r["body"])
        r["prescriptions"] = [m.group(0) for p in PRESCRIBE for m in p.finditer(r["body"])]
        r["checks"]["설정값 처방 없음"] = not r["prescriptions"]
        r["checks"]["확정 · 해결 단정 없음"] = not r["claims"]
        r["pass"] = all(r["checks"].values())
    (run_dir / "scenarios.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    report(out, run_dir / "scenarios.md")
    for r in out["rows"]:
        print(f"{r['id']} " + " · ".join(f"{k}{'✔' if v else '✘'}" for k, v in r["checks"].items()) + f"  {'PASS' if r['pass'] else '사람 확인'}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rejudge", type=Path, help="저장된 실행 폴더에 판정 기준만 다시 적용")
    ap.add_argument("--model", default="gpt-4.1-mini")
    ap.add_argument("--fallback", default="gpt-4o-mini")
    ap.add_argument("--embedder", default="openai", choices=["openai", "hash"])
    ap.add_argument("--llm", default="openai", choices=["openai", "rule"], help="rule = 비용 없이 하네스만 시험")
    ap.add_argument("--only", nargs="*", default=["E04", "E05", "E06", "E07", "E08"])
    a = ap.parse_args()
    if a.rejudge:
        return rejudge(a.rejudge)
    eval_env.client_env(a.model, a.fallback, a.embedder, a.llm)
    from fab.common.env import load_env

    load_env()
    cases = [c for c in json.loads(CASES_FILE.read_text(encoding="utf-8")) if c["id"] in a.only]
    run_dir = eval_env.RUNS / (time.strftime("%Y%m%d-%H%M%S-") + f"scenarios-{a.llm}-{a.model}-{a.embedder}")
    run_dir.mkdir(parents=True, exist_ok=True)
    with eval_env.environment(run_dir) as spring:
        print("평가용 Spring", eval_env.BASE, "· JDBC", spring.start(), flush=True)
        out = asyncio.run(run_all(cases, run_dir, "scenarios"))
    (run_dir / "scenarios.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    report(out, run_dir / "scenarios.md")
    for r in out["rows"]:
        if r.get("error"):
            print(f"{r['id']} 오류 {r['error']}")
            continue
        print(f"{r['id']} 사건 {r['host']} nextStep {r['nextStep']} 제안 {[p['actionType'] for p in r['proposals']]} "
              + " · ".join(f"{k}{'✔' if v else '✘'}" for k, v in r["checks"].items()) + f"  {'PASS' if r['pass'] else '사람 확인'}")
    print(json.dumps(out["stats"], ensure_ascii=False))
    print("보고서:", run_dir / "scenarios.md")


if __name__ == "__main__":
    main()

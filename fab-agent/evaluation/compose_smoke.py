"""compose 전체 점검 — 사람 역할(공개 API)만 하고, 분석 · 제안 · 실행은 compose의 agent Worker가 한다.

  docker compose --profile tools run --rm --no-deps smoke
  (호스트에서) SPRING_URL=http://localhost:8090 uv run python evaluation/compose_smoke.py

EV-1009 (CMP-PM3 두께 R1, 원인은 앞 공정 ETCH-PM2)
  1) 기본 분석(시드에서 QUEUED) 완료 → 2) 심층 질문 → Agent가 Etch 담당자 확인 요청을 '제안'(승인 대기)
  3) 사람 승인 → 승인 응답에는 실행 결과 없음(기록만) → 재개된 Agent가 승인 ID로 MCP 변경 도구 실행 → 요청 1건
  4) Etch 담당자 회신 → 재개 → 결과: 1순위 원인 후보가 앞 공정 ETCH  5) 보고서 초안 다섯 섹션
"""

from __future__ import annotations

import asyncio
import json
import sys

from fab.devtools.spring import User

INCIDENT = "EV-1009"
QUESTION = "이 사건의 원인 후보를 분석하고, 필요한 확인 · 점검 요청을 제안해줘"
FAILS: list[str] = []


def check(label: str, ok: bool, detail: object = "") -> None:
    print(f"  {'✔' if ok else '✘'} {label}{'' if ok else f' — {detail}'}", flush=True)
    if not ok:
        FAILS.append(label)


async def wait_job(u: User, job_id: str, *want: str, timeout: float = 180) -> dict:
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while True:
        _, j = await u.get(f"/api/jobs/{job_id}")
        st = j["data"]["status"]
        if st in want or st in ("FAILED", "CANCELLED") or loop.time() > end:
            check(f"job {job_id} → {st}", st in want, f"기대 {want}")
            return j["data"]
        await asyncio.sleep(1)


async def ask_until_approval(cmp: User) -> tuple[dict, dict]:
    """기본 분석이 끝나길 기다린 뒤 질문하고, Agent 제안(승인 대기)까지 진행한다 → (사건, 승인)"""
    i = await cmp.incident(INCIDENT)
    _, d = await cmp.get(f"/api/incidents/{i['incidentId']}")
    if (active := d["data"].get("activeJob")) and active.get("jobId"):
        await wait_job(cmp, active["jobId"], "SUCCEEDED")
    i = await cmp.incident(INCIDENT)
    st, acc = await cmp.post(f"/api/incidents/{i['incidentId']}/questions",
                             {"question": QUESTION, "expectedIncidentVersion": i["version"]})
    check("질문 접수 (202)", st == 202, acc)
    await wait_job(cmp, acc["data"]["jobId"], "WAITING_APPROVAL")
    _, page = await cmp.get(f"/api/approvals?status=PENDING&incidentId={i['incidentId']}")
    a = page["data"]["items"][0]
    _, a = await cmp.get(f"/api/approvals/{a['approvalId']}")
    a = a["data"]
    check("Agent 제안 = Etch 담당자 확인 요청", a["actionType"] == "CHECK_REQUEST"
          and a["payload"].get("ownerUserId") == "USR-ETCH-01", a)
    _, reqs = await cmp.get("/api/check-requests?box=sent")
    check("승인 전 확인 요청 0건", not [r for r in reqs["data"]["items"] if r["incidentId"] == i["incidentId"]], reqs)
    return i, a


async def approve_to_end(cmp: User, i: dict, a: dict) -> None:
    st, dec = await cmp.post(f"/api/approvals/{a['approvalId']}/decisions",
                             {"decision": "APPROVE", "expectedVersion": a["version"], "actionHash": a["actionHash"]})
    check("승인 (기록만, 실행 결과 없음)", st == 200 and dec["data"].get("executionRef") is None, dec)
    await wait_job(cmp, dec["data"]["resumeJobId"], "WAITING_REPLY")
    _, a2 = await cmp.get(f"/api/approvals/{a['approvalId']}")
    chk = (a2["data"].get("executionRef") or {}).get("id")
    check("재개된 Agent가 승인 ID로 실행 → executionRef", bool(chk), a2)

    etch = await User("etch.lee").login()
    _, r = await etch.get(f"/api/check-requests/{chk}")
    brief = r["data"].get("brief") or {}
    dev = next((d for d in brief.get("deviations", []) if d.get("equipmentId") == "ETCH-PM2"), {})
    check("확인 요청에 브리프 — ETCH-PM2 제거량 · 원인 후보(확정 아님)",
          dev.get("value") == 4896.3 and dev.get("withinNormal") is False
          and [c["causeId"] for c in brief.get("suspectedCauses", [])] == ["ETCH_WALL"]
          and all(c["confirmed"] is False for c in brief["suspectedCauses"]), brief)
    st, rep = await etch.post(f"/api/check-requests/{chk}/replies", {
        "findings": "ETCH-PM2 정비 후 시즈닝 매수가 절차보다 적었습니다. 모니터 결과를 첨부합니다(시연 회신).",
        "evidenceIds": [], "confirmedByUser": True, "expectedVersion": r["data"]["version"]})
    check("Etch 담당자 회신 (201)", st == 201, rep)
    await etch.close()
    await wait_job(cmp, rep["data"]["resumeJobId"], "SUCCEEDED")
    _, res = await cmp.get(f"/api/incidents/{i['incidentId']}/analysis-results")
    res = res["data"]
    # 공개 결과(P07)에는 nextStep이 없다(계약 확장 필드) — 1순위 원인 후보가 앞 공정 Etch인지로 본다
    top = json.dumps((res.get("candidateCauses") or [{}])[0], ensure_ascii=False)
    check("결과 kind = RESUME", res.get("kind") == "RESUME", res.get("kind"))
    check("1순위 원인 후보 = 앞 공정 ETCH", "ETCH" in top, top[:200])
    _, reqs = await cmp.get("/api/check-requests?box=sent")
    check("확인 요청은 정확히 1건(멱등)",
          len([x for x in reqs["data"]["items"] if x["incidentId"] == i["incidentId"]]) == 1, reqs["data"]["items"])

    i = await cmp.incident(INCIDENT)
    st, acc = await cmp.post(f"/api/incidents/{i['incidentId']}/report-jobs", {"expectedIncidentVersion": i["version"]})
    check("보고서 요청 (202)", st == 202, acc)
    await wait_job(cmp, acc["data"]["jobId"], "SUCCEEDED")
    _, reps = await cmp.get(f"/api/incidents/{i['incidentId']}/reports")
    items = reps["data"]["items"] if isinstance(reps["data"], dict) else reps["data"]
    check("보고서 초안 저장", bool(items), reps)


async def main() -> int:
    cmp = await User("cmp.park").login()
    i, a = await ask_until_approval(cmp)
    await approve_to_end(cmp, i, a)
    await cmp.close()
    print(f"결과: 실패 {len(FAILS)}건" + (f" — {FAILS}" if FAILS else ""))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

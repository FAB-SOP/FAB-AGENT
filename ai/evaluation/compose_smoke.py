"""compose 전체 점검 — 사람 역할(공개 API)만 하고, 분석 · 제안 · 실행은 compose의 agent Worker가 한다.

  docker compose --profile tools run --rm --no-deps smoke
  (호스트에서) SPRING_URL=http://localhost:8090 uv run python evaluation/compose_smoke.py

EV-1009 (CMP-PM3 두께 R1, 원인은 앞 공정 ETCH-PM2)
  1) 기본 분석(시드에서 QUEUED) 완료 → 2) 심층 질문 → Agent가 Etch 담당자 확인 요청을 '제안'(승인 대기)
  3) 사람 승인 → 승인 응답에는 실행 결과 없음(기록만) → 재개된 Agent가 승인 ID로 MCP 변경 도구 실행 → 요청 1건
  4) Etch 담당자 회신 → 재개 → 결과: 1순위 원인 후보가 앞 공정 ETCH  5) 보고서 초안 다섯 섹션

검사 모드 — 기본은 규칙 모델(FAB_LLM=rule)의 결정적인 답을 그대로 본다. 실제 모델로 띄웠으면 SMOKE_MODE=real(또는 --real):
  - 브리프 원인 후보: 정확히 [ETCH_WALL] 대신 「ETCH_WALL 포함 · 모두 확정 아님」
  - 회신 뒤 재개에서 Agent가 새 제안을 하면(제안 상한 2 안에서 허용된 흐름) 거절하고 끝날 때까지 따라간다
  docker compose --profile tools run --rm --no-deps -e SMOKE_MODE=real smoke
"""

from __future__ import annotations

import asyncio
import json
import os
import sys

from fab.devtools.spring import User, request_followup

INCIDENT = "EV-1009"
QUESTION = "이 사건의 원인 후보를 분석하고, 필요한 확인 · 점검 요청을 제안해줘"
FAILS: list[str] = []
REAL = os.getenv("SMOKE_MODE") == "real" or "--real" in sys.argv


def check(label: str, ok: bool, detail: object = "") -> None:
    print(f"  {'✔' if ok else '✘'} {label}{'' if ok else f' — {detail}'}", flush=True)
    if not ok:
        FAILS.append(label)


async def wait_job(user: User, job_id: str, *want: str, timeout: float = 180) -> dict:
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while True:
        _, job = await user.get(f"/api/jobs/{job_id}")
        status = job["data"]["status"]
        if status in want or status in ("FAILED", "CANCELLED") or loop.time() > end:
            check(f"job {job_id} → {status}", status in want, f"기대 {want}")
            return job["data"]
        await asyncio.sleep(1)


async def pending_approval(cmp: User, incident: dict) -> dict:
    """사건의 첫 승인 대기 제안(상세)"""
    _, page = await cmp.get(f"/api/approvals?status=PENDING&incidentId={incident['incidentId']}")
    _, approval = await cmp.get(f"/api/approvals/{page['data']['items'][0]['approvalId']}")
    return approval["data"]


async def ask_until_approval(cmp: User) -> tuple[dict, dict]:
    """기본 분석이 끝나길 기다린 뒤 질문하고, Agent 제안(승인 대기)까지 진행한다 → (사건, 승인)"""
    incident = await cmp.incident(INCIDENT)
    _, detail = await cmp.get(f"/api/incidents/{incident['incidentId']}")
    if (active := detail["data"].get("activeJob")) and active.get("jobId"):
        await wait_job(cmp, active["jobId"], "SUCCEEDED")
    incident = await cmp.incident(INCIDENT)
    status, accepted = await cmp.post(f"/api/incidents/{incident['incidentId']}/questions",
                                      {"question": QUESTION, "expectedIncidentVersion": incident["version"]})
    check("질문 접수 (202)", status == 202, accepted)
    await wait_job(cmp, accepted["data"]["jobId"], "WAITING_APPROVAL")
    approval = await pending_approval(cmp, incident)
    check("Agent 제안 = Etch 담당자 확인 요청", approval["actionType"] == "CHECK_REQUEST"
          and approval["payload"].get("ownerUserId") == "USR-ETCH-01", approval)
    _, requests = await cmp.get("/api/check-requests?box=sent")
    check("승인 전 확인 요청 0건",
          not [request for request in requests["data"]["items"] if request["incidentId"] == incident["incidentId"]], requests)
    return incident, approval


async def approve_and_resume(cmp: User, approval: dict) -> str | None:
    """승인은 기록만 하고, 재개된 Agent가 승인 ID로 실행한다 → 등록된 확인 요청 ID"""
    status, decision = await cmp.post(f"/api/approvals/{approval['approvalId']}/decisions", {
        "decision": "APPROVE", "expectedVersion": approval["version"], "actionHash": approval["actionHash"]})
    check("승인 (기록만, 실행 결과 없음)", status == 200 and decision["data"].get("executionRef") is None, decision)
    await wait_job(cmp, decision["data"]["resumeJobId"], "WAITING_REPLY")
    _, decided = await cmp.get(f"/api/approvals/{approval['approvalId']}")
    request_id = (decided["data"].get("executionRef") or {}).get("id")
    check("재개된 Agent가 승인 ID로 실행 → executionRef", bool(request_id), decided)
    return request_id


async def reply_as_etch(request_id: str | None) -> dict:
    """Etch 담당자가 브리프를 확인하고 회신한다 → 회신 응답"""
    etch = await User("etch.lee").login()
    _, request = await etch.get(f"/api/check-requests/{request_id}")
    brief = request["data"].get("brief") or {}
    deviation = next((item for item in brief.get("deviations", []) if item.get("equipmentId") == "ETCH-PM2"), {})
    causes = [cause["causeId"] for cause in brief.get("suspectedCauses", [])]
    # 실제 모델은 ETCH 원인 후보를 여럿 붙이기도 한다 — ETCH_WALL이 들어 있고 모두 확정 아님이면 된다
    causes_ok = "ETCH_WALL" in causes if REAL else causes == ["ETCH_WALL"]
    check("확인 요청에 브리프 — ETCH-PM2 제거량 · 원인 후보(확정 아님)",
          deviation.get("value") == 4896.3 and deviation.get("withinNormal") is False and causes_ok
          and all(cause["confirmed"] is False for cause in brief["suspectedCauses"]), brief)
    status, decided = await etch.post(f"/api/check-requests/{request_id}/decisions",
                                      {"decision": "ACCEPT", "comment": None, "expectedVersion": request["data"]["version"]})
    check("Etch 담당자 요청 수락 (200)", status == 200, decided)
    status, reply = await etch.post(f"/api/check-requests/{request_id}/replies", {
        "findings": "ETCH-PM2 정비 후 시즈닝 매수가 절차보다 적었습니다. 모니터 결과를 첨부합니다(시연 회신).",
        "evidenceIds": [], "confirmedByUser": True, "expectedVersion": decided["data"]["version"]})
    check("Etch 담당자 회신 (201)", status == 201, reply)
    await etch.close()
    return reply


async def check_resumed_result(cmp: User, incident: dict) -> None:
    _, result = await cmp.get(f"/api/incidents/{incident['incidentId']}/analysis-results")
    result = result["data"]
    # 공개 결과(P07)에는 nextStep이 없다(계약 확장 필드) — 1순위 원인 후보가 앞 공정 Etch인지로 본다
    top = json.dumps((result.get("candidateCauses") or [{}])[0], ensure_ascii=False)
    check("결과 kind = RESUME", result.get("kind") == "RESUME", result.get("kind"))
    check("1순위 원인 후보 = 앞 공정 ETCH", "ETCH" in top, top[:200])
    _, requests = await cmp.get("/api/check-requests?box=sent")
    check("확인 요청은 정확히 1건(멱등)",
          len([request for request in requests["data"]["items"] if request["incidentId"] == incident["incidentId"]]) == 1,
          requests["data"]["items"])


async def draft_report(cmp: User) -> None:
    incident = await cmp.incident(INCIDENT)
    status, accepted = await cmp.post(f"/api/incidents/{incident['incidentId']}/report-jobs",
                                      {"expectedIncidentVersion": incident["version"]})
    check("보고서 요청 (202)", status == 202, accepted)
    await wait_job(cmp, accepted["data"]["jobId"], "SUCCEEDED")
    _, reports = await cmp.get(f"/api/incidents/{incident['incidentId']}/reports")
    items = reports["data"]["items"] if isinstance(reports["data"], dict) else reports["data"]
    check("보고서 초안 저장", bool(items), reports)


async def approve_to_end(cmp: User, incident: dict, approval: dict) -> None:
    request_id = await approve_and_resume(cmp, approval)
    reply = await reply_as_etch(request_id)
    # 회신은 저장만 된다 — 작업자가 그 회신을 골라 추가 분석을 요청해야 대기 중인 Agent가 재개된다
    followup = await request_followup(cmp, incident["displayId"], [{"type": "REPLY", "id": reply["data"]["replyId"]}])
    check("회신 선택 추가 분석 → 대기 재개(RESUME)", followup.get("executionMode") == "RESUME", followup)
    await finish_resume(cmp, incident, followup["jobId"])
    await check_resumed_result(cmp, incident)
    await draft_report(cmp)


async def finish_resume(cmp: User, incident: dict, job_id: str) -> None:
    """회신 뒤 재개 작업이 끝날 때까지. 실제 모델 모드에서는 재개 중 새 제안(승인 대기)을 거절하고 이어지는 재개를 따라간다"""
    if not REAL:
        await wait_job(cmp, job_id, "SUCCEEDED")
        return
    for _ in range(3):  # 제안 상한은 2 — 그 이상 이어지지 않는다
        job = await wait_job(cmp, job_id, "SUCCEEDED", "WAITING_APPROVAL")
        if job["status"] != "WAITING_APPROVAL":
            return
        approval = await pending_approval(cmp, incident)
        print(f"    회신 뒤 새 제안 {approval['actionType']} → 거절하고 진행", flush=True)
        status, decision = await cmp.post(f"/api/approvals/{approval['approvalId']}/decisions", {
            "decision": "REJECT", "comment": "점검 — 회신 뒤 추가 요청은 보내지 않는다", "expectedVersion": approval["version"]})
        check("회신 뒤 새 제안 거절 → 재개", status == 200 and bool(decision["data"].get("resumeJobId")), decision)
        job_id = decision["data"]["resumeJobId"]


async def main() -> int:
    cmp = await User("cmp.park").login()
    incident, approval = await ask_until_approval(cmp)
    await approve_to_end(cmp, incident, approval)
    await cmp.close()
    print(f"결과: 실패 {len(FAILS)}건" + (f" — {FAILS}" if FAILS else ""))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

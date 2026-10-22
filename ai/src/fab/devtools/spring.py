"""Spring backend(fab-context-api)에 붙는 개발 도구 — 사람 역할(공개 API)과 Agent(내부 API)를 한 프로세스에서 돌린다.

- User: 공개 API 세션 사용자 (CSRF · 세션 쿠키 · Idempotency-Key)
- make_runner: 업무 MCP(BACKEND_URL=Spring) · 지식 MCP를 같은 프로세스에 띄운 JobRunner + HTTP 작업 소스
- drive: 질문 → (승인/거절) → (회신) → 결과 → 보고서를 사람 역할로 끝까지 진행한다

Spring은 compose의 postgres 위에 외부 Agent 모드로 띄운다: DDL_AUTO=create AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun
"""

from __future__ import annotations

import os
import uuid

import httpx
from langgraph.checkpoint.memory import InMemorySaver

from fab.agent.backend import HttpBackend
from fab.agent.worker import HttpSink, JobRunner, poll_once



def spring_url() -> str:
    """호출할 때 읽는다 — 테스트가 띄운 서버 주소(SPRING_URL)를 나중에 정해도 따라간다"""
    return os.getenv("SPRING_URL", "http://localhost:8090")


TOKEN = os.getenv("SERVICE_TOKEN", "dev-service-token")
# 시드 사용자 (fab-context-api SeedCatalog) — 비밀번호 fab1234!
LOGIN = {"USR-CVD-01": "cvd.kim", "USR-ETCH-01": "etch.lee", "USR-CMP-01": "cmp.park", "USR-MGR-01": "manager.choi",
         "USR-MNT-01": "mnt.jung"}
OWNER_OF_PROCESS = {"CVD": "cvd.kim", "ETCH": "etch.lee", "CMP": "cmp.park"}
# 공개 API(프론트 계약)의 승인 종류 → 팀 계약(Agent 제안 · 정답표) 이름
INTERNAL_ACTION = {"CHECK_REQUEST": "CREATE_CHECK_REQUEST", "MAINTENANCE_REQUEST": "CREATE_MAINTENANCE_REQUEST"}


def internal_action(public: str) -> str:
    return INTERNAL_ACTION.get(public, public)


class User:
    def __init__(self, username: str, base: str | None = None):
        self.c = httpx.AsyncClient(base_url=base or spring_url(), timeout=20)
        self.username = username

    async def login(self) -> "User":
        self.csrf = (await self.c.get("/api/auth/csrf")).json()["data"]["token"]
        response = await self.c.post("/api/auth/login", json={"username": self.username, "password": "fab1234!"},
                                     headers={"X-CSRF-TOKEN": self.csrf})
        response.raise_for_status()
        return self

    async def get(self, path: str) -> tuple[int, dict]:
        response = await self.c.get(path)
        return response.status_code, (response.json() if response.content else {})

    async def post(self, path: str, body: dict) -> tuple[int, dict]:
        response = await self.c.post(path, json=body, headers={"X-CSRF-TOKEN": self.csrf, "Idempotency-Key": str(uuid.uuid4())})
        return response.status_code, (response.json() if response.content else {})

    async def incident(self, display_id: str) -> dict:
        _, page = await self.get(f"/api/incidents?q={display_id}")
        return next(i for i in page["data"]["items"] if i["displayId"] == display_id)

    async def close(self):
        await self.c.aclose()


def make_runner(kb=None, models=None, base: str | None = None) -> tuple[JobRunner, HttpBackend]:
    from fab.business.server import build_server as business
    from fab.rag.server import Knowledge, build_server as knowledge

    base = base or spring_url()
    backend = HttpBackend(base, TOKEN)
    servers = {"business": business(backend_url=base, token=TOKEN), "knowledge": knowledge(kb or Knowledge())}
    return JobRunner(servers, backend, InMemorySaver(), HttpSink(backend), models=models, worker_id="dev-worker"), backend


async def internal(method: str, path: str, base: str | None = None, **kw) -> dict:
    async with httpx.AsyncClient(base_url=base or spring_url(), headers={"Authorization": f"Bearer {TOKEN}"},
                                 timeout=20) as client:
        response = await client.request(method, path, **kw)
        return response.json()


SIMULATOR_TOKEN = os.getenv("SIMULATOR_TOKEN", "dev-simulator-token")


async def fresh_incident(display_id: str, base: str | None = None) -> str:
    """같은 웨이퍼 · run으로 새 사건을 접수해 표시 ID를 돌려준다 — 요청 · 승인 기록이 없는 사건에서 시작해야 하는 시험용.
    요청 상한 · 중복은 사건 단위라, 같은 서버를 쓰는 앞 시험의 요청이 남은 사건에서는 새 제안이 막힌다."""
    source = (await internal("GET", f"/internal/incidents/{display_id}", base))["data"]["data"]
    event = {"eventId": f"EVT-TEST-{uuid.uuid4().hex[:8]}", "source": "test", "anomalyType": source["anomalyType"],
             "processCode": source["processCode"], "equipmentId": source["equipmentId"], "lotId": source.get("lotId"),
             "waferIds": source.get("waferIds") or [], "runIds": source.get("runIds") or [], "sensorIds": [],
             "detectedAt": source["detectedAt"], "title": f"{source['title']} (시험용 복사)"}
    async with httpx.AsyncClient(base_url=base or spring_url(), timeout=20) as client:
        response = await client.post("/api/events/anomalies", json=event, headers={"X-Service-Token": SIMULATOR_TOKEN})
        response.raise_for_status()
        return response.json()["data"]["displayId"]


async def _ask_question(engineer: User, display_id: str, question: str) -> None:
    incident = await engineer.incident(display_id)
    status, accepted = await engineer.post(f"/api/incidents/{incident['incidentId']}/questions",
                                           {"question": question, "expectedIncidentVersion": incident["version"]})
    if status != 202:
        raise RuntimeError(f"질문 접수 실패 {status}: {accepted}")


async def _decide_approval(engineer: User, interrupt: dict, reject: bool, say) -> dict:
    """승인 대기 — 제안을 읽고 승인(또는 거절)한다. 읽은 제안을 팀 계약 이름으로 돌려준다"""
    _, approval = await engineer.get(f"/api/approvals/{interrupt['approvalId']}")
    proposal = approval["data"]
    say(f"  승인 대기 {proposal['actionType']} → {proposal['payload'].get('ownerUserId')}: {proposal['payload']['question'][:70]}")
    body = {"decision": "REJECT", "comment": "지금은 요청 없이 진행", "expectedVersion": proposal["version"]} if reject \
        else {"decision": "APPROVE", "expectedVersion": proposal["version"], "actionHash": proposal["actionHash"]}
    await engineer.post(f"/api/approvals/{interrupt['approvalId']}/decisions", body)
    return proposal | {"actionType": internal_action(proposal["actionType"]), "publicActionType": proposal["actionType"]}


async def _reply_as_owner(interrupt: dict, base: str | None, say, engineer: User | None = None,
                          display_id: str | None = None) -> None:
    """회신 대기 — 받는 담당자가 요청을 수락하고 시연 회신을 남긴다. 회신은 저장만 되고 분석을 열지 않으므로,
    engineer가 있으면 작업자로서 그 회신을 골라 추가 분석을 요청한다(같은 thread · interrupt의 RESUME)"""
    request = (await internal("GET", f"/internal/check-requests/{interrupt['requestId']}", base))["data"]
    owner = await User(LOGIN[request["ownerUserId"]], base).login()
    say(f"  회신 대기 {request['requestType']} → {owner.username}")
    path = f"/api/check-requests/{request['requestId']}"
    status, decided = await owner.post(f"{path}/decisions", {"decision": "ACCEPT", "comment": None,
                                                             "expectedVersion": request["version"]})
    if status != 200:
        raise RuntimeError(f"요청 수락 실패 {status}: {decided}")
    status, reply = await owner.post(f"{path}/replies", {
        "findings": "확인했습니다. 해당 run 전후 정비 · 시즈닝 기록과 모니터 결과를 첨부합니다(시연 회신).",
        "evidenceIds": [], "confirmedByUser": True, "expectedVersion": decided["data"]["version"]})
    await owner.close()
    if status != 201:
        raise RuntimeError(f"회신 등록 실패 {status}: {reply}")
    if engineer is not None and display_id is not None:
        await request_followup(engineer, display_id, [{"type": "REPLY", "id": reply["data"]["replyId"]}])


async def request_followup(engineer: User, display_id: str, source_refs: list[dict], question: str | None = None) -> dict:
    """작업자가 고른 결과(회신 · 내 작업 결과)로 추가 분석을 요청한다 — 회신 대기 중이면 RESUME, 아니면 새 DEEP"""
    incident = await engineer.incident(display_id)
    status, accepted = await engineer.post(f"/api/incidents/{incident['incidentId']}/followup-analysis-jobs", {
        "sourceRefs": source_refs, "question": question, "expectedIncidentVersion": incident["version"]})
    if status != 202:
        raise RuntimeError(f"추가 분석 요청 실패 {status}: {accepted}")
    return accepted["data"]


async def _draft_report(engineer: User, display_id: str, runner: JobRunner, backend: HttpBackend, say) -> dict:
    incident = await engineer.incident(display_id)
    await engineer.post(f"/api/incidents/{incident['incidentId']}/report-jobs", {"expectedIncidentVersion": incident["version"]})
    report_outcome = (await poll_once(runner, backend))[-1]
    say(f"[{display_id}] 보고서 초안 → {report_outcome['status']}")
    return report_outcome


async def drive(display_id: str, question: str, *, reject: bool = False, kb=None, models=None, report: bool = True,
                verbose: bool = True, base: str | None = None) -> dict:
    """사건 하나를 사람 역할로 끝까지 진행한다 — 공개 API로 질문 · 승인 · 회신, Agent는 내부 API로 일한다."""
    say = print if verbose else (lambda *a, **k: None)
    runner, backend = make_runner(kb, models, base)
    sink = runner.sink
    await poll_once(runner, backend)  # 기동 때 대기 중인 작업(예: EV-1009 기본 분석)을 먼저 비운다
    incident = await internal("GET", f"/internal/incidents/{display_id}", base)
    process = incident["data"]["data"]["processCode"]
    engineer = await User(OWNER_OF_PROCESS[process], base).login()
    await _ask_question(engineer, display_id, question)
    outcome = (await poll_once(runner, backend))[-1]
    say(f"[{display_id}] DEEP → {outcome['status']}")
    proposals = []
    while outcome["status"] in ("WAITING_APPROVAL", "WAITING_REPLY"):
        if outcome["status"] == "WAITING_APPROVAL":
            proposals.append(await _decide_approval(engineer, outcome["interrupt"], reject, say))
        else:
            await _reply_as_owner(outcome["interrupt"], base, say, engineer, display_id)
        outcome = (await poll_once(runner, backend))[-1]
        say(f"  재개 → {outcome['status']}")
    result, analysis = outcome.get("result") or {}, outcome.get("analysis") or {}
    for cause in result.get("candidateCauses", []):
        say(f"    우선순위 {cause['priority']}: {cause['hypothesis']}")
    report_outcome = await _draft_report(engineer, display_id, runner, backend, say) if report else None
    await engineer.close()
    return {"incident": display_id, "result": result, "analysis": analysis, "proposals": proposals,
            "events": sink.events, "trace": outcome.get("trace"), "report": (report_outcome or {}).get("report")}

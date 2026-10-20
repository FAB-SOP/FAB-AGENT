"""Spring backend(fab-context-api)에 붙는 개발 도구 — 사람 역할(공개 API)과 Agent(내부 API)를 한 프로세스에서 돌린다.

- User: 공개 API 세션 사용자 (CSRF · 세션 쿠키 · Idempotency-Key)
- make_runner: 업무 MCP(BACKEND_URL=Spring) · 지식 MCP를 같은 프로세스에 띄운 JobRunner + HTTP 작업 소스
- drive: 질문 → (승인/거절) → (회신) → 결과 → 보고서를 사람 역할로 끝까지 진행한다

Spring은 외부 Agent 모드로 띄운다: SPRING_PROFILES_ACTIVE=h2 AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun
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
        r = await self.c.post("/api/auth/login", json={"username": self.username, "password": "fab1234!"},
                              headers={"X-CSRF-TOKEN": self.csrf})
        r.raise_for_status()
        return self

    async def get(self, path: str) -> tuple[int, dict]:
        r = await self.c.get(path)
        return r.status_code, (r.json() if r.content else {})

    async def post(self, path: str, body: dict) -> tuple[int, dict]:
        r = await self.c.post(path, json=body, headers={"X-CSRF-TOKEN": self.csrf, "Idempotency-Key": str(uuid.uuid4())})
        return r.status_code, (r.json() if r.content else {})

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
    async with httpx.AsyncClient(base_url=base or spring_url(), headers={"Authorization": f"Bearer {TOKEN}"}, timeout=20) as c:
        r = await c.request(method, path, **kw)
        return r.json()


async def drive(display_id: str, question: str, *, reject: bool = False, kb=None, models=None, report: bool = True,
                verbose: bool = True, base: str | None = None) -> dict:
    """사건 하나를 사람 역할로 끝까지 진행한다 — 공개 API로 질문 · 승인 · 회신, Agent는 내부 API로 일한다."""
    say = print if verbose else (lambda *a, **k: None)
    runner, backend = make_runner(kb, models, base)
    sink = runner.sink
    await poll_once(runner, backend)  # 기동 때 대기 중인 작업(예: EV-1009 기본 분석)을 먼저 비운다
    inc = await internal("GET", f"/internal/incidents/{display_id}", base)
    proc = inc["data"]["data"]["processCode"]
    engineer = await User(OWNER_OF_PROCESS[proc], base).login()
    i = await engineer.incident(display_id)
    st, acc = await engineer.post(f"/api/incidents/{i['incidentId']}/questions",
                                  {"question": question, "expectedIncidentVersion": i["version"]})
    if st != 202:
        raise RuntimeError(f"질문 접수 실패 {st}: {acc}")
    out = (await poll_once(runner, backend))[-1]
    say(f"[{display_id}] DEEP → {out['status']}")
    proposals = []
    while out["status"] in ("WAITING_APPROVAL", "WAITING_REPLY"):
        intr = out["interrupt"]
        if out["status"] == "WAITING_APPROVAL":
            _, a = await engineer.get(f"/api/approvals/{intr['approvalId']}")
            proposals.append(a["data"] | {"actionType": internal_action(a["data"]["actionType"]),
                                          "publicActionType": a["data"]["actionType"]})
            say(f"  승인 대기 {a['data']['actionType']} → {a['data']['payload'].get('ownerUserId')}: {a['data']['payload']['question'][:70]}")
            body = {"decision": "REJECT", "comment": "지금은 요청 없이 진행", "expectedVersion": a["data"]["version"]} if reject \
                else {"decision": "APPROVE", "expectedVersion": a["data"]["version"], "actionHash": a["data"]["actionHash"]}
            st, _ = await engineer.post(f"/api/approvals/{intr['approvalId']}/decisions", body)
        else:
            req = (await internal("GET", f"/internal/check-requests/{intr['requestId']}", base))["data"]
            owner = await User(LOGIN[req["ownerUserId"]], base).login()
            say(f"  회신 대기 {req['requestType']} → {owner.username}")
            st, _ = await owner.post(f"/api/check-requests/{req['requestId']}/replies", {
                "findings": "확인했습니다. 해당 run 전후 정비 · 시즈닝 기록과 모니터 결과를 첨부합니다(시연 회신).",
                "evidenceIds": [], "confirmedByUser": True, "expectedVersion": req["version"]})
            await owner.close()
        out = (await poll_once(runner, backend))[-1]
        say(f"  재개 → {out['status']}")
    result, analysis = out.get("result") or {}, out.get("analysis") or {}
    for c in result.get("candidateCauses", []):
        say(f"    우선순위 {c['priority']}: {c['hypothesis']}")
    rep = None
    if report:
        i = await engineer.incident(display_id)
        await engineer.post(f"/api/incidents/{i['incidentId']}/report-jobs", {"expectedIncidentVersion": i["version"]})
        rep = (await poll_once(runner, backend))[-1]
        say(f"[{display_id}] 보고서 초안 → {rep['status']}")
    await engineer.close()
    return {"incident": display_id, "result": result, "analysis": analysis, "proposals": proposals,
            "events": sink.events, "trace": out.get("trace"), "report": (rep or {}).get("report")}

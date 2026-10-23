"""Backend 클라이언트 — Agent Worker가 부르는 그래프 제어용 내부 API (계약의 /internal/jobs/... 등).

Agent는 업무 DB를 직접 바꾸지 않는다. 작업 확보 · 제안 저장 · 재개 값 조회 · 보고서 초안 저장만 backend에 맡긴다.
BACKEND_URL은 Spring backend(fab-context-api)다.
"""

from __future__ import annotations

import os

import httpx

from fab.common.envelope import FabToolError


class BackendError(FabToolError):
    pass


class HttpBackend:
    def __init__(self, base_url: str | None = None, token: str | None = None,
                 transport: httpx.AsyncBaseTransport | None = None):
        self.base = (base_url or os.environ["BACKEND_URL"]).rstrip("/")
        self.token = token or os.getenv("SERVICE_TOKEN", "dev-service-token")
        self.transport = transport

    async def _req(self, method: str, path: str, **kw) -> dict:
        async with httpx.AsyncClient(base_url=self.base, transport=self.transport, timeout=10,
                                     headers={"Authorization": f"Bearer {self.token}"}) as client:
            try:
                response = await client.request(method, path, **kw)
            except httpx.TransportError as e:
                raise BackendError("UNAVAILABLE", f"backend 연결 실패({type(e).__name__})", retryable=True) from e
        body = response.json() if response.content else {}
        if response.is_success:
            return body["data"]
        e = body.get("error", {})
        raise BackendError(e.get("code", f"HTTP_{response.status_code}"), e.get("message", response.text[:200]),
                           hint=e.get("hint", ""), retryable=bool(e.get("retryable")) or response.status_code in (429, 503))

    async def claim(self, job_id: str, attempt: int, worker_id: str) -> dict:
        return await self._req("POST", f"/internal/jobs/{job_id}/claims",
                               json={"attempt": attempt, "workerId": worker_id})

    async def create_proposal(self, job_id, action_type, payload, rationale, evidence_ids, requested_by,
                              idempotency_key: str | None = None):
        return await self._req("POST", f"/internal/jobs/{job_id}/proposals", json={
            "actionType": action_type, "payload": payload, "rationale": rationale,
            "evidenceIds": evidence_ids, "requestedBy": requested_by, "idempotencyKey": idempotency_key})

    async def get_approval(self, approval_id):
        return await self._req("GET", f"/internal/approvals/{approval_id}")

    async def get_request(self, request_id):
        return await self._req("GET", f"/internal/check-requests/{request_id}")

    async def get_report_snapshot(self, report_id: str) -> dict:
        """확정 보고서의 스냅샷(섹션 · 원인 분류 · 근거 · snapshotRef) — SUMMARY 작업이 읽는다. CONFIRMED가 아니면 409"""
        return await self._req("GET", f"/internal/reports/{report_id}/snapshot")

    async def save_report_draft(self, incident_id, sections, evidence_ids):
        return await self._req("POST", f"/internal/incidents/{incident_id}/report-drafts",
                               json={"sections": sections, "evidenceIds": evidence_ids})

    # ---- Kafka 전 임시 다리 — 작업을 HTTP로 가져가고 이벤트를 HTTP로 돌려준다 (Spring backend)
    async def queued_jobs(self) -> list[dict]:
        """[{job: job-message, question, scope, status}] — QUEUED 작업"""
        return await self._req("GET", "/internal/jobs")

    async def job_context(self, job_id: str) -> dict:
        return await self._req("GET", f"/internal/jobs/{job_id}/context")

    async def post_event(self, job_id: str, event: dict) -> dict:
        return await self._req("POST", f"/internal/jobs/{job_id}/events", json=event)

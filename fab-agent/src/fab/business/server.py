"""업무 MCP (4번 자리) — backend(Spring fab-context-api)의 /internal/** 를 부르는 얇은 어댑터.

도구 이름은 데이터 팩의 Agent 계약(fab-causal-pack-v1/knowledge/agent-contract.json)을 따른다:
읽기 — 사건 2 · 설비 데이터 6(경로 · run/계측 · 특징 · 원시 시계열 · 관리한계 · 정비 이력) · 협업 4,
쓰기(승인 필요) — 공정 간 확인 요청 · 정비 요청 · 보고서 확정.

규칙(레퍼런스 아키텍처 5장): business-mcp는 DB에 직접 접근하지 않고, 업무 규칙을 복제하지 않는다.
조회 · 계산 · 승인 검사는 전부 backend가 한다. 이 서버는 도구 정의(이름 · 설명 · 입력 스키마)와
오류를 모델이 읽는 문장으로 바꾸는 일만 한다.

  BACKEND_URL=http://localhost:8080 uv run fab-business-mcp    # 기본 :9101/mcp
"""

from __future__ import annotations

import os
from typing import Annotated, Literal

import httpx
from fastmcp import FastMCP
from mcp.types import ToolAnnotations
from pydantic import Field

from fab.common.envelope import FabToolError

READ = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)
WRITE = ToolAnnotations(readOnlyHint=False, destructiveHint=True, idempotentHint=True, openWorldHint=False)

Process = Literal["CVD", "ETCH", "CMP"]
IncidentId = Annotated[str, Field(description="사건 ID. 예: EV-1009 (또는 inc_로 시작하는 ID)")]
WaferId = Annotated[str, Field(description="웨이퍼 ID. 예: W-CURRENT-009-3")]
RunId = Annotated[str, Field(description="run ID. 예: RUN-W-CURRENT-009-3-ETCH — get_wafer_lineage로 얻는다")]
ApprovalId = Annotated[str, Field(description="사람이 승인한 제안의 ID. 예: apv_…")]
IdemKey = Annotated[str, Field(min_length=8, max_length=128, description="같은 실행을 한 번만 하기 위한 키")]


class BackendClient:
    """서비스 자격으로 /internal/** 를 부른다. 오류는 FabToolError로 바꿔 모델이 읽게 한다."""

    def __init__(self, base_url: str, token: str, transport: httpx.AsyncBaseTransport | None = None,
                 timeout: float = 8.0):
        self._c = httpx.AsyncClient(base_url=base_url.rstrip("/"), transport=transport, timeout=timeout,
                                    headers={"Authorization": f"Bearer {token}"})

    async def call(self, method: str, path: str, *, params: dict | None = None, json: dict | None = None) -> dict:
        params = {k: v for k, v in (params or {}).items() if v is not None}
        try:
            r = await self._c.request(method, path, params=params, json=json)
        except httpx.TransportError as e:
            raise FabToolError("UNAVAILABLE", f"backend에 연결하지 못했습니다({type(e).__name__}).",
                               hint="잠시 후 다시 시도하세요.", retryable=True) from e
        body = r.json()
        if r.is_success:
            return body["data"]
        e = body.get("error", {})
        raise FabToolError(e.get("code", "INTERNAL"), e.get("message", r.text[:200]), hint=e.get("hint", ""),
                           retryable=bool(e.get("retryable")) or r.status_code in (429, 503))


def build_server(*, backend_url: str | None = None, transport: httpx.AsyncBaseTransport | None = None,
                 token: str | None = None) -> FastMCP:
    """backend_url(기본 BACKEND_URL)의 /internal/** 를 부르는 업무 MCP를 만든다.
    transport는 테스트에서 backend를 같은 프로세스에 붙일 때만 쓴다(ASGI transport)."""
    backend = BackendClient(backend_url or os.getenv("BACKEND_URL", "http://localhost:8080"),
                            token or os.getenv("SERVICE_TOKEN", "dev-service-token"), transport)

    mcp = FastMCP(
        "fab-business",
        instructions=(
            "반도체 공정(PECVD → Etchback → CMP) 이상 사건의 설비 데이터와 협업 기록을 조회한다. "
            "제거량 · 관리한계 판정 · 정상 분포 대비 편차는 backend가 계산하며 모든 운전 데이터는 시연용 합성이다. "
            "분석 시점 이후 자료는 돌려주지 않는다. 변경 도구(create_cross_process_check_request · "
            "create_maintenance_request · confirm_report)는 사람이 승인한 approvalId로만 실행된다."
        ),
    )
    @mcp.tool(annotations=READ)
    async def get_work_policy() -> dict:
        """업무 유형별 처리 규칙을 조회한다. SELF_CHECK는 담당자 채택, MANAGER_APPROVAL은 채택 후 관리자 결재,
        TEAM_REQUEST는 다른 담당자에게 발송 확인이 필요하다. 분류 불명확은 REVIEW_REQUIRED다. 실제 설비 실행은 지원하지 않는다."""
        return await backend.call("GET", "/internal/work-policy")

    @mcp.tool(annotations=READ)
    async def get_work_items(incidentId: IncidentId) -> dict:
        """사건의 업무 제안·채택·거절·관리자 승인·완료 상태를 조회한다. 이미 채택/완료된 일을 반복 제안하지 않고 후속 분석에 참고한다.
        계획·승인은 수행 사실이 아니다. COMPLETED의 actionId/replyId 원본 기록을 별도로 확인한다."""
        return await backend.call("GET", f"/internal/incidents/{incidentId}/work-items")

    # ------------------------------------------------------------------ 읽기 도구 — 사건
    @mcp.tool(annotations=READ)
    async def get_incident(incidentId: IncidentId) -> dict:
        """사건의 기본 맥락을 돌려준다 — 공정 · 설비(PM) · 로트 · 웨이퍼 · run ID · 감지 시각 · 관리한계 판정(R1)과 분석 시점.
        조사를 시작할 때 가장 먼저 한 번 부른다. waferIds로 get_wafer_lineage를 이어서 부른다."""
        return await backend.call("GET", f"/internal/incidents/{incidentId}")

    @mcp.tool(annotations=READ)
    async def list_incidents(
        processCode: Annotated[Process | None, Field(description="비우면 전체")] = None,
        status: Annotated[Literal["OPEN", "INVESTIGATING", "WAITING_REPLY", "ON_HOLD", "CLOSED", "ALL"], Field()] = "ALL",
    ) -> dict:
        """사건 목록을 돌려준다. 같은 PM에서 비슷한 사건이 또 있었는지 볼 때 쓴다."""
        return await backend.call("GET", "/internal/incidents", params={"processCode": processCode, "status": status})

    # ------------------------------------------------------------------ 읽기 도구 — 설비 데이터 (팩 계약 dataMcpTools)
    @mcp.tool(annotations=READ)
    async def get_wafer_lineage(waferId: WaferId) -> dict:
        """웨이퍼 한 장의 실제 공정 경로(CVD → ETCH → CMP)와 단계별 runId · 설비(PM)를 돌려준다.
        PM 이름이 같다고 앞뒤 장비를 연결하지 않는다 — 앞 공정은 반드시 이 경로로 찾는다."""
        return await backend.call("GET", f"/internal/wafers/{waferId}/lineage")

    @mcp.tool(annotations=READ)
    async def get_run_and_metrology(runId: RunId) -> dict:
        """run 하나의 공정 결과를 돌려준다 — 5위치 두께와 평균, 입고 · 출고 두께, 증착량/제거량, 처리 시간,
        동결 관리한계(I 관리도, R1) 판정과 제품 규격, 같은 PM · 전체 PM 정상 run 대비 편차(z).
        계산은 backend가 한다. 분석 시점 이후 공개된 계측은 available=false로 온다."""
        return await backend.call("GET", f"/internal/runs/{runId}")

    @mcp.tool(annotations=READ)
    async def get_run_features(
        runId: RunId,
        sensorIds: Annotated[list[str] | None, Field(description="예: ['head_pressure', 'slurry_flow']. 비우면 그 run의 전체 센서")] = None,
    ) -> dict:
        """run의 센서 본 처리 평균 · 표준편차 · 설정값 차이와 정상 참고 범위 이탈 여부, 같은 PM 정상 run 대비 편차를 돌려준다.
        장비가 평소와 다르게 동작했는지 볼 때 쓴다. 참고 범위는 run 평균의 분포이며 점 단위 관리한계가 아니다.
        센서 하나와 고장을 일대일로 대응시키지 않는다."""
        return await backend.call("GET", f"/internal/runs/{runId}/features",
                                  params={"sensorIds": ",".join(sensorIds) if sensorIds else None})

    @mcp.tool(annotations=READ)
    async def get_sensor_trace(
        runId: RunId,
        sensorId: Annotated[str, Field(description="예: head_pressure, slurry_flow, source_rf_forward")],
        step: Annotated[Literal["READY", "PROCESS", "END"] | None, Field(description="비우면 전체 구간")] = "PROCESS",
        maxPoints: Annotated[int, Field(ge=10, le=120, description="구간 평균으로 줄일 점 수")] = 60,
    ) -> dict:
        """센서 원시 시계열을 구간 평균으로 줄여 돌려준다 — 설정값 프로파일 · 단계 경계 · 시계열 참조 범위(sensorEnvelope) 포함.
        segments: 2초 이동평균이 참조 범위 밖으로 5초 이상 이어진 관측 구간(run 시작 기준 초, DEMO-SEGMENT-V2) — 고장 시점 ·
        원인 확정이 아니다. 특징만으로 부족할 때(언제부터 · 얼마나 오래 벗어났는지) 쓴다. BASELINE run은 원시 시계열이 없다."""
        return await backend.call("GET", f"/internal/runs/{runId}/traces/{sensorId}",
                                  params={"step": step, "maxPoints": maxPoints})

    @mcp.tool(annotations=READ)
    async def get_frozen_limits(processCode: Annotated[Process | None, Field(description="비우면 세 공정 모두")] = None) -> dict:
        """동결 관리한계(웨이퍼 5위치 평균의 I 관리도, R1만 등록)와 제품 규격, 센서 특징 참고 범위 · 센서 카탈로그를 돌려준다.
        OOC(관리한계 이탈)와 OOS(규격 이탈)를 구분할 때 쓴다."""
        return await backend.call("GET", "/internal/limits", params={"processCode": processCode})

    @mcp.tool(annotations=READ)
    async def get_change_events(
        equipmentId: Annotated[str | None, Field(description="예: ETCH-PM2. 비우면 전체")] = None,
    ) -> dict:
        """설비 정비 · 설정 변경 이력(분석 시점 이전에 기록된 것)을 돌려준다.
        제거량이 달라진 PM에서 최근 정비 · 세정이 있었는지 볼 때 쓴다. 정비 기록이 원인 확정은 아니다."""
        return await backend.call("GET", "/internal/changes", params={"equipmentId": equipmentId})

    # ------------------------------------------------------------------ 읽기 도구 — 협업 · 기록
    @mcp.tool(annotations=READ)
    async def get_process_owner(processCode: Annotated[Process, Field(description="담당자를 찾을 공정")]) -> dict:
        """공정 담당자와 설비 정비 담당의 사용자 ID를 돌려준다(이름은 가린다).
        kind=PROCESS_OWNER는 공정 간 확인 요청, kind=MAINTENANCE는 정비 요청의 대상이다."""
        return await backend.call("GET", f"/internal/processes/{processCode}/owners")

    @mcp.tool(annotations=READ)
    async def get_check_requests(incidentId: IncidentId) -> dict:
        """이 사건에서 보낸 확인 요청 · 정비 요청과 담당자 회신을 돌려준다. 회신은 담당자의 진술이며 측정 근거와 구분한다."""
        return await backend.call("GET", f"/internal/incidents/{incidentId}/check-requests")

    @mcp.tool(annotations=READ)
    async def get_action_history(incidentId: IncidentId) -> dict:
        """사람이 실제로 수행해 등록한 조치와 효과 확인 상태를 돌려준다. AI 추천은 여기에 없다."""
        return await backend.call("GET", f"/internal/incidents/{incidentId}/actions")

    @mcp.tool(annotations=READ)
    async def get_report_context(incidentId: IncidentId) -> dict:
        """보고서 초안에 필요한 기록을 한 번에 돌려준다 — 사건, 요청 · 회신, 조치, 기존 보고서 버전."""
        return await backend.call("GET", f"/internal/incidents/{incidentId}/report-context")

    # ------------------------------------------------------------------ 변경 도구 (사람 승인 필요 — 팩 계약 writeTools)
    @mcp.tool(annotations=WRITE, meta={"approvalRequired": True})
    async def create_cross_process_check_request(approvalId: ApprovalId, idempotencyKey: IdemKey) -> dict:
        """승인된 제안에 저장된 '다른 공정 담당자 확인 요청'을 그대로 등록한다. 새 대상 · 질문을 받지 않는다."""
        return await backend.call("POST", "/internal/approved-actions/check-request",
                                  json={"approvalId": approvalId, "idempotencyKey": idempotencyKey})

    @mcp.tool(annotations=WRITE, meta={"approvalRequired": True})
    async def create_maintenance_request(approvalId: ApprovalId, idempotencyKey: IdemKey) -> dict:
        """승인된 제안에 저장된 '같은 공정 설비 점검(정비) 요청'을 그대로 등록한다. 장비 설정은 바꾸지 않는다."""
        return await backend.call("POST", "/internal/approved-actions/maintenance-request",
                                  json={"approvalId": approvalId, "idempotencyKey": idempotencyKey})

    @mcp.tool(annotations=WRITE, meta={"approvalRequired": True})
    async def confirm_report(approvalId: ApprovalId, idempotencyKey: IdemKey) -> dict:
        """승인된 특정 보고서 버전을 확정한다. 초안 생성 · 사건 종료는 하지 않는다. 후속 확인 결과 없이 해결로 확정하지 않는다."""
        return await backend.call("POST", "/internal/approved-actions/report-confirmation",
                                  json={"approvalId": approvalId, "idempotencyKey": idempotencyKey})

    @mcp.custom_route("/healthz", methods=["GET"])
    async def healthz(request):
        from starlette.responses import JSONResponse

        return JSONResponse({"status": "ok"})

    return mcp


def main() -> None:
    from fab.common.env import load_env

    load_env()
    build_server().run(transport="http", host="0.0.0.0", port=int(os.getenv("PORT", "9101")), path="/mcp")


if __name__ == "__main__":
    main()

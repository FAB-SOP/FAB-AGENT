"""required_evidence — 반드시 있어야 하는 근거를 Harness가 직접 조회한다(모델 판단에 맡기지 않음)."""

from __future__ import annotations

import uuid
import os

from langchain_core.messages import AIMessage, AnyMessage, ToolMessage

from fab.agent.graph.state import AgentState, record_evidence, runtime
from fab.agent.harness import context
from fab.agent.harness.retry import TransientJobError
from fab.agent.tools import ToolCallError


class _EvidenceFetcher:
    """필수 근거 도구를 부르고, 호출 · 결과 메시지와 근거 · 데이터 버전을 모은다."""

    def __init__(self, rt, data_version):
        self.rt = rt
        self.messages: list[AnyMessage] = []
        self.evidence: dict = {}
        self.data_version = data_version

    async def run(self, name, args):
        call_id = f"req_{uuid.uuid4().hex[:8]}"
        self.messages.append(AIMessage(content="(Harness가 필수 근거를 조회합니다)",
                                       tool_calls=[{"id": call_id, "name": name, "args": args}]))
        try:
            result = await self.rt.toolbox.call(name, args, self.rt.tracer)
            evidence, data_version = record_evidence(result)
            self.evidence.update(evidence)
            self.data_version = data_version or self.data_version
            self.messages.append(ToolMessage(content=context.to_tool_text(result), tool_call_id=call_id, name=name))
            return result
        except ToolCallError as e:
            if e.retryable:  # 필수 근거가 일시 장애면 작업 단위 재시도(RETRY_SCHEDULED)로 넘긴다
                raise TransientJobError(e.text) from e
            self.messages.append(ToolMessage(content=e.text, tool_call_id=call_id, name=name))
            return None


def _first_plan(state: AgentState, incident_id: str, legacy: bool) -> list[tuple[str, dict]]:
    if state["mode"] == "REPORT":
        plan = [("get_report_context", {"incidentId": incident_id})]
    else:
        already = {message.name for message in state.get("messages", []) if isinstance(message, ToolMessage)}
        if "get_wafer_lineage" in already:  # 센서 근거는 재사용하되 업무 상태는 매 작업 새로 조회한다.
            plan = [("get_work_policy", {}), ("get_work_items", {"incidentId": incident_id}),
                    ("get_check_requests", {"incidentId": incident_id})]
        else:
            plan = [("get_incident", {"incidentId": incident_id})]
    if legacy:
        plan = [(name, args) for name, args in plan if name != "get_check_requests"]
    return plan


async def _fetch_route_evidence(fetcher: _EvidenceFetcher, data: dict) -> None:
    """웨이퍼 실제 경로 → 단계별 run · 계측 → 현재 공정 run과 정상 변동 밖 앞 공정 run의 센서 특징"""
    wafers = data.get("waferIds") or []
    if not wafers:
        return
    lineage = await fetcher.run("get_wafer_lineage", {"waferId": wafers[0]})
    steps = ((lineage or {}).get("data") or {}).get("steps") or []
    outside = []
    for step in steps:
        run_result = await fetcher.run("get_run_and_metrology", {"runId": step["runId"]})
        if ((run_result or {}).get("data") or {}).get("amountWithinNormalVariation") is False:
            outside.append(step)
    current = next((step for step in steps if step["processCode"] == data.get("processCode")), None)
    if current:
        await fetcher.run("get_run_features", {"runId": current["runId"]})
    # 제거 · 증착량이 정상 변동 밖인 앞 공정 run은 센서 특징까지 본다 — 센서가 정상이면 센서 태그 없는 원인
    # (예: ETCH_WALL)과 센서 원인(ETCH_RF 등)을 가를 근거가 된다
    for step in outside:
        if step is not current:
            await fetcher.run("get_run_features", {"runId": step["runId"]})


async def _fetch_work_state(fetcher: _EvidenceFetcher, incident_id: str, legacy: bool) -> None:
    await fetcher.run("get_work_policy", {})
    await fetcher.run("get_work_items", {"incidentId": incident_id})
    if not legacy:
        await fetcher.run("get_check_requests", {"incidentId": incident_id})


async def required_evidence(state: AgentState, config) -> dict:
    """반드시 있어야 하는 근거는 Harness가 직접 조회한다(모델 판단에 맡기지 않음).
    팩 계약 readSequence: 사건 → 웨이퍼 실제 경로 → 단계별 run · 계측 · 제거량 → 현재 공정 run의 센서 특징
    (+ 제거 · 증착량이 정상 변동 밖인 앞 공정 run의 센서 특징)."""
    incident_id = state["incidentId"]
    legacy = os.getenv("FAB_LEGACY_REQUEST_PROPOSALS", "0") == "1"
    plan = _first_plan(state, incident_id, legacy)
    fetcher = _EvidenceFetcher(runtime(config), state.get("dataVersion"))
    incident_process = None
    for name, args in plan:
        last_result = await fetcher.run(name, args)
    if state["mode"] != "REPORT" and last_result:
        data = last_result.get("data") or {}
        incident_process = data.get("processCode")
        await _fetch_route_evidence(fetcher, data)
    if state["mode"] != "REPORT" and plan[0][0] == "get_incident":
        await _fetch_work_state(fetcher, incident_id, legacy)
    out = {"messages": fetcher.messages, "evidence": fetcher.evidence, "dataVersion": fetcher.data_version}
    if incident_process:
        out["incidentProcess"] = incident_process
    return out

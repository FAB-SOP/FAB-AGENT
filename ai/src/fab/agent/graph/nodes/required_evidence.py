"""required_evidence — 반드시 있어야 하는 근거를 Harness가 직접 조회한다(모델 판단에 맡기지 않음)."""

from __future__ import annotations

import uuid

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

    async def run(self, name, args, optional: bool = False):
        """optional — 없어도 조사를 이어 갈 수 있는 근거(점검 문서 검색). 일시 장애여도 작업을 재시도로 넘기지 않는다"""
        call_id = f"req_{uuid.uuid4().hex[:8]}"
        self.messages.append(AIMessage(content="(Harness가 필수 근거를 조회합니다)",
                                       tool_calls=[{"id": call_id, "name": name, "args": args}]))
        try:
            result = await self.rt.toolbox.call(name, args, self.rt.tracer)
            evidence, data_version = record_evidence(result)
            self.evidence.update(evidence)
            self.data_version = data_version or self.data_version
            self.messages.append(ToolMessage(content=context.to_tool_text(result, name), tool_call_id=call_id, name=name))
            return result
        except ToolCallError as e:
            if e.retryable and not optional:  # 필수 근거가 일시 장애면 작업 단위 재시도(RETRY_SCHEDULED)로 넘긴다
                raise TransientJobError(e.text) from e
            self.messages.append(ToolMessage(content=e.text, tool_call_id=call_id, name=name))
            return None


def _first_plan(state: AgentState, incident_id: str) -> list[tuple[str, dict]] | None:
    """처음 부를 도구 — 같은 thread의 다음 작업이면 이미 조회했으므로 None"""
    if state["mode"] == "REPORT":
        return [("get_report_context", {"incidentId": incident_id})]
    already = {message.name for message in state.get("messages", []) if isinstance(message, ToolMessage)}
    if "get_wafer_lineage" in already:  # 같은 thread의 다음 작업 — 이미 조회했다
        return None
    return [("get_incident", {"incidentId": incident_id})]


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
    current_sensors = []
    if current:
        current_features = await fetcher.run("get_run_features", {"runId": current["runId"]})
        current_sensors = _outside_sensors(current_features)
    # 제거 · 증착량이 정상 변동 밖인 앞 공정 run은 센서 특징까지 본다 — 센서가 정상이면 센서 태그 없는 원인
    # (예: ETCH_WALL)과 센서 원인(ETCH_RF 등)을 가를 근거가 된다
    for step in outside:
        if step is not current:
            await fetcher.run("get_run_features", {"runId": step["runId"]})
    await _fetch_playbooks(fetcher, current, current_sensors, outside)


def _outside_sensors(features: dict | None) -> list[str]:
    return [item["sensorId"] for item in ((features or {}).get("data") or {}).get("items") or []
            if item.get("outsideReferenceBand") and item.get("sensorId")]


async def _fetch_playbooks(fetcher: _EvidenceFetcher, current: dict | None, current_sensors: list[str], outside: list[dict]) -> None:
    """정상 범위 밖 공정의 점검 문서 — 원인 후보의 원인 ID · 다음 확인이 문서 근거를 갖게 늘 대화에 넣는다.
    실제 모델은 업무 근거가 다 있으면 지식 검색 없이 바로 제출했다(기본 분석 12건 중 검색 0건). 현재 공정이 이상이면
    그 공정 · 첫 이탈 센서로, 아니면 정상 변동 밖인 앞 공정으로 찾는다. 셋 다 정상이면(관찰) 찾지 않는다"""
    if current and (current in outside or current_sensors):
        process, focus = current["processCode"], current_sensors[0] if current_sensors else "제거량 감소"
        query = f"{process} {focus} 확인 점검"
    else:
        upstream = next((step for step in outside if step is not current), None)
        if upstream is None:
            return
        process = upstream["processCode"]
        query = f"{process} 제거량 변화 챔버 세정 확인"
    await fetcher.run("search_applicable_playbooks", {"query": query, "process": process, "topK": 5}, optional=True)


async def required_evidence(state: AgentState, config) -> dict:
    """반드시 있어야 하는 근거는 Harness가 직접 조회한다(모델 판단에 맡기지 않음).
    팩 계약 readSequence: 사건 → 웨이퍼 실제 경로 → 단계별 run · 계측 · 제거량 → 현재 공정 run의 센서 특징
    (+ 제거 · 증착량이 정상 변동 밖인 앞 공정 run의 센서 특징)."""
    incident_id = state["incidentId"]
    plan = _first_plan(state, incident_id)
    if plan is None:
        return {}
    fetcher = _EvidenceFetcher(runtime(config), state.get("dataVersion"))
    incident_process = None
    for name, args in plan:
        last_result = await fetcher.run(name, args)
    if state["mode"] != "REPORT" and last_result:
        data = last_result.get("data") or {}
        incident_process = data.get("processCode")
        await _fetch_route_evidence(fetcher, data)
    out ={"messages": fetcher.messages, "evidence": fetcher.evidence, "dataVersion": fetcher.data_version}
    if incident_process:
        out["incidentProcess"] = incident_process
    return out

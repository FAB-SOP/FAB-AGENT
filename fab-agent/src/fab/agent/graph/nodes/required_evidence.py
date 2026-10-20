"""required_evidence — 반드시 있어야 하는 근거를 Harness가 직접 조회한다(모델 판단에 맡기지 않음)."""

from __future__ import annotations

import uuid

from langchain_core.messages import AIMessage, AnyMessage, ToolMessage

from fab.agent.graph.state import AgentState, record_evidence, runtime
from fab.agent.harness import context
from fab.agent.harness.retry import TransientJobError
from fab.agent.tools import ToolCallError


async def required_evidence(state: AgentState, config) -> dict:
    """반드시 있어야 하는 근거는 Harness가 직접 조회한다(모델 판단에 맡기지 않음).
    팩 계약 readSequence: 사건 → 웨이퍼 실제 경로 → 단계별 run · 계측 · 제거량 → 현재 공정 run의 센서 특징
    (+ 제거 · 증착량이 정상 변동 밖인 앞 공정 run의 센서 특징)."""
    rt = runtime(config)
    inc = state["incidentId"]
    if state["mode"] == "REPORT":
        plan = [("get_report_context", {"incidentId": inc})]
    else:
        already = {m.name for m in state.get("messages", []) if isinstance(m, ToolMessage)}
        if "get_wafer_lineage" in already:  # 같은 thread의 다음 작업 — 이미 조회했다
            return {}
        plan = [("get_incident", {"incidentId": inc})]
    msgs: list[AnyMessage] = []
    evidence, dv = {}, state.get("dataVersion")

    async def run(name, args):
        nonlocal dv
        cid = f"req_{uuid.uuid4().hex[:8]}"
        msgs.append(AIMessage(content="(Harness가 필수 근거를 조회합니다)", tool_calls=[{"id": cid, "name": name, "args": args}]))
        try:
            res = await rt.toolbox.call(name, args, rt.tracer)
            ev, dv2 = record_evidence(res)
            evidence.update(ev)
            dv = dv2 or dv
            msgs.append(ToolMessage(content=context.to_tool_text(res), tool_call_id=cid, name=name))
            return res
        except ToolCallError as e:
            if e.retryable:  # 필수 근거가 일시 장애면 작업 단위 재시도(RETRY_SCHEDULED)로 넘긴다
                raise TransientJobError(e.text) from e
            msgs.append(ToolMessage(content=e.text, tool_call_id=cid, name=name))
            return None

    incident_process = None
    for name, args in plan:
        res = await run(name, args)
    if state["mode"] != "REPORT" and res:
        data = res.get("data") or {}
        incident_process = data.get("processCode")
        wafers = data.get("waferIds") or []
        if wafers:
            lin = await run("get_wafer_lineage", {"waferId": wafers[0]})
            steps = ((lin or {}).get("data") or {}).get("steps") or []
            outside = []
            for st in steps:
                r = await run("get_run_and_metrology", {"runId": st["runId"]})
                if ((r or {}).get("data") or {}).get("amountWithinNormalVariation") is False:
                    outside.append(st)
            cur = next((st for st in steps if st["processCode"] == data.get("processCode")), None)
            if cur:
                await run("get_run_features", {"runId": cur["runId"]})
            # 제거 · 증착량이 정상 변동 밖인 앞 공정 run은 센서 특징까지 본다 — 센서가 정상이면 센서 태그 없는 원인
            # (예: ETCH_WALL)과 센서 원인(ETCH_RF 등)을 가를 근거가 된다
            for st in outside:
                if st is not cur:
                    await run("get_run_features", {"runId": st["runId"]})
    out = {"messages": msgs, "evidence": evidence, "dataVersion": dv}
    if incident_process:
        out["incidentProcess"] = incident_process
    return out

"""start — 모드 지시 · 질문을 대화에 넣고 이번 작업의 한도 창을 연다."""

from __future__ import annotations

from langchain_core.messages import AnyMessage, HumanMessage, SystemMessage

from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import instructions, limits


async def start(state: AgentState, config) -> dict:
    text, ver = instructions.load(state["mode"])
    msgs: list[AnyMessage] = []
    if not state.get("messages"):
        msgs.append(SystemMessage(content=text))
    else:  # 같은 사건 thread의 다음 작업 — 모드 지시만 덧붙인다
        msgs.append(HumanMessage(content="[모드 지시]\n" + instructions.mode_text(state["mode"])))
    q = state.get("question") or {
        "BASIC": "이 이상 사건의 기본 분석을 해 주세요.",
        "REPORT": "저장된 기록으로 보고서 초안을 만들어 주세요.",
    }.get(state["mode"], "추가 조사를 해 주세요.")
    msgs.append(HumanMessage(content=f"[작업 {state['jobId']} · 모드 {state['mode']} · 사건 {state['incidentId']}]\n{q}"))
    runtime(config).tracer.event("node", node="start", mode=state["mode"], promptVersion=ver)
    return {"messages": msgs, "counters": limits.new_window(), "result": None, "analysis": None, "report": None,
            "stopReason": None, "promptVersion": ver}

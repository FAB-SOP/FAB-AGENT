"""그래프 갈림길 — 다음 노드 이름을 고른다."""

from __future__ import annotations

from langchain_core.messages import HumanMessage

from fab.agent.graph.state import AgentState
from fab.agent.tools import PROPOSALS


def route_agent(state: AgentState) -> str:
    if state.get("stopReason"):
        return "finalize"
    last = state["messages"][-1]
    if isinstance(last, HumanMessage):  # nudge
        return "agent"
    if any(tool_call["name"] in PROPOSALS for tool_call in getattr(last, "tool_calls", None) or []):
        return "request_brief"
    return "tools"


def route_tools(state: AgentState) -> str:
    if state.get("pending") and state["pending"]["kind"] == "APPROVAL":
        return "approval_wait"
    if state.get("result") or state.get("report"):
        return "finalize"
    return "agent"


def route_approval(state: AgentState) -> str:
    return "reply_wait" if state.get("pending") else "agent"

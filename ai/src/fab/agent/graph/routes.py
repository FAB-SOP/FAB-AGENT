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
    if state.get("retryProposal"):  # 답은 접수됐지만 요청서가 고칠 수 있는 이유로 반려됨 — 한 번은 다시 고칠 기회
        return "agent"
    if state.get("result") or state.get("report"):
        return "finalize"
    return "agent"


def route_approval(state: AgentState) -> str:
    """승인 뒤 — 회신 대기(WAIT 흐름), 요청 발송 뒤 정리(MANUAL 흐름: 답이 이미 있다), 거절이면 다시 정리"""
    if state.get("pending"):
        return "reply_wait"
    return "finalize" if state.get("requestSent") and state.get("result") else "agent"

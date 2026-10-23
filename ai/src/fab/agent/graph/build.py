"""그래프 조립 — 노드 · 간선. 노드 이름은 체크포인트에 저장되므로 바꾸지 않는다."""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from fab.agent.graph.nodes import agent, approval_wait, finalize, reply_wait, request_brief, required_evidence, start, tools
from fab.agent.graph.routes import route_agent, route_approval, route_tools
from fab.agent.graph.state import AgentState


def build_graph(checkpointer):
    graph = StateGraph(AgentState)
    for name, node_function in [("start", start), ("required_evidence", required_evidence), ("agent", agent),
                                ("request_brief", request_brief), ("tools", tools), ("approval_wait", approval_wait),
                                ("reply_wait", reply_wait), ("finalize", finalize)]:
        graph.add_node(name, node_function)
    graph.add_edge(START, "start")
    graph.add_edge("start", "required_evidence")
    graph.add_edge("required_evidence", "agent")
    graph.add_conditional_edges("agent", route_agent, ["request_brief", "tools", "finalize", "agent"])
    graph.add_edge("request_brief", "tools")
    graph.add_conditional_edges("tools", route_tools, ["approval_wait", "finalize", "agent"])
    graph.add_conditional_edges("approval_wait", route_approval, ["reply_wait", "finalize", "agent"])
    graph.add_edge("reply_wait", "agent")
    graph.add_edge("finalize", END)
    return graph.compile(checkpointer=checkpointer)

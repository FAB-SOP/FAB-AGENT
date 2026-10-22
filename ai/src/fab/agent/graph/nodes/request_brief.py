"""request_brief — 제안 저장 전에 받는 사람이 볼 요청 브리프를 도구 결과에서 만든다(승인 해시에 들어간다)."""

from __future__ import annotations

from langchain_core.messages import AIMessage

from fab.agent import brief as request_briefs
from fab.agent.evidence import tool_text
from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import guard
from fab.agent.tools import PROPOSALS


async def request_brief(state: AgentState, config) -> dict:
    """제안 도구 호출마다 요청 브리프를 만든다 — 숫자 · 원인 ID · 근거는 도구 결과에서, 모델은 reason 문장만"""
    job_runtime = runtime(config)
    reply: AIMessage = state["messages"][-1]
    known = set(state.get("evidence", {}))
    text = tool_text(state["messages"])
    briefs = {}
    for tool_call in reply.tool_calls:
        if tool_call["name"] not in PROPOSALS:
            continue
        brief = request_briefs.build(state["messages"], tool_call["args"], PROPOSALS[tool_call["name"]][1])
        brief_errors = guard.validate_brief(brief, known, text)
        briefs[tool_call["id"]] = {"errors": brief_errors} if brief_errors else {"brief": brief}
        job_runtime.tracer.event("brief", toolCallId=tool_call["id"], ok=not brief_errors, deviations=len(brief["deviations"]),
                                 causes=[cause["causeId"] for cause in brief["suspectedCauses"]], errors=brief_errors[:3])
    return {"briefs": briefs}

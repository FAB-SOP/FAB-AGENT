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
    rt = runtime(config)
    ai: AIMessage = state["messages"][-1]
    known = set(state.get("evidence", {}))
    text = tool_text(state["messages"])
    out = {}
    for tc in ai.tool_calls:
        if tc["name"] not in PROPOSALS:
            continue
        b = request_briefs.build(state["messages"], tc["args"], PROPOSALS[tc["name"]][1])
        errs = guard.validate_brief(b, known, text)
        out[tc["id"]] = {"errors": errs} if errs else {"brief": b}
        rt.tracer.event("brief", toolCallId=tc["id"], ok=not errs, deviations=len(b["deviations"]),
                        causes=[c["causeId"] for c in b["suspectedCauses"]], errors=errs[:3])
    return {"briefs": out}

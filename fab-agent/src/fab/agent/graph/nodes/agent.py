"""agent — 모델이 다음 행동(도구 호출)을 고른다. 한도를 넘으면 멈춘다."""

from __future__ import annotations

from langchain_core.messages import HumanMessage

from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import context, limits
from fab.agent.harness.retry import call_with_recovery


async def agent(state: AgentState, config) -> dict:
    job_runtime = runtime(config)
    counters = dict(state["counters"])
    reason = limits.check(counters)
    if reason:
        job_runtime.tracer.event("limit", reason=reason)
        return {"stopReason": reason}
    messages = context.fit_budget(state["messages"])
    tools = job_runtime.toolbox.model_tools(state["mode"])
    with job_runtime.tracer.span("llm", node="agent") as span_record:
        reply, used = await call_with_recovery(job_runtime.primary, job_runtime.fallback, messages, tools, job_runtime.tracer)
        usage = getattr(reply, "usage_metadata", None) or {}
        name = getattr(job_runtime.primary if used == "primary" else job_runtime.fallback, "name", used)  # 우리가 고른 모델
        # API가 실제로 답한 모델(스냅샷 이름) — API를 부르지 않는 규칙 모델은 response_metadata가 비어 있어 고른 이름을 쓴다
        meta = getattr(reply, "response_metadata", None) or {}
        span_record.update(model=used, modelName=name, apiModel=meta.get("model_name") or name,
                           tokens=usage.get("total_tokens", 0), inputTokens=usage.get("input_tokens", 0),
                           outputTokens=usage.get("output_tokens", 0),
                           reasoningTokens=(usage.get("output_token_details") or {}).get("reasoning", 0))
    counters["modelCalls"] += 1
    if not reply.tool_calls:  # 제출 없이 말로 끝내려 하면 제출 도구를 쓰라고 되돌린다
        nudge = HumanMessage(content="[Harness] 결과는 반드시 submit_result(또는 submit_report)로 제출하세요.")
        return {"messages": [reply, nudge], "counters": counters}
    return {"messages": [reply], "counters": counters}

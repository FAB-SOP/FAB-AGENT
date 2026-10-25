"""agent — 모델이 다음 행동(도구 호출)을 고른다. 한도를 넘으면 멈춘다."""

from __future__ import annotations

import uuid

from langchain_core.messages import AIMessage, HumanMessage

from fab.agent import auto_request
from fab.agent.evidence import tool_results
from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import context, limits
from fab.agent.harness.retry import call_with_recovery
from fab.agent.purpose import may_propose


LAST_CALL = ("[Harness] 이번이 이 작업의 마지막 판단입니다. 더 조회하지 말고 지금까지 조회한 근거로 submit_result를 내세요 — "
             "근거가 모자라면 nextStep을 INSUFFICIENT_EVIDENCE로, 확인하지 못한 것은 한계(limitations)에 적습니다.")


def _last_call(state: AgentState, counters: dict) -> bool:
    """판단 한도의 마지막 호출이고 아직 접수된 답이 없나 — 실제 모델은 조회를 거듭하다 한도에 닿아 빈 결과로 끝나곤 했다.
    답이 이미 있으면(요청서 재시도 등) 도구를 줄이지 않는다"""
    return counters["modelCalls"] == limits.LIMITS.max_model_calls - 1 and not state.get("result") and state["mode"] != "REPORT"


def _drafted_request(state: AgentState, counters: dict, tracer) -> dict:
    """원인 질문의 답에 요청서가 없다 — 모델을 부르지 않고 Harness가 담당자 조회 · 요청서 초안을 낸다(fab.agent.auto_request).
    담당자 조회 한 번 뒤에도 초안을 못 만들면 그만둔다(답만으로 끝난다)"""
    result = state.get("result") or {}
    top = min(result.get("candidateCauses") or [{}], key=lambda cause: cause.get("priority") or 99) or None
    call = auto_request.draft(tool_results(state["messages"], keep_text=True), state.get("incidentProcess"),
                              result.get("nextStep"), top, set(state.get("evidence") or {}))
    steps = counters.get("autoRequestSteps", 0)
    if call is None or steps >= 2 or (call[0] == "get_process_owner" and steps >= 1):
        tracer.event("auto_request", drafted=False, step=call[0] if call else None)
        return {"autoRequest": None, "counters": counters}
    counters["autoRequestSteps"] = steps + 1
    tracer.event("auto_request", drafted=call[0] != "get_process_owner", step=call[0])
    message = AIMessage(content="(Harness가 근거 있는 요청서 초안을 냅니다)",
                        tool_calls=[{"id": f"auto_{uuid.uuid4().hex[:8]}", "name": call[0], "args": call[1]}])
    return {"messages": [message], "counters": counters, "autoRequest": call[0] == "get_process_owner" or None}


async def agent(state: AgentState, config) -> dict:
    job_runtime = runtime(config)
    counters = dict(state["counters"])
    if state.get("autoRequest"):
        return _drafted_request(state, counters, job_runtime.tracer)
    reason = limits.check(counters)
    if reason:
        job_runtime.tracer.event("limit", reason=reason)
        return {"stopReason": reason}
    messages = context.fit_budget(state["messages"])
    tools = job_runtime.toolbox.model_tools(state["mode"], allow_proposals=may_propose(state),
                                            answer_only=bool(state.get("answerOnly")))
    tool_choice = None
    if _last_call(state, counters):  # 한도 직전 — 조회 대신 지금 근거로 답하게(빈 결과로 멈추지 않게)
        job_runtime.tracer.event("last_call", modelCalls=counters["modelCalls"])
        tools = [tool for tool in tools if tool["function"]["name"] in ("submit_result", "submit_report")]
        messages = [*messages, HumanMessage(content=LAST_CALL)]
        tool_choice = "submit_result"  # 도구 목록만 줄이면 실제 모델은 제출하지 않고 글로 답하곤 했다(실제 모델 평가)
    with job_runtime.tracer.span("llm", node="agent") as span_record:
        reply, used = await call_with_recovery(job_runtime.primary, job_runtime.fallback, messages, tools, job_runtime.tracer,
                                                tool_choice=tool_choice)
        usage = getattr(reply, "usage_metadata", None) or {}
        name = getattr(job_runtime.primary if used == "primary" else job_runtime.fallback, "name", used)  # 우리가 고른 모델
        # API가 실제로 답한 모델(스냅샷 이름) — API를 부르지 않는 규칙 모델은 response_metadata가 비어 있어 고른 이름을 쓴다
        meta = getattr(reply, "response_metadata", None) or {}
        span_record.update(model=used, modelName=name, apiModel=meta.get("model_name") or name,
                           tokens=usage.get("total_tokens", 0), inputTokens=usage.get("input_tokens", 0),
                           outputTokens=usage.get("output_tokens", 0),
                           reasoningTokens=(usage.get("output_token_details") or {}).get("reasoning", 0))
    counters["modelCalls"] += 1
    if not reply.tool_calls and state.get("result"):  # 답은 이미 접수됐다(요청서 알림에 말로 「필요 없다」고 답한 경우) — 그대로 끝낸다
        return {"messages": [reply], "counters": counters}
    if not reply.tool_calls:  # 왜 도구 호출이 없나 — 마지막 호출(제출 강제) 뒤에도 제출 없이 끝나는 일이 있었다
        job_runtime.tracer.event("no_tool_call", lastCall=tool_choice is not None, textChars=len(str(reply.content or "")),
                                 invalidToolCalls=len(getattr(reply, "invalid_tool_calls", None) or []))
    if not reply.tool_calls:  # 제출 없이 말로 끝내려 하면 제출 도구를 쓰라고 되돌린다
        nudge = HumanMessage(content="[Harness] 결과는 반드시 submit_result(또는 submit_report)로 제출하세요.")
        return {"messages": [reply, nudge], "counters": counters}
    return {"messages": [reply], "counters": counters}

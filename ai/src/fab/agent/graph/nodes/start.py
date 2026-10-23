"""start — 모드 지시 · 질문을 대화에 넣고 이번 작업의 한도 창을 연다."""

from __future__ import annotations

from langchain_core.messages import AnyMessage, HumanMessage, SystemMessage

from fab.agent import followup, report
from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import instructions, limits


async def start(state: AgentState, config) -> dict:
    text, prompt_version = instructions.load(state["mode"])
    messages: list[AnyMessage] = []
    if not state.get("messages"):
        messages.append(SystemMessage(content=text))
    else:  # 같은 사건 thread의 다음 작업 — 모드 지시만 덧붙인다
        messages.append(HumanMessage(content="[모드 지시]\n" + instructions.mode_text(state["mode"])))
    question = state.get("question") or {
        "BASIC": "이 이상 사건의 기본 분석을 해 주세요.",
        "REPORT": "저장된 기록으로 보고서 초안을 만들어 주세요.",
    }.get(state["mode"], "추가 조사를 해 주세요.")
    messages.append(HumanMessage(content=f"[작업 {state['jobId']} · 모드 {state['mode']} · 사건 {state['incidentId']}]\n{question}"))
    if state.get("lockedSections"):  # 보고서 다시 만들기 — 사람이 고친 칸은 쓰지 않는다
        messages.append(HumanMessage(content=report.note(state["lockedSections"])))
    request = state.get("requestInput") or {}
    if request:  # 사람이 연 요청서 작업 — 무엇을 만들지(재생성이면 대체할 승인)
        messages.append(HumanMessage(content="[요청서 작업] " + ", ".join(
            f"{label} {request[key]}" for key, label in (("targetProcessCode", "대상 공정"), ("causeIds", "원인 ID"),
                                                        ("supersedesApprovalId", "대체할 승인")) if request.get(key))))
    selected = state.get("selectedInputs") or []
    if selected:  # 추가 분석 — 작업자가 고른 결과를 진술 근거로 넣는다(인용할 수 있게 근거로 등록)
        messages.append(HumanMessage(content=followup.note(selected)))
    runtime(config).tracer.event("node", node="start", mode=state["mode"], promptVersion=prompt_version)
    return {"messages": messages, "counters": limits.new_window(), "result": None, "previousResult": None, "requestSent": None, "analysis": None, "report": None, "conclusion": None,
            "stopReason": None, "promptVersion": prompt_version,
            **({"evidence": followup.evidence_of(selected, state.get("dataVersion"))} if selected else {})}

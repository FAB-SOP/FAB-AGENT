"""tools — 모델이 고른 도구 호출을 처리한다. 호출마다 셋 중 하나로 보낸다:
제출 처리(submit_result · submit_report) / 제안 저장(propose_* — 정합성 검사 뒤 사람 승인 대기) / 도구 실행(업무 · 지식 MCP 읽기).
도구 한도 · Harness 도구의 모드 제한은 고르기 전에 본다.
"""

from __future__ import annotations

import uuid
from typing import Any

from langchain_core.messages import AIMessage, AnyMessage, ToolMessage

from fab.agent.evidence import tool_results
from fab.agent.graph.state import AgentState, current_job, record_evidence, runtime
from fab.agent.harness import context, guard, limits
from fab.agent.harness.retry import TransientJobError
from fab.agent.purpose import may_propose
from fab.agent.tools import HARNESS_NAMES, PROPOSALS, ToolCallError


class _Turn:
    """도구 호출 한 묶음의 누적 — 도구 메시지 · 새 근거 · 데이터 버전 · 상태 갱신"""

    def __init__(self, state: AgentState, config):
        self.state, self.config, self.rt = state, config, runtime(config)
        self.counters = dict(state["counters"])
        self.counters["toolCalls"] = dict(self.counters["toolCalls"])
        self.out: list[AnyMessage] = []
        self.evidence: dict = {}
        self.dv = state.get("dataVersion")
        self.update: dict[str, Any] = {}
        self.known = set(state.get("evidence", {}))

    def known_ids(self) -> set[str]:
        """이번 작업에서 조회한 근거 — 앞선 노드에서 모은 것 + 이 묶음에서 방금 받은 것"""
        return self.known | set(self.evidence)

    def say(self, text: str, cid: str, name: str) -> None:
        self.out.append(ToolMessage(content=text, tool_call_id=cid, name=name))

    def result(self) -> dict:
        return {"messages": self.out, "evidence": self.evidence, "dataVersion": self.dv, "counters": self.counters,
                **self.update}


def _submits_first(calls: list[dict]) -> list[dict]:
    """답 먼저 — 한 응답 안에서는 제출(submit_*)을 먼저 처리해, 같은 응답의 제안이 그 답을 보고 접수되게 한다"""
    return sorted(calls, key=lambda call: call["name"] not in ("submit_result", "submit_report"))


async def tools(state: AgentState, config) -> dict:
    turn = _Turn(state, config)
    reply: AIMessage = state["messages"][-1]
    for call in _submits_first(reply.tool_calls):
        name, args, cid = call["name"], call["args"], call["id"]
        turn.counters["toolCalls"][name] = turn.counters["toolCalls"].get(name, 0) + 1
        if not limits.tool_allowed(state["counters"], name):
            turn.say(f"[LIMIT] {name}는 이번 작업에서 {limits.LIMITS.max_same_tool}회까지만 부를 수 있습니다. "
                     "지금 근거로 정리하세요.", cid, name)
        elif name in ("submit_result", "submit_report"):
            handle_submit(turn, name, args, cid)
        elif name in PROPOSALS:
            await save_proposal(turn, name, args, cid)
        elif name in HARNESS_NAMES:
            turn.say(f"[FORBIDDEN] {name}는 이 모드에서 쓸 수 없습니다.", cid, name)
        else:
            await run_tool(turn, name, args, cid)
    order = {call["id"]: i for i, call in enumerate(reply.tool_calls)}
    turn.out.sort(key=lambda message: order.get(message.tool_call_id, len(order)))  # 도구 메시지는 원래 호출 순서로
    return turn.result()


def handle_submit(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """제출 처리 — 결과는 guard가 검사하고 통과해야 접수한다. 보고서는 그대로 접수한다(finalize가 저장)"""
    state = turn.state
    if name == "submit_report":
        turn.say("[ACCEPTED] 보고서 초안을 접수했습니다.", cid, name)
        turn.update["report"] = args
        return
    errs = guard.validate_result(args, turn.known_ids(), state.get("incidentProcess"))
    if errs:
        turn.rt.tracer.event("guard_reject", tool=name, errors=errs)
        turn.say("[REJECTED] " + " / ".join(errs), cid, name)
    else:
        turn.say("[ACCEPTED] 결과를 접수했습니다.", cid, name)
        turn.update["result"] = args


def _proposal_rejection(turn: _Turn, name: str, args: dict, cid: str, action_type: str, made_brief: dict) -> str | None:
    """제안 검사(모드 · 근거 · 브리프 · 한 번에 하나 · 상한 · 중복) — 걸리면 모델에 돌려줄 문장, 통과면 None"""
    state, job_runtime = turn.state, turn.rt
    if not may_propose(state):
        return ("[FORBIDDEN] 이 작업에서는 요청을 제안할 수 없습니다 — 원인 분석은 nextStep으로 판단만 남기고, "
                "승인 대기 중인 요청서가 있으면 질문에만 답합니다.")
    if not (state.get("result") or turn.update.get("result")):  # 답 먼저 — 사람은 답을 보고 승인을 정한다
        return ("[REJECTED] 먼저 submit_result로 답을 제출하세요. 요청 제안은 답이 접수된 뒤에만 받습니다 — "
                "같은 응답에서 submit_result와 함께 호출하면 됩니다.")
    unknown = set(args.get("evidenceIds", [])) - turn.known_ids()
    if unknown:
        return f"[REJECTED] 조회하지 않은 근거 ID: {sorted(unknown)}"
    errors = guard.validate_proposal(action_type, args, tool_results(state["messages"]), state.get("incidentProcess"))
    if errors:  # 제안 종류 · 대상이 이번 조사의 근거와 맞아야 한다(근거 없는 정비 · 확인 요청을 막는다)
        job_runtime.tracer.event("guard_reject", tool=name, errors=errors)
        return "[REJECTED] " + " / ".join(errors)
    if made_brief.get("errors"):
        return "[REJECTED] 요청 브리프: " + " / ".join(made_brief["errors"])
    if turn.update.get("pending"):  # 한 번에 하나만 승인 대기에 넣는다
        return "[REJECTED] 제안은 한 번에 하나씩 합니다. 이 제안의 승인을 먼저 기다리세요."
    made = [proposal for proposal in state.get("proposals") or [] if isinstance(proposal, dict)]
    if len(state.get("proposals") or []) >= limits.LIMITS.max_proposals:
        return (f"[LIMIT] 이 사건에서 제안은 최대 {limits.LIMITS.max_proposals}개입니다. "
                "더 제안하지 말고 지금 근거로 submit_result를 부르세요.")
    if any(proposal["actionType"] == action_type and proposal["ownerUserId"] == args.get("ownerUserId") for proposal in made):
        return ("[REJECTED] 같은 담당자에게 같은 종류의 요청을 이미 제안했습니다. "
                "회신 · 결정을 근거로 결과를 정리하세요.")
    return None


async def _create_proposal(turn: _Turn, args: dict, cid: str, action_type: str, brief: dict) -> dict:
    state, job_runtime = turn.state, turn.rt
    job_id = current_job(state, turn.config)  # 제안은 지금 대기에 들어갈 작업에 붙인다(회신 뒤 재개 작업 포함)
    return await job_runtime.backend.create_proposal(
        job_id, action_type,
        {"incidentId": state["incidentId"], "ownerUserId": args["ownerUserId"], "question": args["question"],
         "targetProcessCode": args.get("processCode"), "equipmentId": args.get("equipmentId"),
         "causeIds": args.get("causeIds", []), "evidenceIds": args.get("evidenceIds", []),
         "brief": brief},
        args.get("rationale", ""), args.get("evidenceIds", []), job_runtime.actor,
        idempotency_key=f"{job_id}:{cid}")  # 같은 도구 호출을 재시도해도 제안은 하나


async def save_proposal(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """제안 저장 — 검사(모드 · 근거 · 브리프 · 한 번에 하나 · 상한 · 중복)를 통과하면 backend에 저장하고 승인 대기로 둔다"""
    write_tool, action_type = PROPOSALS[name]
    made_brief = (turn.state.get("briefs") or {}).get(cid) or {"errors": ["요청 브리프가 만들어지지 않았습니다."]}
    rejection = _proposal_rejection(turn, name, args, cid, action_type, made_brief)
    if rejection:
        return turn.say(rejection, cid, name)
    try:
        approval = await _create_proposal(turn, args, cid, action_type, made_brief["brief"])
    except Exception as e:  # noqa: BLE001 — backend 검증 실패는 모델이 읽고 고치게
        return turn.say(f"[REJECTED] {e}", cid, name)
    turn.rt.tracer.event("proposal", approvalId=approval["approvalId"], actionType=action_type)
    turn.say(f"[PROPOSED] 승인 {approval['approvalId']}로 저장했습니다. 사람의 승인을 기다립니다. "
             "아직 요청은 등록되지 않았습니다.", cid, name)
    turn.update["pending"] = {"kind": "APPROVAL", "approvalId": approval["approvalId"], "writeTool": write_tool,
                              "approvalVersion": approval["version"], "interruptId": f"INT-APP-{uuid.uuid4().hex[:8]}"}
    turn.update["proposals"] = [{"approvalId": approval["approvalId"], "actionType": action_type,
                                 "ownerUserId": args.get("ownerUserId")}]


async def run_tool(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """도구 실행 — 업무 · 지식 MCP 읽기 도구. 근거와 데이터 버전을 모은다. 일시 장애가 남으면 작업 단위 재시도로 넘긴다"""
    job_runtime = turn.rt
    try:
        result = await job_runtime.toolbox.call(name, args, job_runtime.tracer)
        new_evidence, data_version = record_evidence(result)
        turn.evidence.update(new_evidence)
        turn.dv = data_version or turn.dv
        turn.say(context.to_tool_text(result), cid, name)
    except ToolCallError as e:
        turn.say(e.text, cid, name)
        if e.retryable:  # 재시도 후에도 일시 장애면 작업 단위 재시도로 넘긴다
            raise TransientJobError(e.text) from e

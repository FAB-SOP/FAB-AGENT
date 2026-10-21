"""tools — 모델이 고른 도구 호출을 처리한다. 호출마다 셋 중 하나로 보낸다:
제출 처리(submit_result · submit_report) / 제안 저장(propose_* — 정합성 검사 뒤 사람 승인 대기) / 도구 실행(업무 · 지식 MCP 읽기).
도구 한도 · Harness 도구의 모드 제한은 고르기 전에 본다.
"""

from __future__ import annotations

import uuid
import os
from typing import Any

from langchain_core.messages import AIMessage, AnyMessage, ToolMessage

from fab.agent.evidence import tool_results
from fab.agent.graph.state import AgentState, current_job, record_evidence, runtime
from fab.agent.harness import context, guard, limits
from fab.agent.harness.retry import TransientJobError
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


async def tools(state: AgentState, config) -> dict:
    turn = _Turn(state, config)
    ai: AIMessage = state["messages"][-1]
    for call in ai.tool_calls:
        name, args, cid = call["name"], call["args"], call["id"]
        turn.counters["toolCalls"][name] = turn.counters["toolCalls"].get(name, 0) + 1
        if not limits.tool_allowed(state["counters"], name):
            turn.say(f"[LIMIT] {name}는 이번 작업에서 {limits.LIMITS.max_same_tool}회까지만 부를 수 있습니다. "
                     "지금 근거로 정리하세요.", cid, name)
        elif name in ("submit_result", "submit_report"):
            handle_submit(turn, name, args, cid)
        elif name in PROPOSALS:
            if os.getenv("FAB_LEGACY_REQUEST_PROPOSALS", "0") == "1":
                await save_proposal(turn, name, args, cid)
            else:
                turn.say("[FORBIDDEN] 새 업무는 submit_result.workSuggestions로 제안하세요. 채택 전에는 발송하지 않습니다.", cid, name)
        elif name in HARNESS_NAMES:
            turn.say(f"[FORBIDDEN] {name}는 이 모드에서 쓸 수 없습니다.", cid, name)
        else:
            await run_tool(turn, name, args, cid)
    return turn.result()


def handle_submit(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """제출 처리 — 결과는 guard가 검사하고 통과해야 접수한다. 보고서는 그대로 접수한다(finalize가 저장)"""
    state = turn.state
    if name == "submit_report":
        turn.say("[ACCEPTED] 보고서 초안을 접수했습니다.", cid, name)
        turn.update["report"] = args
        return
    errs = guard.validate_result(args, turn.known_ids(), state.get("incidentProcess"))
    # 심층 조사에서 점검 · 확인이 필요하다고 판단했으면 요청 초안을 먼저 제안한다(팩 E03 — 보낼지는 사람이 승인으로 정한다)
    if (os.getenv("FAB_LEGACY_REQUEST_PROPOSALS", "0") == "1" and state["mode"] == "DEEP" and args.get("nextStep") in ("CURRENT_CHECK", "UPSTREAM_CHECK")
            and not state.get("proposals") and not turn.update.get("pending")):
        kind = "propose_cross_process_check(앞 공정 PROCESS_OWNER)" if args["nextStep"] == "UPSTREAM_CHECK" \
            else "propose_maintenance_request(설비정비팀 MAINTENANCE)"
        errs.append(f"nextStep이 {args['nextStep']}이면 먼저 {kind}로 확인 · 점검 요청 초안을 제안하세요. "
                    "get_process_owner로 받는 사람을 찾고, 보낼지는 사람이 승인으로 정합니다.")
    if errs:
        turn.rt.tracer.event("guard_reject", tool=name, errors=errs)
        turn.say("[REJECTED] " + " / ".join(errs), cid, name)
    else:
        turn.say("[ACCEPTED] 결과를 접수했습니다.", cid, name)
        turn.update["result"] = args


async def save_proposal(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """제안 저장 — 검사(모드 · 근거 · 브리프 · 한 번에 하나 · 상한 · 중복)를 통과하면 backend에 저장하고 승인 대기로 둔다"""
    state, config, rt = turn.state, turn.config, turn.rt
    if state["mode"] != "DEEP":
        return turn.say("[FORBIDDEN] 이 모드에서는 요청을 제안할 수 없습니다.", cid, name)
    unknown = set(args.get("evidenceIds", [])) - turn.known_ids()
    if unknown:
        return turn.say(f"[REJECTED] 조회하지 않은 근거 ID: {sorted(unknown)}", cid, name)
    write_tool, action_type = PROPOSALS[name]
    errs = guard.validate_proposal(action_type, args, tool_results(state["messages"]), state.get("incidentProcess"))
    if errs:  # 제안 종류 · 대상이 이번 조사의 근거와 맞아야 한다(근거 없는 정비 · 확인 요청을 막는다)
        rt.tracer.event("guard_reject", tool=name, errors=errs)
        return turn.say("[REJECTED] " + " / ".join(errs), cid, name)
    made_brief = (state.get("briefs") or {}).get(cid) or {"errors": ["요청 브리프가 만들어지지 않았습니다."]}
    if made_brief.get("errors"):
        return turn.say("[REJECTED] 요청 브리프: " + " / ".join(made_brief["errors"]), cid, name)
    if turn.update.get("pending"):  # 한 번에 하나만 승인 대기에 넣는다
        return turn.say("[REJECTED] 제안은 한 번에 하나씩 합니다. 이 제안의 승인을 먼저 기다리세요.", cid, name)
    made = [p for p in state.get("proposals") or [] if isinstance(p, dict)]
    if len(state.get("proposals") or []) >= limits.LIMITS.max_proposals:
        return turn.say(f"[LIMIT] 이 사건에서 제안은 최대 {limits.LIMITS.max_proposals}개입니다. "
                        "더 제안하지 말고 지금 근거로 submit_result를 부르세요.", cid, name)
    if any(p["actionType"] == action_type and p["ownerUserId"] == args.get("ownerUserId") for p in made):
        return turn.say("[REJECTED] 같은 담당자에게 같은 종류의 요청을 이미 제안했습니다. "
                        "회신 · 결정을 근거로 결과를 정리하세요.", cid, name)
    try:
        job_id = current_job(state, config)  # 제안은 지금 대기에 들어갈 작업에 붙인다(회신 뒤 재개 작업 포함)
        appr = await rt.backend.create_proposal(
            job_id, action_type,
            {"incidentId": state["incidentId"], "ownerUserId": args["ownerUserId"], "question": args["question"],
             "targetProcessCode": args.get("processCode"), "equipmentId": args.get("equipmentId"),
             "causeIds": args.get("causeIds", []), "evidenceIds": args.get("evidenceIds", []),
             "brief": made_brief["brief"]},
            args.get("rationale", ""), args.get("evidenceIds", []), rt.actor,
            idempotency_key=f"{job_id}:{cid}")  # 같은 도구 호출을 재시도해도 제안은 하나
    except Exception as e:  # noqa: BLE001 — backend 검증 실패는 모델이 읽고 고치게
        return turn.say(f"[REJECTED] {e}", cid, name)
    rt.tracer.event("proposal", approvalId=appr["approvalId"], actionType=action_type)
    turn.say(f"[PROPOSED] 승인 {appr['approvalId']}로 저장했습니다. 사람의 승인을 기다립니다. "
             "아직 요청은 등록되지 않았습니다.", cid, name)
    turn.update["pending"] = {"kind": "APPROVAL", "approvalId": appr["approvalId"], "writeTool": write_tool,
                              "approvalVersion": appr["version"], "interruptId": f"INT-APP-{uuid.uuid4().hex[:8]}"}
    turn.update["proposals"] = [{"approvalId": appr["approvalId"], "actionType": action_type,
                                 "ownerUserId": args.get("ownerUserId")}]


async def run_tool(turn: _Turn, name: str, args: dict, cid: str) -> None:
    """도구 실행 — 업무 · 지식 MCP 읽기 도구. 근거와 데이터 버전을 모은다. 일시 장애가 남으면 작업 단위 재시도로 넘긴다"""
    rt = turn.rt
    try:
        res = await rt.toolbox.call(name, args, rt.tracer)
        ev, dv2 = record_evidence(res)
        turn.evidence.update(ev)
        turn.dv = dv2 or turn.dv
        turn.say(context.to_tool_text(res), cid, name)
    except ToolCallError as e:
        turn.say(e.text, cid, name)
        if e.retryable:  # 재시도 후에도 일시 장애면 작업 단위 재시도로 넘긴다
            raise TransientJobError(e.text) from e

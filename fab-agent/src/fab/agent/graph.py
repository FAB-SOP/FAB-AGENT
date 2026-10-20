"""조사 Agent 그래프 (LangGraph).

  start ─▶ required_evidence ─▶ agent ◀──────────────┐
                                  │ (제안 도구를 고르면)   │
                                  ├──▶ request_brief ─┐ │
                                  ▼                   ▼ │
                                tools ── 읽기 · 검색 ──┘
                                  │
            ┌─────────────────────┼──────────────────────┐
            ▼                     ▼                      ▼
     approval_wait           finalize                 (한도 초과 → finalize)
   (interrupt: 사람 승인)
     ├ 거절 → agent
     └ 승인 → 승인 ID로 변경 도구 실행 → reply_wait (interrupt: 담당자 회신) → agent

모델은 다음 행동을 '제안'만 한다. 도구 실행 · 한도 · 승인 · 기록은 Harness 코드가 한다.
request_brief는 제안 저장 '전'에 받는 사람이 볼 요청 브리프(편차 수치 · 원인 후보 · 관련 기록)를 도구 결과에서 만든다 —
브리프는 제안 payload에 들어가 승인 해시로 묶이므로, 승인자가 본 내용과 받는 사람이 보는 내용이 같다.
"""

from __future__ import annotations

import json
import operator
import uuid
from typing import Annotated, Any, TypedDict

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.types import interrupt

from fab.agent import brief as request_briefs
from fab.agent.harness import context, guard, instructions, limits
from fab.agent.harness.retry import TransientJobError, call_with_recovery
from fab.agent.toolbox import HARNESS_NAMES, PROPOSALS, REPORT_SECTIONS, ToolCallError


def _merge(a: dict, b: dict) -> dict:
    return {**(a or {}), **(b or {})}


class AgentState(TypedDict, total=False):
    incidentId: str
    incidentProcess: str | None  # 사건 공정 — nextStep 규칙 검증에 쓴다
    threadId: str
    jobId: str
    mode: str  # BASIC | DEEP | REPORT
    question: str
    messages: Annotated[list[AnyMessage], add_messages]
    evidence: Annotated[dict, _merge]  # evidenceId -> ref
    dataVersion: str
    counters: dict
    pending: dict | None  # {"kind": APPROVAL|REPLY, "approvalId"|"requestId", "interruptId"}
    proposals: Annotated[list, operator.add]
    result: dict | None
    analysis: dict | None  # 계약 밖 정보(nextStep 등) — 계약 개정 전까지 따로 둔다
    report: dict | None
    stopReason: str | None
    promptVersion: str
    briefs: dict | None  # tool_call_id → {"brief"} 또는 {"errors"} — request_brief가 만들고 tools가 제안에 붙인다


class Runtime:
    """그래프 노드가 쓰는 실행 자원 — 실행마다 worker가 넣어 준다(checkpoint에는 저장하지 않는다)."""

    def __init__(self, toolbox, backend, primary, fallback, tracer, actor: str = "USR-CMP-01", job_id: str | None = None):
        self.toolbox, self.backend, self.primary, self.fallback = toolbox, backend, primary, fallback
        self.tracer, self.actor = tracer, actor
        # 지금 처리 중인 작업 — 체크포인트의 state["jobId"]는 thread를 시작한 작업이라 RESUME 뒤에는 다르다
        self.job_id = job_id


def current_job(state, config) -> str:
    return _rt(config).job_id or state["jobId"]


def _rt(config) -> Runtime:
    return config["configurable"]["runtime"]


def _record_evidence(result: dict) -> tuple[dict, str | None]:
    """근거와 데이터 버전 — 결과의 dataVersion은 데이터 팩 버전(업무 MCP)만 쓴다. 지식 MCP의 버전(KNOWLEDGE-*)은 문서 색인 버전이라
    덮어쓰지 않는다(예전에는 마지막 검색 결과가 결과의 dataVersion이 됐다)"""
    ev = {e["evidenceId"]: e for e in result.get("evidenceRefs", [])}
    dv = result.get("dataVersion")
    return ev, None if str(dv or "").startswith("KNOWLEDGE") else dv


# ------------------------------------------------------------------------------ nodes
async def start(state: AgentState, config) -> dict:
    text, ver = instructions.load(state["mode"])
    msgs: list[AnyMessage] = []
    if not state.get("messages"):
        msgs.append(SystemMessage(content=text))
    else:  # 같은 사건 thread의 다음 작업 — 모드 지시만 덧붙인다
        msgs.append(HumanMessage(content="[모드 지시]\n" + instructions.mode_text(state["mode"])))
    q = state.get("question") or {
        "BASIC": "이 이상 사건의 기본 분석을 해 주세요.",
        "REPORT": "저장된 기록으로 보고서 초안을 만들어 주세요.",
    }.get(state["mode"], "추가 조사를 해 주세요.")
    msgs.append(HumanMessage(content=f"[작업 {state['jobId']} · 모드 {state['mode']} · 사건 {state['incidentId']}]\n{q}"))
    _rt(config).tracer.event("node", node="start", mode=state["mode"], promptVersion=ver)
    return {"messages": msgs, "counters": limits.new_window(), "result": None, "analysis": None, "report": None,
            "stopReason": None, "promptVersion": ver}


async def required_evidence(state: AgentState, config) -> dict:
    """반드시 있어야 하는 근거는 Harness가 직접 조회한다(모델 판단에 맡기지 않음).
    팩 계약 readSequence: 사건 → 웨이퍼 실제 경로 → 단계별 run · 계측 · 제거량 → 현재 공정 run의 센서 특징
    (+ 제거 · 증착량이 정상 변동 밖인 앞 공정 run의 센서 특징)."""
    rt = _rt(config)
    inc = state["incidentId"]
    if state["mode"] == "REPORT":
        plan = [("get_report_context", {"incidentId": inc})]
    else:
        already = {m.name for m in state.get("messages", []) if isinstance(m, ToolMessage)}
        if "get_wafer_lineage" in already:  # 같은 thread의 다음 작업 — 이미 조회했다
            return {}
        plan = [("get_incident", {"incidentId": inc})]
    msgs: list[AnyMessage] = []
    evidence, dv = {}, state.get("dataVersion")

    async def run(name, args):
        nonlocal dv
        cid = f"req_{uuid.uuid4().hex[:8]}"
        msgs.append(AIMessage(content="(Harness가 필수 근거를 조회합니다)", tool_calls=[{"id": cid, "name": name, "args": args}]))
        try:
            res = await rt.toolbox.call(name, args, rt.tracer)
            ev, dv2 = _record_evidence(res)
            evidence.update(ev)
            dv = dv2 or dv
            msgs.append(ToolMessage(content=context.to_tool_text(res), tool_call_id=cid, name=name))
            return res
        except ToolCallError as e:
            if e.retryable:  # 필수 근거가 일시 장애면 작업 단위 재시도(RETRY_SCHEDULED)로 넘긴다
                raise TransientJobError(e.text) from e
            msgs.append(ToolMessage(content=e.text, tool_call_id=cid, name=name))
            return None

    incident_process = None
    for name, args in plan:
        res = await run(name, args)
    if state["mode"] != "REPORT" and res:
        data = res.get("data") or {}
        incident_process = data.get("processCode")
        wafers = data.get("waferIds") or []
        if wafers:
            lin = await run("get_wafer_lineage", {"waferId": wafers[0]})
            steps = ((lin or {}).get("data") or {}).get("steps") or []
            outside = []
            for st in steps:
                r = await run("get_run_and_metrology", {"runId": st["runId"]})
                if ((r or {}).get("data") or {}).get("amountWithinNormalVariation") is False:
                    outside.append(st)
            cur = next((st for st in steps if st["processCode"] == data.get("processCode")), None)
            if cur:
                await run("get_run_features", {"runId": cur["runId"]})
            # 제거 · 증착량이 정상 변동 밖인 앞 공정 run은 센서 특징까지 본다 — 센서가 정상이면 센서 태그 없는 원인
            # (예: ETCH_WALL)과 센서 원인(ETCH_RF 등)을 가를 근거가 된다
            for st in outside:
                if st is not cur:
                    await run("get_run_features", {"runId": st["runId"]})
    out = {"messages": msgs, "evidence": evidence, "dataVersion": dv}
    if incident_process:
        out["incidentProcess"] = incident_process
    return out


async def agent(state: AgentState, config) -> dict:
    rt = _rt(config)
    counters = dict(state["counters"])
    reason = limits.check(counters)
    if reason:
        rt.tracer.event("limit", reason=reason)
        return {"stopReason": reason}
    msgs = context.fit_budget(state["messages"])
    tools = rt.toolbox.model_tools(state["mode"])
    with rt.tracer.span("llm", node="agent") as rec:
        ai, used = await call_with_recovery(rt.primary, rt.fallback, msgs, tools, rt.tracer)
        usage = getattr(ai, "usage_metadata", None) or {}
        rec.update(model=used, modelName=getattr(rt.primary if used == "primary" else rt.fallback, "name", used),
                   tokens=usage.get("total_tokens", 0), inputTokens=usage.get("input_tokens", 0),
                   outputTokens=usage.get("output_tokens", 0),
                   reasoningTokens=(usage.get("output_token_details") or {}).get("reasoning", 0))
    counters["modelCalls"] += 1
    if not ai.tool_calls:  # 제출 없이 말로 끝내려 하면 제출 도구를 쓰라고 되돌린다
        nudge = HumanMessage(content="[Harness] 결과는 반드시 submit_result(또는 submit_report)로 제출하세요.")
        return {"messages": [ai, nudge], "counters": counters}
    return {"messages": [ai], "counters": counters}


async def request_brief(state: AgentState, config) -> dict:
    """제안 도구 호출마다 요청 브리프를 만든다 — 숫자 · 원인 ID · 근거는 도구 결과에서, 모델은 reason 문장만"""
    rt = _rt(config)
    ai: AIMessage = state["messages"][-1]
    known = set(state.get("evidence", {}))
    text = request_briefs.tool_text(state["messages"])
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


async def tools(state: AgentState, config) -> dict:
    rt = _rt(config)
    ai: AIMessage = state["messages"][-1]
    counters = dict(state["counters"])
    counters["toolCalls"] = dict(counters["toolCalls"])
    out: list[AnyMessage] = []
    evidence: dict = {}
    dv = state.get("dataVersion")
    update: dict[str, Any] = {}
    known = set(state.get("evidence", {}))

    for call in ai.tool_calls:
        name, args, cid = call["name"], call["args"], call["id"]
        counters["toolCalls"][name] = counters["toolCalls"].get(name, 0) + 1
        if not limits.tool_allowed(state["counters"], name):
            out.append(ToolMessage(content=f"[LIMIT] {name}는 이번 작업에서 {limits.LIMITS.max_same_tool}회까지만 부를 수 있습니다. "
                                           "지금 근거로 정리하세요.", tool_call_id=cid, name=name))
            continue

        if name == "submit_result":
            errs = guard.validate_result(args, known | set(evidence), state.get("incidentProcess"))
            # 심층 조사에서 점검 · 확인이 필요하다고 판단했으면 요청 초안을 먼저 제안한다(팩 E03 — 보낼지는 사람이 승인으로 정한다)
            if (state["mode"] == "DEEP" and args.get("nextStep") in ("CURRENT_CHECK", "UPSTREAM_CHECK")
                    and not state.get("proposals") and not update.get("pending")):
                kind = "propose_cross_process_check(앞 공정 PROCESS_OWNER)" if args["nextStep"] == "UPSTREAM_CHECK" \
                    else "propose_maintenance_request(설비정비팀 MAINTENANCE)"
                errs.append(f"nextStep이 {args['nextStep']}이면 먼저 {kind}로 확인 · 점검 요청 초안을 제안하세요. "
                            "get_process_owner로 받는 사람을 찾고, 보낼지는 사람이 승인으로 정합니다.")
            if errs:
                rt.tracer.event("guard_reject", tool=name, errors=errs)
                out.append(ToolMessage(content="[REJECTED] " + " / ".join(errs), tool_call_id=cid, name=name))
            else:
                out.append(ToolMessage(content="[ACCEPTED] 결과를 접수했습니다.", tool_call_id=cid, name=name))
                update["result"] = args
            continue

        if name == "submit_report":
            out.append(ToolMessage(content="[ACCEPTED] 보고서 초안을 접수했습니다.", tool_call_id=cid, name=name))
            update["report"] = args
            continue

        if name in PROPOSALS:
            if state["mode"] != "DEEP":
                out.append(ToolMessage(content="[FORBIDDEN] 이 모드에서는 요청을 제안할 수 없습니다.", tool_call_id=cid, name=name))
                continue
            unknown = set(args.get("evidenceIds", [])) - (known | set(evidence))
            if unknown:
                out.append(ToolMessage(content=f"[REJECTED] 조회하지 않은 근거 ID: {sorted(unknown)}", tool_call_id=cid, name=name))
                continue
            write_tool, action_type = PROPOSALS[name]
            made_brief = (state.get("briefs") or {}).get(cid) or {"errors": ["요청 브리프가 만들어지지 않았습니다."]}
            if made_brief.get("errors"):
                out.append(ToolMessage(content="[REJECTED] 요청 브리프: " + " / ".join(made_brief["errors"]),
                                       tool_call_id=cid, name=name))
                continue
            if update.get("pending"):  # 한 번에 하나만 승인 대기에 넣는다
                out.append(ToolMessage(content="[REJECTED] 제안은 한 번에 하나씩 합니다. 이 제안의 승인을 먼저 기다리세요.",
                                       tool_call_id=cid, name=name))
                continue
            made = [p for p in state.get("proposals") or [] if isinstance(p, dict)]
            if len(state.get("proposals") or []) >= limits.LIMITS.max_proposals:
                out.append(ToolMessage(content=f"[LIMIT] 이 사건에서 제안은 최대 {limits.LIMITS.max_proposals}개입니다. "
                                               "더 제안하지 말고 지금 근거로 submit_result를 부르세요.", tool_call_id=cid, name=name))
                continue
            if any(p["actionType"] == action_type and p["ownerUserId"] == args.get("ownerUserId") for p in made):
                out.append(ToolMessage(content="[REJECTED] 같은 담당자에게 같은 종류의 요청을 이미 제안했습니다. "
                                               "회신 · 결정을 근거로 결과를 정리하세요.", tool_call_id=cid, name=name))
                continue
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
                out.append(ToolMessage(content=f"[REJECTED] {e}", tool_call_id=cid, name=name))
                continue
            rt.tracer.event("proposal", approvalId=appr["approvalId"], actionType=action_type)
            out.append(ToolMessage(content=f"[PROPOSED] 승인 {appr['approvalId']}로 저장했습니다. 사람의 승인을 기다립니다. "
                                           "아직 요청은 등록되지 않았습니다.", tool_call_id=cid, name=name))
            update["pending"] = {"kind": "APPROVAL", "approvalId": appr["approvalId"], "writeTool": write_tool,
                                 "approvalVersion": appr["version"], "interruptId": f"INT-APP-{uuid.uuid4().hex[:8]}"}
            update["proposals"] = [{"approvalId": appr["approvalId"], "actionType": action_type,
                                    "ownerUserId": args.get("ownerUserId")}]
            continue

        if name in HARNESS_NAMES:
            out.append(ToolMessage(content=f"[FORBIDDEN] {name}는 이 모드에서 쓸 수 없습니다.", tool_call_id=cid, name=name))
            continue
        try:
            res = await rt.toolbox.call(name, args, rt.tracer)
            ev, dv2 = _record_evidence(res)
            evidence.update(ev)
            dv = dv2 or dv
            out.append(ToolMessage(content=context.to_tool_text(res), tool_call_id=cid, name=name))
        except ToolCallError as e:
            out.append(ToolMessage(content=e.text, tool_call_id=cid, name=name))
            if e.retryable:  # 재시도 후에도 일시 장애면 작업 단위 재시도로 넘긴다
                raise TransientJobError(e.text) from e

    return {"messages": out, "evidence": evidence, "dataVersion": dv, "counters": counters, **update}


async def approval_wait(state: AgentState, config) -> dict:
    pending = state["pending"]
    decision = interrupt({"kind": "APPROVAL", "approvalId": pending["approvalId"],
                          "approvalVersion": pending.get("approvalVersion", 1),
                          "interruptId": pending["interruptId"]})
    # --- 재개 이후 (위 줄 이전 코드는 재개 때 다시 실행되므로 부수 효과를 두지 않는다) ---
    rt = _rt(config)
    if decision.get("interruptId") != pending["interruptId"] or decision.get("approvalId") != pending["approvalId"]:
        raise ValueError("재개 참조가 대기 중인 interrupt와 다릅니다.")
    if decision["decision"] != "APPROVE":
        rt.tracer.event("approval", approvalId=pending["approvalId"], decision="REJECT")
        note = HumanMessage(content=f"[시스템 알림] 승인 결과: REJECT ({pending['approvalId']}). 사유: "
                                    + guard.untrusted("approval-comment", decision.get("comment", "")))
        return {"pending": None, "messages": [note], "counters": limits.new_window()}
    res = await rt.toolbox.call(pending.get("writeTool", "create_cross_process_check_request"),
                                {"approvalId": pending["approvalId"], "idempotencyKey": f"{pending['approvalId']}:EXEC"},
                                rt.tracer, allow_write=True)
    req = res["data"]
    rt.tracer.event("approval", approvalId=pending["approvalId"], decision="APPROVE", requestId=req["requestId"])
    note = HumanMessage(content=f"[시스템 알림] 승인 결과: APPROVE. 확인 요청 {req['requestId']}를 등록했습니다. 회신을 기다립니다.")
    return {"messages": [note], "pending": {"kind": "REPLY", "requestId": req["requestId"],
                                           "interruptId": f"INT-REPLY-{uuid.uuid4().hex[:8]}"}}


async def reply_wait(state: AgentState, config) -> dict:
    pending = state["pending"]
    reply = interrupt({"kind": "REPLY", "requestId": pending["requestId"], "interruptId": pending["interruptId"]})
    if reply.get("interruptId") != pending["interruptId"] or reply.get("requestId") != pending["requestId"]:
        raise ValueError("재개 참조가 대기 중인 interrupt와 다릅니다.")
    eid = f"E-REQ-{pending['requestId']}"
    body = json.dumps({"findings": reply.get("findings", {}), "memo": reply.get("memo", "")}, ensure_ascii=False)
    note = HumanMessage(content=f"[시스템 알림] 담당자 회신({pending['requestId']}, 근거 ID {eid} — 담당자 진술이며 측정 근거와 구분):\n"
                                + guard.untrusted("check-request-reply", body))
    _rt(config).tracer.event("reply", requestId=pending["requestId"])
    return {"messages": [note], "pending": None, "counters": limits.new_window(),
            "evidence": {eid: {"evidenceId": eid, "sourceType": "REPLY", "sourceId": pending["requestId"],
                               "dataVersion": state.get("dataVersion"), "synthetic": True}}}


REF_TYPES = {"SENSOR", "METROLOGY", "ROUTE", "CHANGE", "REPLY", "DOCUMENT"}  # 계약 Reference.sourceType


SEGMENT_LIMITATION = ("시계열 구간(DEMO-SEGMENT-V2)의 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아닙니다. "
                      "구간은 관측이지 고장 시점 · 원인 확정이 아닙니다.")


def _segment_limit(r: dict, facts: list[dict]) -> list[str]:
    """시계열 구간 근거(E-SEG-*)를 인용한 결과에는 Harness가 그 한계를 반드시 남긴다(모델이 빠뜨려도)"""
    cited = {e for f in facts for e in f["evidenceIds"]} | {e for c in r.get("candidateCauses", [])
                                                           for e in c.get("supportingEvidenceIds") or []}
    text = " ".join(str(x) for x in r.get("limitations", []))
    return [SEGMENT_LIMITATION] if any(e.startswith("E-SEG-") for e in cited) and "시연 선택값" not in text else []


def _contract_ref(ref: dict, data_version: str | None) -> dict:
    t = ref.get("sourceType")
    return {"evidenceId": ref["evidenceId"], "sourceType": t if t in REF_TYPES else "METROLOGY",
            "sourceId": str(ref.get("sourceId", ref["evidenceId"])),
            "dataVersion": ref.get("dataVersion") or data_version or "UNKNOWN", "synthetic": True}


async def finalize(state: AgentState, config) -> dict:
    rt = _rt(config)
    if state["mode"] == "REPORT":
        rep = state.get("report") or {}
        sections = {k: str((rep.get("sections") or {}).get(k) or "기록 없음") for k in REPORT_SECTIONS}
        if not state.get("report"):
            sections["unresolved"] = f"초안 생성이 끝나기 전에 멈췄습니다: {state.get('stopReason')}"
        known = set(state.get("evidence", {}))
        ev_ids = [e for e in rep.get("evidenceIds", []) if e in known]
        saved = await rt.backend.save_report_draft(state["incidentId"], sections, ev_ids)
        return {"report": saved}
    r = state.get("result")
    limitations = []
    if r is None:  # 한도 초과 등으로 제출 없이 끝남 — 지금까지의 근거로 부분 결과
        r = {"nextStep": "INSUFFICIENT_EVIDENCE", "observedFacts": [], "candidateCauses": [], "limitations": []}
        limitations.append(f"조사가 끝나기 전에 멈췄습니다: {state.get('stopReason')}")
    facts = [{"text": str(f.get("text", "")), "evidenceIds": list(f.get("evidenceIds") or [])}
             for f in r.get("observedFacts", [])]
    causes = [{"priority": max(1, int(c.get("priority") or i + 1)), "hypothesis": str(c.get("hypothesis", "")),
               "supportingEvidenceIds": list(c.get("supportingEvidenceIds") or []),
               "conflictingEvidenceIds": list(c.get("conflictingEvidenceIds") or []),
               "missingData": [str(x) for x in c.get("missingData") or []],
               "nextCheck": str(c.get("nextCheck", ""))}
              for i, c in enumerate(r.get("candidateCauses", []))]
    used = {e for f in facts for e in f["evidenceIds"]} | {e for c in causes for e in c["supportingEvidenceIds"]}
    ev = state.get("evidence", {})
    result = {
        "resultId": f"RESULT-{current_job(state, config)}",
        "jobId": current_job(state, config), "incidentId": state["incidentId"],
        "dataVersion": state.get("dataVersion") or "UNKNOWN",
        "observedFacts": facts, "candidateCauses": causes,
        "limitations": [str(x) for x in r.get("limitations", [])] + limitations + _segment_limit(r, facts),
        "evidenceRefs": [_contract_ref(ev[e], state.get("dataVersion")) for e in sorted(used) if e in ev],
        "synthetic": True,
    }
    # 계약 AnalysisResult.candidateCauses는 추가 필드를 막는다 — 원인 매트릭스 ID는 계약 밖 analysis에 둔다
    analysis = {"nextStep": r.get("nextStep"), "stopReason": state.get("stopReason"),
                "promptVersion": state.get("promptVersion"),
                "causeIds": [[str(x) for x in c.get("causeIds") or []] for c in r.get("candidateCauses", [])]}
    return {"result": result, "analysis": analysis}


# ------------------------------------------------------------------------------ routes
def route_agent(state: AgentState) -> str:
    if state.get("stopReason"):
        return "finalize"
    last = state["messages"][-1]
    if isinstance(last, HumanMessage):  # nudge
        return "agent"
    if any(tc["name"] in PROPOSALS for tc in getattr(last, "tool_calls", None) or []):
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


def build_graph(checkpointer):
    g = StateGraph(AgentState)
    for name, fn in [("start", start), ("required_evidence", required_evidence), ("agent", agent),
                     ("request_brief", request_brief), ("tools", tools), ("approval_wait", approval_wait), ("reply_wait", reply_wait),
                     ("finalize", finalize)]:
        g.add_node(name, fn)
    g.add_edge(START, "start")
    g.add_edge("start", "required_evidence")
    g.add_edge("required_evidence", "agent")
    g.add_conditional_edges("agent", route_agent, ["request_brief", "tools", "finalize", "agent"])
    g.add_edge("request_brief", "tools")
    g.add_conditional_edges("tools", route_tools, ["approval_wait", "finalize", "agent"])
    g.add_conditional_edges("approval_wait", route_approval, ["reply_wait", "agent"])
    g.add_edge("reply_wait", "agent")
    g.add_edge("finalize", END)
    return g.compile(checkpointer=checkpointer)

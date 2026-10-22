"""approval_wait — 사람의 승인을 기다린다(interrupt). 승인되면 승인 ID로 변경 도구를 실행하고 회신 대기로 넘어간다."""

from __future__ import annotations

import uuid

from langchain_core.messages import HumanMessage
from langgraph.types import interrupt

from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import guard, limits


async def approval_wait(state: AgentState, config) -> dict:
    pending = state["pending"]
    decision = interrupt({"kind": "APPROVAL", "approvalId": pending["approvalId"],
                          "approvalVersion": pending.get("approvalVersion", 1),
                          "interruptId": pending["interruptId"]})
    # --- 재개 이후 (위 줄 이전 코드는 재개 때 다시 실행되므로 부수 효과를 두지 않는다) ---
    job_runtime = runtime(config)
    if decision.get("interruptId") != pending["interruptId"] or decision.get("approvalId") != pending["approvalId"]:
        raise ValueError("재개 참조가 대기 중인 interrupt와 다릅니다.")
    if decision["decision"] != "APPROVE":
        job_runtime.tracer.event("approval", approvalId=pending["approvalId"], decision="REJECT")
        note = HumanMessage(content=f"[시스템 알림] 승인 결과: REJECT ({pending['approvalId']}). 사유: "
                                    + guard.untrusted("approval-comment", decision.get("comment", "")))
        return {"pending": None, "messages": [note], "counters": limits.new_window()}
    res = await job_runtime.toolbox.call(pending.get("writeTool", "create_cross_process_check_request"),
                                         {"approvalId": pending["approvalId"], "idempotencyKey": f"{pending['approvalId']}:EXEC"},
                                         job_runtime.tracer, allow_write=True)
    req = res["data"]
    job_runtime.tracer.event("approval", approvalId=pending["approvalId"], decision="APPROVE", requestId=req["requestId"])
    note = HumanMessage(content=f"[시스템 알림] 승인 결과: APPROVE. 확인 요청 {req['requestId']}를 등록했습니다. 회신을 기다립니다.")
    return {"messages": [note], "pending": {"kind": "REPLY", "requestId": req["requestId"],
                                           "interruptId": f"INT-REPLY-{uuid.uuid4().hex[:8]}"}}

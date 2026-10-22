"""reply_wait — 담당자 회신을 기다린다(interrupt). 회신은 진술 근거(E-REQ-*)로 대화에 넣는다."""

from __future__ import annotations

import json

from langchain_core.messages import HumanMessage
from langgraph.types import interrupt

from fab.agent.graph.state import AgentState, runtime
from fab.agent.harness import guard, limits


async def reply_wait(state: AgentState, config) -> dict:
    pending = state["pending"]
    reply = interrupt({"kind": "REPLY", "requestId": pending["requestId"], "interruptId": pending["interruptId"]})
    if reply.get("interruptId") != pending["interruptId"] or reply.get("requestId") != pending["requestId"]:
        raise ValueError("재개 참조가 대기 중인 interrupt와 다릅니다.")
    eid = f"E-REQ-{pending['requestId']}"
    body = json.dumps({"findings": reply.get("findings", {}), "memo": reply.get("memo", "")}, ensure_ascii=False)
    note = HumanMessage(content=f"[시스템 알림] 담당자 회신({pending['requestId']}, 근거 ID {eid} — 담당자 진술이며 측정 근거와 구분):\n"
                                + guard.untrusted("check-request-reply", body))
    runtime(config).tracer.event("reply", requestId=pending["requestId"])
    return {"messages": [note], "pending": None, "counters": limits.new_window(), "result": None,
            "evidence": {eid: {"evidenceId": eid, "sourceType": "REPLY", "sourceId": pending["requestId"],
                               "dataVersion": state.get("dataVersion"), "synthetic": True}}}

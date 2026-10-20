"""그래프 상태(AgentState)와 실행 자원(Runtime) — 노드들이 같이 쓴다. Runtime은 checkpoint에 저장하지 않는다."""

from __future__ import annotations

import operator
from typing import Annotated, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


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
    return runtime(config).job_id or state["jobId"]


def runtime(config) -> Runtime:
    return config["configurable"]["runtime"]


def record_evidence(result: dict) -> tuple[dict, str | None]:
    """근거와 데이터 버전 — 결과의 dataVersion은 데이터 팩 버전(업무 MCP)만 쓴다. 지식 MCP의 버전(KNOWLEDGE-*)은 문서 색인 버전이라
    덮어쓰지 않는다(예전에는 마지막 검색 결과가 결과의 dataVersion이 됐다)"""
    ev = {e["evidenceId"]: e for e in result.get("evidenceRefs", [])}
    dv = result.get("dataVersion")
    return ev, None if str(dv or "").startswith("KNOWLEDGE") else dv

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
    answerOnly: bool  # 수치 · 사실만 묻는 질문 — 원인 후보 · 할 일 후보 · 요청서 없이 답만(fab.agent.question_kind)
    purpose: str  # BASIC | REPLY | QUESTION | REQUEST | REPORT — 작업 종류(fab.agent.purpose)
    pendingApproval: dict | None  # 이 사건에서 승인을 기다리는 요청서 — 있으면 질문은 질문 전용(사건 기록 조회가 채운다)
    baseline: dict | None  # 사건의 가장 최근 원인 분석 — 결론 변경 검사의 기준(사건 기록 조회가 채운다)
    conclusion: dict | None  # 결론 변경 검사 결과 — changed · fields · reason · newEvidenceIds · adjusted
    question: str
    requestInput: dict | None  # 요청서 생성 · 재생성 입력(fab.agent.job_input) — 대상 공정 · 원인 · 대체할 승인
    selectedInputs: list | None  # 추가 분석에 작업자가 고른 조사 결과(fab.agent.followup) — 진술 근거로 등록한다
    messages: Annotated[list[AnyMessage], add_messages]
    evidence: Annotated[dict, _merge]  # evidenceId -> ref
    dataVersion: str
    counters: dict
    pending: dict | None  # {"kind": APPROVAL|REPLY, "approvalId"|"requestId", "interruptId"}
    proposals: Annotated[list, operator.add]
    result: dict | None
    previousResult: dict | None  # 승인 · 회신으로 재개하기 전에 접수된 답 — 다시 정리하기 전에 멈추면 이것을 쓴다
    retryProposal: bool  # 요청서가 고칠 수 있는 이유로 반려돼 모델에게 한 번 돌려보낼지
    autoRequest: bool | None  # 원인 질문의 답에 요청서가 없어 Harness가 요청서 초안을 낼 차례(fab.agent.auto_request)
    analysis: dict | None  # 계약 밖 정보(nextStep 등) — 계약 개정 전까지 따로 둔다
    workSuggestions: list | None  # 답과 함께 내는 업무 후보(fab.agent.work) — RESULT_READY.payload.workSuggestions
    report: dict | None
    replyFlow: str | None  # 회신 흐름 — WAIT(회신 대기) · MANUAL(요청 발송 뒤 끝냄). thread를 시작할 때 정해 바뀌지 않는다
    proposalLimit: bool | None  # 사건당 Agent 요청 한도에 닿아 요청서를 만들지 못했나 — 결과에 이유를 남긴다
    requestSent: str | None  # MANUAL 흐름에서 승인으로 보낸 요청 ID — 결과는 「발송 사실」이다
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
    evidence = {e["evidenceId"]: e for e in result.get("evidenceRefs", [])}
    analysis = (result.get("data") or {}).get("latestCauseAnalysis") if isinstance(result.get("data"), dict) else None
    for ref in (analysis or {}).get("evidenceRefs") or []:  # 보고서 입력의 최신 원인 분석 근거 — 초안이 인용할 수 있게
        if isinstance(ref, dict) and ref.get("evidenceId"):
            evidence.setdefault(ref["evidenceId"], {"evidenceId": ref["evidenceId"], "sourceType": ref.get("type") or ref.get("sourceType"),
                                                    "sourceId": ref.get("ref") or ref.get("sourceId") or ref["evidenceId"]})
    data_version = result.get("dataVersion")
    return evidence, None if str(data_version or "").startswith("KNOWLEDGE") else data_version

"""작업 종류(purpose) — 같은 그래프를 도는 작업이 무엇을 하러 왔는지. 「누가 요청을 제안할 수 있나」를 여기 한곳에서 정한다.

  BASIC    기본 조사(이상 접수 · 재분석)        원인 분석 — 판단(nextStep)만, 제안 안 함
  REPLY    회신 반영 분석(회신 대기 재개)        원인 분석 — 판단만, 제안 안 함
  FOLLOWUP 추가 분석(작업자가 고른 결과 반영)   원인 분석 — 질문과 같이 제안 가능(승인 대기가 있으면 제안 안 함)
  QUESTION 사용자 질문                          질문 답 — 제안 가능. 단 사건에 승인 대기가 있으면 질문 전용
  REQUEST  「요청 만들기」                     질문 답 — 요청서를 만들러 온 작업
  REGENERATE 「요청서 다시 만들기」              REQUEST와 같다 — 대체할 승인(input.request.supersedesApprovalId)이 있다
  REPORT   보고서 초안 · 수정
"""

from __future__ import annotations

BASIC, REPLY, QUESTION, REQUEST, REPORT, FOLLOWUP = "BASIC", "REPLY", "QUESTION", "REQUEST", "REPORT", "FOLLOWUP"
REGENERATE = "REGENERATE"
HUMAN_REQUESTED = (REQUEST, REGENERATE)  # 사람이 연 요청서 작업

# backend 작업의 트리거(job.input.trigger) — 이름은 D와 맞춘 뒤 여기만 고친다
_REPLY_TRIGGERS = {"WORK_REPLY", "REPLY"}
_REQUEST_TRIGGERS = {"REQUEST_BUTTON", "REGENERATE"}
_FOLLOWUP_TRIGGERS = {"FOLLOWUP_REQUEST"}  # backend 추가 분석(작업 input.trigger) — 맥락에 실리기 전에는 질문 칸으로 알아본다


def purpose_of(kind: str, trigger: str | None) -> str:
    if kind in (BASIC, REPORT):
        return kind
    if trigger in _REPLY_TRIGGERS:
        return REPLY
    if trigger in _REQUEST_TRIGGERS:
        return REQUEST
    if trigger in _FOLLOWUP_TRIGGERS:
        return FOLLOWUP
    return QUESTION


def can_propose(purpose: str, has_pending_approval: bool) -> bool:
    return purpose in HUMAN_REQUESTED or (purpose in (QUESTION, FOLLOWUP) and not has_pending_approval)


def may_propose(state: dict) -> bool:
    """그래프 상태로 본 제안 허용 — purpose가 없는 상태(예전 체크포인트)는 모드로 정한다"""
    return can_propose(state.get("purpose") or purpose_of(state["mode"], None), bool(state.get("pendingApproval")))


def result_kind(purpose: str, conclusion_changed: bool) -> str:
    """원인 분석 · 질문 답 — 질문 답이라도 결론(1순위 원인 · nextStep · 대상 공정)을 바꿨으면 원인 분석으로 올린다"""
    if purpose in (BASIC, REPLY, FOLLOWUP) or conclusion_changed:
        return "CAUSE_ANALYSIS"
    return "ANSWER"

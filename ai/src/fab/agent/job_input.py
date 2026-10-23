"""작업 입력 해석 — 실행 목적과 목적별 입력을 작업 메시지의 공개 input에서 읽는다(아름님과 합의).

  input.purpose   BASIC · QUESTION · FOLLOWUP · REQUEST · REGENERATE · REPORT(SUMMARY는 kind와 input.summaryRef로 따로)
  input.followup  {analysisRequestId, question, selectedInputs[]} — backend가 원본을 조회해 만든 접수 시점 스냅샷
  input.request   {causeIds?, targetProcessCode?, supersedesApprovalId?} — 요청서 생성 · 재생성(재생성은 대체할 승인 필수)
전환 기간에는 기존 방식(맥락 trigger · 질문 칸에 붙은 선택 결과)도 읽는다. 둘 다 오면 input.purpose가 우선이고,
기존 방식이 다른 목적을 가리키면 모순이라 INVALID_INPUT으로 실패한다(서버 검증과 별개로 한 번 더).
"""

from __future__ import annotations

from fab.agent import purpose
from fab.agent.followup import split_question
from fab.agent.harness.retry import PermanentJobError

_ALLOWED = {"BASIC": {purpose.BASIC}, "REPORT": {purpose.REPORT},
            "DEEP": {purpose.QUESTION, purpose.FOLLOWUP, purpose.REQUEST, purpose.REGENERATE}}
_SAME = {purpose.REGENERATE: purpose.REQUEST}  # 기존 trigger(REGENERATE)는 REQUEST로 읽혔다 — 모순 검사에서는 같은 목적


class InvalidJobInput(PermanentJobError):
    code = "INVALID_INPUT"


def _legacy(kind: str, question: str | None, trigger: str | None) -> tuple[str, str, list[dict] | None]:
    text, selected = split_question(question)
    return (purpose.FOLLOWUP if selected is not None else purpose.purpose_of(kind, trigger)), text, selected


def resolve(job: dict, question: str | None, trigger: str | None) -> dict:
    """{purpose, question, selectedInputs, request} — 그래프 시작 입력에 들어간다"""
    kind, given = job["kind"], (job.get("input") or {})
    legacy_purpose, legacy_question, legacy_selected = _legacy(kind, question, trigger)
    wanted = given.get("purpose")
    if wanted is None:
        return {"purpose": legacy_purpose, "question": legacy_question, "selectedInputs": legacy_selected, "request": None}
    if wanted not in _ALLOWED.get(kind, set()):
        raise InvalidJobInput(f"작업 종류 {kind}에 목적 {wanted}는 쓸 수 없습니다(허용: {sorted(_ALLOWED.get(kind, set()))}).")
    legacy_given = legacy_selected is not None or bool(trigger)
    if legacy_given and _SAME.get(legacy_purpose, legacy_purpose) != _SAME.get(wanted, wanted):
        raise InvalidJobInput(f"input.purpose({wanted})와 기존 입력이 가리키는 목적({legacy_purpose})이 다릅니다.")
    out = {"purpose": wanted, "question": legacy_question, "selectedInputs": None, "request": None}
    if wanted == purpose.FOLLOWUP:
        followup = given.get("followup") or {}
        selected = followup.get("selectedInputs")
        if not isinstance(selected, list) or not selected or not all(isinstance(i, dict) and i.get("evidenceId") for i in selected):
            raise InvalidJobInput("추가 분석(FOLLOWUP)에는 input.followup.selectedInputs(evidenceId가 있는 선택 결과)가 필요합니다.")
        out |= {"selectedInputs": selected, "question": followup.get("question") or legacy_question}
    if wanted in (purpose.REQUEST, purpose.REGENERATE):
        request = given.get("request") or {}
        if wanted == purpose.REGENERATE and not request.get("supersedesApprovalId"):
            raise InvalidJobInput("요청서 재생성(REGENERATE)에는 대체할 승인 input.request.supersedesApprovalId가 필요합니다.")
        out["request"] = request
    return out

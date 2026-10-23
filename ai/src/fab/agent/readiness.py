"""조사 정리 완료 신호(investigationReady) — 「결론이 섰다」만 알린다. 사건 종료 명령이 아니다.

승인 대기 · 미회신 요청 · 실행 중 작업 확인과 자동 초안 생성은 backend(보고서 모듈)가 한다. Agent는 결과만 본다:
  ready      모든 공정이 정상 변동(OBSERVE), 또는 1순위가 지지이고 확인 결과(회신 E-REQ-* · 내 작업 결과 E-ACT-*)를 인용
  not ready  한도로 멈춤 · 더 볼 근거가 남음(NEEDS_DEEP_ANALYSIS · INSUFFICIENT_EVIDENCE) · 1순위 근거가 갈림 · 점검 결과가 아직 없음
기본 분석 직후의 「현재 공정 점검」처럼 확인할 일이 남은 결과로 초안이 생기지 않게, 지지만으로는 ready가 아니다.
"""

from __future__ import annotations

from fab.agent.stance import SUPPORTED, cause_stance

_CONFIRMATION = ("E-REQ-", "E-ACT-")


def _ready(ready: bool, reason: str) -> dict:
    return {"ready": ready, "reason": reason}


def investigation_ready(result: dict, stop_reason: str | None) -> dict:
    """result: submit_result 모양(nextStep · candidateCauses · observedFacts). stop_reason: 한도로 멈췄으면 그 이유"""
    if stop_reason:
        return _ready(False, f"조사가 끝나기 전에 멈춤: {stop_reason}")
    step = result.get("nextStep")
    if step in ("NEEDS_DEEP_ANALYSIS", "INSUFFICIENT_EVIDENCE", None):
        return _ready(False, "더 볼 근거가 남음 — 원인을 좁히기 전")
    if step == "OBSERVE":
        return _ready(True, "모든 공정이 정상 변동 — 이상 원인 없음으로 정리 가능")
    causes = result.get("candidateCauses") or []
    if not causes:
        return _ready(False, "더 볼 근거가 남음 — 원인 후보 없음")
    top = min(causes, key=lambda cause: cause.get("priority") or 99)
    if cause_stance(top) != SUPPORTED:
        return _ready(False, "1순위 원인의 근거가 갈림 — 지지 근거가 반대 근거보다 많지 않음")
    cited = set(top.get("supportingEvidenceIds") or []) | {e for fact in result.get("observedFacts") or []
                                                         for e in fact.get("evidenceIds") or []}
    if any(evidence_id.startswith(_CONFIRMATION) for evidence_id in cited):
        return _ready(True, "점검 결과(회신 · 작업 결과)를 반영해 1순위 원인을 지지 — 보고서로 정리 가능")
    return _ready(False, "1순위 원인을 확인할 점검 결과가 아직 없음 — 회신 · 작업 결과를 반영한 뒤")

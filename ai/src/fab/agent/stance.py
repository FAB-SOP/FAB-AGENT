"""원인 후보의 판정 — 지지 · 반증 · 판정 보류(팀 TO-DO C4). 모델이 나눠 적은 지지 · 반대 근거 수로 Harness가 정한다.

  SUPPORTED     지지 — 지지 근거가 반대 근거보다 많다
  REFUTED       반증 — 반대 근거가 지지 근거보다 많다(검토했지만 근거가 반대한다는 기록)
  UNDETERMINED  판정 보류 — 둘이 같다(둘 다 없음 포함). 무엇이 더 필요한지는 missingData에
확률이 아니다. 같은 근거면 같은 판정이 나온다.
"""

from __future__ import annotations

SUPPORTED, REFUTED, UNDETERMINED = "SUPPORTED", "REFUTED", "UNDETERMINED"


def cause_stance(cause: dict) -> str:
    support = len(set(cause.get("supportingEvidenceIds") or []))
    counter = len(set(cause.get("conflictingEvidenceIds") or []))
    return SUPPORTED if support > counter else REFUTED if counter > support else UNDETERMINED

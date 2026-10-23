"""제출 결과의 형식 보정 — 모델에게 다시 물을 필요가 없는 것만 Harness가 고친다(고친 것은 trace에 남긴다).

줄여 쓴 근거 ID: 모델이 원문 청크 ID(E-DOC-DOC01-P004-C003) 대신 문서 ID(E-DOC-DOC01)만 인용하면 「조회하지 않은 근거」로
반려되고, 다시 낼 때 원문 인용을 아예 빼곤 했다(실제 모델 평가). 조회한 ID 가운데 앞부분이 맞는 것이 하나뿐일 때만 바꾼다.
"""

from __future__ import annotations


def longer_ids(evidence_id: str, known: set[str]) -> list[str]:
    """조회한 ID 가운데 이 ID로 시작하는 더 긴 ID — 줄여 쓴 ID의 후보"""
    return sorted(k for k in known if k.startswith(evidence_id + "-"))


def resolve_short_ids(result: dict, known: set[str]) -> tuple[dict, dict[str, str]]:
    """관측 사실 · 원인 후보의 근거 ID 중 조회하지 않은 ID를, 후보가 하나뿐이면 그 ID로 바꾼다 — (결과, {줄인 ID: 바꾼 ID})"""
    fixed: dict[str, str] = {}

    def resolve(ids):
        out = []
        for evidence_id in ids or []:
            candidates = [] if evidence_id in known else longer_ids(evidence_id, known)
            if len(candidates) == 1:
                fixed[evidence_id] = candidates[0]
                evidence_id = candidates[0]
            out.append(evidence_id)
        return out

    facts = [{**fact, "evidenceIds": resolve(fact.get("evidenceIds"))} for fact in result.get("observedFacts") or []]
    causes = [{**cause, "supportingEvidenceIds": resolve(cause.get("supportingEvidenceIds")),
               "conflictingEvidenceIds": resolve(cause.get("conflictingEvidenceIds"))}
              for cause in result.get("candidateCauses") or []]
    if not fixed:
        return result, {}
    return {**result, "observedFacts": facts, "candidateCauses": causes}, fixed

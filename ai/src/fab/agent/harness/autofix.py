"""제출 결과의 형식 보정 — 모델에게 다시 물을 필요가 없는 것만 Harness가 고친다(고친 것은 trace에 남긴다).

줄여 쓴 근거 ID: 모델이 원문 청크 ID(E-DOC-DOC01-P004-C003) 대신 문서 ID(E-DOC-DOC01)만 인용하면 「조회하지 않은 근거」로
반려되고, 다시 낼 때 원문 인용을 아예 빼곤 했다(실제 모델 평가). 조회한 ID 가운데 앞부분이 맞는 것이 하나뿐일 때만 바꾼다.
"""

from __future__ import annotations

import re

from fab.agent.stance import REFUTED, cause_stance

RERANKED = "1순위 후보가 반대 근거가 더 많아(반증) Harness가 반증이 아닌 후보를 1순위로 올렸습니다 — 나머지 순서는 그대로입니다."


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


def rank_by_stance(result: dict) -> tuple[dict, bool]:
    """1순위 후보가 반증(반대 근거가 더 많음)이면 반증이 아닌 후보 중 가장 앞의 것을 1순위로 올린다 — 나머지 순서는 그대로,
    priority는 1..n으로 다시 매긴다. 순서만 틀린 답을 반려하면 실제 모델은 같은 순서로 다시 내 판단 한도에 닿곤 했다"""
    causes = sorted(result.get("candidateCauses") or [], key=lambda cause: cause.get("priority") or 99)
    if not causes or cause_stance(causes[0]) != REFUTED:
        return result, False
    first = next((cause for cause in causes if cause_stance(cause) != REFUTED), None)
    if first is None:
        return result, False
    ordered = [first, *(cause for cause in causes if cause is not first)]
    return {**result, "candidateCauses": [{**cause, "priority": i + 1} for i, cause in enumerate(ordered)]}, True


_ID = re.compile(r"E-[A-Z]+-[A-Za-z0-9_-]+")
_NOT_FOUND = re.compile(r"조회되지\s*않|조회\s*결과가\s*없|찾을\s*수\s*없|확인되지\s*않|존재하지\s*않")


def move_text_ids(result: dict, known: set[str]) -> tuple[dict, int]:
    """근거 칸이 빈 관측 사실의 글에 조회한 근거 ID가 적혀 있으면 근거 칸으로 옮긴다 — (결과, 옮긴 사실 수).
    실제 모델은 「(evidenceId: E-RUN-…)」를 글에만 적고 칸을 비워 「근거 ID가 없는 사실」 반려를 되풀이했다"""
    moved, facts = 0, []
    for fact in result.get("observedFacts") or []:
        ids = [] if fact.get("evidenceIds") else [i for i in dict.fromkeys(_ID.findall(str(fact.get("text") or ""))) if i in known]
        moved += bool(ids)
        facts.append({**fact, "evidenceIds": ids} if ids else fact)
    return ({**result, "observedFacts": facts}, moved) if moved else (result, 0)


def not_found_facts_to_limitations(result: dict) -> tuple[dict, int]:
    """근거 없는 「조회되지 않음」 문장은 관측 사실이 아니라 한계다 — 한계로 옮긴다(관측 사실은 근거가 있어야 한다)"""
    keep, moved = [], []
    for fact in result.get("observedFacts") or []:
        (moved if not fact.get("evidenceIds") and _NOT_FOUND.search(str(fact.get("text") or "")) else keep).append(fact)
    if not moved:
        return result, 0
    limitations = result.get("limitations") or []
    limitations = [limitations] if isinstance(limitations, str) else list(limitations)
    return {**result, "observedFacts": keep, "limitations": [*limitations, *(str(fact["text"]) for fact in moved)]}, len(moved)

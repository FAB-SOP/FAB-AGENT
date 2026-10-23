"""규칙 요약 문장 — LLM 없이 확정 스냅샷만으로 만든다(규칙 모델 · 모델 요약이 검사를 못 넘을 때 · backend 시뮬레이터 · 시드).

문장 틀(backend가 Java로 같게 쓸 수 있게 단순하게 둔다):
  1줄: 「원인 분류: {분류 이름}[ · {장비 파라미터, …}] — {개요 첫 문장}」
  2줄: 「미해결: {미해결 첫 문장}」 — 미해결이 비었거나 「기록 없음」이면 생략
  줄당 120자 — 넘으면 마지막 공백에서 끊고 「…」. 공백 없이 긴 문자열이면 자르지 않는다(숫자 · 낱말을 강제로 자르지 않음)
  위 문장이 검사를 못 넘거나 120자 안에 끊을 자리가 없으면 「원인 분류: …. 상세는 확정 보고서 v{n}을 참고하세요.」 한 줄
  장비 파라미터가 많아 그 한 줄도 길면 「{첫 파라미터} 외 N개」로, 그래도 길면 파라미터를 빼고 「장비 파라미터 N개」로
"""

from __future__ import annotations

import re

from fab.agent.summary.guard import MAX_CHARS, validate_summary

LABELS = {"MATERIAL": "Material(자재)", "MAN": "Man(사람)", "METHOD": "Method(방법)", "MACHINE": "Machine(설비)",
          "HOLD": "보류(원인 미확정)"}
_SENTENCE_END = re.compile(r"(?<=[.!?。])\s+|\n+")


def _first_sentence(text: str | None) -> str:
    text = (text or "").strip()
    return _SENTENCE_END.split(text, maxsplit=1)[0].strip() if text and text != "기록 없음" else ""


def _clip(line: str) -> str | None:
    """120자 안으로 — 마지막 공백에서 끊는다. 끊을 공백이 없으면 None(낱말 · 숫자를 강제로 자르지 않는다)"""
    if len(line) <= MAX_CHARS:
        return line
    head = line[: MAX_CHARS - 1]
    if " " not in head:
        return None
    cut = head.rsplit(" ", 1)[0].rstrip(" ,·—-")
    return cut + "…" if cut else None


def _clip_after(prefix: str, text: str) -> str | None:
    """「{prefix} {text}」을 120자 안으로 — 잘라서 text가 한 낱말도 안 남으면 None"""
    line = _clip(f"{prefix} {text}")
    return line if line and len(line.removesuffix("…")) > len(prefix) else None


def _category(snapshot: dict, params_shown: int | None = None) -> str:
    """「원인 분류: {이름}[ · {파라미터}]」 — params_shown이면 그 개수만 보이고 「외 N개」, 0이면 「장비 파라미터 N개」"""
    classification = snapshot.get("classification") or {}
    label = LABELS.get((classification.get("category") or "").upper(), "미분류")
    params = list(classification.get("equipmentParameters") or [])
    if not params:
        return f"원인 분류: {label}"
    if params_shown is None or params_shown >= len(params):
        return f"원인 분류: {label} · {', '.join(params)}"
    if params_shown == 0:
        return f"원인 분류: {label} · 장비 파라미터 {len(params)}개"
    return f"원인 분류: {label} · {', '.join(params[:params_shown])} 외 {len(params) - params_shown}개"


def _minimal(snapshot: dict) -> list[str]:
    """대체 한 줄 — 120자 안에 들 때까지 파라미터를 줄인다"""
    tail = f". 상세는 확정 보고서 v{snapshot.get('reportVersion')}을 참고하세요."
    for shown in (None, 1, 0):
        line = _category(snapshot, shown) + tail
        if len(line) <= MAX_CHARS:
            return [line]
    return [f"상세는 확정 보고서 v{snapshot.get('reportVersion')}을 참고하세요."]


def rule_summary(snapshot: dict) -> dict:
    sections = snapshot.get("sections") or {}
    head, overview, unresolved = _category(snapshot), _first_sentence(sections.get("overview")), \
        _first_sentence(sections.get("unresolved"))
    lines = [_clip_after(head + " —", overview) if overview else _clip(head)] + \
        ([_clip_after("미해결:", unresolved)] if unresolved else [])
    evidence_ids = list(snapshot.get("evidenceIds") or [])[:3]
    if None in lines or validate_summary(lines, evidence_ids, snapshot):
        lines = _minimal(snapshot)
    return {"lines": lines, "evidenceIds": evidence_ids, "generatedBy": "rule"}

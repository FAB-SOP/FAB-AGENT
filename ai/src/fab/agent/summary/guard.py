"""요약 검사 — 확정 보고서를 읽는 사람이 보는 1~2줄이라, 확정 스냅샷에 없는 것은 하나도 들이지 않는다."""

from __future__ import annotations

import json
import re

from fab.agent.harness.guard import _NUM, _PRESCRIBE, _PROB, _numbers

MAX_LINES, MAX_CHARS = 2, 120
CATEGORIES = ("MATERIAL", "MAN", "METHOD", "MACHINE")  # 4M — HOLD(보류)는 원인 범주가 아니다
_CATEGORY = re.compile(r"\b(Material|Man|Method|Machine)\b", re.IGNORECASE)


def display_id(snapshot: dict) -> str | None:
    """사건 표시 ID — backend 스냅샷은 incidentDisplayId, 예전 모양은 displayId"""
    return snapshot.get("incidentDisplayId") or snapshot.get("displayId")


def section_texts(snapshot: dict) -> dict[str, str]:
    """칸별 글 — 값이 글이든 {title, content} 같은 객체든 글로 읽는다(backend 칸 값 모양 확정 전)"""
    out = {}
    for key, value in (snapshot.get("sections") or {}).items():
        if isinstance(value, dict):
            value = value.get("content") or value.get("text") or value.get("body") or ""
        out[key] = str(value or "")
    return out


def snapshot_text(snapshot: dict) -> str:
    """숫자 대조용 — 확정 스냅샷의 글 전부(섹션 · 원인 분류 · 표시 ID)"""
    classification = snapshot.get("classification") or {}
    return json.dumps({"displayId": display_id(snapshot), "sections": section_texts(snapshot),
                       "classification": classification}, ensure_ascii=False)


def validate_summary(lines: list[str], evidence_ids: list[str], snapshot: dict) -> list[str]:
    """줄 수 · 길이, 근거(스냅샷 근거 안에서 1개 이상), 숫자(스냅샷 글에 있는 것만), 확률 · 처방 금지,
    확정한 원인 분류와 다른 원인 범주 금지"""
    errors: list[str] = []
    if not 1 <= len(lines) <= MAX_LINES or any(not str(line).strip() for line in lines):
        errors.append(f"요약은 비어 있지 않은 1~2줄이어야 합니다(지금 {len(lines)}줄).")
    for line in lines:
        if len(line) > MAX_CHARS:
            errors.append(f"요약 한 줄은 {MAX_CHARS}자 이내여야 합니다(지금 {len(line)}자).")
    known_ids = set(snapshot.get("evidenceIds") or [])
    if known_ids and not evidence_ids:
        errors.append("요약의 근거 ID(evidenceIds)를 확정 보고서의 근거에서 1개 이상 고르세요.")
    unknown = sorted(set(evidence_ids) - known_ids)
    if unknown:
        errors.append(f"확정 보고서에 없는 근거 ID입니다: {unknown}")
    text = " ".join(lines)
    seen = _numbers(snapshot_text(snapshot))
    for token in _NUM.findall(text.replace(",", "")):
        value, decimals = float(token), len(token.split(".")[1]) if "." in token else 0
        if not any(round(known, decimals) == value for known in seen):
            errors.append(f"숫자 {token}는 확정 보고서에 없습니다. 보고서에 있는 값만 쓰세요.")
    if _PROB.search(text):
        errors.append("원인 확률을 숫자로 쓰지 않습니다.")
    if _PRESCRIBE.search(text):
        errors.append("설비 설정값 변경을 처방하지 않습니다(처방 표현이 있습니다).")
    category = ((snapshot.get("classification") or {}).get("category") or "").upper()
    named = {match.upper() for match in _CATEGORY.findall(text)}
    if named - ({category} & set(CATEGORIES)):
        errors.append(f"확정한 원인 분류({category or '없음'})와 다른 원인 범주({', '.join(sorted(named))})를 말합니다.")
    return errors

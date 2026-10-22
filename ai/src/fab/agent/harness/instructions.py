"""Instructions — 프롬프트를 코드에 박지 않고 prompts/*.md로 버전 관리한다."""

from __future__ import annotations

import hashlib
from pathlib import Path

PROMPTS = Path(__file__).resolve().parent.parent / "prompts"


def mode_text(mode: str) -> str:
    return (PROMPTS / "modes" / f"{mode}.md").read_text(encoding="utf-8")


def load(mode: str) -> tuple[str, str]:
    """(시스템 지시문, 버전 해시)를 돌려준다. 해시는 trace에 남겨 어떤 지시문으로 돌았는지 추적한다."""
    system = (PROMPTS / "system.md").read_text(encoding="utf-8")
    mode_text = (PROMPTS / "modes" / f"{mode}.md").read_text(encoding="utf-8")
    text = system + "\n\n" + mode_text
    return text, hashlib.sha256(text.encode()).hexdigest()[:12]

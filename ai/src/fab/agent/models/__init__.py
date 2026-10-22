"""모델 어댑터 — 모두 `await model.ainvoke(messages, tools) -> AIMessage` 모양.

- OpenAIModel (openai.py): FAB_LLM=openai (기본 모델 FAB_MODEL, 대체 모델 FAB_FALLBACK_MODEL)
- RuleModel   (rule.py)  : FAB_LLM=rule — API 키 없이 그래프 · 도구 · 승인/재개 배선을 검증하는 결정적 정책
"""

from __future__ import annotations

import os

from fab.agent.models.openai import OpenAIModel
from fab.agent.models.rule import RuleModel

__all__ = ["OpenAIModel", "RuleModel", "make_models"]


def make_models():
    kind = os.getenv("FAB_LLM", "openai" if os.getenv("OPENAI_API_KEY") else "rule")
    if kind == "openai":
        key = os.getenv("OPENAI_API_KEY", "")
        if not key or not key.startswith("sk-"):
            raise RuntimeError("OPENAI_API_KEY가 비어 있거나 형식이 다릅니다. ai/.env에 키를 넣거나, "
                               "키 없이 돌리려면 FAB_LLM=rule로 바꾸세요.")
        primary = OpenAIModel(os.getenv("FAB_MODEL", "gpt-4.1-mini"))
        fallback_name = os.getenv("FAB_FALLBACK_MODEL", "gpt-4o-mini")
        return primary, (OpenAIModel(fallback_name) if fallback_name and fallback_name != primary.name else None)
    return RuleModel(), None

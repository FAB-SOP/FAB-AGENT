"""Context — 모델 입력을 예산 안에 둔다. 원시 데이터 대신 도구 요약만, 오래된 도구 결과는 줄인다."""

from __future__ import annotations

import json

from langchain_core.messages import BaseMessage, ToolMessage

from fab.agent.harness.limits import LIMITS


def to_tool_text(result: dict) -> str:
    text = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    if len(text) > LIMITS.max_tool_result_chars:
        text = text[: LIMITS.max_tool_result_chars] + '…"[결과가 길어 잘렸습니다. 범위를 좁혀 다시 조회하세요]"'
    return text


def fit_budget(messages: list[BaseMessage], budget: int = LIMITS.context_char_budget) -> list[BaseMessage]:
    """총 글자 수가 예산을 넘으면, 최근 4개를 뺀 오래된 ToolMessage 본문을 짧게 줄인다(메시지 순서 · 짝은 유지)."""
    total = sum(len(str(message.content)) for message in messages)
    if total <= budget:
        return messages
    trimmed = list(messages)
    for i, message in enumerate(trimmed[:-4]):
        if isinstance(message, ToolMessage) and len(str(message.content)) > 300:
            trimmed[i] = ToolMessage(content=str(message.content)[:300] + "…[예산 초과로 요약됨]", tool_call_id=message.tool_call_id,
                                     name=message.name)
            total = sum(len(str(item.content)) for item in trimmed)
            if total <= budget:
                break
    return trimmed

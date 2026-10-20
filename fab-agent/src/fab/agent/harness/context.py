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
    total = sum(len(str(m.content)) for m in messages)
    if total <= budget:
        return messages
    out = list(messages)
    for i, m in enumerate(out[:-4]):
        if isinstance(m, ToolMessage) and len(str(m.content)) > 300:
            out[i] = ToolMessage(content=str(m.content)[:300] + "…[예산 초과로 요약됨]", tool_call_id=m.tool_call_id,
                                 name=m.name)
            total = sum(len(str(x.content)) for x in out)
            if total <= budget:
                break
    return out

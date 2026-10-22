"""OpenAI 모델 어댑터 — FAB_LLM=openai (기본 모델 FAB_MODEL, 대체 모델 FAB_FALLBACK_MODEL)."""

from __future__ import annotations

import os


class OpenAIModel:
    def __init__(self, model: str):
        from langchain_openai import ChatOpenAI

        self.name = model
        if model.startswith(("gpt-5", "o1", "o3", "o4")):
            # 추론 모델은 temperature를 받지 않는다. gpt-5.x는 도구 + 추론 설정을 Responses API에서만 받는다
            self._llm = ChatOpenAI(model=model, max_retries=0, use_responses_api=True,
                                   reasoning={"effort": os.getenv("FAB_REASONING_EFFORT", "low")})
        else:
            self._llm = ChatOpenAI(model=model, temperature=0, max_retries=0)

    async def ainvoke(self, messages, tools):
        return await self._llm.bind_tools(tools).ainvoke(messages)

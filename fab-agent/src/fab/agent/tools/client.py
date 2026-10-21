"""Toolbox — MCP Server의 도구를 모델이 쓸 수 있는 형태로 바꾸고, 호출을 통제한다.

- 업무 MCP · 지식 MCP에 FastMCP Client로 붙는다(URL 또는 같은 프로세스의 서버 객체).
- 모델에게는 읽기 도구 + Harness 도구(제안 · 제출, definitions.py)만 준다. 변경 도구는 숨기고 Harness만 승인 ID로 부른다.
- 결과는 길이를 자르고, 오류는 ToolMessage 문장으로 돌려준다. 일시 오류(UNAVAILABLE 등)는 재시도한다.
"""

from __future__ import annotations

import asyncio
import json
from contextlib import AsyncExitStack

from fastmcp import Client

from fab.agent.harness.guard import mask
from fab.agent.harness.limits import LIMITS
from fab.agent.tools.definitions import HARNESS_TOOLS, PROPOSALS, WRITE_TOOLS
from fab.common.envelope import RETRYABLE_CODES, parse_error_code


class ToolCallError(Exception):
    def __init__(self, text: str, code: str | None, retryable: bool):
        super().__init__(text)
        self.text, self.code, self.retryable = text, code, retryable


class Toolbox:
    def __init__(self, servers: dict[str, object]):
        """servers: {"business": URL 또는 FastMCP 객체, "knowledge": ...}"""
        self._targets = servers
        self._clients: dict[str, Client] = {}
        self._stack: AsyncExitStack | None = None
        self.tool_server: dict[str, str] = {}
        self.specs: list[dict] = []

    async def __aenter__(self):
        self._stack = AsyncExitStack()
        for name, target in self._targets.items():
            c = await self._stack.enter_async_context(Client(target, timeout=LIMITS.mcp_timeout))
            self._clients[name] = c
            for t in await c.list_tools():
                self.tool_server[t.name] = name
                if t.name in WRITE_TOOLS:
                    continue  # 모델에게 보이지 않는다
                self.specs.append({"type": "function", "function": {
                    "name": t.name, "description": t.description or "", "parameters": t.input_schema}})
        return self

    async def __aexit__(self, *exc):
        await self._stack.aclose()

    def model_tools(self, mode: str) -> list[dict]:
        allowed = {"REPORT": {"submit_report"}, "BASIC": {"submit_result"}}.get(mode, {"submit_result", *PROPOSALS})
        return self.specs + [t for t in HARNESS_TOOLS if t["function"]["name"] in allowed]

    async def call(self, name: str, args: dict, tracer, *, allow_write: bool = False) -> dict:
        if name in WRITE_TOOLS and not allow_write:
            raise ToolCallError(f"[FORBIDDEN] {name}는 승인된 실행에서만 Harness가 호출합니다. 제안 도구를 쓰세요.",
                                "FORBIDDEN", False)
        server = self.tool_server.get(name)
        if not server:
            raise ToolCallError(f"[NOT_FOUND] 도구 {name}가 없습니다.", "NOT_FOUND", False)
        client = self._clients[server]
        for attempt in range(1, LIMITS.llm_retries + 2):
            with tracer.span("tool", tool=name, server=server, attempt=attempt):
                try:
                    res = await asyncio.wait_for(client.call_tool(name, args, raise_on_error=False),
                                                 timeout=LIMITS.mcp_timeout)
                except asyncio.TimeoutError:
                    res = None
                    err_text, code = f"[TIMEOUT] {name}가 {LIMITS.mcp_timeout}초 안에 응답하지 않았습니다.", "TIMEOUT"
            if res is not None and not res.is_error:
                return mask(res.structured_content or {"text": "".join(getattr(b, "text", "") for b in res.content)})
            if res is not None:
                err_text = "".join(getattr(b, "text", "") for b in res.content)
                code = parse_error_code(err_text)
            retryable = code in RETRYABLE_CODES
            if not retryable or attempt > LIMITS.llm_retries:
                raise ToolCallError(err_text, code, retryable)
            tracer.event("tool_retry", tool=name, attempt=attempt, code=code)
            await asyncio.sleep(0.2 * attempt)
        raise AssertionError("unreachable")


def result_text(result: dict) -> str:
    return json.dumps(result, ensure_ascii=False)

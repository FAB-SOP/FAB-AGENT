"""업무 MCP ↔ Spring 내부 API — 서비스 토큰 · backend 장애 · 시연 장애 주입 후 회복."""

from fastmcp import Client

from fab.business.server import build_server
from fab.devtools.spring import internal


async def test_wrong_service_token_is_rejected(spring_url):
    async with Client(build_server(backend_url=spring_url, token="wrong")) as c:
        r = await c.call_tool("get_incident", {"incidentId": "EV-1009"}, raise_on_error=False)
        assert r.is_error and "UNAUTH" in r.content[0].text


async def test_backend_down_is_retryable_unavailable():
    async with Client(build_server(backend_url="http://127.0.0.1:1")) as c:
        r = await c.call_tool("get_incident", {"incidentId": "EV-1009"}, raise_on_error=False)
        assert r.is_error and "[UNAVAILABLE]" in r.content[0].text and "재시도 대상입니다" in r.content[0].text


async def test_injected_fault_then_recovers(spring_url):
    await internal("POST", "/internal/demo/faults", spring_url, json={"tool": "get_run_and_metrology", "count": 1})
    async with Client(build_server(backend_url=spring_url)) as c:
        r = await c.call_tool("get_run_and_metrology", {"runId": "RUN-W-CURRENT-006-3-CMP"}, raise_on_error=False)
        assert r.is_error and "[UNAVAILABLE]" in r.content[0].text
        r = await c.call_tool("get_run_and_metrology", {"runId": "RUN-W-CURRENT-006-3-CMP"})
        assert not r.is_error

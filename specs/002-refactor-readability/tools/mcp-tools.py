"""업무 MCP · 지식 MCP의 도구 목록(이름 · 설명 · 인자 스키마 · 주석 · 순서)과 리소스 템플릿을 JSON으로 출력한다.
리팩터링 전후 diff로 「모델이 보는 도구 표면」이 같은지 확인한다(contracts/preserved-surface.md).

사용: uv run --project fab-agent python specs/002-refactor-readability/tools/mcp-tools.py   (저장소 루트에서)
"""
import asyncio
import json
import os
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
os.environ["EMBEDDER"] = "hash"
os.environ.pop("PG_DSN", None)
os.environ.setdefault("FAB_PACK_DIR", str(REPO / "data" / "fab-causal-pack-v2"))
os.environ.setdefault("FAB_RAG_DIR", str(REPO / "FAB-실제문서-선별RAG-센서데이터-통합" / "fab-rag-real-documents-v1"))

from fastmcp import Client  # noqa: E402
from fab.business.server import build_server as build_business_server  # noqa: E402
from fab.rag.server import build_server as build_knowledge_server  # noqa: E402


async def describe(server):
    async with Client(server) as client:
        tools = [tool.model_dump(mode="json", exclude_none=True) for tool in await client.list_tools()]
        templates = [template.model_dump(mode="json", exclude_none=True)
                     for template in await client.list_resource_templates()]
    return {"tools": tools, "resourceTemplates": templates}


async def main():
    surface = {
        "business": await describe(build_business_server(backend_url="http://unused.invalid")),
        "knowledge": await describe(build_knowledge_server()),
    }
    print(json.dumps(surface, ensure_ascii=False, indent=1, sort_keys=False))


asyncio.run(main())

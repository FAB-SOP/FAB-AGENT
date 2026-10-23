"""Context — 모델 입력을 예산 안에 둔다. 원시 데이터 대신 도구 요약만, 오래된 도구 결과는 줄인다.

지식 검색 결과는 모델용으로 간추린다 — 문서 정보(제목 · 출처 · 적용 한계)는 문서마다 한 번, 조각은 근거 ID · 쪽 · 발췌만.
그대로 보내면 원문 topK=5가 6,000자를 넘어 다섯째 근거와 끝의 경고가 잘렸다. MCP 서버의 결과는 그대로다(모델에 보이는 모양만).
"""

from __future__ import annotations

import json
import re

from langchain_core.messages import BaseMessage, ToolMessage

from fab.agent.harness.limits import LIMITS

SEARCH_TOOLS = {"search_technical_documents", "search_applicable_playbooks", "search_past_cases"}
# 문서마다 한 번 싣는 정보 / 조각마다 싣는 정보 — 나머지(score · resourceUri · corpus · version · 중복 ID)는 모델에 보내지 않는다
_DOC_KEYS = ("title", "publisher", "sourceUrl", "origin", "synthetic", "applicabilityLimits",
             "figuresEquationsTablesRequireSourceCheck", "approvedForProductionAction")
_ITEM_KEYS = ("evidenceId", "chunkId", "docId", "originalPdfPage", "retrievalTier", "process", "causeId", "candidate",
              "sensorIds", "causeIds", "resolutionStatus", "caseDate", "excerpt")
_EVIDENCE_ID = re.compile(r'"evidenceId":"([^"]+)"')


def _search_view(result: dict) -> dict:
    """검색 결과의 모델용 모양 — 경고를 앞에, 문서 정보는 문서마다 한 번, 끝의 evidenceRefs(조각의 evidenceId와 같다)는 뺀다.
    서버가 한·영 용어를 병기해 검색했으면(augmentedQuery) 그 질의도 남긴다"""
    data = result.get("data") or {}
    documents: dict[str, dict] = {}
    items = []
    for item in data.get("items") or []:
        documents.setdefault(item.get("docId"), {k: item[k] for k in _DOC_KEYS if k in item})
        items.append({k: item[k] for k in _ITEM_KEYS if k in item})
    query = {"query": data.get("query"), **({"augmentedQuery": data["augmentedQuery"]} if data.get("augmentedQuery") else {})}
    return {"warnings": result.get("warnings") or [], "dataVersion": result.get("dataVersion"),
            "data": {**query, "documents": documents, "items": items}}


def to_tool_text(result: dict, tool: str | None = None) -> str:
    view = _search_view(result) if tool in SEARCH_TOOLS and isinstance(result.get("data"), dict) else result
    text = json.dumps(view, ensure_ascii=False, separators=(",", ":"))
    if len(text) > LIMITS.max_tool_result_chars:
        text = text[: LIMITS.max_tool_result_chars] + '…"[결과가 길어 잘렸습니다. 범위를 좁혀 다시 조회하세요]"'
    return text


def _trimmed(content: str) -> str:
    """줄인 도구 결과 — 앞 300자와 그 결과의 근거 ID(인용할 수 있게)"""
    ids = list(dict.fromkeys(_EVIDENCE_ID.findall(re.sub(r'":\s*"', '":"', content))))
    tail = f" 근거 ID: {', '.join(ids)}" if ids else ""
    return content[:300] + f"…[예산 초과로 요약됨{tail}]"


def fit_budget(messages: list[BaseMessage], budget: int = LIMITS.context_char_budget) -> list[BaseMessage]:
    """총 글자 수가 예산을 넘으면, 최근 4개를 뺀 오래된 ToolMessage 본문을 짧게 줄인다(메시지 순서 · 짝은 유지).
    줄여도 그 결과의 근거 ID는 남긴다 — 앞서 읽은 근거를 답에 인용할 수 있어야 한다"""
    total = sum(len(str(message.content)) for message in messages)
    if total <= budget:
        return messages
    trimmed = list(messages)
    for i, message in enumerate(trimmed[:-4]):
        if isinstance(message, ToolMessage) and len(str(message.content)) > 300:
            trimmed[i] = ToolMessage(content=_trimmed(str(message.content)), tool_call_id=message.tool_call_id,
                                     name=message.name)
            total = sum(len(str(item.content)) for item in trimmed)
            if total <= budget:
                break
    return trimmed

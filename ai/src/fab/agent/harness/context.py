"""Context — 모델 입력을 예산 안에 둔다. 원시 데이터 대신 도구 요약만, 오래된 도구 결과는 줄인다.

지식 검색 결과는 모델용으로 간추린다 — 문서 정보(제목 · 출처 · 적용 한계)는 문서마다 한 번, 조각은 근거 ID · 쪽 · 발췌만.
그대로 보내면 원문 topK=5가 6,000자를 넘어 다섯째 근거와 끝의 경고가 잘렸다. MCP 서버의 결과는 그대로다(모델에 보이는 모양만).
"""

from __future__ import annotations

import json
import re

from langchain_core.messages import BaseMessage, ToolMessage

from fab.agent.harness.limits import LIMITS
from fab.agent.harness.shrink import shrink

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


# 보고서 입력(get_report_context) — 모델에 보낼 칸만. 원인 분석은 후보 전체(반증 포함) · 한계를, 요청은 회신 · 거절 사유를 지킨다
_REQUEST_KEYS = ("requestId", "requestType", "origin", "status", "targetProcessCode", "equipmentId", "question", "causeIds",
                 "evidenceIds", "createdAt", "decisionAt", "decisionComment", "repliedAt", "withdrawnAt", "withdrawReason")
_BRIEF_KEYS = ("reason", "askFor", "suspectedCauses", "deviations", "relatedRecords")
_DEVIATION_KEYS = ("processCode", "equipmentId", "runId", "metric", "value", "unit", "z", "withinNormal")
_REPLY_KEYS = ("findings", "memo", "authorUserId", "evidenceIds", "confirmedByUser", "createdAt")
_WORK_KEYS = ("title", "operationLabel", "route", "status", "reason", "evidenceIds", "decisionComment", "closeReason",
              "requestId", "actionId", "replyId")
# 줄이는 순서 — 업무 후보 → 요청서 부속(관련 기록 · 편차 표 · 확인 항목) → 보고서 판 목록 → 요청서 본문 → (그래도 넘치면) 오래된 요청.
# 원인 분석의 후보(반증 포함) · 한계 · 답, 회신 · 거절 사유, 실제 조치는 빼지 않는다(글은 짧아질 수 있다)
_REPORT_DROP = (("data", "workItems"), ("data", "checkRequests", "*", "brief", "relatedRecords"),
                ("data", "checkRequests", "*", "brief", "deviations"), ("data", "checkRequests", "*", "brief", "askFor"),
                ("data", "reports"), ("data", "checkRequests", "*", "brief"))
_REPORT_KEEP = (("data", "latestCauseAnalysis", "candidateCauses"), ("data", "latestCauseAnalysis", "limitations"),
                ("data", "latestCauseAnalysis", "answer"), ("data", "checkRequests", "*", "reply", "findings"),
                ("data", "checkRequests", "*", "decisionComment"), ("data", "checkRequests", "*", "withdrawReason"),
                ("data", "actions"), ("data", "followupNote"))


def _pick(row: dict | None, keys: tuple[str, ...]) -> dict:
    return {k: row[k] for k in keys if isinstance(row, dict) and k in row}


def _report_view(result: dict) -> dict:
    """보고서 입력의 모델용 모양 — 내부 ID · 감사 칸을 빼고, 줄일 때 반증 · 미해결 · 회신이 먼저 빠지지 않게 순서를 정한다"""
    data = result.get("data") or {}
    requests = []
    for request in data.get("checkRequests") or []:
        brief = _pick(request.get("brief"), _BRIEF_KEYS)
        if brief.get("deviations"):
            brief["deviations"] = [_pick(d, _DEVIATION_KEYS) for d in brief["deviations"]]
        requests.append(_pick(request, _REQUEST_KEYS) | ({"brief": brief} if brief else {})
                        | ({"reply": _pick(request["reply"], _REPLY_KEYS)} if request.get("reply") else {}))
    requests.sort(key=lambda request: str(request.get("createdAt") or ""), reverse=True)  # 최신 요청 먼저 — 줄일 때 오래된 것부터 빠진다
    view = {"incident": {k: v for k, v in (data.get("incident") or {}).items() if k != "incidentId"},
            **({"latestCauseAnalysis": data["latestCauseAnalysis"]} if "latestCauseAnalysis" in data else {}),
            "checkRequests": requests, "actions": data.get("actions") or [],
            "workItems": [_pick(item, _WORK_KEYS) for item in data.get("workItems") or []],
            "reports": data.get("reports") or [], "followupNote": data.get("followupNote")}
    return {"warnings": result.get("warnings") or [], "dataVersion": result.get("dataVersion"),
            "evidenceRefs": result.get("evidenceRefs") or [], "data": view}


def to_tool_text(result: dict, tool: str | None = None) -> str:
    """모델에 보낼 도구 결과 — 한도를 넘으면 항목 단위로 줄인 정상 JSON(문자열을 중간에서 자르지 않는다, fab.agent.harness.shrink)"""
    report = tool == "get_report_context" and isinstance(result.get("data"), dict)
    view = (_search_view(result) if tool in SEARCH_TOOLS and isinstance(result.get("data"), dict)
            else _report_view(result) if report else result)
    text = json.dumps(view, ensure_ascii=False, separators=(",", ":"))
    if len(text) > LIMITS.max_tool_result_chars:
        view = shrink(view, LIMITS.max_tool_result_chars, drop_order=_REPORT_DROP if report else (),
                      protected=_REPORT_KEEP if report else ())
        text = json.dumps(view, ensure_ascii=False, separators=(",", ":"))
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

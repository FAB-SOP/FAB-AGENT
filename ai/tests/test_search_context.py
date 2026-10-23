"""검색 근거 읽기. 지식 검색 결과가 모델에게 잘리지 않고, 오래된 결과를 줄여도 근거 ID는 남는다."""

from __future__ import annotations

import json

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from fab.agent.harness.context import fit_budget, to_tool_text
from fab.agent.harness.limits import LIMITS


def _search(kb, tool: str, corpus: str, query: str, top_k: int = 5, **filters) -> dict:
    from fab.rag import server

    return server._result(kb, query, kb.search(query, corpus, filters, top_k), ["경고 문장"], corpus)


def test_five_document_chunks_fit_without_losing_ids(kb):
    """topK=5 원문 조각은 그대로 직렬화하면 약 7,000자라 6,000자 한도에서 다섯째 근거 ID와 경고가 잘렸다"""
    result = _search(kb, "search_technical_documents", "TECH", "CMP removal rate head_pressure pressure slurry", process="CMP")
    if len(result["data"]["items"]) < 5:  # 실제 원문 조각(rag/source-chunks.jsonl)은 git 밖이라 새로 받은 저장소에는 없다
        pytest.skip("실제 원문 조각이 없어 원문 검색 결과가 비었습니다 — data/fab-rag-real-documents-v1/rag/source-chunks.jsonl")
    assert len(json.dumps(result, ensure_ascii=False, separators=(",", ":"))) > LIMITS.max_tool_result_chars
    text = to_tool_text(result, "search_technical_documents")
    assert len(text) <= LIMITS.max_tool_result_chars
    view = json.loads(text)  # 잘리지 않은 JSON
    assert view["warnings"] == ["경고 문장"]
    assert [i["evidenceId"] for i in view["data"]["items"]] == [i["evidenceId"] for i in result["data"]["items"]]
    titles = {doc["title"] for doc in view["data"]["documents"].values()}
    assert text.count(next(iter(titles))) == 1  # 문서 정보는 문서마다 한 번
    item = view["data"]["items"][0]
    assert item["excerpt"] and item["docId"] in view["data"]["documents"] and "originalPdfPage" in item
    assert "applicabilityLimits" in view["data"]["documents"][item["docId"]]


def test_playbook_view_keeps_fields_the_rule_model_and_brief_read(kb):
    result = _search(kb, "search_applicable_playbooks", "PLAYBOOK", "CMP head_pressure 확인 점검", process="CMP")
    item = json.loads(to_tool_text(result, "search_applicable_playbooks"))["data"]["items"][0]
    for key in ("evidenceId", "chunkId", "causeId", "candidate", "sensorIds", "excerpt"):
        assert key in item, key


def test_augmented_query_reaches_the_model():
    """지식 MCP가 한·영 용어를 병기해 검색했으면(data.augmentedQuery) 모델도 어떤 질의로 찾았는지 본다 — 없으면 칸도 없다"""
    item = {"evidenceId": "E-DOC-D1-P1-C1", "chunkId": "D1-P1-C1", "docId": "D1", "title": "문서", "excerpt": "control limit"}
    result = {"data": {"query": "관리한계 이탈", "augmentedQuery": "관리한계(control limit) 이탈", "items": [item]},
              "evidenceRefs": [], "warnings": [], "dataVersion": "KNOWLEDGE-v2"}
    view = json.loads(to_tool_text(result, "search_technical_documents"))["data"]
    assert view["query"] == "관리한계 이탈" and view["augmentedQuery"] == "관리한계(control limit) 이탈"
    del result["data"]["augmentedQuery"]
    assert "augmentedQuery" not in json.loads(to_tool_text(result, "search_technical_documents"))["data"]


def test_other_tools_are_serialized_as_before():
    result = {"data": {"runId": "R-1"}, "evidenceRefs": [{"evidenceId": "E-1"}], "warnings": []}
    assert to_tool_text(result, "get_run_features") == json.dumps(result, ensure_ascii=False, separators=(",", ":"))


def test_trimmed_old_results_keep_their_evidence_ids():
    """예산을 넘어 오래된 도구 결과를 300자로 줄여도, 그 결과의 근거 ID는 남아 모델이 인용할 수 있다"""
    big = json.dumps({"data": {"items": [{"evidenceId": f"E-DOC-{i}", "excerpt": "x" * 900} for i in range(8)]},
                      "evidenceRefs": [{"evidenceId": f"E-DOC-{i}"} for i in range(8)]}, ensure_ascii=False)
    messages = [HumanMessage(content="질문")]
    for n in range(10):
        messages += [AIMessage(content="", tool_calls=[{"id": f"c{n}", "name": "search_technical_documents", "args": {}}]),
                     ToolMessage(content=big, tool_call_id=f"c{n}", name="search_technical_documents")]
    trimmed = fit_budget(messages, budget=20000)
    short = [m for m in trimmed if isinstance(m, ToolMessage) and "예산 초과" in str(m.content)]
    assert short and all("E-DOC-7" in str(m.content) and "E-DOC-0" in str(m.content) for m in short)

"""MCP Server — 업무 MCP(Spring 내부 API 어댑터) 도구 · 계산 결과 · 오류, 지식 MCP 코퍼스 · 검색 · Resource."""

import json

import pytest
from fastmcp import Client

from fab.business.server import build_server as business
from fab.common.env import rag_dir
from fab.rag.server import build_server as knowledge

# 실제 원문 조각은 공개 저장소에 없다(출판자 저작물) — 있을 때만 원문 검색을 확인한다
HAS_SOURCE = (rag_dir() / "rag" / "source-chunks.jsonl").exists()
needs_source = pytest.mark.skipif(not HAS_SOURCE, reason="실제 원문 조각(source-chunks.jsonl)이 없다 — 팀 내부로 받는다")

READ = {"get_incident", "list_incidents", "get_wafer_lineage", "get_run_and_metrology", "get_run_features",
        "get_sensor_trace", "get_frozen_limits", "get_change_events", "get_process_owner", "get_check_requests",
        "get_action_history", "get_report_context"}
WRITE = {"create_cross_process_check_request", "create_maintenance_request", "confirm_report"}


@pytest.fixture
def biz(spring_url):
    return business(backend_url=spring_url)


async def data(c, tool, **args):
    r = await c.call_tool(tool, args)
    return r.structured_content["data"]


async def test_business_tool_catalog_follows_pack_contract(biz):
    async with Client(biz) as c:
        tools = {t.name: t for t in await c.list_tools()}
    assert set(tools) == READ | WRITE
    descs = [t.description for t in tools.values()]
    assert len(set(descs)) == len(descs), "도구 설명이 겹치면 모델이 고르기 어렵다"
    assert all(tools[n].annotations.destructive_hint for n in WRITE)
    assert all(tools[n].annotations.read_only_hint for n in READ)


async def test_l009_lineage_and_server_side_calculation(biz):
    async with Client(biz) as c:
        lin = await data(c, "get_wafer_lineage", waferId="W-CURRENT-009-3")
        assert lin["path"] == "CVD:CVD-PM1 → ETCH:ETCH-PM2 → CMP:CMP-PM3"  # 같은 PM3끼리 잇지 않는다
        etch = await data(c, "get_run_and_metrology", runId="RUN-W-CURRENT-009-3-ETCH")
        cmp_ = await data(c, "get_run_and_metrology", runId="RUN-W-CURRENT-009-3-CMP")
    assert len(etch["sites"]) == 5 and etch["amountKind"] == "REMOVED"
    assert etch["amountA"] == pytest.approx(4896.3, abs=0.1) and not etch["amountWithinNormalVariation"]
    assert not etch["r1"]["outside"]  # Etch 잔막은 R1 안
    assert cmp_["r1"]["side"] == "ABOVE_UCL" and cmp_["oos"] is False  # OOC이며 OOS 아님
    assert cmp_["amountWithinNormalVariation"]  # CMP 제거량은 정상 변동 안


async def test_features_traces_and_limits(biz):
    async with Client(biz) as c:
        f6 = await data(c, "get_run_features", runId="RUN-W-CURRENT-006-3-CMP")
        f7 = await data(c, "get_run_features", runId="RUN-W-CURRENT-007-3-CMP")
        tr = await data(c, "get_sensor_trace", runId="RUN-W-CURRENT-007-3-CMP", sensorId="slurry_flow", maxPoints=10)
        lim = await data(c, "get_frozen_limits", processCode="CMP")
    assert f6["outsideReferenceBand"] == ["head_pressure"] and f7["outsideReferenceBand"] == ["slurry_flow"]
    assert tr["step"] == "PROCESS" and len(tr["points"]) <= 12 and tr["summary"]["mean"] < 120
    q = lim["qualityLimits"][0]
    assert q["rule"] == "R1_ONLY" and q["usl"] == 11000 and q["frozen"] is True


async def test_sensor_trace_segments_match_validation(biz):
    """DEMO-SEGMENT-V2 구간 — 전달 묶음 검증값(L006 head_pressure 101.1–210.9초 LOW, L007 slurry_flow 14–309초 LOW)"""
    async with Client(biz) as c:
        r6 = (await c.call_tool("get_sensor_trace", {"runId": "RUN-W-CURRENT-006-3-CMP", "sensorId": "head_pressure"})).structured_content
        r7 = await data(c, "get_sensor_trace", runId="RUN-W-CURRENT-007-3-CMP", sensorId="slurry_flow")
        r9 = await data(c, "get_sensor_trace", runId="RUN-W-CURRENT-009-3-CMP", sensorId="head_pressure")
        r1 = await data(c, "get_sensor_trace", runId="RUN-W-CURRENT-001-3-CMP", sensorId="head_pressure")
    s6, = r6["data"]["segments"]
    assert (s6["fromSeconds"], s6["toSeconds"], s6["direction"], s6["confirmedAtSeconds"]) == (101.1, 210.9, "LOW", 106.1)
    assert s6["mean"] == pytest.approx(2.5707481856233008) and s6["ruleVersion"] == "DEMO-SEGMENT-V2"
    assert r6["data"]["sensorEnvelope"]["calibratedForRealTool"] is False
    assert "E-SEG-RUN-W-CURRENT-006-3-CMP-head_pressure" in [e["evidenceId"] for e in r6["evidenceRefs"]]
    assert any("고장 발생 시점" in w for w in r6["warnings"])
    s7, = r7["segments"]
    assert (s7["fromSeconds"], s7["toSeconds"], s7["direction"]) == (14.0, 309.0, "LOW")
    assert s7["mean"] == pytest.approx(109.94891546959455)
    assert r9["segments"] == [] and r1["segments"] == []  # 앞 공정 원인 · 정상 LOT — 구간을 만들지 않는다
    assert len(json.dumps(r6, ensure_ascii=False)) < 6000  # 도구 결과 한도 안


async def test_errors_are_readable_and_followup_is_blocked(biz):
    async with Client(biz) as c:
        r = await c.call_tool("get_incident", {"incidentId": "EV-9999"}, raise_on_error=False)
        assert r.is_error and "[NOT_FOUND]" in r.content[0].text and "EV-1009" in r.content[0].text
        r = await c.call_tool("get_run_and_metrology", {"runId": "RUN-W-FOLLOWUP-001-1-CMP"}, raise_on_error=False)
        assert r.is_error and "[FORBIDDEN]" in r.content[0].text and "분석 시점" in r.content[0].text
        r = await c.call_tool("get_sensor_trace", {"runId": "RUN-W-BASELINE-001-1-CMP", "sensorId": "head_pressure"},
                              raise_on_error=False)
        assert r.is_error and "[NOT_FOUND]" in r.content[0].text and "get_run_features" in r.content[0].text
        r = await c.call_tool("create_cross_process_check_request", {"approvalId": "apv_nope", "idempotencyKey": "key-00000001"},
                              raise_on_error=False)
        assert r.is_error and "[NOT_FOUND]" in r.content[0].text


async def test_knowledge_corpora_follow_ingestion_policy(kb):
    info = kb.index_info["corpora"]
    assert info == {"TECH": 100 if HAS_SOURCE else 0, "PLAYBOOK": 15, "CASE": 7}  # 공개 요약 9개는 실제 원문과 겹쳐 뺐다
    assert not any(c.chunkId.startswith("PUB-") for c in kb.chunks.values())
    assert not any("DO-NOT-INDEX" in str(c.meta) for c in kb.chunks.values())


@needs_source
async def test_knowledge_search_tools_and_resources(kb):
    async with Client(knowledge(kb)) as c:
        tech = (await c.call_tool("search_technical_documents", {"query": "I chart control limits out of control",
                                                                 "retrievalTier": "STATISTICAL_INTERPRETATION"})).structured_content
        pb = (await c.call_tool("search_applicable_playbooks", {"query": "CMP head_pressure 점검", "process": "CMP"})).structured_content
        case = (await c.call_tool("search_past_cases", {"query": "Etch 잔막 상승 CMP OOC", "process": "ETCH"})).structured_content
        t0 = tech["data"]["items"][0]
        assert t0["docId"] == "DOC07" and t0["synthetic"] is False and t0["applicabilityLimits"]
        assert pb["data"]["items"][0]["causeId"] == "CMP_PRESSURE" and pb["synthetic"] is True
        assert case["data"]["items"][0]["chunkId"] == "DEMO-CASE-003"
        text = (await c.read_resource(t0["resourceUri"]))[0].text
        assert text.startswith("[실제 원문 발췌") and "적용 한계" in text
        assert (await c.read_resource("case://DEMO-CASE-003"))[0].text.startswith("[가상 사례")
        # 적재 정책의 검색 조건(retrievalFilters): 문서 · 판으로 좁힌다
        only = (await c.call_tool("search_technical_documents", {"query": "slurry pad wear removal", "documentId": "DOC01",
                                                                 "topK": 5})).structured_content["data"]["items"]
        assert only and {i["documentId"] for i in only} == {"DOC01"} and {i["version"] for i in only} == {"2026-10-20"}
        none = (await c.call_tool("search_technical_documents", {"query": "slurry", "version": "1999-01-01"})).structured_content
        assert none["data"]["items"] == [] and any("찾지 못했습니다" in w for w in none["warnings"])

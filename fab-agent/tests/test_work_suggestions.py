"""새 기본 경로: 자동 정비 요청 대신 분류된 업무 제안."""
from pathlib import Path
import re
import httpx
from fastmcp import Client
from fab.agent.harness.guard import validate_result
from fab.agent.models.rule import _submit
from fab.agent.tools.client import Toolbox
from fab.agent.tools.definitions import WORK_OPERATIONS
from fab.business.server import build_server


def valid_result():
    return {"nextStep":"CURRENT_CHECK","observedFacts":[{"text":"압력 편차 관측","evidenceIds":["E-1"]}],
            "candidateCauses":[{"priority":1,"hypothesis":"헤드 압력 후보","causeIds":["CMP_PRESSURE"],"supportingEvidenceIds":["E-1"]}],
            "workSuggestions":[{"operationCode":"REVIEW_SENSOR_DATA","title":"압력 실측과 설정값 비교","reason":"압력 편차 확인 필요","evidenceIds":["E-1"]}],"limitations":[]}


def test_work_evidence_and_operation_are_validated():
    result=valid_result()
    assert validate_result(result,{"E-1"},"CMP")==[]
    result["workSuggestions"][0]["evidenceIds"]=["INVENTED"]
    assert any("INVENTED" in error for error in validate_result(result,{"E-1"},"CMP"))
    result["workSuggestions"][0]["operationCode"]="EXECUTE_ANYTHING"
    assert any("operationCode" in error for error in validate_result(result,{"E-1"},"CMP"))


def test_new_default_hides_legacy_request_proposal_tools(monkeypatch):
    monkeypatch.delenv("FAB_LEGACY_REQUEST_PROPOSALS",raising=False)
    names={tool["function"]["name"] for tool in Toolbox({}).model_tools("DEEP")}
    assert names=={"submit_result"}
    monkeypatch.setenv("FAB_LEGACY_REQUEST_PROPOSALS","1")
    assert "propose_maintenance_request" in {tool["function"]["name"] for tool in Toolbox({}).model_tools("DEEP")}


def test_rule_result_proposes_local_check_without_request():
    call=_submit("CURRENT_CHECK",[{"text":"관측","evidenceIds":["E-1"]}],"압력 편차",{"causeId":"CMP_PRESSURE"},["E-1"]).tool_calls[0]
    assert call["name"]=="submit_result"
    assert call["args"]["workSuggestions"][0]["operationCode"]=="REVIEW_SENSOR_DATA"


def test_operation_hints_match_backend_authoritative_policy():
    java=Path(__file__).resolve().parents[2]/"fab-context-api/src/main/java/com/fabcontext/api/domain/work/WorkOperation.java"
    assert set(WORK_OPERATIONS)==set(re.findall(r'^\t([A-Z_]+)\("',java.read_text(),re.MULTILINE))


async def test_new_mcp_tools_are_read_only_thin_adapters():
    paths=[]
    def backend(request):
        paths.append(request.url.path)
        return httpx.Response(200,json={"data":{"data":{"version":"WORK-POLICY-V1"} if request.url.path.endswith("work-policy") else {"items":[]},"evidenceRefs":[],"warnings":[]}})
    async with Client(build_server(backend_url="http://backend",transport=httpx.MockTransport(backend))) as client:
        tools={tool.name:tool for tool in await client.list_tools()}
        assert tools["get_work_policy"].annotations.readOnlyHint
        assert tools["get_work_items"].annotations.readOnlyHint
        assert not (await client.call_tool("get_work_policy",{})).is_error
        assert not (await client.call_tool("get_work_items",{"incidentId":"EV-1006"})).is_error
    assert paths==["/internal/work-policy","/internal/incidents/EV-1006/work-items"]


async def test_new_default_persists_suggestions_without_sending_requests(spring_url,kb,monkeypatch):
    import uuid
    from fab.agent.worker import poll_once
    from fab.devtools.spring import User,make_runner
    monkeypatch.delenv("FAB_LEGACY_REQUEST_PROPOSALS",raising=False)
    runner,backend=make_runner(kb,base=spring_url)
    event={"eventId":"WORK-TEST-"+str(uuid.uuid4()),"anomalyType":"THICKNESS_OOC_R1","severity":"MEDIUM","processCode":"CMP","equipmentId":"CMP-PM3","lotId":"CURRENT-L006","waferIds":["W-CURRENT-006-3"],"runIds":["RUN-W-CURRENT-006-3-CMP"],"sensorIds":["head_pressure"],"detectedAt":"2026-10-19T01:30:00Z","metrics":{}}
    async with httpx.AsyncClient(base_url=spring_url) as client:
        response=await client.post("/api/events/anomalies",headers={"X-Service-Token":"dev-simulator-token"},json=event)
    assert response.status_code==202,response.text
    incident=response.json()["data"]["incidentId"]
    await poll_once(runner,backend)
    user=await User("cmp.park",spring_url).login()
    status,tasks=await user.get(f"/api/incidents/{incident}/work-items")
    assert status==200 and tasks["data"]["items"],tasks
    assert tasks["data"]["items"][0]["route"]=="SELF_CHECK"
    assert tasks["data"]["items"][0]["status"]=="PROPOSED"
    _,requests=await user.get(f"/api/check-requests?box=sent&incidentId={incident}")
    assert requests["data"]["items"]==[]
    _,actions=await user.get(f"/api/incidents/{incident}/actions")
    assert actions["data"]["items"]==[]

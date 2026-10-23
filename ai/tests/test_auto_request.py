"""원인 질문의 답에 요청서가 없으면 Harness가 근거 있는 요청서 초안을 낸다 — 보낼지는 사람이 승인으로 정한다.

실제 모델은 답 접수 뒤 「요청서를 낼지 정하세요」 알림을 받고도 요청서를 내지 않고 끝나곤 했다(같은 원인 질문에 냈다 안 냈다).
"""

from langchain_core.messages import AIMessage

from fab.agent.models.rule import RuleModel
from fab.agent.tools import PROPOSALS
from fab.agent.worker import poll_once
from fab.devtools.spring import User, fresh_incident, make_runner


class AnswerWithoutRequestModel(RuleModel):
    """규칙 모델과 같은 답을 내되 요청서는 내지 않는다"""

    async def ainvoke(self, messages, tools, *args, **kwargs):
        reply = await super().ainvoke(messages, tools)
        calls = [call for call in reply.tool_calls if call["name"] not in PROPOSALS]
        return AIMessage(content=reply.content, tool_calls=calls)


async def _ask(spring_url, kb, source, question):
    runner, backend = make_runner(kb, models=(AnswerWithoutRequestModel(), None), base=spring_url)
    display = await fresh_incident(source, spring_url)
    await poll_once(runner, backend)
    user = await User("cmp.park", spring_url).login()
    incident = await user.incident(display)
    status, accepted = await user.post(f"/api/incidents/{incident['incidentId']}/questions",
                                       {"question": question, "expectedIncidentVersion": incident["version"]})
    assert status == 202, accepted
    return user, (await poll_once(runner, backend))[-1]


async def test_cause_answer_without_request_gets_a_harness_drafted_maintenance_request(spring_url, kb):
    user, out = await _ask(spring_url, kb, "EV-1006", "원인 분석해줘")
    assert out["status"] == "WAITING_APPROVAL", out
    _, approval = await user.get(f"/api/approvals/{out['interrupt']['approvalId']}")
    payload = approval["data"]["payload"]
    assert approval["data"]["actionType"] == "MAINTENANCE_REQUEST"
    assert payload["targetProcessCode"] == "CMP" and payload["causeIds"] == ["CMP_PRESSURE"]
    assert out["result"]["answer"]  # 답이 먼저 접수돼 있다
    await user.close()


async def test_upstream_answer_without_request_gets_a_harness_drafted_check_request(spring_url, kb):
    user, out = await _ask(spring_url, kb, "EV-1009", "CMP 장비 고장이야?")
    assert out["status"] == "WAITING_APPROVAL", out
    _, approval = await user.get(f"/api/approvals/{out['interrupt']['approvalId']}")
    assert approval["data"]["actionType"] == "CHECK_REQUEST"
    assert approval["data"]["payload"]["targetProcessCode"] == "ETCH"
    await user.close()


async def test_fact_question_gets_no_drafted_request(spring_url, kb):
    user, out = await _ask(spring_url, kb, "EV-1006", "CMP 제거량 알려줘")
    assert out["status"] == "DONE", out
    assert not out.get("interrupt")
    await user.close()

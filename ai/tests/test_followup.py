"""추가 분석 — 작업자가 고른 조사 결과(회신 · 내 작업 결과)로 다시 분석한다. 회신만으로는 분석이 돌지 않는다(backend).

backend는 작업 맥락의 질문 칸에 「추가 질문 + 안내 줄 + 선택 결과 JSON」을 싣는다(AgentJobService.jobContext).
Agent는 그것을 나눠 선택 결과를 진술 근거(E-REQ-* · E-ACT-*)로 등록하고, 결과가 인용하면 backend가 실제 인용으로 센다.
"""

from __future__ import annotations

import json

from fab.agent import purpose
from fab.agent.followup import MARKER, evidence_of, split_question

INPUTS = [{"sourceRef": {"type": "REPLY", "id": "rpl_1"}, "incidentId": "inc_1", "requestId": "req_1",
           "content": "레귤레이터 출력이 낮고 다이어프램 마모 관찰", "authorUserId": "USR-MNT-01",
           "createdAt": "2026-10-22T10:00:00Z", "evidenceIds": [], "evidenceId": "E-REQ-req_1"},
          {"sourceRef": {"type": "ACTION", "id": "act_1"}, "incidentId": "inc_1", "content": "센서 교정 확인 — 정상",
           "authorUserId": "USR-CMP-01", "createdAt": "2026-10-22T11:00:00Z", "evidenceIds": [], "evidenceId": "E-ACT-act_1"}]


def _backend_question(question: str, inputs: list[dict]) -> str:
    """AgentJobService.jobContext와 같은 모양 — question + 안내 줄 + JSON"""
    return question + MARKER + json.dumps(inputs, ensure_ascii=False)


def test_split_reads_question_and_selected_results():
    question, inputs = split_question(_backend_question("이 결과로 원인 후보를 갱신해 주세요.", INPUTS))
    assert question == "이 결과로 원인 후보를 갱신해 주세요." and inputs == INPUTS


def test_plain_question_has_no_selected_results():
    assert split_question("헤드 압력이 왜 낮아?") == ("헤드 압력이 왜 낮아?", None)
    assert split_question(None) == ("", None)


def test_broken_json_is_kept_as_plain_question():
    text = "질문" + MARKER + "{깨진"
    assert split_question(text) == (text, None)


def test_selected_results_become_statement_evidence():
    evidence = evidence_of(INPUTS, "PACK-1")
    assert evidence["E-REQ-req_1"] | {} == {"evidenceId": "E-REQ-req_1", "sourceType": "REPLY", "sourceId": "rpl_1",
                                         "dataVersion": "PACK-1", "synthetic": True}
    assert evidence["E-ACT-act_1"]["sourceType"] == "ACTION"  # 내 작업 결과 — 계약 근거 종류 ACTION


def test_followup_purpose_follows_question_rules_and_updates_cause_analysis():
    assert purpose.purpose_of("DEEP", "FOLLOWUP_REQUEST") == purpose.FOLLOWUP
    assert purpose.can_propose(purpose.FOLLOWUP, False) and not purpose.can_propose(purpose.FOLLOWUP, True)
    assert purpose.result_kind(purpose.FOLLOWUP, False) == "CAUSE_ANALYSIS"


async def test_start_puts_selected_results_in_conversation_as_untrusted_data():
    from fab.agent.graph.nodes.start import start

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        tracer = _Tracer()
    out = await start({"mode": "DEEP", "jobId": "JOB-1", "incidentId": "inc_1", "question": "갱신해 주세요",
                       "selectedInputs": INPUTS, "dataVersion": "PACK-1"}, {"configurable": {"runtime": _Runtime()}})
    note = next(str(m.content) for m in out["messages"] if "선택한 조사 결과" in str(m.content))
    assert "<untrusted_data" in note and "E-REQ-req_1" in note and "E-ACT-act_1" in note and "다이어프램" in note
    assert set(out["evidence"]) == {"E-REQ-req_1", "E-ACT-act_1"}


async def test_followup_after_reply_cites_the_selected_reply(spring_url, kb):
    """회신 → (회신 대기 재개로 정리) → 같은 회신을 다시 골라 추가 분석(새 DEEP) → 결과가 회신을 실제 인용한다"""
    from fab.devtools.spring import OWNER_OF_PROCESS, User, fresh_incident, internal, make_runner, request_followup
    from fab.agent.worker import poll_once
    from fab.devtools.spring import drive

    display = await fresh_incident("EV-1009", spring_url)
    flow = await drive(display, "원인 분석과 필요한 요청을 제안해줘", kb=kb, verbose=False, base=spring_url, report=False)
    assert flow["proposals"], "회신을 만들 요청이 있어야 한다"
    requests = (await internal("GET", f"/internal/incidents/{display}/check-requests", spring_url))["data"]["data"]["items"]
    replied = next(r for r in requests if r.get("status") == "REPLIED")
    process = (await internal("GET", f"/internal/incidents/{display}", spring_url))["data"]["data"]["processCode"]
    engineer = await User(OWNER_OF_PROCESS[process], spring_url).login()
    accepted = await request_followup(engineer, display, [{"type": "REPLY", "id": replied["reply"]["replyId"]}],
                                      "이 회신을 반영해 원인 후보를 갱신해 주세요.")
    assert accepted["executionMode"] == "NEW"
    runner, backend = make_runner(kb, base=spring_url)
    outcome = next(o for o in await poll_once(runner, backend) if o["job"]["jobId"] == accepted["jobId"])
    assert outcome["status"] == "DONE"
    cited = {ref["evidenceId"] for ref in outcome["result"]["evidenceRefs"]}
    assert f"E-REQ-{replied['requestId']}" in cited
    status, tracked = await engineer.get(f"/api/analysis-requests/{accepted['analysisRequestId']}")
    await engineer.close()
    assert status == 200 and [i["citedInResult"] for i in tracked["data"]["inputs"]] == [True]


def test_resume_memo_carries_question_and_other_selected_results():
    """회신 대기 재개 — backend가 reply.memo에 {analysisRequestId, question, selectedInputs} JSON을 싣는다. 일반 메모 글은 무시"""
    from fab.agent.followup import memo_inputs

    memo = json.dumps({"analysisRequestId": "ar_1", "question": "교정 결과도 반영해 주세요", "selectedInputs": INPUTS})
    assert memo_inputs(memo) == ("교정 결과도 반영해 주세요", INPUTS)
    assert memo_inputs("담당자 메모") == ("", []) and memo_inputs(None) == ("", [])

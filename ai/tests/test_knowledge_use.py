"""지식 MCP 사용 — 점검 문서는 Harness가 미리 찾고, 문서 근거를 요구하는 질문은 원문(E-DOC) 인용을 요구한다.

실제 모델은 업무 근거가 대화에 다 있으면 지식 검색 없이 바로 제출했다(기본 분석 12건 중 검색 0건). 규칙 모델은 늘
검색해서 시험이 이를 못 잡았다 — 여기서는 검색하지 않고 바로 제출하는 모델로 본다.
"""

from __future__ import annotations

from langchain_core.messages import AIMessage, ToolMessage

from fab.agent import doc_citation
from fab.agent.causes import CAUSES
from fab.agent.evidence import runs_by_process, tool_results
from fab.agent.harness.limits import LIMITS
from fab.agent.worker import poll_once
from fab.devtools.spring import User, fresh_incident, make_runner


def test_document_questions_are_recognized():
    assert doc_citation.asks_for_documents("압력이 떨어지면 왜 덜 깎이는지 원문 자료로 보여줘.")
    assert doc_citation.asks_for_documents("슬러리 공급이 줄면 왜 제거량이 줄어드는지 원리를 알려줘")
    assert not doc_citation.asks_for_documents("슬러리 쪽 원인일 가능성은 몇 퍼센트쯤 돼?")
    assert not doc_citation.asks_for_documents(None)


def test_document_citation_counts_supporting_and_conflicting_evidence():
    assert doc_citation.cites_document({"observedFacts": [{"evidenceIds": ["E-DOC-TECH-01"]}]})
    assert doc_citation.cites_document({"candidateCauses": [{"conflictingEvidenceIds": ["E-DOC-TECH-01"]}]})
    assert not doc_citation.cites_document({"observedFacts": [{"evidenceIds": ["E-PB-DEMO-CMP_PRESSURE"]}]})


class LazyModel:
    """검색하지 않고 대화에 있는 업무 근거만으로 바로 제출하는 모델 — 실제 모델에서 본 행동"""
    name = "lazy"

    def __init__(self):
        self.calls: list[dict] = []

    async def ainvoke(self, messages, tools):
        names = {tool["function"]["name"] for tool in tools}
        deep = "propose_cross_process_check" in names
        seen = tool_results(messages)
        self.sizes = {"playbooks": sum(len(str(m.content)) for m in messages if isinstance(m, ToolMessage)
                                       and m.name == "search_applicable_playbooks"),
                      "total": sum(len(str(m.content)) for m in messages)}
        self.calls.append({"deep": deep, "playbooks": "search_applicable_playbooks" in seen,
                           "rejected": [str(m.content) for m in messages if isinstance(m, ToolMessage)
                                        and m.name == "submit_result" and str(m.content).startswith("[REJECTED]")]})
        process = (seen["get_incident"][-1].get("data") or {})["processCode"]
        run = runs_by_process(seen)[process]
        cause = next(cause_id for cause_id, (cause_process, _) in CAUSES.items() if cause_process == process)
        result = {"nextStep": "CURRENT_CHECK", "limitations": [],
                  "observedFacts": [{"text": f"{run['processCode']} run 제거량 확인", "evidenceIds": [run["_ev"]]}],
                  "candidateCauses": [{"priority": 1, "hypothesis": f"{run['processCode']} 공정 조건 변화", "causeIds": [cause],
                                       "supportingEvidenceIds": [run["_ev"]], "missingData": [], "nextCheck": "현장 확인"}]}
        if deep:
            result["answer"] = f"{run['processCode']} 공정 조건 변화가 1순위입니다({run['_ev']})."
        return AIMessage(content="", tool_calls=[{"id": f"s{len(messages)}", "name": "submit_result", "args": result}])


async def test_playbooks_are_in_the_conversation_before_the_first_turn(spring_url, kb):
    model = LazyModel()
    runner, backend = make_runner(kb, models=(model, None), base=spring_url)
    display = await fresh_incident("EV-1006", spring_url)
    out = (await poll_once(runner, backend))[-1]  # 기본 분석 — 모델이 검색하지 않아도 점검 문서가 대화에 있다
    assert out["status"] == "DONE" and model.calls[0] == {"deep": False, "playbooks": True, "rejected": []}
    # 대화가 늘어나는 만큼 — 점검 문서 6,000자 · 전체 맥락 예산 안
    assert model.sizes["playbooks"] <= 6000 and model.sizes["total"] <= LIMITS.context_char_budget, model.sizes


async def test_document_question_is_sent_back_once_then_recorded_as_a_limitation(spring_url, kb):
    model = LazyModel()
    runner, backend = make_runner(kb, models=(model, None), base=spring_url)
    display = await fresh_incident("EV-1006", spring_url)
    await poll_once(runner, backend)
    u = await User("cmp.park", spring_url).login()
    incident = await u.incident(display)
    status, _ = await u.post(f"/api/incidents/{incident['incidentId']}/questions",
                             {"question": "압력이 떨어지면 왜 덜 깎이는지 원문 자료로 보여줘.",
                              "expectedIncidentVersion": incident["version"]})
    assert status == 202
    out = (await poll_once(runner, backend))[-1]
    deep = [call for call in model.calls if call["deep"]]
    # 한 번 돌려보내고(두 번째 호출이 반려를 본다), 세 번째는 요청서가 없다는 안내 뒤의 호출이다
    assert len(deep) == 3 and "search_technical_documents" in deep[1]["rejected"][0]
    assert out["status"] == "DONE" and any("문서 근거 미확인" in item for item in out["result"]["limitations"])  # 두 번째는 받는다


def test_document_cited_only_in_the_answer_text_is_not_enough():
    """평가 · 화면은 관측 사실 · 원인 후보의 근거 목록을 본다 — answer 글의 괄호 인용만으로는 원문 근거로 묶이지 않는다"""
    from fab.agent.graph.nodes.tools import _check_documents

    class _Turn:
        state = {"question": "기술 문서 근거로 설명해줘"}
        counters = {}
    _, error = _check_documents(_Turn(), {"answer": "압력이 낮으면 제거량이 준다(E-DOC-1).",
                                          "observedFacts": [{"evidenceIds": ["E-1"]}]})
    assert error and "evidenceIds" in error

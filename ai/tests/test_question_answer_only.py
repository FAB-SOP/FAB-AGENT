"""질문 작업은 물은 것만 답한다 — 수치 · 사실 질문에 원인 후보 · 할 일 후보 · 요청 권유를 붙이지 않고, 앞 질문 · 답을 참고로 안다.

10-23 화면: 「CMP 공정에서 제거량 알려줘」에 가설 3개가, 「제거량 정확한 수치로는 못 알려줘?」에 세 공정의 원인 분석이 다시 나왔다.
"""

from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage

from fab.agent.recent import note
from fab.agent.question_kind import answer_only
from fab.agent.tools.definitions import ANSWER_SUBMIT, HARNESS_TOOLS

FACT = {"nextStep": "OBSERVE", "observedFacts": [{"text": "CMP 제거량 4,983.2 Å", "evidenceIds": ["E-1"]}],
        "limitations": [], "answer": "CMP 제거량은 4,983.2 Å로 정상 변동 안입니다(E-1)."}
SUGGESTION = {"operationCode": "REVIEW_SENSOR_DATA", "title": "CMP 센서 실측 비교", "reason": "제거량 확인", "evidenceIds": ["E-1"]}


def _submit(tool: dict) -> dict:
    return tool["function"]["parameters"]


@pytest.mark.parametrize("question", ["CMP 공정에서 제거량 알려줘", "제거량 정확한 수치로는 못 알려줘?",
                                      "CMP-PM3의 다음 PM 예정일과 지난 달 슬러리 로트 교체 이력을 알려줘"])
def test_fact_questions_are_answer_only(question):
    assert answer_only(question)


@pytest.mark.parametrize("question", ["원인 분석해줘", "CMP 제거량은 정상인데 두께가 UCL을 넘은 이유를 숫자로 알려줘",
                                      "헤드 압력이 고장이야?", "ETCH 영향 가능성이 얼마나 돼?", "무엇을 확인해야 해?", "", None])
def test_cause_and_judgement_questions_keep_cause_analysis(question):
    assert not answer_only(question)  # 헷갈리면 원인 질문으로 둔다


def test_answer_submit_has_no_cause_or_work_fields():
    base = _submit(next(t for t in HARNESS_TOOLS if t["function"]["name"] == "submit_result"))
    assert "candidateCauses" in base["required"]  # 다른 작업 · 원인 질문은 그대로
    props = _submit(ANSWER_SUBMIT)["properties"]
    assert "candidateCauses" not in props and "workSuggestions" not in props and "answer" in props
    assert set(_submit(ANSWER_SUBMIT)["required"]) == {"nextStep", "observedFacts", "limitations"}


def test_model_tools_swap_submit_only_for_answer_only_questions():
    from fab.agent.tools.client import Toolbox

    box = Toolbox.__new__(Toolbox)
    box.specs = []
    names = lambda tools: {t["function"]["name"]: t for t in tools}  # noqa: E731
    assert names(box.model_tools("DEEP", allow_proposals=False, answer_only=True))["submit_result"] is ANSWER_SUBMIT
    assert names(box.model_tools("DEEP", allow_proposals=True))["submit_result"] is not ANSWER_SUBMIT


def test_answer_only_questions_never_propose():
    from fab.agent.purpose import may_propose

    assert may_propose({"mode": "DEEP", "purpose": "QUESTION"})
    assert not may_propose({"mode": "DEEP", "purpose": "QUESTION", "answerOnly": True})


def _turn(purpose: str = "QUESTION", **extra):
    from fab.agent.graph.nodes.tools import _Turn
    from fab.agent.harness import limits

    class _Tracer:
        events: list = []

        def event(self, kind, **kwargs):
            self.events.append(kind)

    class _Runtime:
        tracer = _Tracer()
    state = {"mode": "DEEP", "purpose": purpose, "messages": [AIMessage(content="", tool_calls=[])],
             "counters": limits.new_window(), "evidence": {"E-1": {"evidenceId": "E-1"}},
             "proposals": [], "result": None, "incidentProcess": "CMP"} | extra
    return _Turn(state, {"configurable": {"runtime": _Runtime()}})


def test_fact_answer_without_causes_is_accepted_and_sheds_work_suggestions():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn()
    handle_submit(turn, "submit_result", FACT | {"workSuggestions": [SUGGESTION]}, "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    assert turn.update["result"]["workSuggestions"] == [] and "candidateCauses" not in turn.update["result"]
    assert "work_suggestions_dropped" in turn.rt.tracer.events
    assert not turn.update.get("autoRequest")  # 사실 답에는 요청서를 내지 않는다


def test_answer_only_submit_sheds_causes_the_model_sent_anyway():
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn(answerOnly=True)
    cause = {"priority": 1, "hypothesis": "앞 공정 ETCH 영향", "causeIds": ["ETCH_WALL"], "supportingEvidenceIds": ["E-1"],
             "missingData": [], "nextCheck": "ETCH 담당자 확인"}
    handle_submit(turn, "submit_result", FACT | {"nextStep": "UPSTREAM_CHECK", "candidateCauses": [cause],
                                                 "workSuggestions": [SUGGESTION]}, "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    result = turn.update["result"]
    assert "candidateCauses" not in result and "workSuggestions" not in result
    assert "answer_only_dropped" in turn.rt.tracer.events


async def test_fact_answer_reaches_the_result_without_causes():
    from fab.agent.graph.nodes.finalize import finalize
    from fab.agent.graph.nodes.tools import handle_submit

    turn = _turn()
    handle_submit(turn, "submit_result", FACT, "c1")

    class _Runtime:
        job_id = "JOB-1"
    out = await finalize({"mode": "DEEP", "purpose": "QUESTION", "incidentId": "INC-1", "jobId": "JOB-1", "proposals": [],
                          "evidence": {"E-1": {"evidenceId": "E-1"}}, "result": turn.update["result"],
                          "counters": turn.counters}, {"configurable": {"runtime": _Runtime()}})
    assert out["result"]["candidateCauses"] == [] and out["result"]["answer"].startswith("CMP 제거량은 4,983.2")
    assert out["workSuggestions"] == []


HISTORY = {"items": [  # 최신순 — backend 사건 이력 모양
    {"type": "QUESTION_ASKED", "summary": "추가 조사 질문: 제거량 정확한 수치로는 못 알려줘?", "sourceRef": {"type": "JOB", "id": "job_now"}},
    {"type": "ANSWER", "summary": "CMP 제거량은 4,983.2 Å로 정상 변동 안입니다.", "sourceRef": {"type": "ANALYSIS_RESULT", "id": "res_2"}},
    {"type": "WORK_PROPOSED", "summary": "업무 제안: ETCH 확인", "sourceRef": {"type": "WORK_ITEM", "id": "task_1"}},
    {"type": "QUESTION_ASKED", "summary": "추가 조사 질문: CMP 공정에서 제거량 알려줘", "sourceRef": {"type": "JOB", "id": "job_prev"}},
    {"type": "CAUSE_ANALYSIS", "summary": "앞 공정 ETCH 기여", "sourceRef": {"type": "ANALYSIS_RESULT", "id": "res_1"}},
]}


def test_recent_note_lists_earlier_questions_and_answers_oldest_first():
    text = note(HISTORY, "job_now")
    assert text.index("질문: CMP 공정에서 제거량 알려줘") < text.index("AI 답: CMP 제거량은 4,983.2")
    assert "정확한 수치로는" not in text  # 지금 질문은 빼고
    assert "업무 제안" not in text and "앞 공정 ETCH 기여" not in text  # 질문 · 답만(기본 분석은 사건 도구가 준다)
    assert "근거가 아니다" in text and "untrusted_data" in text


def test_recent_note_is_empty_without_earlier_questions():
    assert note({"items": [HISTORY["items"][0]]}, "job_now") is None
    assert note({}, "job_now") is None


async def test_start_adds_recent_note_for_questions_and_survives_history_failure():
    from fab.agent.graph.nodes.start import start

    class _Tracer:
        events: list = []

        def event(self, kind, **kwargs):
            self.events.append(kind)

    class _Backend:
        def __init__(self, fail=False):
            self.fail = fail

        async def incident_history(self, incident_id):
            if self.fail:
                raise RuntimeError("down")
            return HISTORY

    class _Runtime:
        def __init__(self, backend):
            self.backend, self.tracer, self.job_id = backend, _Tracer(), "job_now"

    state = {"mode": "DEEP", "purpose": "QUESTION", "jobId": "job_now", "incidentId": "INC-1",
             "question": "제거량 정확한 수치로는 못 알려줘?"}
    out = await start(state, {"configurable": {"runtime": _Runtime(_Backend())}})
    assert any("incident-history" in str(m.content) for m in out["messages"])
    out = await start(state, {"configurable": {"runtime": _Runtime(_Backend(fail=True))}})
    assert not any("incident-history" in str(m.content) for m in out["messages"])
    out = await start(state | {"purpose": "BASIC", "mode": "BASIC"}, {"configurable": {"runtime": _Runtime(_Backend())}})
    assert not any("incident-history" in str(m.content) for m in out["messages"])


def test_words_after_an_accepted_answer_end_the_job_instead_of_a_resubmit_loop():
    """요청서 알림 뒤 모델이 말로 「제출했습니다」라고만 하면 — 예전에는 「반드시 submit_result로」를 한도까지 되풀이했고,
    결국 그 말을 answer로 다시 제출해 좋은 답을 덮어썼다(10-23 재현: 「이전 답변에서 이미 submit_result로 결과를 제출했습니다」)"""
    from fab.agent.graph.routes import route_agent

    said = AIMessage(content="결과는 이미 submit_result로 제출되어 있습니다.")
    assert route_agent({"messages": [said], "result": FACT}) == "finalize"
    assert route_agent({"messages": [said], "result": None}) == "tools"  # 답이 없으면 지금처럼(agent 노드가 제출을 요구)


async def test_agent_node_does_not_nag_once_an_answer_is_accepted():
    from fab.agent.graph.nodes.agent import agent
    from fab.agent.harness import limits

    class _Model:
        name = "m"

        async def ainvoke(self, messages, tools=None):
            return AIMessage(content="결과는 이미 제출되어 있습니다.")

    class _Tracer:
        def event(self, *a, **k):
            pass

        def span(self, *a, **k):
            import contextlib
            return contextlib.nullcontext({})

    class _Tools:
        def model_tools(self, mode, *, allow_proposals, answer_only=False):
            return []

    class _Runtime:
        toolbox, tracer, primary, fallback = _Tools(), _Tracer(), _Model(), _Model()

    state = {"mode": "DEEP", "purpose": "QUESTION", "messages": [AIMessage(content="")], "counters": limits.new_window(),
             "result": FACT, "proposals": []}
    out = await agent(state, {"configurable": {"runtime": _Runtime()}})
    assert [m.type for m in out["messages"]] == ["ai"]  # 제출 독촉(human) 없음


async def test_start_marks_fact_questions_answer_only():
    from fab.agent.graph.nodes.start import start

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Backend:
        async def incident_history(self, incident_id):
            return {"items": []}

    class _Runtime:
        backend, tracer, job_id = _Backend(), _Tracer(), "job_now"

    base = {"mode": "DEEP", "purpose": "QUESTION", "jobId": "job_now", "incidentId": "INC-1"}
    fact = await start(base | {"question": "CMP 공정에서 제거량 알려줘"}, {"configurable": {"runtime": _Runtime()}})
    assert fact["answerOnly"] and any("[답만 하는 질문]" in str(m.content) for m in fact["messages"])
    cause = await start(base | {"question": "원인 분석해줘"}, {"configurable": {"runtime": _Runtime()}})
    assert not cause["answerOnly"]

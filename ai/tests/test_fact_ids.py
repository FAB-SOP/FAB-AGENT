"""관측 사실의 근거 ID — 실제 모델은 근거를 조회해 놓고 사실 글 안에 「(evidenceId: E-RUN-…)」로 적고 근거 칸은 비우거나,
「L013 조회되지 않음」 같은 미확인 문장을 근거 없는 관측 사실로 냈다. 「근거 ID가 없는 사실」 반려가 되풀이돼 판단 한도로 빈 답이 됐다.
글 속 ID가 조회한 ID면 근거 칸으로 옮기고, 근거 없는 미확인 문장은 한계로 옮긴다."""

from langchain_core.messages import AIMessage

from fab.agent.harness.autofix import move_text_ids, not_found_facts_to_limitations


def test_known_id_written_in_the_text_moves_to_evidence_ids():
    result = {"observedFacts": [{"text": "CMP 두께가 관리한계 밖 (evidenceId: E-RUN-RUN-X-CMP)", "evidenceIds": []}]}
    out, moved = move_text_ids(result, {"E-RUN-RUN-X-CMP"})
    assert moved == 1 and out["observedFacts"][0]["evidenceIds"] == ["E-RUN-RUN-X-CMP"]


def test_unseen_id_in_the_text_is_not_moved():
    result = {"observedFacts": [{"text": "값 (E-RUN-RUN-NOPE)", "evidenceIds": []}]}
    out, moved = move_text_ids(result, {"E-RUN-RUN-X-CMP"})
    assert moved == 0 and out is result


def test_fact_with_evidence_is_left_alone():
    result = {"observedFacts": [{"text": "값 (E-RUN-RUN-X-CMP)", "evidenceIds": ["E-1"]}]}
    assert move_text_ids(result, {"E-RUN-RUN-X-CMP", "E-1"})[1] == 0


def test_unsupported_not_found_fact_moves_to_limitations():
    result = {"observedFacts": [{"text": "CMP run 확인", "evidenceIds": ["E-1"]},
                                {"text": "CURRENT-L088 로트의 웨이퍼 경로가 조회되지 않음", "evidenceIds": []}], "limitations": []}
    out, moved = not_found_facts_to_limitations(result)
    assert moved == 1 and [fact["text"] for fact in out["observedFacts"]] == ["CMP run 확인"]
    assert out["limitations"] == ["CURRENT-L088 로트의 웨이퍼 경로가 조회되지 않음"]


def test_unsupported_ordinary_fact_is_not_moved():
    result = {"observedFacts": [{"text": "CMP 제거량이 크게 줄었다", "evidenceIds": []}], "limitations": []}
    assert not_found_facts_to_limitations(result)[1] == 0


def test_submit_with_ids_in_text_and_a_not_found_fact_is_accepted():
    from fab.agent.graph.nodes.tools import _Turn, handle_submit
    from fab.agent.harness import limits

    class _Runtime:
        class tracer:
            @staticmethod
            def event(*args, **kwargs):
                pass

    state = {"mode": "DEEP", "messages": [AIMessage(content="", tool_calls=[])], "counters": limits.new_window(),
             "evidence": {"E-RUN-RUN-X-CMP": {}}, "proposals": [], "result": None, "incidentProcess": "CMP"}
    turn = _Turn(state, {"configurable": {"runtime": _Runtime()}})
    result = {"nextStep": "INSUFFICIENT_EVIDENCE", "candidateCauses": [], "limitations": [],
              "observedFacts": [{"text": "CMP 두께가 관리한계 밖 (evidenceId: E-RUN-RUN-X-CMP)", "evidenceIds": []},
                                {"text": "요청한 로트는 조회되지 않음", "evidenceIds": []}]}
    handle_submit(turn, "submit_result", result, "c1")
    assert str(turn.out[-1].content).startswith("[ACCEPTED]"), turn.out[-1].content
    assert turn.update["result"]["observedFacts"][0]["evidenceIds"] == ["E-RUN-RUN-X-CMP"]
    assert "요청한 로트는 조회되지 않음" in turn.update["result"]["limitations"]


async def test_reply_without_a_tool_call_records_why():
    """마지막 호출(제출 강제) 뒤에도 제출 없이 끝나는 일이 있었다 — 도구 호출 없는 응답은 글 길이 · 깨진 호출 수를 trace에 남긴다"""
    from fab.agent.graph.nodes.agent import agent
    from fab.agent.graph.state import Runtime
    from fab.agent.harness import limits
    from fab.agent.harness.trace import Tracer
    from langchain_core.messages import HumanMessage

    class TextOnly:
        name = "text-only"

        async def ainvoke(self, messages, tools, tool_choice=None):
            return AIMessage(content="결과를 글로만 씁니다", tool_calls=[],
                             invalid_tool_calls=[{"name": "submit_result", "args": "{깨진", "id": "x", "error": "json"}])

    class Tools:
        def model_tools(self, mode, *, allow_proposals, answer_only=False):
            return [{"function": {"name": "submit_result"}}]

    tracer = Tracer("T", "J", "I")
    counters = {**limits.new_window(), "modelCalls": limits.LIMITS.max_model_calls - 1}
    state = {"counters": counters, "mode": "DEEP", "messages": [HumanMessage(content="[사건 EV-1006] 확인")]}
    await agent(state, {"configurable": {"runtime": Runtime(Tools(), None, TextOnly(), None, tracer)}})
    rec = next(r for r in tracer.records if r["kind"] == "no_tool_call")
    assert rec["lastCall"] is True and rec["textChars"] == len("결과를 글로만 씁니다") and rec["invalidToolCalls"] == 1

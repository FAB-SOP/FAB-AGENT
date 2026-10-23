"""사건 단위 규칙(harness/incident_rules.py) — 순수 함수라 backend 없이 돈다."""

from fab.agent.harness.incident_rules import proposal_block

CHECK, MNT = "CREATE_CHECK_REQUEST", "CREATE_MAINTENANCE_REQUEST"


def _req(kind: str, target: str, status: str, at: str = "2026-10-22T10:00:00+09:00") -> dict:
    return {"requestType": kind, "targetProcessCode": target, "status": status, "createdAt": at,
            "reply": {"createdAt": at} if status == "REPLIED" else None}


def _never(_at) -> bool:
    return False


def test_same_key_open_blocks():
    reason = proposal_block(CHECK, "ETCH", [_req("CROSS_PROCESS", "ETCH", "OPEN")], [], _never, 3)
    assert reason and "진행 중" in reason


def test_pending_approval_blocks():
    reason = proposal_block(MNT, "CMP", [], [{"actionType": MNT, "targetProcessCode": "CMP", "status": "PENDING"}], _never, 3)
    assert reason and "진행 중" in reason


def test_other_team_after_reply_allowed():
    """ETCH 확인 요청에 회신이 왔으면, 같은 사건이라도 설비정비팀 점검 요청은 새로 낼 수 있다"""
    assert proposal_block(MNT, "CMP", [_req("CROSS_PROCESS", "ETCH", "REPLIED")], [], _never, 3) is None


def test_replied_same_key_blocked_until_cause_changes():
    """이미 회신받은 같은 팀 · 종류 요청은 다시 내지 않는다(10/21 문제 2) — 그 뒤 원인 분석이 바뀌었으면 허용"""
    requests = [_req("CROSS_PROCESS", "ETCH", "REPLIED")]
    reason = proposal_block(CHECK, "ETCH", requests, [], _never, 3)
    assert reason and "회신" in reason
    assert proposal_block(CHECK, "ETCH", requests, [], lambda _at: True, 3) is None


def test_rejected_blocked_until_cause_changes():
    approvals = [{"actionType": MNT, "targetProcessCode": "CMP", "status": "REJECTED", "decidedAt": "2026-10-22T10:00:00+09:00"}]
    reason = proposal_block(MNT, "CMP", [], approvals, _never, 3)
    assert reason and "거절" in reason
    assert proposal_block(MNT, "CMP", [], approvals, lambda _at: True, 3) is None


def test_limit_three_ignores_superseded():
    requests = [_req("CROSS_PROCESS", "ETCH", "REPLIED"), _req("MAINTENANCE", "CMP", "REPLIED")]
    superseded = [{"actionType": CHECK, "targetProcessCode": "CVD", "status": "SUPERSEDED"}]
    assert proposal_block(CHECK, "CVD", requests, superseded, _never, 3) is None  # 대체됨은 세지 않는다 → 2개
    rejected = [{"actionType": CHECK, "targetProcessCode": "CVD", "status": "REJECTED", "decidedAt": None}]
    reason = proposal_block(CHECK, "ETCH", requests, rejected, lambda _at: True, 3)
    assert reason and "한도" in reason  # 회신 둘 + 거절 하나 = 3


def test_cancelled_request_does_not_count_or_block():
    assert proposal_block(CHECK, "ETCH", [_req("CROSS_PROCESS", "ETCH", "CANCELLED")], [], _never, 1) is None


# ---- 결론 변경 검사(확정 16)

from fab.agent.harness.incident_rules import compare_conclusion, conclusion_change_error  # noqa: E402

BASELINE = {"resultId": "RESULT-job_a", "nextStep": "UPSTREAM_CHECK", "topCauseIds": ["ETCH_WALL"], "targetProcess": "ETCH",
            "evidenceIds": ["E-RUN-ETCH", "E-CHG-1"],
            "candidateCauses": [{"priority": 1, "hypothesis": "ETCH 챔버 벽 상태로 제거량 감소", "causeIds": ["ETCH_WALL"],
                                 "supportingEvidenceIds": ["E-RUN-ETCH"]}]}


def _result(next_step: str, cause_ids: list[str], **extra) -> dict:
    return {"nextStep": next_step, "observedFacts": [], "limitations": [],
            "candidateCauses": [{"priority": 1, "hypothesis": "가설", "causeIds": cause_ids}] if cause_ids else [], **extra}


def test_compare_no_baseline_is_unchanged():
    assert compare_conclusion(_result("CURRENT_CHECK", ["CMP_PRESSURE"]), None)["changed"] is False


def test_compare_answer_without_causes_is_unchanged():
    """원인 후보 없이 사실만 답한 질문(예: 다음 PM 일정) — 결론을 바꾼 것이 아니다"""
    assert compare_conclusion(_result("INSUFFICIENT_EVIDENCE", []), BASELINE)["changed"] is False


def test_compare_detects_top_cause_change():
    cmp = compare_conclusion(_result("CURRENT_CHECK", ["CMP_PAD"]), BASELINE)
    assert cmp["changed"] and set(cmp["fields"]) == {"topCause", "nextStep", "targetProcess"}
    assert cmp["baselineResultId"] == "RESULT-job_a"
    assert compare_conclusion(_result("UPSTREAM_CHECK", ["ETCH_WALL"]), BASELINE)["changed"] is False


def test_change_needs_reason_and_new_evidence():
    cmp = compare_conclusion(_result("CURRENT_CHECK", ["CMP_PAD"]), BASELINE)
    known = {"E-RUN-ETCH", "E-CHG-1", "E-FEAT-CMP"}
    assert "새 근거" in conclusion_change_error(cmp, {}, BASELINE, known, 0)
    old_only = {"changeReason": "다시 보니 CMP", "changeEvidenceIds": ["E-RUN-ETCH"]}  # 기준 결과가 이미 인용한 근거뿐
    assert conclusion_change_error(cmp, old_only, BASELINE, known, 0)
    unseen = {"changeReason": "CMP 패드", "changeEvidenceIds": ["E-NOT-FETCHED"]}  # 이번에 조회하지 않은 근거
    assert conclusion_change_error(cmp, unseen, BASELINE, known, 0)


def test_change_with_new_evidence_passes():
    cmp = compare_conclusion(_result("CURRENT_CHECK", ["CMP_PAD"]), BASELINE)
    ok = {"changeReason": "CMP 패드 사용 시간이 교체 주기를 넘었다", "changeEvidenceIds": ["E-FEAT-CMP"]}
    assert conclusion_change_error(cmp, ok, BASELINE, {"E-FEAT-CMP", "E-RUN-ETCH"}, 0) is None


def test_second_rejection_is_left_to_caller():
    """같은 작업에서 두 번째부터는 막지 않는다 — 호출부가 기준 순위를 유지하고 새 가능성을 2순위로 내린다"""
    cmp = compare_conclusion(_result("CURRENT_CHECK", ["CMP_PAD"]), BASELINE)
    assert conclusion_change_error(cmp, {}, BASELINE, set(), 1) is None


def test_human_requested_skips_replied_and_rejected_rules():
    """사람이 연 요청서 작업(업무 후보 수락 · 다시 만들기)은 회신받은 · 거절된 같은 요청을 다시 보낼 수 있다 — 진행 중 · 한도만 본다"""
    replied = [_req("CROSS_PROCESS", "ETCH", "REPLIED")]
    assert proposal_block(CHECK, "ETCH", replied, [], _never, 3, human_requested=True) is None
    rejected = [{"actionType": CHECK, "targetProcessCode": "ETCH", "status": "REJECTED", "decidedAt": None}]
    assert proposal_block(CHECK, "ETCH", [], rejected, _never, 3, human_requested=True) is None
    assert "진행 중" in proposal_block(CHECK, "ETCH", [_req("CROSS_PROCESS", "ETCH", "OPEN")], [], _never, 3, human_requested=True)


# ---- 받는 사람의 수락 · 거절(backend 업무 요청 수락 단계) — OPEN → ACCEPTED → REPLIED, 또는 OPEN → REJECTED

def test_accepted_request_is_still_in_progress():
    """수락은 회신이 아니다 — ACCEPTED도 OPEN처럼 진행 중이라 같은 팀 · 종류 요청을 또 내지 않는다"""
    reason = proposal_block(CHECK, "ETCH", [_req("CROSS_PROCESS", "ETCH", "ACCEPTED")], [], _never, 3)
    assert reason and "진행 중" in reason


def test_request_rejected_by_owner_blocked_until_cause_changes():
    """받는 사람이 거절한 요청은 회신이 아니다 — 거절된 승인처럼, 원인 분석이 바뀐 뒤에만 다시 낸다"""
    requests = [_req("CROSS_PROCESS", "ETCH", "REJECTED") | {"decisionAt": "2026-10-22T11:00:00+09:00"}]
    reason = proposal_block(CHECK, "ETCH", requests, [], _never, 3)
    assert reason and "거절" in reason
    assert proposal_block(CHECK, "ETCH", requests, [], lambda _at: True, 3) is None
    assert proposal_block(CHECK, "ETCH", requests, [], _never, 3, human_requested=True) is None


# ---- 사람이 연 요청서 작업의 범위 — 지정한 대상 공정으로만(재생성 · 대체는 이번 MVP에서 제외)

from fab.agent.harness.incident_rules import request_scope_error  # noqa: E402


def test_request_for_a_named_process_stays_on_it():
    assert request_scope_error(CHECK, "ETCH", {"targetProcessCode": "ETCH"}) is None
    reason = request_scope_error(CHECK, "CVD", {"targetProcessCode": "ETCH"})
    assert reason and "ETCH" in reason


def test_plain_request_without_scope_is_free():
    assert request_scope_error(MNT, "CMP", {}) is None
    assert request_scope_error(MNT, "CMP", None) is None


# ---- 한도 도달 이유 — 모델에게만 알리던 것을 결과에도 남겨 사람이 왜 요청서가 없는지 본다

async def test_limit_rejection_is_kept_for_the_result(monkeypatch):
    from langchain_core.messages import AIMessage

    import importlib

    from fab.agent.harness import limits

    tools = importlib.import_module("fab.agent.graph.nodes.tools")  # 패키지가 같은 이름의 함수 tools를 내보내서 모듈을 직접 가져온다

    async def _limit(turn, args, action_type):
        return "[LIMIT] 이 사건의 요청 한도(3개)에 닿았습니다."
    monkeypatch.setattr(tools, "_proposal_rejection", lambda *a, **k: None)
    monkeypatch.setattr(tools, "_incident_block", _limit)

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        tracer = _Tracer()
    state = {"mode": "DEEP", "messages": [AIMessage(content="", tool_calls=[])], "counters": limits.new_window(),
             "evidence": {}, "proposals": [], "briefs": {"c1": {"brief": {}}}}
    turn = tools._Turn(state, {"configurable": {"runtime": _Runtime()}})
    await tools.save_proposal(turn, "propose_cross_process_check", {"processCode": "ETCH"}, "c1")
    assert turn.update["proposalLimit"] is True
    assert str(turn.out[-1].content).startswith("[LIMIT]")


async def test_finalize_says_why_no_request_was_proposed():
    from fab.agent.graph.nodes.finalize import finalize

    class _Runtime:
        job_id = "JOB-1"
    state = {"mode": "DEEP", "incidentId": "INC-1", "jobId": "JOB-1", "evidence": {"E-1": {"evidenceId": "E-1"}},
             "proposalLimit": True,
             "result": {"nextStep": "UPSTREAM_CHECK", "observedFacts": [{"text": "사실", "evidenceIds": ["E-1"]}],
                        "candidateCauses": [], "limitations": []}}
    out = await finalize(state, {"configurable": {"runtime": _Runtime()}})
    assert out["analysis"]["proposalLimit"] is True
    assert any("요청 한도" in line and "업무 후보" in line for line in out["result"]["limitations"])

"""작업 입력 — 실행 목적을 작업 메시지의 공개 input.purpose로 받는다(아름님과 합의). 전환 기간에는 기존 방식(맥락 trigger ·
질문 칸에 붙은 선택 결과)도 읽고, 둘이 함께 오면 input.purpose가 우선 — 서로 모순되면 INVALID_INPUT으로 실패한다.

  kind BASIC   → BASIC        kind REPORT → REPORT
  kind DEEP    → QUESTION · FOLLOWUP(input.followup.selectedInputs 필수) · REQUEST · REGENERATE(input.request.supersedesApprovalId 필수)
"""

from __future__ import annotations

import json

import pytest

from fab.agent import purpose
from fab.agent.followup import MARKER
from fab.agent.job_input import InvalidJobInput, resolve

SELECTED = [{"sourceRef": {"type": "REPLY", "id": "rpl_1"}, "evidenceId": "E-REQ-req_1", "content": "확인 결과",
             "authorUserId": "USR-MNT-01", "createdAt": "2026-10-23T01:00:00Z", "requestId": "req_1", "evidenceIds": []}]


def _job(kind="DEEP", **input_) -> dict:
    return {"kind": kind, **({"input": input_} if input_ else {})}


def test_legacy_inputs_still_work():
    assert resolve(_job(), "헤드 압력이 왜 낮아?", None)["purpose"] == purpose.QUESTION
    assert resolve(_job(), "질문", "REQUEST_BUTTON")["purpose"] == purpose.REQUEST
    legacy = resolve(_job(), "갱신해 주세요" + MARKER + json.dumps(SELECTED, ensure_ascii=False), None)
    assert legacy["purpose"] == purpose.FOLLOWUP and legacy["selectedInputs"] == SELECTED and legacy["question"] == "갱신해 주세요"
    assert resolve(_job("BASIC"), None, None)["purpose"] == purpose.BASIC


def test_structured_followup():
    out = resolve(_job(purpose="FOLLOWUP", followup={"analysisRequestId": "ar_1", "question": "회신을 반영해 주세요",
                                                     "selectedInputs": SELECTED}), "회신을 반영해 주세요", None)
    assert out["purpose"] == purpose.FOLLOWUP and out["selectedInputs"] == SELECTED and out["question"] == "회신을 반영해 주세요"


def test_structured_followup_wins_over_the_same_legacy_text():
    """전환 기간 — backend가 두 방식을 함께 보내도 같은 목적이면 구조화 입력을 쓴다"""
    text = "회신을 반영해 주세요" + MARKER + json.dumps(SELECTED, ensure_ascii=False)
    out = resolve(_job(purpose="FOLLOWUP", followup={"analysisRequestId": "ar_1", "selectedInputs": SELECTED}), text, None)
    assert out["question"] == "회신을 반영해 주세요" and out["selectedInputs"] == SELECTED


def test_regenerate_needs_the_approval_to_replace_and_acts_as_request():
    out = resolve(_job(purpose="REGENERATE", request={"supersedesApprovalId": "apr_1", "targetProcessCode": "ETCH"}), "", None)
    assert out["purpose"] == purpose.REGENERATE and out["request"]["supersedesApprovalId"] == "apr_1"
    assert purpose.can_propose(purpose.REGENERATE, False)
    with pytest.raises(InvalidJobInput):
        resolve(_job(purpose="REGENERATE", request={}), "", None)


@pytest.mark.parametrize("job,question,trigger", [
    (_job("DEEP", purpose="BASIC"), "", None),  # 종류와 목적이 맞지 않음
    (_job("BASIC", purpose="QUESTION"), "", None),
    (_job("DEEP", purpose="UNKNOWN"), "", None),
    (_job("DEEP", purpose="FOLLOWUP"), "", None),  # 선택 결과 없음
    (_job("DEEP", purpose="QUESTION"), "질문" + MARKER + json.dumps(SELECTED), None),  # 질문 칸은 추가 분석인데 목적은 질문
    (_job("DEEP", purpose="QUESTION"), "질문", "REQUEST_BUTTON"),  # trigger와 모순
])
def test_contradictory_or_incomplete_input_is_rejected(job, question, trigger):
    with pytest.raises(InvalidJobInput) as caught:
        resolve(job, question, trigger)
    assert caught.value.code == "INVALID_INPUT"


def test_job_message_schema_accepts_the_structured_inputs(contract_validators):
    _, job_validator = contract_validators
    base = {"schemaVersion": "1.0", "eventId": "EVT-job_1-A1", "jobId": "job_1", "incidentId": "inc_1", "threadId": "thr_1",
            "kind": "DEEP", "attempt": 1, "contextRef": "job-context://job_1", "resume": None,
            "createdAt": "2026-10-23T01:00:00Z", "traceId": "TRACE-job_1"}
    followup = base | {"input": {"purpose": "FOLLOWUP", "followup": {"analysisRequestId": "ar_1", "question": "반영",
                                                                      "selectedInputs": SELECTED}}}
    regenerate = base | {"input": {"purpose": "REGENERATE", "request": {"supersedesApprovalId": "apr_1"}}}
    assert not list(job_validator.iter_errors(followup)) and not list(job_validator.iter_errors(regenerate))
    assert list(job_validator.iter_errors(base | {"input": {"purpose": "FOLLOWUP"}}))  # 선택 결과 없는 추가 분석
    assert list(job_validator.iter_errors(base | {"input": {"purpose": "NOPE"}}))


async def test_regenerate_looks_up_the_replaced_approval_once():
    """재생성 작업은 대체할 승인을 조회해 범위를 맞춘다 — 같은 묶음에서 제안이 여러 번이어도 조회는 한 번"""
    from fab.agent.graph.nodes.tools import _request_scope

    calls = []

    class _Backend:
        async def get_approval(self, approval_id):
            calls.append(approval_id)
            return {"approvalId": approval_id, "actionType": "CREATE_CHECK_REQUEST", "payload": {"targetProcessCode": "ETCH"}}

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        backend, tracer = _Backend(), _Tracer()

    class _TurnStub:
        rt, state, cache = _Runtime(), {"purpose": "REGENERATE", "requestInput": {"supersedesApprovalId": "apr_1"}}, {}
    turn = _TurnStub()
    assert await _request_scope(turn, "CREATE_CHECK_REQUEST", "ETCH") is None
    assert "apr_1" in await _request_scope(turn, "CREATE_MAINTENANCE_REQUEST", "CMP")
    assert calls == ["apr_1"]

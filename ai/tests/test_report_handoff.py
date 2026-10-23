"""보고서와 이어지는 두 가지 — 조사 정리 완료 신호(investigationReady)와 사람이 고친 칸 잠금(lockedSections).

조사 정리 완료는 Agent가 「결론이 섰다」만 알린다. 승인 대기 · 미회신 요청 · 작업 상태 확인과 자동 초안 생성은 backend(보고서 모듈)가 한다.
사건 종료 명령이 아니다. 잠긴 칸은 사람이 고친 칸이라 Agent가 쓰지 않고, backend가 이전 판 원문을 그대로 옮긴다.
"""

from __future__ import annotations

import pytest

from fab.agent.readiness import investigation_ready


def _cause(support, counter=(), priority=1):
    return {"priority": priority, "hypothesis": "가설", "supportingEvidenceIds": list(support),
            "conflictingEvidenceIds": list(counter), "missingData": [], "nextCheck": ""}


@pytest.mark.parametrize("step,causes,facts,stop,ready,word", [
    ("OBSERVE", [], [], None, True, "정상 변동"),
    ("CURRENT_CHECK", [_cause(["E-RUN-1", "E-REQ-req_1"])], [], None, True, "점검 결과"),
    ("CURRENT_CHECK", [_cause(["E-RUN-1"])], [{"text": "작업 결과", "evidenceIds": ["E-ACT-act_1"]}], None, True, "점검 결과"),
    ("CURRENT_CHECK", [_cause(["E-RUN-1"])], [], None, False, "점검 결과가 아직"),
    ("UPSTREAM_CHECK", [_cause(["E-RUN-1"], ["E-REQ-req_1"])], [], None, False, "갈림"),
    ("NEEDS_DEEP_ANALYSIS", [_cause(["E-RUN-1", "E-REQ-req_1"])], [], None, False, "더 볼"),
    ("INSUFFICIENT_EVIDENCE", [], [], None, False, "더 볼"),
    ("OBSERVE", [], [], "모델 판단 한도(8회)에 도달했습니다.", False, "멈춤"),
])
def test_investigation_ready_rules(step, causes, facts, stop, ready, word):
    out = investigation_ready({"nextStep": step, "candidateCauses": causes, "observedFacts": facts}, stop)
    assert out["ready"] is ready and word in out["reason"], out


async def test_result_ready_event_carries_the_signal(spring_url, kb):
    """기본 분석(규칙 모델) — 점검 결과가 아직 없으니 ready=false. 결과 이벤트 payload에 실리고 계약을 지킨다"""
    from fab.agent.worker import poll_once
    from fab.devtools.spring import fresh_incident, make_runner

    await fresh_incident("EV-1009", spring_url)
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    event = next(e for e in reversed(runner.sink.events) if e["type"] == "RESULT_READY")
    assert event["payload"]["investigationReady"] == {"ready": False, "reason": event["payload"]["investigationReady"]["reason"]}
    assert "점검 결과" in event["payload"]["investigationReady"]["reason"]


# ---- 잠긴 칸

LOCKED = ["overview", "unresolved"]


def test_report_job_input_reads_locked_sections():
    from fab.agent.report import locked_of as report_locks

    job = {"kind": "REPORT", "input": {"report": {"baseReportVersion": 1, "lockedSections": LOCKED}}}
    assert report_locks(job) == LOCKED
    assert report_locks({"kind": "REPORT"}) == [] and report_locks({"kind": "DEEP", "input": job["input"]}) == []
    assert report_locks({"kind": "REPORT", "input": {"report": {"lockedSections": ["overview", "nope"]}}}) == ["overview"]


async def test_start_tells_the_model_which_sections_are_locked():
    from fab.agent.graph.nodes.start import start

    class _Tracer:
        def event(self, *a, **k):
            pass

    class _Runtime:
        tracer = _Tracer()
    out = await start({"mode": "REPORT", "jobId": "JOB-R", "incidentId": "inc_1", "lockedSections": LOCKED},
                      {"configurable": {"runtime": _Runtime()}})
    note = "\n".join(str(m.content) for m in out["messages"])
    assert "사람이 고친 칸" in note and "overview" in note and "unresolved" in note


async def test_locked_sections_are_never_written_by_the_agent():
    """모델이 잠긴 칸을 써도 Harness가 표시 문구로 바꿔 보낸다 — backend가 그 칸을 이전 판 원문으로 옮긴다"""
    from fab.agent.graph.nodes.finalize import finalize
    from fab.agent.report import LOCKED_MARK

    sent = {}

    class _Backend:
        async def save_report_draft(self, incident_id, sections, evidence_ids):
            sent.update(sections)
            return {"reportId": "rpt_2", "version": 2}

    class _Runtime:
        backend, job_id = _Backend(), "JOB-R"
    state = {"mode": "REPORT", "incidentId": "inc_1", "jobId": "JOB-R", "lockedSections": LOCKED, "evidence": {},
             "report": {"sections": {"overview": "모델이 다시 쓴 개요", "investigation": "조사", "requestsAndReplies": "요청",
                                     "actionsAndVerification": "조치", "unresolved": "모델이 쓴 미해결"}, "evidenceIds": []}}
    await finalize(state, {"configurable": {"runtime": _Runtime()}})
    assert sent["overview"] == sent["unresolved"] == LOCKED_MARK
    assert sent["investigation"] == "조사" and sent["requestsAndReplies"] == "요청"

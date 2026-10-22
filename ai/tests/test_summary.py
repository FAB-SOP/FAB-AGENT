"""C5 · C6 — 보고서 확정 뒤 AI 요약 작업. backend 없이 가짜 확정 스냅샷으로 돈다(계약: local 09-summary-contract.md v0.3).

요약 작업(SUMMARY)은 조사 그래프 · checkpointer를 쓰지 않는 별도 경로다 — 확정 스냅샷만 읽고 1~2줄을 SUMMARY_READY로 낸다.
"""

import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver

from fab.agent.summary import rule_summary, validate_summary
from fab.agent.worker import JobRunner
from fab.agent.worker.sinks import EventSink

SNAPSHOT = {
    "reportId": "rpt_1", "incidentId": "inc_1", "displayId": "EV-1009", "reportVersion": 2, "contentRevision": 5,
    "snapshotRef": "snapshot://reports/rpt_1/v2/r5", "confirmedAt": "2026-10-22T18:00:00+09:00",
    "sections": {
        "overview": "CMP 후 두께 10,140.4 Å가 관리한계 UCL을 6.9 Å 넘었습니다(OOC). 같은 로트 W03 · W05도 높았습니다.",
        "investigation": "ETCH-PM2 제거량이 같은 PM 정상 대비 z=-4.1로 적었습니다. CMP 제거량은 정상 변동 안입니다.",
        "requestsAndReplies": "ETCH 담당자 확인 요청 — 회신: 직전 세정 누락 확인.",
        "actionsAndVerification": "기록 없음",
        "unresolved": "후속 확인 run 결과가 아직 없습니다.",
    },
    "classification": {"category": "MACHINE", "equipmentParameters": ["rf_power"]},
    "evidenceIds": ["E-RUN-ETCH", "E-REQ-chk_1"],
}
GOOD = ["ETCH-PM2 제거량 부족(z=-4.1)으로 CMP 후 두께가 UCL을 6.9 Å 넘었고, 회신으로 직전 세정 누락이 확인됐습니다.",
        "원인 분류는 Machine(rf_power), 후속 확인 run 결과는 아직 없습니다."]


def test_good_summary_passes():
    assert validate_summary(GOOD, ["E-RUN-ETCH"], SNAPSHOT) == []


@pytest.mark.parametrize("lines,ids,needle", [
    ([], ["E-RUN-ETCH"], "1~2줄"),
    (GOOD + ["세 번째 줄"], ["E-RUN-ETCH"], "1~2줄"),
    (["가" * 121], ["E-RUN-ETCH"], "120자"),
    (["CMP 후 두께가 UCL을 12.5 Å 넘었습니다."], ["E-RUN-ETCH"], "12.5"),          # 스냅샷에 없는 숫자
    ([GOOD[0]], ["E-NOT-IN-SNAPSHOT"], "근거"),                                      # 스냅샷에 없는 근거
    ([GOOD[0]], [], "근거"),                                                         # 근거 없음
    (["ETCH 세정 누락이 원인일 확률 80%입니다."], ["E-RUN-ETCH"], "확률"),
    (["ETCH-PM2 RF 전력을 올려 제거량을 맞추세요."], ["E-RUN-ETCH"], "처방"),
    (["근본 원인은 Material(자재)입니다."], ["E-RUN-ETCH"], "원인 분류"),             # 확정한 분류(MACHINE)와 다른 범주
])
def test_summary_guard_rejects(lines, ids, needle):
    errors = validate_summary(lines, ids, SNAPSHOT)
    assert errors and any(needle in e for e in errors), errors


def test_rule_summary_uses_only_snapshot_and_passes_guard():
    summary = rule_summary(SNAPSHOT)
    assert summary["generatedBy"] == "rule" and 1 <= len(summary["lines"]) <= 2
    assert "Machine" in summary["lines"][0] and "rf_power" in summary["lines"][0]
    assert validate_summary(summary["lines"], summary["evidenceIds"], SNAPSHOT) == []


def test_rule_summary_falls_back_to_minimal_line_when_text_would_fail():
    """사람이 쓴 본문에 처방 문장이 있으면 그대로 옮기지 않고 최소 문장으로 — 규칙 문장도 Guard를 통과해야 한다"""
    risky = SNAPSHOT | {"sections": SNAPSHOT["sections"] | {"overview": "압력을 낮춰 맞추면 됩니다.", "unresolved": "기록 없음"}}
    summary = rule_summary(risky)
    assert validate_summary(summary["lines"], summary["evidenceIds"], risky) == []
    assert "확정 보고서 v2" in summary["lines"][-1]


def test_hold_category_rule_summary():
    held = SNAPSHOT | {"classification": {"category": "HOLD", "equipmentParameters": []}}
    summary = rule_summary(held)
    assert "보류" in summary["lines"][0] and validate_summary(summary["lines"], summary["evidenceIds"], held) == []


# ---- 요약 작업 실행(runner) — 가짜 backend · 가짜 모델

class FakeBackend:
    def __init__(self, snapshot=SNAPSHOT):
        self.snapshot, self.claims = snapshot, []

    async def claim(self, job_id, attempt, worker_id):
        self.claims.append(job_id)
        return {}

    async def get_report_snapshot(self, report_id):
        assert report_id == self.snapshot["reportId"]
        return self.snapshot


class ScriptModel:
    """정해 둔 요약을 차례로 낸다 — 마지막 것을 반복"""

    def __init__(self, *scripts, name="script-model"):
        self.scripts, self.name, self.calls = list(scripts), name, 0

    async def ainvoke(self, messages, tools):
        assert [t["function"]["name"] for t in tools] == ["submit_summary"]
        lines, ids = self.scripts[min(self.calls, len(self.scripts) - 1)]
        self.calls += 1
        return AIMessage(content="", tool_calls=[{"id": f"s{self.calls}", "name": "submit_summary",
                                                   "args": {"lines": lines, "evidenceIds": ids}}])


def _job(**ref) -> dict:
    summary_ref = {"reportId": "rpt_1", "reportVersion": 2, "contentRevision": 5,
                   "snapshotRef": "snapshot://reports/rpt_1/v2/r5"} | ref
    return {"schemaVersion": "1.0", "eventId": "EVT-job_s-A1", "jobId": "job_s", "incidentId": "inc_1", "threadId": "thr_s",
            "kind": "SUMMARY", "attempt": 1, "contextRef": "job-context://job_s", "resume": None,
            "createdAt": "2026-10-22T18:00:01+09:00", "traceId": "TRACE-job_s", "input": {"summaryRef": summary_ref}}


def _runner(model, backend=None):
    saver, sink = InMemorySaver(), EventSink()
    return JobRunner({}, backend or FakeBackend(), saver, sink, models=(model, None), worker_id="test"), saver, sink


async def test_summary_job_emits_ready_and_touches_no_checkpoint():
    """C6 — 요약 작업은 그래프를 돌지 않는다: checkpointer에 아무것도 쓰지 않는다"""
    runner, saver, sink = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    out = await runner.process(_job())
    assert out["status"] == "DONE", out
    ready = [e for e in sink.events if e["type"] == "SUMMARY_READY"]
    assert len(ready) == 1 and ready[0]["payload"]["reportId"] == "rpt_1"
    assert ready[0]["payload"]["snapshotRef"] == "snapshot://reports/rpt_1/v2/r5"
    assert ready[0]["payload"]["summary"] == {"lines": GOOD, "evidenceIds": ["E-RUN-ETCH"], "generatedBy": "model"}
    assert "metrics" in ready[0]
    assert not list(saver.list(None)), "요약 작업이 체크포인트를 남겼다"


async def test_rejected_summary_retries_once_then_uses_rule_text():
    bad = (["CMP 후 두께가 UCL을 12.5 Å 넘었습니다."], ["E-RUN-ETCH"])  # 스냅샷에 없는 숫자
    model = ScriptModel(bad)
    runner, _, sink = _runner(model)
    out = await runner.process(_job())
    summary = next(e for e in sink.events if e["type"] == "SUMMARY_READY")["payload"]["summary"]
    assert out["status"] == "DONE" and model.calls == 2 and summary["generatedBy"] == "rule"


async def test_rejected_then_fixed_summary_is_accepted():
    model = ScriptModel((["CMP 후 두께가 12.5 Å 넘었습니다."], ["E-RUN-ETCH"]), (GOOD, ["E-RUN-ETCH"]))
    runner, _, sink = _runner(model)
    await runner.process(_job())
    summary = next(e for e in sink.events if e["type"] == "SUMMARY_READY")["payload"]["summary"]
    assert model.calls == 2 and summary["generatedBy"] == "model"


async def test_rule_model_writes_rule_summary_without_llm():
    from fab.agent.models.rule import RuleModel

    runner, _, sink = _runner(RuleModel())
    await runner.process(_job())
    summary = next(e for e in sink.events if e["type"] == "SUMMARY_READY")["payload"]["summary"]
    assert summary["generatedBy"] == "rule"


@pytest.mark.parametrize("job", [_job(snapshotRef="snapshot://reports/rpt_1/v1/r2"),  # 확정 스냅샷과 다른 입력
                                 {**_job(), "input": {}}])                             # summaryRef 없음
async def test_summary_job_with_bad_input_fails_without_summary(job):
    runner, _, sink = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    out = await runner.process(job)
    assert out["status"] == "FAILED" and not [e for e in sink.events if e["type"] == "SUMMARY_READY"]
    assert any(e["type"] == "JOB_FAILED" for e in sink.events)


# ---- 팀 계약(ai/contracts) — SUMMARY 작업 메시지 · SUMMARY_READY 이벤트

def test_summary_job_message_follows_contract(contract_validators):
    _, job_v = contract_validators
    assert not list(job_v.iter_errors(_job()))
    assert list(job_v.iter_errors({**_job(), "input": {}})), "SUMMARY는 input.summaryRef가 필수"
    assert list(job_v.iter_errors({k: v for k, v in _job().items() if k != "input"}))
    assert list(job_v.iter_errors({**_job(), "input": {"summaryRef": {"reportId": "rpt_1"}}})), "summaryRef 네 칸 필수"
    assert list(job_v.iter_errors({**_job(), "input": {"summaryRef": _job()["input"]["summaryRef"], "secret": "x"}})), \
        "계약에 없는 입력 칸은 싣지 않는다"
    basic = {**_job(), "kind": "BASIC"}
    basic.pop("input")
    assert not list(job_v.iter_errors(basic)), "다른 종류는 input 없이도 된다(지금 backend 그대로)"


async def test_summary_events_follow_contract(contract_validators):
    ev_v, _ = contract_validators
    runner, _, sink = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    await runner.process(_job())
    bad = [(e["type"], [x.message[:120] for x in ev_v.iter_errors(e)]) for e in sink.events]
    assert not [b for b in bad if b[1]], bad
    ready = next(e for e in sink.events if e["type"] == "SUMMARY_READY")
    too_long = {**ready, "payload": {**ready["payload"], "summary": {**ready["payload"]["summary"], "lines": ["가" * 121]}}}
    assert list(ev_v.iter_errors(too_long)), "줄당 120자는 계약에서도 막는다"


async def test_events_carry_explicit_attempt(contract_validators):
    """아름 지적 — 늦은 attempt 검사는 선택 칸 metrics.attempt가 아니라 이벤트 본체의 attempt로 한다"""
    ev_v, _ = contract_validators
    runner, _, sink = _runner(ScriptModel((GOOD, ["E-RUN-ETCH"])))
    await runner.process({**_job(), "attempt": 2, "eventId": "EVT-job_s-A2"})
    assert sink.events and all(e["attempt"] == 2 for e in sink.events)
    no_attempt = {k: v for k, v in sink.events[-1].items() if k != "attempt"}
    assert list(ev_v.iter_errors(no_attempt)), "attempt는 이벤트 계약의 필수 칸"


def test_rule_summary_never_cuts_inside_a_word_or_number():
    """아름 지적 — 공백 없는 긴 문자열은 강제로 자르지 않고 짧은 대체 문장으로"""
    long_token = "W-CURRENT-009-1의두께" * 12  # 공백 없이 120자를 넘는다
    snap = SNAPSHOT | {"sections": SNAPSHOT["sections"] | {"overview": long_token}}
    summary = rule_summary(snap)
    assert all(long_token[:20] not in line for line in summary["lines"]), summary
    assert "확정 보고서 v2" in summary["lines"][-1]
    assert validate_summary(summary["lines"], summary["evidenceIds"], snap) == []


def test_rule_summary_with_many_parameters_stays_short():
    params = [f"very_long_sensor_parameter_name_{i}" for i in range(8)]
    snap = SNAPSHOT | {"classification": {"category": "MACHINE", "equipmentParameters": params}}
    summary = rule_summary(snap)
    assert all(len(line) <= 120 for line in summary["lines"]), summary
    assert validate_summary(summary["lines"], summary["evidenceIds"], snap) == []

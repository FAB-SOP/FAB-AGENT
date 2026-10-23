"""수락 · 승인 · 거절 · 회신만으로 조사가 끝나지 않는다(팀 고정 규칙) — 진짜 backend로 단계마다 본다.

  - 사건이 종료(CLOSED)되지 않는다 — 사건 종료는 사람이 확정 보고서로 하는 일이다
  - 새 Agent 작업이 생기지 않는다 — 회신 · 수락 · 업무 처리는 저장만 하고, 분석은 사람이 요청할 때만 연다
  - 기다리던 그래프는 그대로 기다린다 — 수락 · 회신이 그래프 END 조건이 아니다(END는 이번 실행의 끝일 뿐)
"""

from __future__ import annotations

from fab.agent.worker import poll_once
from fab.devtools.spring import (LOGIN, OWNER_OF_PROCESS, User, _ask_question, _decide_approval, fresh_incident, internal,
                                 make_runner)

QUESTION = "원인 분석과 필요한 요청을 제안해줘"


def _quiet(*_args, **_kwargs):
    pass


async def _setup(spring_url, kb):
    """새 사건(EV-1009 복사) — 기본 분석까지 끝낸 상태로 돌려준다"""
    display = await fresh_incident("EV-1009", spring_url)
    runner, backend = make_runner(kb, base=spring_url)
    await poll_once(runner, backend)
    process = (await internal("GET", f"/internal/incidents/{display}", spring_url))["data"]["data"]["processCode"]
    engineer = await User(OWNER_OF_PROCESS[process], spring_url).login()
    return display, runner, backend, engineer


async def _waiting(runner, thread_id: str) -> bool:
    snap = await runner.graph.aget_state({"configurable": {"thread_id": thread_id}})
    return bool(snap.next)


async def _not_closed(engineer: User, display: str) -> str:
    status = (await engineer.incident(display))["status"]
    assert status != "CLOSED", status
    return status


async def _until_reply_wait(spring_url, kb):
    """질문 → 요청 제안(승인 대기) → 승인 → 요청 등록 · 회신 대기까지"""
    display, runner, backend, engineer = await _setup(spring_url, kb)
    await _ask_question(engineer, display, QUESTION)
    outcome = (await poll_once(runner, backend))[-1]
    assert outcome["status"] == "WAITING_APPROVAL", outcome["status"]
    await _decide_approval(engineer, outcome["interrupt"], False, _quiet)
    outcome = (await poll_once(runner, backend))[-1]
    return display, runner, backend, engineer, outcome


async def _owner_of(spring_url, request_id: str) -> tuple[User, dict]:
    request = (await internal("GET", f"/internal/check-requests/{request_id}", spring_url))["data"]
    return await User(LOGIN[request["ownerUserId"]], spring_url).login(), request


async def test_approval_acceptance_and_reply_do_not_end_the_investigation(spring_url, kb):
    display, runner, backend, engineer, outcome = await _until_reply_wait(spring_url, kb)
    thread = outcome["job"]["threadId"]
    assert outcome["status"] == "WAITING_REPLY"  # 승인 = 요청 등록. 그래프는 끝나지 않고 회신을 기다린다
    assert await _waiting(runner, thread)
    await _not_closed(engineer, display)

    owner, request = await _owner_of(spring_url, outcome["interrupt"]["requestId"])
    path = f"/api/check-requests/{request['requestId']}"
    status, decided = await owner.post(f"{path}/decisions", {"decision": "ACCEPT", "comment": None,
                                                             "expectedVersion": request["version"]})
    assert status == 200, decided
    assert await poll_once(runner, backend) == []  # 수락만으로 분석이 돌지 않는다
    assert await _waiting(runner, thread)
    await _not_closed(engineer, display)

    status, reply = await owner.post(f"{path}/replies", {"findings": "점검 결과를 첨부합니다(시험 회신).", "evidenceIds": [],
                                                         "confirmedByUser": True, "expectedVersion": decided["data"]["version"]})
    await owner.close()
    assert status == 201, reply
    assert await poll_once(runner, backend) == []  # 회신도 저장만 — 자동 재분석 없음
    assert await _waiting(runner, thread)
    await _not_closed(engineer, display)
    await engineer.close()


async def test_rejected_approval_ends_only_this_run(spring_url, kb):
    """승인 거절 → Agent는 이번 실행을 정리하고 끝낸다(END). 사건은 그대로 조사 중이고 다음 질문을 받는다"""
    display, runner, backend, engineer = await _setup(spring_url, kb)
    await _ask_question(engineer, display, QUESTION)
    outcome = (await poll_once(runner, backend))[-1]
    assert outcome["status"] == "WAITING_APPROVAL"
    await _decide_approval(engineer, outcome["interrupt"], True, _quiet)
    outcome = (await poll_once(runner, backend))[-1]
    assert outcome["status"] == "DONE"
    assert not await _waiting(runner, outcome["job"]["threadId"])
    await _not_closed(engineer, display)
    await _ask_question(engineer, display, "다른 원인 가능성도 알려줘")  # 202가 아니면 예외
    await engineer.close()


async def test_request_rejected_by_owner_does_not_end_the_investigation(spring_url, kb):
    display, runner, backend, engineer, outcome = await _until_reply_wait(spring_url, kb)
    owner, request = await _owner_of(spring_url, outcome["interrupt"]["requestId"])
    status, decided = await owner.post(f"/api/check-requests/{request['requestId']}/decisions",
                                       {"decision": "REJECT", "comment": "이번 주는 점검 일정이 없습니다(시험).",
                                        "expectedVersion": request["version"]})
    await owner.close()
    assert status == 200, decided
    assert await poll_once(runner, backend) == []  # 거절만으로 분석이 돌지 않는다
    assert await _waiting(runner, outcome["job"]["threadId"])
    await _not_closed(engineer, display)
    await engineer.close()


async def test_work_item_adoption_and_decline_do_not_run_the_agent(spring_url, kb):
    """기본 분석의 업무 후보 — 다른 팀 요청 채택(backend가 요청을 바로 발송) · 미채택 모두 Agent 작업을 만들지 않는다"""
    display, runner, backend, engineer = await _setup(spring_url, kb)
    incident = await engineer.incident(display)
    status, page = await engineer.get(f"/api/incidents/{incident['incidentId']}/work-items")
    assert status == 200
    items = {item["operationCode"]: item for item in page["data"]["items"]}
    team, own = items["UPSTREAM_CHECK"], items["REVIEW_PROCESS_HISTORY"]  # 규칙 모델 — 앞 공정 기여 사건(EV-1009)

    status, adopted = await engineer.post(f"/api/work-items/{team['taskId']}/adoptions", {
        "expectedVersion": team["version"], "ownerUserId": "USR-ETCH-01", "targetProcessCode": "ETCH"})
    assert status == 200, adopted
    assert await poll_once(runner, backend) == []
    status, declined = await engineer.post(f"/api/work-items/{own['taskId']}/decisions",
                                           {"decision": "DECLINE", "comment": None, "expectedVersion": own["version"]})
    assert status == 200, declined
    assert await poll_once(runner, backend) == []
    await _not_closed(engineer, display)
    await engineer.close()


# ---- 회신 대기 전환 — 새 흐름(FAB_REPLY_FLOW=MANUAL)으로 시작한 thread는 요청 발송 뒤 그래프를 끝낸다.
# 회신은 저장 · 알림만 되고, 사람이 추가 분석을 요청하면 새 DEEP · 새 thread로 반영한다. 기준은 승인 시점이 아니라
# 그 thread를 어느 흐름으로 시작했는지 — 전에 시작한 thread는 플래그를 켠 뒤에 승인해도 회신 대기 그대로다.

async def _ask_until_approval(spring_url, kb):
    display, runner, backend, engineer = await _setup(spring_url, kb)
    await _ask_question(engineer, display, QUESTION)
    outcome = (await poll_once(runner, backend))[-1]
    assert outcome["status"] == "WAITING_APPROVAL", outcome["status"]
    return display, runner, backend, engineer, outcome


async def test_manual_reply_flow_ends_the_graph_after_sending_the_request(spring_url, kb, monkeypatch):
    monkeypatch.setenv("FAB_REPLY_FLOW", "MANUAL")
    display, runner, backend, engineer, outcome = await _ask_until_approval(spring_url, kb)
    thread = outcome["job"]["threadId"]
    await _decide_approval(engineer, outcome["interrupt"], False, _quiet)
    resumed = (await poll_once(runner, backend))[-1]
    assert resumed["status"] == "DONE", resumed["status"]  # 회신을 기다리지 않는다
    assert not await _waiting(runner, thread)
    assert resumed["analysis"]["requestSent"]  # 발송 사실 — backend가 최신 원인 분석으로 덮지 않게
    assert any("추가 분석" in line for line in resumed["result"]["limitations"])
    requests = (await internal("GET", f"/internal/incidents/{display}/check-requests", spring_url))["data"]["data"]["items"]
    assert [r["requestId"] for r in requests if r["status"] == "OPEN"] == [resumed["analysis"]["requestSent"]]
    await _not_closed(engineer, display)
    await engineer.close()


async def test_thread_started_in_the_old_flow_keeps_waiting_for_the_reply(spring_url, kb, monkeypatch):
    monkeypatch.delenv("FAB_REPLY_FLOW", raising=False)
    display, runner, backend, engineer, outcome = await _ask_until_approval(spring_url, kb)
    monkeypatch.setenv("FAB_REPLY_FLOW", "MANUAL")  # 플래그를 켠 뒤에 승인
    await _decide_approval(engineer, outcome["interrupt"], False, _quiet)
    resumed = (await poll_once(runner, backend))[-1]
    assert resumed["status"] == "WAITING_REPLY"
    assert await _waiting(runner, outcome["job"]["threadId"])
    await engineer.close()


def test_reply_flow_comes_from_the_job_first():
    """흐름 판은 backend가 작업에 저장해 input.replyFlow로 준다 — 없을 때만 Worker 설정(FAB_REPLY_FLOW), 기본은 WAIT"""
    from fab.agent.worker.runner import reply_flow

    assert reply_flow({"input": {"replyFlow": "MANUAL"}}) == "MANUAL"
    assert reply_flow({"input": {"replyFlow": "WAIT"}}) == "WAIT"
    assert reply_flow({}) == "WAIT"


def test_reply_flow_falls_back_to_the_worker_setting(monkeypatch):
    from fab.agent.worker.runner import reply_flow

    monkeypatch.setenv("FAB_REPLY_FLOW", "MANUAL")
    assert reply_flow({}) == "MANUAL"
    assert reply_flow({"input": {"replyFlow": "WAIT"}}) == "WAIT"  # 작업에 적힌 값이 먼저

"""이 사건의 최근 질문과 답 — 질문 작업은 질문마다 새 thread라 앞 질문을 몰랐다.

「제거량 정확한 수치로는 못 알려줘?」가 바로 앞 「CMP 공정에서 제거량 알려줘」를 가리키는데, 모델은 그것을 모른 채 세 공정의 원인
분석을 처음부터 다시 했다(10-23 화면). Backend 사건 이력(GET /internal/incidents/{id}/history)에서 최근 질문 · AI 답을 읽어
참고로 넣는다. 답은 근거가 아니다 — 수치는 이번에 다시 조회해 인용한다. 이력을 못 읽어도 작업은 그대로 진행한다.
"""

from __future__ import annotations

from fab.agent.harness.guard import untrusted

MAX_ITEMS = 6  # 질문 · 답 세 쌍 정도
_KINDS = {"QUESTION_ASKED": "질문", "ANSWER": "AI 답"}
_QUESTION_PREFIX = "추가 조사 질문: "


def note(history: dict, current_job_id: str | None) -> str | None:
    """이력(최신순)에서 이번 작업을 뺀 최근 질문 · 답을 오래된 것부터 — 없으면 None"""
    rows = []
    for item in history.get("items") or []:
        kind = _KINDS.get(item.get("type"))
        source = item.get("sourceRef") or {}
        if kind is None or (source.get("type") == "JOB" and source.get("id") == current_job_id):
            continue
        text = str(item.get("summary") or "").removeprefix(_QUESTION_PREFIX).strip()
        if text:
            rows.append(f"- {kind}: {text}")
        if len(rows) == MAX_ITEMS:
            break
    if not rows:
        return None
    body = "\n".join(reversed(rows))
    return ("[이 사건의 최근 질문과 답] 이번 질문이 앞 질문을 가리키면(「그 수치」 · 「정확한 값으로는?」) 무엇을 가리키는지 여기서 "
            "찾는다. 답은 참고일 뿐 근거가 아니다 — 수치는 이번에 다시 조회해 인용한다.\n" + untrusted("incident-history", body))


async def load(backend, incident_id: str, current_job_id: str | None, tracer) -> str | None:
    try:
        history = await backend.incident_history(incident_id)
    except Exception as e:  # noqa: BLE001 — 이력은 참고라 못 읽어도 질문에는 답한다
        tracer.event("recent_history_unavailable", error=type(e).__name__, message=str(e)[:200])
        return None
    return note(history, current_job_id)

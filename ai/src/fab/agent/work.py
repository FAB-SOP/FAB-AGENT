"""업무 후보(workSuggestions) — 답과 함께 내는 「다음에 할 일」 최대 5개. 채택 · 승인 · 발송 기록이 아니다(팀 TO-DO 「업무 협업 · Agent 범위」).

세 갈래는 operationCode가 정한다(backend WorkOperation.route) — 작업자가 후보별로 수락 · 거절한다.
  내 할 일(SELF_CHECK)       REVIEW_SENSOR_DATA · REVIEW_PROCESS_HISTORY · COMPARE_METROLOGY
  상급자 허가(MANAGER)       REVIEW_REQUIRED — 변경 계획 코드(CHANGE_SETPOINT 등)는 Agent에게 열지 않는다(설정값 처방 금지)
  다른 팀 요청(TEAM_REQUEST)  EQUIPMENT_INSPECTION · FACILITY_INSPECTION · UPSTREAM_CHECK
"""

from __future__ import annotations

from fab.agent.harness.guard import _PRESCRIBE, _PROB

AGENT_OPERATIONS = ("REVIEW_SENSOR_DATA", "REVIEW_PROCESS_HISTORY", "COMPARE_METROLOGY", "REVIEW_REQUIRED",
                    "EQUIPMENT_INSPECTION", "FACILITY_INSPECTION", "UPSTREAM_CHECK")
MAX_SUGGESTIONS = 5
_FIELDS = ("operationCode", "title", "reason", "evidenceIds")


def validate_work_suggestions(items: list[dict] | None, cited: set[str]) -> list[str]:
    """후보 검사 — cited는 답(관측 사실 · 원인 후보 지지 근거)이 인용한 근거. backend는 결과 evidenceRefs 밖의 근거를 막는다"""
    items = items or []
    errors: list[str] = []
    if len(items) > MAX_SUGGESTIONS:
        errors.append(f"업무 후보는 최대 {MAX_SUGGESTIONS}개입니다.")
    for item in items:
        title, reason = str(item.get("title") or "").strip(), str(item.get("reason") or "").strip()
        label = title[:30] or "(제목 없음)"
        if item.get("operationCode") not in AGENT_OPERATIONS:
            errors.append(f"업무 후보 「{label}」의 operationCode는 {list(AGENT_OPERATIONS)} 중 하나여야 합니다 — "
                          "설정 변경 · 교체 · 재가동 계획은 Agent가 제안하지 않습니다(상급자 검토는 REVIEW_REQUIRED).")
        if not title or not reason or len(title) > 2000 or len(reason) > 2000:
            errors.append(f"업무 후보 「{label}」의 title · reason은 1~2000자입니다.")
        ids = list(item.get("evidenceIds") or [])
        if not ids:
            errors.append(f"업무 후보 「{label}」에 근거 ID가 없습니다. 답이 인용한 evidenceId를 붙이세요.")
        elif set(ids) - cited:
            errors.append(f"업무 후보 「{label}」의 근거 {sorted(set(ids) - cited)}는 답이 인용하지 않았습니다. "
                          "답(관측 사실 · 원인 후보)에 넣거나 후보에서 빼세요.")
        if _PRESCRIBE.search(f"{title} {reason}"):
            errors.append(f"업무 후보 「{label}」가 장비 설정값 변경을 처방합니다. 확인 · 점검할 항목만 적으세요.")
        if _PROB.search(f"{title} {reason}"):
            errors.append(f"업무 후보 「{label}」에 원인 확률을 숫자로 적지 않습니다.")
    return errors


def contract_suggestions(items: list[dict] | None, evidence_ids: set[str]) -> list[dict]:
    """계약 모양(RESULT_READY.payload.workSuggestions) — 계약 칸만, 근거는 결과 evidenceRefs에 있는 것만"""
    return [{"operationCode": item["operationCode"], "title": str(item["title"]), "reason": str(item["reason"]),
             "evidenceIds": [e for e in item.get("evidenceIds") or [] if e in evidence_ids]}
            for item in (items or [])[:MAX_SUGGESTIONS]]

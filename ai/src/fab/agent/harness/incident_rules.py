"""사건 단위 규칙 — 질문마다 새 thread를 쓰므로 thread가 아니라 사건의 요청 · 승인 기록을 보고 판단한다. 순수 함수만 둔다.

요청 · 제안의 「같은 팀 · 종류」 = (정비 요청인지 다른 공정 확인인지, 대상 공정).
"""

from __future__ import annotations

from typing import Callable

ALIVE_REQUESTS = {"OPEN", "ACCEPTED"}  # 수락 전 · 수락 뒤 회신 전 — 둘 다 미해결(수락은 회신이 아니다)
DONE_APPROVALS = {"APPROVED", "SUPERSEDED"}  # 승인된 것은 요청으로 세고, 대체된 것은 세지 않는다


def _kind(action_or_request_type: str | None) -> str:
    return "MAINTENANCE" if "MAINTENANCE" in (action_or_request_type or "") else "CROSS_PROCESS"


def _replied_at(request: dict) -> str | None:
    return (request.get("reply") or {}).get("createdAt") or request.get("createdAt")


def proposal_block(action_type: str, target_process: str | None, requests: list[dict], approvals: list[dict],
                   cause_changed_after: Callable[[str | None], bool], limit: int, *, human_requested: bool = False) -> str | None:
    """이 제안을 막을 이유(모델에게 돌려줄 문장) — 막지 않으면 None.

    requests: get_check_requests 항목(requestType · targetProcessCode · status · reply) / approvals: 이 사건의 승인
    (actionType · targetProcessCode · status · decidedAt) / cause_changed_after(t): t 뒤에 원인 분석이 바뀌었나 /
    human_requested: 사람이 연 요청서 작업(업무 후보 수락 · 「요청서 다시 만들기」) — 회신받은 · 거절된 같은 요청을
    다시 보내는 것은 사람의 판단이라 그 두 규칙은 보지 않는다(진행 중 · 한도만 본다)
    """
    key = (_kind(action_type), target_process)
    requests = [request for request in requests if request.get("status") != "CANCELLED"]
    same_requests = [r for r in requests if (r.get("requestType"), r.get("targetProcessCode")) == key]
    same_approvals = [a for a in approvals if (_kind(a.get("actionType")), a.get("targetProcessCode")) == key]
    if any(r.get("status") in ALIVE_REQUESTS for r in same_requests) or any(a.get("status") == "PENDING" for a in same_approvals):
        return ("[REJECTED] 같은 담당 팀에 같은 종류의 요청이 이미 진행 중입니다(승인 대기 또는 회신 대기). "
                "다시 제안하지 말고 지금 근거로 답하세요.")
    for request in [] if human_requested else same_requests:
        if request.get("status") == "REPLIED" and not cause_changed_after(_replied_at(request)):
            return ("[REJECTED] 같은 담당 팀에 같은 종류의 요청을 이미 보내 회신을 받았습니다. 그 회신(E-REQ 근거)으로 답하세요 — "
                    "회신 뒤 원인 분석이 바뀐 경우에만 다시 요청할 수 있습니다.")
    for request in [] if human_requested else same_requests:  # 받는 사람의 거절 — 회신이 아니다, 거절된 승인처럼 본다
        if request.get("status") == "REJECTED" and not cause_changed_after(request.get("decisionAt") or request.get("createdAt")):
            return ("[REJECTED] 같은 담당 팀 · 종류의 요청을 받는 사람이 거절했습니다. "
                    "원인 분석이 바뀐 뒤에만 다시 제안할 수 있습니다. 지금 근거로 답하세요.")
    for approval in [] if human_requested else same_approvals:
        if approval.get("status") == "REJECTED" and not cause_changed_after(approval.get("decidedAt")):
            return ("[REJECTED] 같은 담당 팀 · 종류의 요청은 거절됐습니다. 원인 분석이 바뀐 뒤에만 다시 제안할 수 있습니다. "
                    "지금 근거로 답하세요.")
    made = len(requests) + sum(1 for a in approvals if a.get("status") not in DONE_APPROVALS)
    if made >= limit:
        return f"[LIMIT] 이 사건의 요청 한도({limit}개)에 닿았습니다. 더 제안하지 말고 지금 근거로 submit_result를 부르세요."
    return None


def request_scope_error(action_type: str, target_process: str | None, request_input: dict | None,
                        superseded: dict | None) -> str | None:
    """사람이 연 요청서 작업의 범위 — 지정한 대상 공정(input.request.targetProcessCode)과, 재생성이면 대체할 승인과 같은
    팀 · 종류(정비 요청인지 다른 공정 확인인지 · 대상 공정). 어긋나면 모델에게 돌려줄 문장, 맞으면 None"""
    wanted = (request_input or {}).get("targetProcessCode")
    if wanted and target_process and wanted != target_process:
        return (f"[REJECTED] 이 요청서 작업의 대상 공정은 {wanted}입니다. {target_process}가 아니라 {wanted} 대상으로 요청서를 만드세요.")
    if superseded:
        replaced = (_kind(superseded.get("actionType")), (superseded.get("payload") or {}).get("targetProcessCode"))
        if (_kind(action_type), target_process) != replaced:
            kind = "정비 요청" if replaced[0] == "MAINTENANCE" else "다른 공정 확인 요청"
            return (f"[REJECTED] 재생성은 대체할 승인 {superseded.get('approvalId')}와 같은 팀 · 종류({kind}, 대상 공정 {replaced[1]})의 "
                    "요청서만 만들 수 있습니다. 같은 대상으로 내용을 고쳐 다시 제안하세요.")
    return None


# ---- 결론 변경 검사 — 결론은 새 근거로만 바뀐다. 기준 = 사건의 가장 최근 원인 분석(사건 기록 조회가 채운다)

def _top(causes: list[dict]) -> dict:
    return min(causes, key=lambda cause: cause.get("priority") or 99)


def compare_conclusion(result: dict, baseline: dict | None) -> dict:
    """이번 답이 기준 결론(1순위 원인 · nextStep · 대상 공정)을 바꿨나. 원인 후보 없이 사실만 답했으면 바꾼 것이 아니다"""
    from fab.agent.causes import CAUSES

    causes = result.get("candidateCauses") or []
    if not baseline or not causes:
        return {"changed": False, "fields": [], "baselineResultId": (baseline or {}).get("resultId")}
    top_ids = sorted(_top(causes).get("causeIds") or [])
    target = next((CAUSES[cause_id][0] for cause_id in top_ids if cause_id in CAUSES), None)
    fields = [name for name, now, before in (("topCause", top_ids, sorted(baseline.get("topCauseIds") or [])),
                                             ("nextStep", result.get("nextStep"), baseline.get("nextStep")),
                                             ("targetProcess", target, baseline.get("targetProcess"))) if now != before]
    return {"changed": bool(fields), "fields": fields, "baselineResultId": baseline.get("resultId")}


def conclusion_change_error(comparison: dict, args: dict, baseline: dict, known_ids: set[str], rejects_so_far: int) -> str | None:
    """결론을 바꿨으면 「바뀐 이유」와, 기준 결과가 인용하지 않은 새 근거(이번에 조회한 것)가 1개 이상 있어야 한다.
    같은 작업에서 두 번째부터는 None — 호출부가 기준 순위를 유지하고 새 가능성을 2순위 이하로 내린다"""
    if not comparison["changed"] or rejects_so_far >= 1:
        return None
    new = (set(args.get("changeEvidenceIds") or []) & known_ids) - set(baseline.get("evidenceIds") or [])
    if (args.get("changeReason") or "").strip() and new:
        return None
    return (f"[REJECTED] 이전 원인 분석({baseline.get('resultId')})의 결론({', '.join(comparison['fields'])})을 바꾸려면 "
            "changeReason(바뀐 이유)과 changeEvidenceIds(이번에 새로 조회한, 이전 결론이 인용하지 않은 새 근거 ID)를 적으세요. "
            "새 근거가 없으면 이전 순위를 유지하고 새 가능성은 2순위 이하로 적으세요.")


def keep_baseline_rank(result: dict, baseline: dict) -> dict:
    """두 번째 반려부터 — 기준 결과의 1순위와 nextStep을 지키고, 이번 답의 원인 후보는 2순위부터 잇는다"""
    base_top = dict(_top(baseline["candidateCauses"]), priority=1)
    rest = [cause for cause in sorted(result.get("candidateCauses") or [], key=lambda cause: cause.get("priority") or 99)
            if sorted(cause.get("causeIds") or []) != sorted(base_top.get("causeIds") or [])]
    return {**result, "nextStep": baseline["nextStep"],
            "candidateCauses": [base_top] + [dict(cause, priority=i + 2) for i, cause in enumerate(rest)][:4]}

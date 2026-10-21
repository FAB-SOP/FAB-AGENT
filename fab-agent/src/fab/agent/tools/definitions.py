"""모델에 보이는 Harness 도구 스키마 — 제안(propose_*) · 제출(submit_*)과 제안 → 변경 도구 · 승인 종류 대응.

변경 도구(WRITE_TOOLS)는 모델에게 보이지 않는다. Harness만 승인 ID로 부른다.
"""

from __future__ import annotations

from fab.agent.causes import CAUSE_IDS

WRITE_TOOLS = {"create_cross_process_check_request", "create_maintenance_request", "confirm_report"}

# 제안 도구 → 승인 뒤 Harness가 부르는 변경 도구 · backend 승인 종류
PROPOSALS = {
    "propose_cross_process_check": ("create_cross_process_check_request", "CREATE_CHECK_REQUEST"),
    "propose_maintenance_request": ("create_maintenance_request", "CREATE_MAINTENANCE_REQUEST"),
}

_PROPOSAL_PARAMS = {
    "type": "object",
    "properties": {
        "processCode": {"type": "string", "enum": ["CVD", "ETCH", "CMP"], "description": "요청을 받을 공정"},
        "equipmentId": {"type": "string", "description": "확인 · 점검할 설비(PM). 예: ETCH-PM2"},
        "ownerUserId": {"type": "string", "description": "get_process_owner 결과의 userId"},
        "question": {"type": "string", "description": "확인 · 점검할 항목을 구체적으로. 원인 확정 · 설정값 처방 금지"},
        "reason": {"type": "string", "description": "받는 사람에게 보이는 요청 이유 1~2문장 — 무엇이 얼마나 벗어나 이 설비를 보는지. "
                                                    "숫자는 도구 결과의 값만, 원인 확정 · 확률 · 설정값 처방 금지. 수치 표 · 원인 후보 · "
                                                    "정비 이력은 Harness가 도구 결과에서 붙인다"},
        "causeIds": {"type": "array", "items": {"type": "string", "enum": CAUSE_IDS}, "description": "원인 매트릭스 ID"},
        "evidenceIds": {"type": "array", "items": {"type": "string"}},
        "rationale": {"type": "string"},
    },
    "required": ["processCode", "equipmentId", "ownerUserId", "question", "reason", "causeIds", "evidenceIds", "rationale"],
}

HARNESS_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "propose_cross_process_check",
            "description": (
                "다른 공정(앞 공정) 담당자에게 보낼 사실 확인 요청을 '제안'한다. 사람이 승인해야 등록된다. "
                "웨이퍼의 실제 경로에서 앞 공정 run의 결과가 정상 변동 밖이고 현재 공정 근거가 약할 때 쓴다. "
                "ownerUserId는 get_process_owner의 kind=PROCESS_OWNER userId를 쓴다."
            ),
            "parameters": _PROPOSAL_PARAMS,
        },
    },
    {
        "type": "function",
        "function": {
            "name": "propose_maintenance_request",
            "description": (
                "같은 공정 설비(PM)의 정비 담당에게 보낼 점검 요청을 '제안'한다. 사람이 승인해야 등록된다. "
                "현재 공정 run의 센서가 정상 참고 범위를 벗어나고 제거량이 정상 변동 밖일 때 쓴다. "
                "장비 설정 변경을 처방하지 않고 점검 항목만 적는다. ownerUserId는 kind=MAINTENANCE userId를 쓴다."
            ),
            "parameters": _PROPOSAL_PARAMS,
        },
    },
    {
        "type": "function",
        "function": {
            "name": "submit_result",
            "description": ("조사 결과를 제출하고 끝낸다. 모든 사실과 가설에 조회한 evidenceId를 붙인다. "
                            "원인 순위는 증거 기반 우선순위이며 확률이 아니다. 부품 원인을 모르면 미확인으로 둔다."),
            "parameters": {
                "type": "object",
                "properties": {
                    "nextStep": {"type": "string", "enum": ["CURRENT_CHECK", "UPSTREAM_CHECK", "OBSERVE",
                                                           "INSUFFICIENT_EVIDENCE", "NEEDS_DEEP_ANALYSIS"],
                                 "description": "1순위 가설의 원인 ID가 사건 공정이면 CURRENT_CHECK, 앞 공정이면 "
                                                "UPSTREAM_CHECK · 모든 단계가 정상 변동이면 OBSERVE · 판단할 근거가 없으면 "
                                                "INSUFFICIENT_EVIDENCE"},
                    "observedFacts": {"type": "array", "items": {"type": "object", "properties": {
                        "text": {"type": "string"}, "evidenceIds": {"type": "array", "items": {"type": "string"}}},
                        "required": ["text", "evidenceIds"]}},
                    "candidateCauses": {"type": "array", "items": {"type": "object", "properties": {
                        "priority": {"type": "integer"}, "hypothesis": {"type": "string"},
                        "causeIds": {"type": "array", "items": {"type": "string", "enum": CAUSE_IDS},
                                     "description": "원인 매트릭스 ID(15개 중에서만). 1순위 가설에는 반드시 하나 이상"},
                        "supportingEvidenceIds": {"type": "array", "items": {"type": "string"}},
                        "conflictingEvidenceIds": {"type": "array", "items": {"type": "string"}},
                        "missingData": {"type": "array", "items": {"type": "string"}},
                        "nextCheck": {"type": "string"}},
                        "required": ["priority", "hypothesis", "supportingEvidenceIds", "missingData", "nextCheck"]}},
                    "limitations": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["nextStep", "observedFacts", "candidateCauses", "limitations"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "submit_report",
            "description": "보고서 초안을 제출한다(REPORT 모드 전용). 다섯 칸을 모두 채우고, 기록에 없는 칸은 '기록 없음'으로 쓴다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "sections": {"type": "object", "properties": {
                        "overview": {"type": "string", "description": "사건 개요"},
                        "investigation": {"type": "string", "description": "기본 분석 · 조사 경과와 조사 우선순위 · 근거"},
                        "requestsAndReplies": {"type": "string", "description": "확인 요청과 담당자 회신"},
                        "actionsAndVerification": {"type": "string", "description": "실제 조치와 효과 확인"},
                        "unresolved": {"type": "string", "description": "미해결 사항 · 데이터 한계"}},
                        "required": ["overview", "investigation", "requestsAndReplies", "actionsAndVerification",
                                     "unresolved"]},
                    "evidenceIds": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["sections", "evidenceIds"],
            },
        },
    },
]
REPORT_SECTIONS = ("overview", "investigation", "requestsAndReplies", "actionsAndVerification", "unresolved")
HARNESS_NAMES = {t["function"]["name"] for t in HARNESS_TOOLS}

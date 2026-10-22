# Contract: 시나리오 ↔ 검증 추적표

**Feature**: [spec.md](../spec.md) · **Date**: 2026-10-21

spec.md의 각 사용자 시나리오가 **이미 있는** 자동 검증으로 덮이는지 보여 준다. 이 표가 문서와 코드 사이의 계약이다 —
시나리오나 테스트 이름이 바뀌면 이 표를 같이 고친다. 「수동」은 자동 검증이 없어 quickstart의 사람 확인으로 본다.

**이번 검증 범위**: US1 · US2 (plan.md · research R7). US3–US6 표는 다음 반복을 위해 남겨 두며 이번에는 검증하지 않는다(⏸ 보류).

경로 약어: `API` = `fab-context-api/src/test/java/com/fabcontext/api/api/`, `PY` = `fab-agent/tests/`,
`SMOKE` = `fab-agent/evaluation/compose_smoke.py`, `WEB` = `fab-context-web/tests/`

## US1 — 이상 사건 자동 접수와 기본 분석 ✅ 이번 범위

| 수용 기준 | 검증 |
|---|---|
| 1 이벤트 → 사건 생성 + 기본 분석 대기열 | `API/EventApiTest.newEventCreatesIncidentAndQueuesBasicAnalysis` |
| 2 같은 이벤트 ID → 기존 사건 | `API/EventApiTest.duplicateEventReturnsExistingIncident` |
| 3 사실 · 후보 · 한계, 근거 ID, 확률 · 확정 없음 | `API/AnalysisShapeApiTest`, `PY/test_guard_rejects_unknown_evidence_and_probabilities`, `PY/test_guard_rejects_unknown_cause_and_inconsistent_next_step` |
| 4 실시간 갱신 · 끊김 복구 | `WEB/analysis-request-status.test.tsx`, `WEB/incident-notifications.test.ts` (끊김 복구는 수동 — 2026-10-21 ✅ 재구독 snapshot · getJob 복구, verification.md) |
| 5 분석 시점 이후 데이터 제외 | `API/InternalApiTest.analysisTimeAndBaselineRulesAreEnforced`, `PY/test_errors_are_readable_and_followup_is_blocked` |

## US2 — 추가 질문 · 제안 · 승인 ✅ 이번 범위

| 수용 기준 | 검증 |
|---|---|
| 1 질문 → 근거에 맞는 제안 종류 · 대상 | `PY/test_proposal_must_match_evidence`, `PY/test_eval_all_incidents_pass`, `SMOKE`「Agent 제안 = Etch 담당자 확인 요청」, 수동 EV-1006 → 정비 요청(2026-10-21 ✅). `test_eval_all_incidents_pass`는 정답표가 없으면 건너뛴다 |
| 2 승인 전 요청 0건 | `PY/test_no_change_before_approval_and_reject_path`, `SMOKE`「승인 전 확인 요청 0건」 |
| 3 내용 변경 · 만료 · 선결정 시 거부 | `API/ApprovalApiTest.decisionGuardsVersionHashStateAndScope`, `API/ApprovalApiTest.expiredApprovalCannotBeExecuted` |
| 4 승인 내용 그대로 한 번만 실행 | `API/InternalApiTest.agentProposalIsRecordedOnApprovalAndExecutedOnceByApprovalId`, `API/ApprovalApiTest.approvingRecordActionExecutesStoredPayloadOnly`, `SMOKE`「확인 요청은 정확히 1건(멱등)」 |
| 5 받는 사람 검증 | `API/InternalApiTest.proposalRecipientIsCheckedByBackend` |
| 6 거절 → 재개 · 정리 | `PY/test_no_change_before_approval_and_reject_path`, `SMOKE`「회신 뒤 새 제안 거절 → 재개」(`SMOKE_MODE=real`에서만 실행 — 규칙 모드는 수동: 2026-10-21 ✅ EV-1007, verification.md) |
| 7 브리프 원인 「확정 아님」 · 처방 없음 | `API/InternalApiTest.briefCannotConfirmACause`, `PY/test_guard_rejects_brief_with_unseen_numbers_or_evidence`, `PY/test_action_hash_covers_brief` |

## US3 — 요청 확인과 회신 ⏸ 보류

| 수용 기준 | 검증 |
|---|---|
| 1 받는 사람에게 브리프 · 원인 후보 · 설비 표시 | `API/InternalApiTest.requestBriefIsHashedCopiedAndShownToRecipient`, `PY/test_upstream_request_brief_carries_numbers_causes_and_records`, `PY/test_maintenance_request_brief_has_sensor_deviation` |
| 2 원 사건 분석 · 원시 데이터 접근 거부 | `API/InvestigationPermissionsApiTest.maintenanceCanReadButCannotRunOrChangeAnotherTeamsInvestigation`, `API/InvestigationPermissionsApiTest.foreignProcessIsDeniedEvenWhenCallingTheApiDirectly` |
| 3 회신 → 조사 재개 | `API/CheckRequestApiTest.replyRules`, `SMOKE`「Etch 담당자 회신 (201)」「결과 kind = RESUME」 |
| 4 회신 반영 결과 · 이전 결과 이력 | `PY/test_second_proposal_after_reply_attaches_to_resume_job`, `SMOKE`「1순위 원인 후보 = 앞 공정 ETCH」 (이력 표시는 수동) |

## US4 — 조치 · 효과 · 보고서 ⏸ 보류

| 수용 기준 | 검증 |
|---|---|
| 1 미래 시각 조치 거부 | `API/ActionApiTest.recordValidation` |
| 2 효과 기록 ≠ 자동 종료 | `API/ActionApiTest.recordAndVerifyWithoutClosingIncident` |
| 3 요청 시에만 다섯 섹션 초안 | `API/ReportApiTest.draftIsComposedFromRecordsOnly`, `API/InternalApiTest.agentReportDraftNeedsAllFiveSections`, `SMOKE`「보고서 초안 저장」 |
| 4 동시 편집 충돌 거부 | `API/ReportApiTest.editConfirmExportAndNewVersion` |
| 5 확정 판본 보존 · 새 버전 | `API/ReportApiTest.editConfirmExportAndNewVersion` |
| 6 확인 run 없으면 해결 완료 아님 | 수동 (보고서 「미해결」 섹션 확인) |

## US5 — 모니터링 · 참고 지식 ⏸ 보류

| 수용 기준 | 검증 |
|---|---|
| 1 공정별 대시보드 집계 | `API/DashboardApiTest` |
| 2 시계열 원본 간격 · 결측 · 기준선 없음 사유 | `API/MonitoringApiTest.sensorDataMatchesFullExample`, `WEB/sensor-observations.test.ts` |
| 3 출처 구분 · 버전 · 시점 필터 | `API/KnowledgeApiTest.documentMetadataVersionsAndContent`, `PY/test_knowledge_search_tools_and_resources` |

## US6 — 관리자 상태 관리 ⏸ 보류

| 수용 기준 | 검증 |
|---|---|
| 1–3 보류 · 재개 · 종료, 엔지니어 거부 | `API/IncidentApiTest.managerTransitionsFollowStateRules` |

## 공통 Edge Cases

| 상황 | 검증 |
|---|---|
| 일시 오류 → 재시도 → 실패 처리 | `PY/test_transient_fault_retries_then_exhausts_to_dlq`, `PY/test_injected_fault_then_recovers` |
| 승인 대기 중 재시작 → 재개 | `fab-agent/evaluation/restart_smoke.py` (compose) |
| 같은 작업 중복 전달 | `PY/test_duplicate_delivery_is_skipped_and_unexpected_error_fails`, `API/InternalApiTest.claimsBlockDuplicateAttemptsAndEventsDriveJobState` |
| 같은 쓰기 재전송 | `API/ActionApiTest.idempotencyKeysAreScopedPerUser` |
| 제안 상한 · 반복 금지 | `PY/test_proposals_are_capped_and_not_repeated` |
| 루프 상한 | `PY/test_loop_limits_stop_the_agent` |
| 키 없는 의미 검색 → 시작 실패 | `PY/test_openai_without_key_fails_startup` |
| 처방 · 확정 요구 질문 | 수동 (실제 모델 평가 E04–E08, 정답표 미보유) |

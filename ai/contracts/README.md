# 팀 계약 사본 — 테스트 검증용

`tests/conftest.py`가 Agent 이벤트 · 작업 메시지를 이 스키마로 검증한다(`test_events_and_jobs_follow_team_contract`).

| 파일 | 쓰임 |
|---|---|
| `schemas/agent-event.schema.json` | Agent → Spring 이벤트(APPROVAL_REQUIRED · WAITING_REPLY · RESULT_READY …) |
| `schemas/job-message.schema.json` | Spring → Agent 작업 메시지 |
| `public-openapi.json` | 위 이벤트 스키마가 `$ref`로 `AnalysisResult` · `Report` 정의를 가져다 쓴다 |

원본은 팀 계약 폴더(`fab-team-plan_draft/contracts/`, 팀 내부)다. 계약이 바뀌면 같은 세 파일을 다시 복사한다 — 배치(`schemas/`에서 `../public-openapi.json`)를 바꾸면 `$ref`가 깨진다.

Observability 확장: 이벤트 바깥(envelope)에 선택 필드 `metrics`를 추가했다. 작업을 끝내거나 멈추는 이벤트에만 이번 시도의 trace 요약(모델 호출 · 토큰 · 실제 응답 모델 · 도구 호출 · 재시도 · Harness 거부 · 시간)을 싣고, backend가 `job.metrics`(jsonb)에 저장한다.

호환용 업무 분류 필드: `RESULT_READY.payload.workSuggestions`는 기존 소비자와의 호환을 위해 선택 필드로 남긴다. 현재 Agent는 이 필드를 보내지 않고 요청 제안 → 사람 승인 → MCP 실행 → 회신 대기 흐름을 사용한다. `result`와 관측용 `metrics` 계약은 유지한다.

보고서 요약(SUMMARY) 확장(10-22, 보고서 · Job 담당과 합의): 작업 종류 `SUMMARY`와 작업 메시지의 선택 칸 `input`(종류별로 정의한 필드만 — SUMMARY는 `input.summaryRef{reportId, reportVersion, contentRevision, snapshotRef}` 필수), 이벤트 envelope의 필수 칸 `attempt`(모든 이벤트 — backend의 늦은 이벤트 검사용), 이벤트 `SUMMARY_READY`(payload `reportId` · `snapshotRef` · `summary{lines 1~2줄 · 줄당 120자, evidenceIds, generatedBy model|rule}`), 공개 API `Job.kind`의 `SUMMARY`와 `Report.aiSummary`(`ReportAiSummary` — PENDING · READY · FAILED)를 더했다. 요약 작업은 조사 그래프 · 체크포인트를 쓰지 않고, 사건 상태 · 타임라인을 바꾸지 않는다. 프론트 계약 사본(`data/backend-handoff-v2/combined.openapi.json`)은 `ops/tools/openapi_diff.py`가 서버와 대조하므로 엔드포인트를 구현하는 쪽이 구현과 함께 고친다.

이벤트 `eventId`는 같은 작업 · 시도 · 종류면 같다(`EVT-{jobId}-A{attempt}-{type}`, 진행 알림만 `-{n}`, 10-23) — backend가 같은 eventId를 한 번만 적용할 수 있게.

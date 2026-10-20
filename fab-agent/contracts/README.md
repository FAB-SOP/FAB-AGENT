# 팀 계약 사본 — 테스트 검증용

`tests/conftest.py`가 Agent 이벤트 · 작업 메시지를 이 스키마로 검증한다(`test_events_and_jobs_follow_team_contract`).

| 파일 | 쓰임 |
|---|---|
| `schemas/agent-event.schema.json` | Agent → Spring 이벤트(APPROVAL_REQUIRED · WAITING_REPLY · RESULT_READY …) |
| `schemas/job-message.schema.json` | Spring → Agent 작업 메시지 |
| `public-openapi.json` | 위 이벤트 스키마가 `$ref`로 `AnalysisResult` · `Report` 정의를 가져다 쓴다 |

원본은 팀 계약 폴더(`fab-team-plan_draft/contracts/`, 팀 내부)다. 계약이 바뀌면 같은 세 파일을 다시 복사한다 — 배치(`schemas/`에서 `../public-openapi.json`)를 바꾸면 `$ref`가 깨진다.

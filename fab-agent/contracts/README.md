# 팀 계약 사본 — 테스트 검증용

`tests/conftest.py`가 Agent 이벤트 · 작업 메시지를 이 스키마로 검증한다(`test_events_and_jobs_follow_team_contract`).

| 파일 | 쓰임 |
|---|---|
| `schemas/agent-event.schema.json` | Agent → Spring 이벤트(APPROVAL_REQUIRED · WAITING_REPLY · RESULT_READY …) |
| `schemas/job-message.schema.json` | Spring → Agent 작업 메시지 |
| `public-openapi.json` | 위 이벤트 스키마가 `$ref`로 `AnalysisResult` · `Report` 정의를 가져다 쓴다 |

원본은 팀 계약 폴더(`fab-team-plan_draft/contracts/`, 팀 내부)다. 계약이 바뀌면 같은 세 파일을 다시 복사한다 — 배치(`schemas/`에서 `../public-openapi.json`)를 바꾸면 `$ref`가 깨진다.

업무 분류 확장: `RESULT_READY.payload.workSuggestions` 선택 필드를 추가했다. `result`의 기존 AnalysisResult 계약은 유지하며, 서버가 업무 유형으로 채택·관리자 승인·타 팀 발송 경로를 결정한다. 배포할 때 이 이벤트 스키마도 백엔드·Agent와 함께 동기화한다.

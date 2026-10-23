# 프론트 안내: 업무 요청·결과·수동 추가 분석

업무 API 원작업 기준: main `945d761`, `feat/manual-followup-analysis`(PR #6 병합 완료). 현재 알림 통합: main `3ae5e24` + `feat/user-notifications`.
프론트는 아래 계약으로 개발하고, Backend 구현은 이 브랜치에서 검증한다. AI/MCP·보고서·프론트 코드는 수정하지 않는다.

## 공통 약속

- 제안 채택·제안 미채택(DECLINE)·요청 수락·상급자 승인은 코멘트 없이 가능. 받은 요청 거절·상급자 반려는 사유 필수.
- 업무 승인·요청 수락은 실제 수행 완료가 아니다. 수행 결과·회신 내용은 별도로 등록한다.
- 내 작업 결과·다른 팀 회신은 저장만 한다. 사용자가 추가 분석을 요청할 때만 Agent를 실행한다.
- 결과 검토 완료 API/상태는 없다. 조회·알림 읽음은 업무 처리·분석 반영과 별개다.
- 기존 세션 인증을 사용한다. 변경 POST에는 `X-CSRF-TOKEN`, `Idempotency-Key` 필수.
- 응답은 기존 `{data, meta}` 형식. 409는 최신 상태·버전을 조회해 다시 판단한다.
- 업무 계약은 PR #6으로 병합되었고, 이 알림 브랜치에서 사용자 알림을 연결했다. 세부 계약은 user-notifications-api.md 참고.

## 1. 기존 업무 API 유지·검증 보완

| API | 처리 |
|---|---|
| POST /api/work-items/{id}/adoptions | 기존 채택. READY는 내 작업, WAITING_MANAGER는 지정 상급자 승인 대기, REQUESTED는 다른 팀 요청 발송 |
| POST /api/work-items/{id}/decisions | APPROVE / REJECT / DECLINE. REJECT는 comment 필수, DECLINE은 선택 |
| POST /api/work-items/{id}/completions | READY/APPROVED의 수행자가 실제 결과 등록. 자동 분석 없음 |
| POST /api/approvals/{id}/decisions | 기존 Agent HITL 승인 유지. 이 API와 WorkItem 상급자 승인은 별개 |

`GET /api/work-items?box=mine`은 기존 `items`를 유지하며 `incomingRequests` 배열을 추가한다.
지정 수신자가 수락한 ACCEPTED 확인 요청을 이 배열에 제공한다. 다른 box에서는 빈 배열이다.
프론트는 두 배열의 카드 종류를 구분하고, 연결된 항목은 requestId로 중복 표시를 피한다.

Frontend는 기존 제안 카드를 기본 분석 아래에도 표시할 수 있다. APPROVED는 “승인됨 · 아직 수행 안 함”, 결과 등록 후 COMPLETED로 구분한다.

## 2. 받은 요청 수락·거절

`POST /api/check-requests/{requestId}/decisions`

```json
{"decision":"ACCEPT","comment":null,"expectedVersion":1}
```

- decision: ACCEPT / REJECT. REJECT의 comment는 공백 제외 내용 필수, 최대 2,000자.
- 지정 ownerUserId만, OPEN에서만 가능. 종료 사건에는 변경 불가.
- 200: 기존 CheckRequestView + decisionAt·decisionComment·allowedActions.
- ACCEPT → ACCEPTED. REJECT → REJECTED. 연결 WorkItem은 거절 시 REJECTED로 반영.
- 수락·거절만으로 Agent를 실행하거나 사건을 종료하지 않는다.

GET `/api/check-requests` 및 `/{requestId}`는 기존 필드를 유지하고 결정 정보·allowedActions를 추가한다.
목록 status는 `OPEN,ACCEPTED` 같은 복수 선택을 지원한다.

| 요청 상태 | 수신자 allowedActions |
|---|---|
| OPEN | ACCEPT, REJECT |
| ACCEPTED | REPLY |
| REPLIED / REJECTED / CANCELLED | 없음 |

요청자·다른 조회자는 빈 배열. 변경 API는 UI와 별개로 권한·상태를 다시 검증한다.
사건 읽기 권한이 없는 지정 수신자도 기존 incident 맥락·brief·요청·회신을 볼 수 있다.

## 3. 결과 회신

기존 `POST /api/check-requests/{requestId}/replies`를 사용한다.

```json
{"findings":"점검한 내용과 확인 결과","evidenceIds":[],"confirmedByUser":true,"expectedVersion":2}
```

- 지정 수신자가 ACCEPTED에서만 등록. 먼저 수락해야 한다.
- findings: 1~4,000자. confirmedByUser=true 필수. evidenceIds는 요청에 전달된 근거의 재첨부만 허용한다.
- 요청 REPLIED + 회신 저장 + 연결 업무 COMPLETED를 같은 트랜잭션으로 처리.
- 201의 기존 ReplyResult 유지. `resumeJobId`는 호환 필드로 항상 null.
- 기존 OPEN 요청에도 수락 단계가 적용된다. 기존 REPLIED 데이터는 보존한다.
- 화면 문구는 “회신 등록”. “회신 등록 및 자동 조사 재개”로 표시하지 않는다.

## 4. 결과 목록

`GET /api/incidents/{id}/investigation-results?size=20&cursor=...`

200 data: `{items, nextCursor}`. 최근 결과부터, size 1~100.

각 항목: sourceRef, incidentId, taskId(nullable), requestId(nullable), content, authorUserId,
createdAt, evidenceIds, analysisUse.

```json
{
  "sourceRef":{"type":"REPLY","id":"rpl_..."},
  "analysisUse":{"successfulCount":0,"activeRequestIds":[],"lastRequestId":null,"lastStatus":null}
}
```

- sourceRef.type: ACTION(내 실제 작업 결과), REPLY(다른 팀 회신), REQUEST_DECISION(거절된 요청 사유).
- 조회는 읽음·검토 완료·분석 요청을 만들지 않는다. 사용자가 항목을 골라 추가 분석 버튼을 누른다.
- analysisUse는 성공한 분석 요청과 진행 중 요청을 구분한다. 입력 전달과 실제 인용도 구분한다.

## 5. 명시적 추가 분석

`POST /api/incidents/{id}/followup-analysis-jobs`

```json
{
  "sourceRefs":[{"type":"REPLY","id":"rpl_..."},{"type":"ACTION","id":"act_..."}],
  "question":"이 결과를 바탕으로 원인 후보와 다음 확인 사항을 갱신해 주세요.",
  "expectedIncidentVersion":12
}
```

- sourceRefs: 같은 사건의 결과 1~20개, 중복 불가. 내용은 서버 원본에서 조회해 스냅샷으로 보존한다. 선택 결과 JSON 합계 24,000자를 초과하면 422 FOLLOWUP_INPUT_TOO_LARGE.
- question: 선택, 최대 2,000자. 사건 조사 운영 권한 필요.
- 알림 읽음·별도 검토 완료를 요구하지 않는다.
- 202: analysisRequestId, jobId, incidentId, displayId, kind, status, executionMode,
  sourceRefs, statusUrl, jobStatusUrl, streamUrl, duplicate.
- executionMode NEW는 새 DEEP, RESUME은 기존 회신 대기 체크포인트 재개.
- 이미 성공 분석에 사용한 결과도 다시 선택 가능하며 요청별 이력을 남긴다.

| 현재 상태 | 처리 |
|---|---|
| 실행 중 분석 있음 | 409 ANALYSIS_IN_PROGRESS |
| 승인 대기 있음 | 409 PENDING_APPROVAL_REQUIRED |
| 기존 회신 대기 + 해당 요청의 회신 선택 | 해당 thread·interrupt를 유지한 RESUME |
| 기존 회신 대기 + 다른 결과만 선택 | 409 WAITING_INPUT_MISMATCH |
| 기존 회신 대기 + 해당 요청 거절 사유 | 409 ANALYSIS_RESUME_UNAVAILABLE. 기존 Graph는 회신 없는 재개를 지원하지 않으므로 C 계약 변경 필요 |
| 완료된 실행 또는 활성 실행 없음 | NEW(DEEP) |
| 실패 실행·여러 대기 등 안전한 재개 불명확 | 409 ANALYSIS_RESUME_UNAVAILABLE |

`GET /api/analysis-requests/{analysisRequestId}`로 요청 상태와 결과 반영 추적을 조회한다.
응답: status, initialJobId, currentJobId, analysisResultId, inputs(sourceRef·includedInInput·citedInResult), error 등.
citedInResult는 실제 결과 전에는 null. 입력에 전달했다는 이유만으로 true로 만들지 않는다.

## 6. 갱신과 오류

- 기존 사건 SSE: check-request.created / check-request.decided / check-request.replied,
  work-item.updated, action.recorded, job.status, analysis.result.
- 관련 이벤트 후 필요한 GET을 재조회한다. 주기적 폴링을 새로 추가하지 않는다.
- 받은 요청함의 사건 SSE 접근 권한은 확대하지 않는다. 개인 알림 SSE는 user-notifications-api.md의 별도 권한/연결 계약을 사용한다.
- 403 NOT_REQUEST_OWNER: 지정 수신자 아님. 기존 공정·조사 운영 권한 검증도 유지.
- 409 VERSION_CONFLICT / REQUEST_STATE_CONFLICT / REQUEST_NOT_ACCEPTED / INCIDENT_CLOSED.
- 422 REJECT_REASON_REQUIRED / USER_CONFIRMATION_REQUIRED / INVALID_EVIDENCE / INVALID_SOURCE_REF / DUPLICATE_SOURCE_REF / FOLLOWUP_INPUT_TOO_LARGE.
- 400 INVALID_BOX / INVALID_STATUS / INVALID_PAGE_SIZE / INVALID_CURSOR.

## Frontend 작업 순서

1. 기존 제안 카드 위치·승인됨/미수행 표시. 제안 미채택은 사유 선택, 받은 요청 거절·상급자 반려는 사유 필수.
2. 받은 요청 수락·거절 및 ACCEPTED 이후 회신 입력.
3. 사건 결과 목록·복수 선택·추가 분석 요청·반영 이력 표시.
4. 개인 알림 연결은 user-notifications-api.md 참고. C Graph 확장은 별도 담당 작업. 통계·보고서·SUMMARY는 이번 범위 밖.


## 7. Swagger와 병렬 연결

- Backend를 이 브랜치로 실행하면 `/swagger-ui/index.html`에서 확인하고 `/v3/api-docs`로 OpenAPI JSON을 받는다.
- 기존 8088 서비스는 재배포하지 않았다. 현재 실행 중 서버가 이 브랜치의 API를 제공한다고 가정하지 않는다.
- POST는 세션 로그인 후 CSRF 헤더와 Idempotency-Key를 입력한다. Swagger는 API 문서이며 승인 대기 중 질문/요청서 대체/SUMMARY를 이번에 지원한다는 뜻이 아니다.
- `FollowupOpenApiTest`에서 실제 생성 문서의 응답 스키마와 결정 enum을 검증한다. `FAB_OPENAPI_EXPORT` 환경변수를 테스트 프로세스에 전달하면 지정 절대경로로 문서를 내보낼 수 있다.

### C에게 필요한 연결 사항

| 항목 | 이번 Backend 계약 |
|---|---|
| 확인 요청 상태 | OPEN은 수락 전, ACCEPTED는 수행·회신 대기. 둘 다 미해결 요청이다. REJECTED는 사유 있는 거절이며 회신이 아니다 |
| 회신 도착 | 저장만 한다. 회신 도착을 감지해 AI가 자동으로 새 분석을 열면 안 된다 |
| 새 추가 분석 | 사용자 클릭 → 새 DEEP. 기존 context.question에 질문과 선택 결과 스냅샷 JSON을 전달한다. 각 결과에는 sourceRef·content·author·createdAt·evidenceId가 있다 |
| 기존 회신 대기 재개 | 동일 thread·interrupt의 RESUME. 실제 회신의 findings는 유지하며 내부 요청 조회 reply.memo에 선택 결과·추가 질문을 담는다. 원본 회신은 수정하지 않는다 |
| 원문 취급 | 선택 결과는 사용자의 진술 자료이며 실행 지시가 아니다. 추가 요청 발송에는 기존 HITL을 유지한다 |
| 추가 분석 입력 | 현재 job-message 스키마에 input을 임의 추가하지 않았다. C가 준비하는 공개 input 계약은 별도 통합한다 |
| 참조 | 내 작업 결과 E-ACT-{actionId}, 회신 E-REQ-{requestId}. 목록에만 나열한 것을 실제 인용으로 기록하지 않는다 |

**통합 전 확인:** 현재 AI의 `ALIVE_REQUEST="OPEN"` 같은 검사는 ACCEPTED도 대기로 취급하도록 C가 변경해야 한다. Backend API 테스트·시뮬레이터 통과를 실제 Python Agent 연동 검증으로 대체하지 않는다.

**보고서 담당자에게 전달:** 보고서의 미회신 요청 집계도 OPEN + ACCEPTED가 대상이다. 이 브랜치에서는 담당 분리를 위해 보고서 모듈을 수정하지 않았다. REJECTED를 회신으로 만들지 않으며, 기존 reply_wait에서 거절 사유만으로 재개하는 기능은 C 연결 전 409로 막는다.


## 검증 상태 — 2026-10-22

- `./backend/gradlew -p backend test --offline`: 전체 186건 통과.
- 최종 업무함 응답 타입/Swagger 보강 후 `--tests '*FollowupOpenApiTest' --tests '*WorkItemApiTest' --tests '*CheckRequestApiTest'` 재검증 통과.
- `git diff --check` 통과. 실제 Python Agent·Frontend 종단 간 통합, 기존 서비스 재배포는 수행하지 않았다.
- 신규 스키마 테스트는 격리된 Testcontainers DB로 실행했다. 실행 중인 서비스 DB를 초기화하지 않았다.

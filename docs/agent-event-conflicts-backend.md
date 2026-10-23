# Agent 이벤트 중복·내용 충돌 — Backend 연결 안내

브랜치 `feat/agent-event-conflicts`, 기준 main `72d6e01`. Backend 수신부만 변경한다. AI/MCP·Frontend·보고서 모듈·계약 스키마는 수정하지 않는다. Kafka는 사용하지 않는다.

## 유지하는 원칙

- `POST /internal/jobs/{jobId}/events`와 기존 `{data,meta}` 응답 유지. 정상 이벤트의 요청 필드 추가 없음.
- 처음 검증·커밋된 결과를 보존한다. 충돌은 Job 실패/재시도/사건 상태 변경/새 알림/새 결과를 만들지 않는다.
- 처리 실패 시 수신 기록과 업무 변경이 함께 롤백된다. 잘못된 첫 요청을 고친 후 같은 ID로 다시 보낼 수 있다.
- 질문 문자열은 중복 기준이 아니다. 다른 Job과 승인 RESUME의 새 결과는 독립적이다.

## 처리 기준

| 상황 | HTTP / data.reason | 결과 |
| --- | --- | --- |
| 같은 eventId·같은 내용 | 200 / DUPLICATE_EVENT | 재적용 없음 |
| 같은 eventId·다른 내용 또는 다른 Job/attempt/type 재사용 | 200 / EVENT_CONTENT_CONFLICT | 최초 기록 보존·충돌 기록 |
| 같은 Job/attempt의 최종 결과, 다른 eventId·같은 내용 | 200 / DUPLICATE_RESULT | 결과·알림 중복 생성 없음 |
| 같은 Job/attempt의 최종 결과, 다른 eventId·다른 내용 | 200 / EVENT_CONTENT_CONFLICT | 최초 결과 보존·충돌 기록 |
| 이전/미래 attempt, 기존 eventId 수신 기록 없음 | 200 / ATTEMPT_MISMATCH | 기존 방식대로 무시 |
| 종료 작업에 해당 논리 결과 수신 기록도 없는 새 이벤트 | 200 / JOB_FINISHED | 무시 |

모든 무시 응답은 `applied:false`다. 최초 적용 응답은 기존 `jobId/applied:true/status`다. 충돌을 HTTP 오류로 바꾸지 않으므로 Worker가 같은 잘못된 이벤트를 오류 재시도하지 않게 한다. Agent는 EVENT_CONTENT_CONFLICT를 정상 적용으로 보고하지 말고 trace 경고로 남기면 된다.

최종 결과 범주: RESULT_READY / REPORT_DRAFT_READY / SUMMARY_READY / JOB_FAILED. 같은 Job/attempt에서 성공 결과와 실패가 경쟁해도 먼저 커밋한 하나만 적용한다. APPROVAL_REQUIRED와 WAITING_REPLY는 각각 그 타입 내에서 같은 논리 이벤트를 비교한다. 반복 ANALYSIS_PROGRESS는 서로 다른 ID를 계속 적용하며 하나로 합치지 않는다.

## 내용 비교

키 순서와 JSON 숫자 표현(1/1.0)은 정규화하고 배열 순서는 보존한다. 전송 메타데이터를 제외한 envelope/payload의 SHA-256을 저장한다.

- 제외: 최상위 eventId, at, sequence, metrics, traceId.
- 추가 제외: JOB_STARTED.payload.startedAt, RETRY_SCHEDULED.payload.nextRetryAt.
- 유지: 답변·후보·근거·순서·보고서/스냅샷 ID·업무 시각·RETRY_SCHEDULED의 nextAttempt/errorCode 등 업무 내용. payload 안의 at/sequence/metrics 같은 이름을 재귀적으로 삭제하지 않는다.
- 기존 호환 입력에서 생략 가능한 schemaVersion/jobId/incidentId/threadId는 현재 Job의 기본값으로 정규화한다. 필드가 명시돼 있으면 그 값을 비교한다.

C가 재전송 때 추가로 바꾸는 필드가 있다면 제외 가능 여부를 함께 확인한다. 제외 목록을 넓히기 전에 그 필드가 전송 정보인지 업무 내용인지 구분한다.

## 저장·로그·동시 수신

기존 processed_agent_event에 nullable 컬럼 contentHash, conflictCount, lastConflictHash, lastConflictEventId, lastConflictAt과 Job/attempt/type 조회 인덱스를 추가한다. 별도 테이블·원문 복사본은 만들지 않는다. conflictCount는 충돌 수신 횟수이고 서로 다른 payload 종류 수가 아니다. 최초 수신 해시는 변경하지 않으며 마지막 충돌 정보만 보존한다.

- 기존 사건→Job DB 잠금으로 같은 사건의 결과 수신을 직렬화한다.
- 전역 eventId를 서로 다른 사건이 동시에 재사용하는 오류도 처리하기 위해 PostgreSQL 트랜잭션 advisory lock으로 같은 ID를 직렬화한다. 충돌 기록 행도 잠근 뒤 갱신한다. JVM 메모리 락에 의존하지 않는다.
- 커밋 후 WARN `event=agent.event.conflict`. Job/eventId/attempt/type/해시만 남기며 답변·회신·숫자 측정값 같은 원문을 로그에 담지 않는다.
- 옛 행은 해시가 없어 내용을 검증할 수 없다. 같은 식별 정보면 기존 중복 무시를 유지하고 WARN `agent.event.legacy-unverifiable`로 남긴다. 재수신한 내용을 과거의 정답 해시로 채우지 않는다. 옛 행도 Job/attempt/type가 다르면 충돌이다.
- 현재 ddl-auto=update에 따라 다음 해당 버전 기동 시 컬럼/인덱스가 추가된다. 서비스 DB 재시드·마이그레이션·배포는 이번 작업에서 실행하지 않았다. 기존 행의 내용 비교를 사후 복원하지 않는다.

RETRY_SCHEDULED는 nextRetryAt 비교 제외 변경을 구분하도록 신규 해시에 v2 접두사를 사용한다. 기존 v1 RETRY 행은 원래 nextRetryAt 원문이 없어 새 비교식으로 변환할 수 없으므로 식별 정보가 같으면 검증 불가 경고와 기존 중복 무시를 유지한다. 다른 이벤트 해시는 바꾸지 않는다. 상세 호환 규칙은 report-context-analysis-api.md 참고.

## 의진님 전달·확인

이벤트 충돌 보완 자체는 요청 형식을 바꾸지 않는다. 같은 이벤트 재전송은 같은 eventId를 쓰고 업무 payload를 유지한다. `at/sequence/metrics/traceId/JOB_STARTED.startedAt/RETRY_SCHEDULED.nextRetryAt` 차이는 허용한다. 다른 payload가 EVENT_CONTENT_CONFLICT로 무시되면 재전송/새 ID 생성으로 덮어쓰려 하지 않고 trace에 남긴다.

함께 연결할 기존 작업:

1. APPROVAL_REQUIRED의 답·업무 후보 전송. Backend는 저장·공개하되 대기 유지.
2. payload.result / payload.analysis 형제 구조, answer/resultKind/nextStep와 후보 candidateId/causeIds/stance 전송.
3. `/internal/incidents/{id}/history` MCP 연결 및 새 질문/분석 시작 시 조회.
4. 신규 thread의 MANUAL 전환 시점·설정 조율. 기존 thread/체크포인트는 호환 유지.
5. 송신 전환 후 승인 전 답변→질문 중 승인→발송→회신→사람의 추가 분석 통합 확인.

위 항목은 이번 브랜치에서 AI 코드를 대신 수정하지 않는다. FAB_RESULT_EXTENSIONS만 켜는 것으로 새 필드 배치가 맞아지는 것은 아니다.

## 선웅님 전달·확인

보고서 확정/자동 종료/미처리 업무 검사/SUMMARY 연동은 main의 기존 구현을 유지한다. 이번 변경은 SUMMARY_READY의 중복·충돌도 수신 단계에서 차단하며 최초 요약·확정 보고서·알림을 다시 만들지 않는다. 보고서 담당의 별도 코드 변경은 이 충돌 검증 때문에 필요하지 않다.

별도 협의할 내용: 현재 get_report_context에는 사건·요청/회신·작업·보고서 목록이 있고 최신 원인 분석이 없다. Backend 내부 조회에 latestCauseAnalysis(resultId/분석 시각/답·요약/후보·근거/nextStep/한계)를 추가할지, 선웅님/C가 어떤 필드를 소비할지 확인한다. 같은 보완을 로컬/별도 브랜치에서 이미 하는지도 먼저 확인한다. 보고서 초안 자동 생성·이전 판 이어받기 정책과 보고서 모듈은 선웅님/C 범위다.

## 검증

- `cd backend && ./gradlew test --offline --tests '*AgentEventFingerprintTest' --tests '*AgentEventDeliveryApiTest'`: 18건 통과.
- `cd backend && ./gradlew test --offline`: 전체 347건, 통과345/실패0/오류0/기존 KnowledgeApiTest 건너뜀2.
- 신규 9건: 키/숫자 정규화·배열/업무 시각 보존, 전송 정보 변경 허용·진행 ID 분리, 같은 ID 다른 답 보존, 다른 ID 최종 결과 비교, 서로 다른 최종 답 동시 수신, 다른 사건의 동일 전역 ID 동시 수신, 과거 해시 없는 행 호환, SUMMARY 최초 요약/확정본/알림 보존.
- 기존 잘못된 최초 이벤트 롤백 후 재수신, 이전 attempt 차단, 같은 내용 8개 동시 수신, 승인/RESUME/회신/보고서 테스트도 전체 실행에 포함.
- `git diff --check` 통과. 격리 Testcontainers PostgreSQL 사용. 실제 서비스 DB·배포·브라우저는 변경하지 않았다.
- Python Agent는 수정/재실행하지 않았다. 현재 HTTP 클라이언트는 성공 응답 data를 반환하므로 새 applied:false/reason 처리는 C에게 trace 경고로 연결 요청한다.

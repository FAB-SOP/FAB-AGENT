# 승인 대기와 현재 실행 분리 — Backend 연결

브랜치 `feat/approval-resume`, 시작 main `4c237c3`(PR #18·#19 병합), 최신 main `e3b82d8`(PR #23 테스트 수정 포함) 통합. 기존 API와 상태값을 유지하고 대기 작업 조회 및 실행 제어를 확장한다. AI/Python/MCP·Frontend·보고서 모듈 소스는 변경하지 않는다.

## 기존 동작과 변경

| 기존 | 변경 |
| --- | --- |
| 새 질문이 activeJobId를 바꿔 승인 카드 조회가 끊김 | 현재 작업과 별도로 waitingJobs를 조회 |
| 질문 실행 중 승인하면 JOB_IN_PROGRESS로 결정까지 롤백 | 결정과 QUEUED RESUME을 같은 트랜잭션에 저장 |
| 재개가 즉시 시작되어 다른 작업과 겹칠 수 있음 | 사건별 실행 가능 여부를 대기열·claim·시작 이벤트·발송에서 재검사 |
| 승인 대기가 있으면 선택 결과 추가 분석도 차단 | 실행 중인 작업이 없으면 질문·선택 결과 추가 분석 가능 |
| 여러 Agent 승인 요청이 동시에 생성될 수 있음 | 사건당 PENDING Agent 승인 요청 하나. 같은 제안 키 재전송은 기존 승인 반환 |

## 사용자 동작

1. 승인 대기 중 질문: 새 DEEP/thread가 질문을 처리하고 기존 승인 작업·카드는 보존한다.
2. 질문 QUEUED/RUNNING 중 승인·거절: 기존 승인 결정 API로 저장한다. 외부 Agent는 같은 부모 thread에 연결된 RESUME이 QUEUED로 생성된다. 현재 질문의 activeJobId/latestJobStatus를 덮지 않는다.
3. 이미 접수한 질문이 먼저 진행된다. 질문이 완료·실패하거나 사람 대기로 전환되면 재개 작업을 실행할 수 있다. 새 질문은 진행 중/대기열의 실행이 끝날 때까지 기존 409로 차단한다.
4. 같은 사건에서 실제 조사/보고서 작업은 한 번에 하나만 RUNNING이 된다. 다음 대기열에서는 RESUME을 우선하고 같은 종류는 접수 시각·ID로 정렬한다.
5. SUMMARY는 조사 실행 검사와 독립이며 activeJobId/latestJobStatus를 바꾸지 않는다.
6. 승인·수락은 사건 종료가 아니다. 부모 대기 Job이 RESUME_JOB 참조로 끝나는 것도 실행 인계이며 Graph 삭제/조사 종료가 아니다. 실제 Agent는 원래 thread의 체크포인트를 재개한다.
7. 회신·직접 작업 결과 저장만으로 자동 재분석하지 않는다. 사람이 결과를 골라 추가 분석하는 흐름은 유지한다.

## 프론트: 사건 상세·SSE

`GET /api/incidents/{incidentId}`의 기존 필드 유지, `waitingJobs` 추가. 사건별 SSE 최초 snapshot에도 같은 waitingJobs를 제공한다.

```json
{
  "activeJobId":"job_question",
  "activeJob":{"jobId":"job_question","kind":"DEEP","status":"RUNNING","attempt":1,"waitingRef":null},
  "waitingJobs":[{
    "jobId":"job_original",
    "kind":"DEEP",
    "status":"WAITING_APPROVAL",
    "attempt":1,
    "waitingRef":{"type":"APPROVAL","id":"apv_original"}
  }]
}
```

- 현재 질문은 activeJob으로, 승인·회신 대기 카드는 waitingJobs로 조회한다. WAITING_APPROVAL 카드의 상세는 waitingRef.id로 기존 승인 조회 API를 사용한다.
- 기존 화면 호환을 위해 실행이 없으면 activeJob이 대기 작업을 가리킬 수도 있다. activeJob과 waitingJobs의 같은 jobId는 중복 표시하지 않는다.
- 승인 카드 보존을 activeJobId 하나에 의존하지 않는다. 승인 후에는 승인 상세의 상태와 resumeJobId로 재개 진행을 확인한다.
- 기존 `job.status`, `approval.created`, `approval.decided`, 분석/회신 이벤트가 오면 필요한 상세를 재조회한다. 새 알림 종류나 주기적 알림 폴링은 추가하지 않는다.
- 승인 상세의 sourceResultId(이전 PR)는 유지한다. 오래된 기준 결과로 만든 카드가 새 질문 때문에 자동 거절·대체·삭제되지 않는다.
- 사건 읽기/쓰기 권한은 기존과 동일하다. 다른 공정 수신자에게 사건 전체 조회 권한을 부여하지 않는다.

## 기존 승인 결정 API

`POST /api/approvals/{approvalId}/decisions`

```json
{"decision":"APPROVE","expectedVersion":1}
```

또는 거절 사유를 포함한 REJECT. 승인은 코멘트 없이, 거절은 사유 필수다. CSRF·Idempotency-Key·버전/해시·사건 권한 검증을 유지한다.

성공은 기존 `{approval,resumeJobId,executionRef}` 응답이다. 질문 중에도 200이며 승인 상태가 저장된다. 외부 Agent 재개는 resumeJobId의 GET /api/jobs 응답에서 QUEUED를 볼 수 있다. QUEUED는 결정 실패가 아니라 실행 순서를 기다리는 상태다. 실제 요청은 승인된 재개가 실행할 때 생성된다.

- 동시에 서로 다른 결정을 보내면 먼저 저장된 하나만 적용한다. 다음 요청은 기존 승인 상태/버전 충돌 409다. 재개 작업도 하나만 생성한다.
- 같은 Idempotency-Key 재전송은 기존 멱등 계약을 따른다.
- 시뮬레이터의 거절은 기존처럼 거절 기록으로 해당 대기 Job을 정리한다. 실제 외부 Agent는 거절도 RESUME으로 전달해 근거를 반영한다. 두 경우 모두 사건을 종료하지 않는다.

## C: 대기열·실행

- `GET /internal/jobs?status=QUEUED`는 지금 실행 가능한 작업을 제공한다. DB의 모든 QUEUED 목록이 아니다. blocked RESUME은 DB와 GET /internal/jobs/{id}/context에는 남고, 선행 질문이 끝나면 대기열에 나타난다.
- `POST /internal/jobs/{id}/claims`에서 사건→작업 순서로 잠근 뒤 재검사한다. 아직 차례가 아니면 409 CONFLICT이며 현재 작업을 실패 처리하지 않고 다음 대기열 조회를 기다린다. 기존 Agent의 claim 충돌 건너뛰기 동작과 호환된다.
- 너무 이른 JOB_STARTED/ANALYSIS_PROGRESS/결과/승인대기 이벤트도 409이며 수신 기록·업무 상태를 변경하지 않는다. 따라서 원래 eventId로 나중에 다시 보낼 수 있다.
- 재개가 아직 막혀 있을 때 승인된 변경 API를 직접 호출해도 409 JOB_START_DEFERRED(내부 envelope code=CONFLICT)다. 승인 기록은 유효하지만 질문을 건너뛰어 요청을 발송하지 않는다.
- 기존 RESUME.parentJobId·threadId·interruptId·requestedBy·입력 스냅샷·흐름 버전을 유지한다. 승인 결정자를 새 분석 요청자로 바꾸지 않는다.
- 새 Agent 제안이 기존 PENDING Agent 승인과 겹치면 409 AGENT_APPROVAL_PENDING(내부 code=CONFLICT)이다. 기존 카드를 유지하고 지금 질문에는 답만 제공한다. 사람의 수동 제안·WorkItem 승인은 이 한 개 제한의 대상이 아니다.
- `GET /internal/incidents/{id}/history`의 pendingApproval과 기존 요청 상태를 읽어 중복 제안을 피하는 연결은 C 담당이다. Backend는 이미 한 개 제한과 실행 제어를 적용한다.

## 구현·운영

- 기존 Job QUEUED와 DB 저장을 재사용한다. 새 JobStatus·승인 상태·테이블·Kafka를 추가하지 않는다.
- 승인 결정과 RESUME 등록은 같은 트랜잭션이고 이벤트 전달은 커밋 후다. 사건 잠금으로 질문 접수·승인 결정·실행 확보·결과 반영의 경합을 제어한다.
- 시뮬레이터가 처음 기동을 시도했을 때 순서 대기로 반환돼도, 현재 작업이 끝나면 기존 커밋 후 JobQueuedEvent로 다시 깨운다. 정상 순서 대기를 분석 실패로 기록하지 않는다.
- 상태 로그는 기존 `event=job.status` INFO/ERROR 및 requestId/incidentId/jobId/kind/status를 사용한다. 승인·거절 사유·질문 원문을 새 로그에 추가하지 않는다.
- 기존 사건 데이터와 대기 체크포인트를 초기화하거나 삭제하지 않는다. 과거 WAITING_REPLY와 회신 선택 재개 경로도 유지한다.
- 서버가 다시 켜지면 외부 Agent는 DB 대기열을 다시 조회할 수 있다. 시뮬레이터의 별도 재시작 복구 시스템이나 기존 RUNNING lease 복구 범위는 이번에 확대하지 않았다.

## 검증

테스트 DB는 Testcontainers로 격리했으며 실제 서비스 서버·DB·배포는 변경하지 않았다. 최신 main `e3b82d8` 통합 뒤 다시 실행했다.

- Backend: `cd backend && ./gradlew test --offline` — **321건 중 319건 통과, 실패 0, 기존 KnowledgeApiTest 2건 건너뜀**.
- 신규 9건: 질문 중 승인/거절 저장, 승인 카드 보존, 조기 claim/이벤트/발송 차단, 선행 완료/실패 후 재개, 동시 결정과 실행 경합, Agent 승인 1개 제한, 승인 대기 중 추가 분석, SUMMARY 독립, 시뮬레이터 자동 순차 실행.
- 기존 Python Agent: `cd ai && uv run --offline pytest -q` — **176건 통과, 실패 0, 데이터 미포함으로 4건 건너뜀**. 이전 승인 해시 테스트 충돌은 C가 PR #23에서 수정했고 해당 main 변경을 그대로 통합했다.
- 실제 Python Agent(규칙 모델) 추가 연동 1건 통과: 기존 승인 대기 → 새 질문 QUEUED → 승인 결정 저장/RESUME QUEUED → 첫 poll에서 질문만 실행 → 다음 poll에서 원래 thread/부모 작업으로 승인 재개 → 요청 1건 발송. 검증 함수는 저장소 밖 `/tmp/fab-handoffs/test_approval_resume_agent_order.py`에서 실행했고 AI 소스·테스트를 이 브랜치에서 새로 수정하지 않았다.
- 실제 LLM·브라우저의 승인 카드 유지/자동 갱신은 프론트 연결 뒤 통합 검증해야 한다. 이번 검증은 Backend API·DB 동시성 및 규칙 Agent 연동 기준이다.
- Kafka·새 테이블·새 JobStatus 추가 없음. 통합 대화 조회는 별도 `feat/incident-conversation` 작업이며 이 변경에 포함하지 않는다.

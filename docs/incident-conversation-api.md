# 프론트 연결: 사건 상세 통합 대화 조회 v1

브랜치 `feat/incident-conversation`, 최신 main `f0e3daa`(PR #24 승인 재개까지 포함) 병합. Backend만 구현한다. Kafka 미사용. C의 Agent 내부 history API와 별개인 사람용 화면 API다.

## 달라지는 점

| 기존 | 새 연결 |
| --- | --- |
| 분석 history → 결과별 상세 → 질문별 Job → RESUME 연결을 프론트가 조회/추적 | conversation 한 번으로 대화 본문·전체 조사 작업·현재 재개 상태·승인 카드를 받음 |
| 회신·내 작업·추가 분석을 프론트에서 다시 연결 | 서버가 원본 ID·부모 기록·선택 입력을 연결 |
| activeJobId에만 의존해 이전 승인 카드가 사라질 수 있음 | waitingJobs·pendingApprovals·pendingWorkItems를 페이지와 별도로 제공 |

사건 기본 정보·센서/공정 그래프·보고서 탭의 기존 조회는 유지한다. 이 API는 대화 부분의 여러 호출을 하나로 대체한다. 프론트·AI·보고서 코드는 이번에 수정하지 않았다.

## API

`GET /api/incidents/{incidentId}/conversation?size=50&cursor=...`

- 기존 세션 쿠키와 사건 **전체 조회 권한** 필요. 읽기 전용 GET이라 CSRF/Idempotency-Key 불필요.
- 최초에는 cursor 생략. size 기본 50, 1~100. 현재 시점의 **최근 size개**를 가져와 **오래된 순**으로 반환한다.
- nextCursor가 있으면 이전 기록 더보기. 각 페이지도 오래된 순이므로 앞에 붙인다.
- 성공은 기존 `{data,meta}`. 실패는 기존 `{error,meta}`.

| data 필드 | 의미 |
| --- | --- |
| incidentId / incidentVersion / generatedAt | 사건·도메인 버전·조회 응답 시각 |
| items | 시간순 ConversationEntry |
| nextCursor | 이전 기록 커서, 더 없으면 null |
| jobs | BASIC/DEEP/RESUME 전체 JobView. REPORT/SUMMARY 제외 |
| runningJobs | QUEUED/RUNNING 조사 작업. 새 질문/추가 분석 차단 안내에 사용 |
| waitingJobs | WAITING_APPROVAL/WAITING_REPLY 조사 작업 |
| pendingApprovals | 현재 승인 대기 카드. Agent 카드는 해당 Job이 실제 WAITING_APPROVAL에 도달한 뒤 노출 |
| pendingWorkItems | PROPOSED 업무 후보와 WAITING_MANAGER 업무 승인 카드 |
| latestCauseAnalysis | 최신 원인 분석 전체. 좁은 질문 답이 덮지 않음. 없으면 null |
| investigationResults | 기존 결과 검토 패널에 전달할 회신/작업 결과/요청 거절 사유. sourceRef·analysisUse 포함 |

jobs와 대기 카드·latestCauseAnalysis·investigationResults는 items의 현재 페이지 밖에 있어도 제공한다. 질문별 getJob/getAnalysis를 추가 호출할 필요가 없다. 읽기 정보이며 실제 승인·채택·회신·추가 분석은 기존 변경 API가 다시 권한·버전·상태를 검증한다.

## items 공통 형식

아래 ID는 설명용이다. 실제 응답은 Swagger 스키마를 따른다.

```json
{
  "entryId": "result:res_01",
  "type": "AI_ANSWER",
  "occurredAt": "2026-10-23T07:00:00Z",
  "actor": {"type": "AI", "userId": null, "displayName": "AI"},
  "text": "정비 이력을 추가 확인해야 합니다.",
  "links": {
    "threadId": "thread_01",
    "rootJobId": "job_question",
    "jobId": "job_resume",
    "parentJobId": "job_question",
    "parentEntryId": "approval:apv_01:decided",
    "resultId": "res_01",
    "approvalId": "apv_01",
    "linkStatus": "COMPLETE"
  },
  "job": {"jobId": "job_resume", "kind": "RESUME", "status": "SUCCEEDED"},
  "currentJob": {"jobId": "job_resume", "kind": "RESUME", "status": "SUCCEEDED"},
  "data": {"analysis": {"resultId": "res_01", "answer": "정비 이력을 추가 확인해야 합니다."}}
}
```

예시는 설명에 필요한 필드만 발췌했다. 실제 JobView/분석 응답에는 기존 필드들이 포함된다. 타입과 관계없는 nullable 필드는 null이다.

| 공통 필드 | 프론트 사용 |
| --- | --- |
| entryId | React key·중복 제거. 새로고침해도 동일. 문자열 내부 규칙을 분석하지 않음 |
| type | 아래 종류별 렌더러 선택 |
| occurredAt | 서버가 보존한 등록/발생 시각. action.performedAt과 구분 |
| actor | HUMAN/AI/SYSTEM 구분, userId와 표시 이름 |
| text | 질문·답변·회신·결과·처리 기록 본문 |
| links | 원본과 연결 정보. 페이지 밖 부모를 다시 조회해야만 그릴 수 있는 구조가 아님 |
| job | 이 기록이 속한 원래 작업의 현재 상태 |
| currentJob | 서버가 RESUME_JOB 연결을 끝까지 따라간 현재 작업. 질문 진행 상태는 원래 Job의 SUCCEEDED만으로 판단하지 않음 |
| data | 종류별 상세 객체. 아래 표 참고 |
| eventType | 승인 결정(APPROVED/REJECTED), WORK_ADOPTED/WORK_DECIDED/WORK_CLOSED_UNPERFORMED 등 |
| sourceRef / analysisUse | 회신·작업 결과·요청 거절을 추가 분석에 선택할 때 사용 |

links에는 threadId/rootJobId/jobId/parentJobId/parentEntryId/resultId/previousResultId/sourceResultId/approvalId/requestId/replyId/actionId/taskId/analysisRequestId 중 해당 값을 제공한다. 없는 연결은 null이다. 부모가 현재 페이지 밖에 있을 수 있다.

- linkStatus=COMPLETE: 저장된 작업 연결을 확인함.
- MISSING_PARENT/CYCLE: 과거 연결이 끊겼거나 순환함. 표시 가능한 본문은 제공하며 다른 사건을 따라 조회하지 않는다.
- UNLINKED: 작업 없이 직접 입력한 결과 등. 잘못된 사실/원인이라는 뜻이 아니다.

## 종류별 렌더링

| type | 표시 및 상세 |
| --- | --- |
| BASIC_REQUESTED | 기본 분석 요청. job/currentJob 상태 표시 |
| QUESTION | 사용자가 작성한 질문 |
| REQUEST_DRAFT_REQUESTED | 확인 요청서 작성 접수. 기준 분석은 links.sourceResultId |
| FOLLOWUP_REQUESTED | 선택 결과 추가 분석. data.followup에 접수 ID·선택 입력·현재 Job |
| AI_ANSWER | data.analysis: 기존 AnalysisResultView 전체. answer/summary·관측·후보·근거·판단 정보·revision/previousResultId 유지 |
| APPROVAL_REQUESTED / APPROVAL_DECIDED | data.approval: 기존 ApprovalView. 승인 버튼용 카드는 pendingApprovals를 기준으로 표시 |
| REQUEST_SENT / REQUEST_ACCEPTED / REQUEST_REJECTED / REQUEST_WITHDRAWN | data.request: 요청·이유/brief·수신자·상태·버전·수락/거절/철회 정보 |
| REPLY | data.reply: 회신 전체, links.requestId로 원 요청 연결 |
| ACTION_RESULT | data.action: 실제 수행 결과·근거·효과 확인. 등록 시각과 수행 시각을 별도 표시 |
| WORK_PROPOSED / WORK_EVENT | data.work: 업무 후보/결정/미수행 종결. text/eventType은 당시 기록, work.status는 현재 상태 |
| ANALYSIS_FAILED | 작업 실패. job.error/currentJob 상태. AI 답변을 만들어 대체하지 않음 |

data.request는 사건 헤더와 회신 본문을 중복하지 않도록 incident=null/replies=[]이다. 회신은 REPLY 항목을 그리며, 원래 사건 기본 정보는 기존 사건 상세 데이터를 사용한다. 요청 상세 이동/수락/회신 변경 API는 기존 경로를 그대로 쓴다.

승인됨·실제 수행 완료·회신 완료·조사 종료는 각각 구분한다. 현재 자원 상태는 같은 entryId라도 후속 조회에서 갱신될 수 있다. 과거 이벤트의 시각·종류·text를 현재 상태 문구로 바꾸지 않는다.

## 추가 분석 연결

- FOLLOWUP_REQUESTED.data.followup.selectedInputs는 접수 당시 원문 스냅샷이다. sourceRef, evidenceId, content, 작성자·시각·근거를 제공한다.
- includedInInput은 입력에 전달됐다는 뜻, citedInResult는 완료된 최종 답변에서 실제 인용했는지다. 최종 결과가 없으면 null이다.
- 회신/거절은 `E-REQ-{requestId}`, 내 작업 결과는 `E-ACT-{actionId}`를 유지한다.
- investigationResults는 기존 `/investigation-results` 결과 검토 응답 항목과 같은 형식이다. 프론트 결과 검토 컴포넌트에 이 배열을 전달하면 별도 조회를 줄일 수 있다.
- 선택 후 `/api/incidents/{id}/followup-analysis-jobs`를 호출한다. 조회·회신 등록·알림 읽음만으로 자동 분석하지 않는다.

## 정렬·더보기·SSE

1. 최초 GET 결과를 표시한다. items는 occurredAt → 서버가 정한 종류 순서 → entryId 순이다. 프론트에서 답변 도착 순으로 다시 그룹화하지 않는다.
2. 같은 Job의 RESUME은 새로운 질문 메시지로 중복 생성하지 않는다. 기본 분석/질문/추가 분석 접수는 한 번씩 표시하고 이후 답변은 각각 보존한다.
3. 승인 카드가 기준 답변과 함께 공개됐으면 카드 시각은 답변 저장과 승인 생성 시각 중 늦은 시각을 사용해 답변 뒤에 표시한다. 실제 승인 createdAt은 data.approval에 보존한다.
4. 이전 기록은 nextCursor를 그대로 전달한다. offset이 아닌 시간·종류·ID 경계이므로 새 기록이 늘어도 기존 항목이 페이지 수에 의해 밀리지 않는다. 다른 사건 커서는 400이다.
5. 기존 사건 SSE의 snapshot/job.status/analysis.result/approval.created/approval.decided/check-request.created/check-request.decided/check-request.replied/action.recorded/action.verified/work-item.updated/incident.updated를 받으면 conversation query를 갱신한다.
6. 사용자 알림 SSE와 사건 SSE가 같은 변경을 알려도 데이터는 entryId로 병합·교체한다. 알림 ID를 대화 ID로 사용하지 않는다.
7. 새로고침/재연결 시 최신 페이지를 다시 읽는다. 이미 불러온 오래된 페이지도 상태/회신 연결 갱신이 필요하면 재조회한다. 별도 정기 폴링이나 전용 결과 검토 완료 API는 만들지 않는다.
8. REPLY 알림의 replyId, 요청의 requestId 등은 items.links에서 찾아 이동한다. 대상이 더 오래된 페이지면 이전 기록을 불러온 뒤 연결한다.

## 권한·읽기 전용·오류

- 사건 전체 조회 권한을 유지한다. 다른 공정의 요청 수신자라는 관계만으로 전체 대화를 볼 수 없다. 해당 사용자는 기존 요청 상세 API로 전달된 맥락/회신만 확인한다.
- Job.input/interruptId/Worker lease/비밀번호·서비스 토큰은 노출하지 않는다. 작업·승인·요청 상태/읽음/Agent 실행을 갱신하지 않는다. 승인 만료도 이 GET에서 저장하지 않는다.
- 한 요청은 REPEATABLE_READ 읽기 전용 트랜잭션으로 일관되게 읽는다. 조회 중 커밋된 새 회신은 다음 재조회에 반영된다.
- 400 INVALID_CURSOR/INVALID_PAGE_SIZE, 401 UNAUTHENTICATED, 403 기존 사건 조회 권한 오류, 404 INCIDENT_NOT_FOUND. 분석 전에도 200으로 대기 작업을 제공한다.
- 원인 후보의 AI 판단과 사람이 확정한 원인은 구분한다. 저장되지 않은 답·근거·처리 사실을 새로 만들지 않는다.

## 검증 및 한계

- `cd backend && SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4 ./gradlew test --offline --rerun-tasks`: **전체 338건 중 336건 통과, 실패 0, 기존 KnowledgeApiTest 2건 건너뜀**. 최초 기본 풀 실행에서는 여러 테스트 컨텍스트가 연결을 보유해 SlowAgentApiTest 기동이 PostgreSQL 연결 한도(`too many clients`)로 실패했다. 재실행의 환경변수로 테스트 풀만 4로 제한했으며 제품 설정·테스트 코드는 바꾸지 않았다.
- 기존 Agent 호환: `cd ai && uv run --offline pytest -q` → **176건 통과, 4건 건너뜀**. AI 코드는 수정하지 않았다.
- 신규 ConversationApiTest **17건 통과**: 기본 분석 전 조회, 질문·답 보존, 승인/회신/RESUME·새 thread 추가 분석, 선택 스냅샷/실제 인용, 승인 카드 공개/페이지 밖 유지, 작업/업무 결정, 요청 거절/철회, 실패·끊긴/순환 연결, 읽기 전용·권한·커서·Swagger.
- 격리 Postgres/Hibernate 통계에서 **기본 사건 SQL 11회 → 질문/답변 25쌍과 작업 결과 5건을 추가해도 SQL 11회**. 결과·작업별 개별 조회를 추가하지 않는다.
- 실제 Frontend 화면 연결·브라우저 SSE 재연결은 미검증. 이 변경은 읽기 전용 Backend이며 Agent/LLM 동작을 변경하지 않았다. 실서버·서비스 DB·배포는 건드리지 않았다.

MVP 사건 규모에 맞춰 저장소별 일괄 조회 후 메모리에서 연결·페이지를 구성한다. 새 대화 테이블·중복 이력 저장·Kafka·Agent 재개 방식 변경은 없다. 아주 큰 사건의 DB 수준 페이지 최적화는 이번 범위가 아니다. 실제 프론트 연결 화면은 별도 통합 검증이 필요하다.

Swagger: 이 브랜치 실행 후 `/swagger-ui/index.html`의 incident-conversation, 원본 `/v3/api-docs`.

## 조사 블록·회신 맥락 보완

items에 placement(분석/질문 블록·업무 흐름)를, REPLY에 requestSummary(페이지 밖 원 요청 요약)를 추가합니다. 기존 경로·필드·이벤트 단위 커서는 유지합니다. 프론트 묶음/중복 제거·SSE·페이지 밖 카드 처리 기준은 [conversation-context-api.md](conversation-context-api.md)를 따릅니다.

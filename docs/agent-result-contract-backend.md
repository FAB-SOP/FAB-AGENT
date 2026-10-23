# Agent v2 입력·결과 Backend 연결

브랜치: `feat/agent-result-contract`. C의 `25-json-contract-draft-v2.md`와 사용자 합의에 맞춘 Backend 수신·저장·조회 구현이다. AI/Python/MCP·계약 사본·Frontend·보고서 모듈 소스는 변경하지 않는다.

## 이번에 연결한 범위

| 기존 | 변경 |
| --- | --- |
| SUMMARY만 공개 input 제공 | 조사 작업도 종류별 공개 `input` 제공 |
| 선택 결과를 질문 문자열로 전달 | 구조화한 `input.followup` 추가. 기존 질문 문자열은 호환용 유지 |
| 답 본문·판단·stance가 저장되지 않음 | answer·analysis·candidateId/causeIds/stance 보존 |
| 승인 대기에는 결과 저장 없음 | payload.result가 있으면 결과·업무 후보·승인 기준 결과를 함께 저장. WAITING_APPROVAL 유지 |
| 승인 후 결과와 앞 답 연결 없음 | 부모 작업 결과에 previousResultId/revision으로 연결. 기존 답변은 보존 |
| 어떤 최신 답이든 상단 분석 대체 | latest=true 기본 조회는 최신 CAUSE_ANALYSIS 우선 |
| 질문 때 Agent용 사건 이력 API 없음 | 요약 이력 GET 제공. 전체 JSON 5,000자 이내, 생략 건수 포함 |

## 작업 입력 — C용

기존 `GET /internal/jobs`, `GET /internal/jobs/{jobId}/context`의 `job.input`에 노출한다. DB input 전체를 공개하지 않는다.

| Job | 공개 input |
| --- | --- |
| BASIC | `{purpose:"BASIC",replyFlow:"WAIT"}` |
| DEEP 일반 질문 | `{purpose:"QUESTION",replyFlow:"WAIT"}` |
| DEEP 선택 결과 추가 분석 | `{purpose:"FOLLOWUP",replyFlow:"WAIT",followup:{analysisRequestId,question,selectedInputs}}` |
| DEEP 요청서 생성 목적 | `{purpose:"REQUEST",replyFlow:"WAIT",request:{targetProcessCode?,causeIds?}}` — 입력 형식 지원. 전용 공개 생성 API는 이번에 추가하지 않음 |
| REPORT | `{purpose:"REPORT",replyFlow:"WAIT"}` |
| RESUME | `{replyFlow:"WAIT"}`. 목적은 부모 체크포인트가 결정. 기존 resume 참조 형식 유지 |
| SUMMARY | 기존 `{summaryRef:{reportId,reportVersion,contentRevision,snapshotRef}}` 유지 |

`selectedInputs`는 서버가 원본에서 구성한 접수 시점 스냅샷이다. 공개 필드는 sourceRef·evidenceId·content·authorUserId·createdAt·requestId?·taskId?·evidenceIds?만이다. DB 내부 incidentId 등은 제거한다. REPLY와 REQUEST_DECISION은 `E-REQ-{requestId}`, ACTION은 `E-ACT-{actionId}`를 유지한다.

아래 예시의 replyFlow=WAIT는 Backend 설정을 명시했을 때의 예시다. 설정하지 않으면 replyFlow를 생략한다.

기존 QUEUED 작업에 목적을 추정해 덮어쓰지 않는다. 새 작업은 목적과 흐름을 DB에 저장한다. 새·구 입력을 함께 소비하는 C와 호환하도록 기존 trigger/질문 문자열 전달은 유지한다. SUMMARY 입력에는 purpose·replyFlow·조사 내부 필드를 섞지 않는다.

### 회신 흐름 전환

- `fab.agent.reply-flow` / 환경 변수 `FAB_AGENT_REPLY_FLOW`는 WAIT 또는 MANUAL. 기본은 미설정이며 replyFlow를 생략해 C의 기존 FAB_REPLY_FLOW 설정을 유지한다. 전환 전에 서버가 WAIT를 강제로 보내 기존 MANUAL 동작을 바꾸지 않는다.
- MANUAL은 C의 정식 결과·승인 대기 답변 전송이 연결된 뒤 함께 활성화한다. 이번 소스 변경만으로 기존 배포의 회신 흐름을 전환하지 않는다.
- Backend 설정을 명시한 뒤 생성되는 새 작업부터 선택한 값을 저장한다. 설정을 바꿔도 기존 작업은 바뀌지 않는다. 과거 흐름 값이 없는 작업에는 값을 추정해 주입하지 않는다. C는 thread 시작 때 기존 Worker 설정(미설정이면 WAIT)을 쓰고 RESUME은 부모 체크포인트를 유지한다.
- 기존 WAITING_REPLY 체크포인트 및 선택 회신으로 재개하는 호환 경로는 유지한다. 회신 저장만으로 자동 분석하지 않는 정책도 유지한다.

## 결과 이벤트 — C용

`POST /internal/jobs/{jobId}/events`의 기존 RESULT_READY와 APPROVAL_REQUIRED를 사용한다.

```json
{
  "payload": {
    "result": {
      "resultId":"RESULT-job_01",
      "jobId":"job_01",
      "incidentId":"inc_01",
      "answer":"관측과 작업 결과에 근거한 답변",
      "candidateCauses":[{
        "candidateId":"CMP_PRESSURE",
        "causeIds":["CMP_PRESSURE"],
        "stance":"UNDETERMINED",
        "hypothesis":"압력 제어 상태 확인 필요",
        "priority":1,
        "supportingEvidenceIds":[],
        "conflictingEvidenceIds":[],
        "missingData":["실측값"],
        "nextCheck":"설정값과 실제값 비교"
      }]
    },
    "analysis": {
      "resultKind":"CAUSE_ANALYSIS",
      "nextStep":"CURRENT_CHECK",
      "conclusion":{"changed":false,"fields":[],"reason":"","newEvidenceIds":[],"adjusted":false}
    },
    "workSuggestions":[]
  }
}
```

실제 이벤트에는 기존 eventId·attempt 등 envelope와 기존 result 필드도 포함한다. result/analysis/workSuggestions는 payload의 형제다.

| 필드 | 허용값·처리 |
| --- | --- |
| answer | result의 문자열, 최대 12,000자. 기존 summary는 호환용으로 답변 앞부분 제공 |
| analysis.resultKind | CAUSE_ANALYSIS / ANSWER |
| analysis.nextStep | CURRENT_CHECK / UPSTREAM_CHECK / OBSERVE / INSUFFICIENT_EVIDENCE / NEEDS_DEEP_ANALYSIS |
| 후보 stance | SUPPORTED / REFUTED / UNDETERMINED. 기존 status와 독립. 사람의 확정 아님 |
| 후보 candidateId / causeIds | Agent 전달값 그대로 보존. 기존 causeId/matrixCauseIds도 호환용 유지 |
| evidenceRefs sourceType | 기존 값과 ACTION 지원. 전환기 ACTION 근거의 CHANGE도 계속 허용 |
| 나머지 저장 analysis | conclusion / requestSent / proposalLimit / droppedWorkSuggestions |

- 새 필드가 없는 기존 결과도 받는다. answer·stance를 임의 생성하지 않는다. resultKind가 없는 기존 결과의 호환 분류는 BASIC/RESUME=CAUSE_ANALYSIS, DEEP=ANSWER. 새 추가 분석은 purpose=FOLLOWUP 또는 명시적 resultKind로 구분한다.
- Backend가 별도의 res_ ID를 발급한다. Agent resultId는 별도 보존하며 Backend ID를 대체하지 않는다.
- 결과는 덮어쓰지 않는다. 승인 뒤 새 결과는 부모 Job의 결과를 previousResultId로 가리키고 revision을 증가시킨다. 새 질문·새 추가 분석의 독립 결과는 revision=1이다.
- APPROVAL_REQUIRED.payload.result가 있으면 승인 ID의 사건·작업 소속을 검사하고 결과·업무 후보·approval.sourceResultId를 같은 트랜잭션에 저장한다. Job은 WAITING_APPROVAL, 결과 공개만으로 성공·조사 종료하지 않는다. SSE는 커밋 뒤 기존 analysis.result로 전달한다.
- 실제 요청 발송만 반환하는 RESUME의 analysis.requestSent는 해당 사건 요청을 검증하고 CHECK_REQUEST 참조로 작업만 완료한다. 새 분석 결과·분석 성공 알림을 만들지 않는다.
- 잘못된 v2 결과 값은 422 + `{error:{code:"INVALID_AGENT_RESULT",details:{errors:[{field,message}]}},meta}`. 결과·업무·수신 기록은 함께 롤백하므로 수정 후 같은 eventId로 재전송 가능하다.
- 같은 작업의 두 번째 결과 저장은 409. 정상 재전송은 기존 eventId 중복 처리로 200 applied:false다. 기존 전역 이벤트 중복·attempt 정책 자체는 이번 작업에서 바꾸지 않았다.

## 조회 — 프론트용

기존 `GET /api/incidents/{incidentId}/analysis-results`와 사건 읽기 권한을 유지한다.

| 호출 | 결과 |
| --- | --- |
| 기본 latest=true | 최신 CAUSE_ANALYSIS 우선. 원인 분석이 아직 없으면 최신 답변 |
| latest=false | 전체 history + 가장 최근 답변. 채팅 이력 용도 유지 |
| resultId 지정 | 같은 사건의 지정 결과. 다른 사건 결과 접근 불가 |

기존 응답에 `answer`, `analysis`, `resultKind`, `previousResultId`, `revision`을 추가한다. history에도 resultKind·answer·previousResultId·revision이 포함된다. 후보 stance와 status는 별개 배지 의미이며, 과거 stance 없는 결과를 확정/보류로 추정하지 않는다. 승인 상세에는 sourceResultId를 추가한다.

결과 수신 시 기존 `analysis.result` SSE가 `{resultId,jobId,kind,resultKind}`를 제공한다. 새 폴링은 추가하지 않는다. Swagger는 실행 중인 이 브랜치의 `/swagger-ui/index.html`, `/v3/api-docs`에서 확인한다.

## 사건 이력 — C용

`GET /internal/incidents/{incidentId}/history`. 기존 내부 서비스 토큰 필요. displayId(EV-1009)도 허용하며, 응답 incidentId는 실제 ID다. 세션 사용자에게 내부 API 권한을 부여하지 않는다.

응답은 `{data:{incidentId,asOf,latestCauseAnalysis,pendingApproval,pendingApprovalCount,requests,approvals,items,omitted},meta}`다. 기존 도구의 이중 data envelope와 달리 이 endpoint는 위 형태로 직접 반환한다.

- latestCauseAnalysis: resultId·jobId·createdAt·nextStep·요약·1순위 후보·최대 3개 근거 ID·요약 한계.
- pendingApproval: 최신 유효 승인 대기의 ID·Job·작업 종류·대상 공정·생성 시각·기준 결과 ID. 기존 다중 대기가 있으면 count와 omitted.pendingApprovals로 명시한다.
- requests: 상태·origin·수락/거절 시각/사유·회신 시각·철회 시각/사유.
- approvals: 승인 상태·결정 시각/사유·기준 결과.
- items: 최신순 조사 기록·AI 답변·회신·실제 작업 결과. recordId·at·type·summary(최대 200자)·sourceRef, 회신/작업은 evidenceId 포함.
- JSON 문자열을 중간에서 자르지 않는다. 전체 직렬화가 5,000자를 넘으면 오래된 items/requests/approvals 항목을 생략하고 omitted에 건수를 기록한다. 최신 원인 분석·승인 대기 참조는 유지한다.
- 제공하지 않는 상세 페이지/detailPaths는 약속하지 않는다. 이 API는 Agent용 요약이며 사람용 통합 채팅 API를 대신하지 않는다.

기존 `/internal/incidents/{id}/check-requests` 조회에도 origin·decisionAt·decisionComment·repliedAt·withdrawnAt·withdrawReason을 추가한다. origin은 AGENT_PROPOSAL(Agent 직접 승인 요청), WORK_ITEM(사람이 채택한 업무 경유), USER_PROPOSAL, UNKNOWN(과거 출처 확인 불가)이다. Agent 자동 요청 한도는 AGENT_PROPOSAL만 센다.

## 다음 묶음과 경계

- C는 정식 answer/analysis/후보 칸을 보내는 변경 및 history를 읽는 MCP 도구를 연결해야 한다. Backend 구현만으로 현재 Agent가 새 필드를 보내기 시작하는 것은 아니다.
- 승인 대기 중 질문 후에도 카드 유지, 질문 실행 중 승인 결정 저장·재개 대기, 사건당 실행 직렬화는 다음 작업이다. 이번 결과 저장 준비와 구분한다.
- 전용 요청서 생성 공개 API와 사람용 통합 채팅 API는 아직 추가하지 않았다.
- report-context에 원인 분석을 포함하는 작업 및 자동 초안 정책은 보고서 담당과 조율 후 진행한다. 이번에 보고서 모듈은 변경하지 않았다.
- 새 analysis_result.analysis JSONB 및 answer/agentResultId/previousResultId/revision, approval.sourceResultId는 기존 ddl-auto=update 설정으로 다음 기동 때 추가된다. 기존 결과를 덮어쓰거나 DB를 초기화하지 않는다.

## 검증

- `cd backend && ./gradlew test --offline`: 총 295건, 통과 293건, 실패/오류 0건. Git 제외 RAG 원문이 없어 기존 Knowledge 테스트 2건 건너뜀.
- 신규 17건: 공개 입력 제한·흐름 호환·선택 결과 접수 스냅샷·answer/stance/ACTION 보존·일반 질문과 원인 분석 조회 분리·승인 대기 답 공개/이전 결과 연결·잘못된 승인 소속/버전·잘못된 stance 롤백/재전송·요청 발송 결과 중복 분석 방지·유효 JSON 5,000자 제한·서비스 토큰 격리·긴 답변 보존을 검증했다.
- 기존 질문 결과 기본 조회 기대값은 합의에 맞춰 갱신하고 원인 분석 유지와 질문 이력 조회를 함께 검사했다.
- 테스트는 격리된 Testcontainers DB를 사용하며 실제 서비스 DB·배포를 변경하지 않았다.

- `cd ai && uv run --offline pytest -q`: 172건 통과, 자료가 없는 기존 4건 건너뜀. 기존 Python Agent와 실제 Backend API를 규칙 모델·별도 DB로 연결해 검증했다. AI 소스·테스트는 수정하지 않았다.
- 초기 검증에서 발견한 기본 흐름 강제 WAIT로 인한 C의 MANUAL 설정 덮어쓰기를 수정하고 위 두 전체 검증을 최종 코드로 재실행했다.
- 브라우저 UI 및 C의 아직 미구현인 정식 v2 필드 전송까지 포함한 종단 간 검증은 수행하지 않았다. 신규 v2 수신은 Backend API 테스트로 검증했다.

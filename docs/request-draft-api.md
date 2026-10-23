# 프론트·C 연결: 확인 요청서 생성 작업 API

브랜치 `feat/request-generation`, 기준 main `728362d`(PR #20 포함). Backend만 변경한다. Kafka는 사용하지 않으며 기존 DB Job + HTTP 작업 조회/claim/이벤트 + SSE를 재사용한다.

## 기존과 변경

| 기존 | 변경 |
| --- | --- |
| 일반 질문 API에 「ETCH 확인 요청서를 만들어줘」 같은 문구를 전달 | 버튼용 전용 API로 목적·대상·원인 후보를 명시 |
| 질문 문구를 보고 일반 답변/요청서 작업을 구분 | 서버가 `kind=DEEP`, `input.purpose=REQUEST`로 고정 |
| 기준 분석과 선택 원인을 별도로 검증할 공개 접수 경로 없음 | 같은 사건의 분석 결과·원인 ID, 실제 공정 경로를 검증 |

기존 질문·업무 후보 채택·승인·회신 API는 유지한다. 업무 후보 3~5개의 개별 수락/미채택과 이 API는 별도 흐름이다. 이 API는 AI가 작성하는 요청서를 HITL 승인 카드로 받기 위한 것이다.

## 프론트 요청

`POST /api/incidents/{incidentId}/request-draft-jobs`

기존 세션 쿠키 인증, `X-CSRF-TOKEN`, `Idempotency-Key` 필요.

```json
{
  "sourceResultId": "res_01",
  "targetProcessCode": "ETCH",
  "causeIds": ["ETCH_WALL"],
  "instruction": "정비 전후 시즈닝 기록을 확인하도록 작성해 주세요.",
  "expectedIncidentVersion": 7
}
```

예시 ID는 설명용이다. 실제 `sourceResultId`·원인 ID·사건 version은 조회 결과에서 가져온다.

| 필드 | 규칙 |
| --- | --- |
| sourceResultId | 필수, 같은 사건의 분석 결과 ID. 서버 원본을 참조하며 결과 본문을 클라이언트가 주입하지 않음 |
| targetProcessCode | 필수, CVD/ETCH/CMP. 현재 공정 또는 사건 웨이퍼의 실제 앞 공정 |
| causeIds | 선택, 생략/null이면 빈 배열. 최대 20개, 중복 불가. 기준 분석 candidateCauses의 causeIds 또는 기존 matrixCauseIds의 원인 매트릭스 ID 사용. 화면용 causeId/candidateId와 혼동하지 않음. 매트릭스 ID인 과거 causeId만 호환 지원 |
| instruction | 선택, 최대 1,000자. 요청서 작성에 추가할 지시 |
| expectedIncidentVersion | 필수, 최신 사건 version |

서버는 현재 공정의 정비 확인 또는 실제 앞 공정 확인을 허용한다. 앞 공정은 사건의 waferIds와 runIds에서 웨이퍼 경로를 조회해 확인한다. 대상 공정 접근 권한을 새로 부여하지 않는다. 실제 수신자는 기존 Agent 담당자 조회·제안 및 Backend 수신자 검증 경로를 따른다. 이 API에 ownerUserId/approverUserId를 임의로 추가하지 않는다.

## 응답과 화면 동작

202 + `Location: /api/jobs/{jobId}`, 기존 `{data,meta}` envelope 및 Accepted 모양 유지.

```json
{
  "data": {
    "jobId": "job_01",
    "incidentId": "inc_01",
    "displayId": "EV-1009",
    "kind": "DEEP",
    "status": "QUEUED",
    "statusUrl": "/api/jobs/job_01",
    "streamUrl": "/api/incidents/inc_01/stream",
    "duplicate": false
  },
  "meta": {"requestId": "req_01", "at": "2026-10-23T07:00:00Z"}
}
```

meta의 실제 공통 필드는 기존 응답 계약을 따른다.

1. 분석의 다음 확인 사항 옆 「○○ 담당자에게 확인 요청 만들기」 버튼에서 기준 결과·대상 공정·선택 원인을 전달한다.
2. 202는 작성 작업 접수이며 요청 발송 완료가 아니다. 기존 job.status/analysis.result/approval.created SSE와 작업 조회로 진행을 표시한다.
3. AI 답과 요청서 승인 카드가 나오면 기존 화면에서 내용을 확인하고 승인/거절한다. 실제 발송은 승인 뒤 기존 승인 재개 경로로 처리한다.
4. 분석 근거·중복 요청 제한 등에 따라 Agent가 요청서를 만들지 않고 답변으로 정리할 수도 있다. Backend가 빈 요청서를 만들어 성공으로 대체하지 않는다.
5. 승인 대기 중인 Agent 요청서가 있으면 409 AGENT_APPROVAL_PENDING과 approvalId를 받는다. 기존 카드로 안내한다. 자동 대체·삭제하지 않는다. 요청서 거절은 사유 필수다.
6. 질문/추가 분석/요청서 작업이 QUEUED/RUNNING이면 중복 작업을 차단한다. 일반 질문의 승인 대기 중 실행 및 카드 유지 확장은 별도 `feat/approval-resume` 작업이다.
7. 회신·내 작업 결과 저장만으로 자동 재분석하지 않는다. 사건 종료나 보고서 확정도 이 API에서 수행하지 않는다.
8. 네트워크 재전송에는 같은 키와 본문을 쓴다. 같은 요청의 최초 응답이 재생되고 `Idempotent-Replayed: true`로 표시된다. 성공 뒤 같은 키를 다른 본문에 재사용하면 422다. 재생된 응답의 duplicate 값은 기존 계약대로 최초 응답 값이다.

기존 사용자 알림·사건 SSE 연결을 재사용하고, 알림 확인용 주기적 폴링을 추가하지 않는다.

## 오류

| HTTP | code | 프론트 대응 |
| --- | --- | --- |
| 401 | UNAUTHENTICATED | 로그인 확인 |
| 403 | 기존 권한/CSRF 오류 | 사건 공정·조사 운영 권한과 CSRF 확인. 정비팀의 사건 조회 권한은 조사 실행 권한이 아님 |
| 404 | INCIDENT_NOT_FOUND | 사건 재조회 |
| 409 | VERSION_CONFLICT | 사건 최신 데이터 재조회 후 판단 |
| 409 | JOB_IN_PROGRESS | 현재 작업 표시, 중복 접수하지 않음 |
| 409 | AGENT_APPROVAL_PENDING | details.approvalId로 기존 승인 카드 표시 |
| 409 | INCIDENT_CLOSED | 종료 사건에 새 작업 접수 불가 |
| 422 | REQUEST_SOURCE_INVALID | 해당 사건의 분석 결과 선택. 없는 ID와 다른 사건 결과는 동일 오류 |
| 422 | REQUEST_CAUSE_INVALID / DUPLICATE_CAUSE_ID | 선택 원인 재확인 |
| 422 | REQUEST_TARGET_INVALID | 현재/실제 앞 공정만 선택 |
| 422 | VALIDATION_FAILED | error.details.errors 확인 |
| 422 | IDEMPOTENCY_KEY_REUSED | 다른 요청에 기존 키 재사용하지 않음 |

기존 헤더 누락·잘못된 키·JSON 파싱 오류는 공통 오류 규약을 따른다. CSRF가 먼저 거부되면 로그인 전 POST도 403일 수 있다.

## C 전달 입력

기존 공개 작업 스키마를 확장하지 않는다. 작업 메시지와 context의 input은 아래와 같다.

```json
{
  "purpose": "REQUEST",
  "request": {
    "targetProcessCode": "ETCH",
    "causeIds": ["ETCH_WALL"]
  }
}
```

- 서버 설정에 명시된 replyFlow가 있으면 기존 계약대로 함께 전달한다. 미설정이면 기존 Worker 설정을 유지한다.
- 새 DEEP/thread이며 parentJobId는 없다. 입력만으로 체크포인트 종료·회신 분석·보고서 작업을 만들지 않는다.
- context.question에 서버가 기준 분석 ID·최대 500자의 요약·추가 지시를 구성한다. 분석 전체는 기존 원본에 보존하며 이 짧은 설명을 전체 조사 이력으로 취급하지 않는다.
- sourceResultId는 서버 Job.input 내부 추적값이다. 공개 input.request에는 추가하지 않는다. public 스키마 변경·C 구현은 이 브랜치에서 수행하지 않는다.
- 기존 C의 REQUEST 입력 해석·대상 공정 검사·요청서 작성·APPROVAL_REQUIRED·승인 RESUME·발송 도구를 재사용한다. 요청서 재생성/대체 전용 API는 이번 MVP에서 제외한다.

## 검증

- 신규 API 통합 테스트 13건 통과: REQUEST 입력, 무발송 접수, 현재/실제 앞 공정, run→wafer 경로, 멱등 재전송, 기존 승인 카드 보존, 수동 승인과 구분, 잘못된 기준 결과/원인/공정, 화면용 원인 ID 차단, 과거 matrixCauseIds 호환, 버전/종료/동시 클릭, 권한/CSRF/Swagger.
- `cd backend && ./gradlew test --offline`: **310건 중 308건 통과, 실패 0, 기존 KnowledgeApiTest 2건 건너뜀**.
- AI 소스를 수정하지 않은 실제 Python Agent(규칙 모델)와 별도 연동 1건 통과. 실제 기본 분석에서 받은 원인 매트릭스 ID로 이 API를 호출하고 REQUEST 작업 → 승인 대기 → 승인 전 발송 0건 → 승인·RESUME → OPEN 요청 1건을 확인했다. 별도 격리 Postgres를 사용했고 임시 검증 함수는 `/tmp/fab-handoffs/test_request_draft_agent_compat.py`에만 있다.
- 실제 LLM·브라우저 UI 검증은 수행하지 않았다. 프론트 연결 후 버튼/승인 카드/SSE 갱신을 함께 확인해야 한다.
- 실제 서비스 DB·실행 중인 서버·배포는 변경하지 않았다. C/프론트/보고서 코드·스키마·패키지 의존성 변경 없음. Kafka 미사용.

Swagger는 이 브랜치를 실행했을 때 `/swagger-ui/index.html`의 investigation-requests, 원본은 `/v3/api-docs`에서 확인한다.

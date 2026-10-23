# 프론트 연결: 조사 블록·업무 흐름·회신 맥락

브랜치 `feat/conversation-context`, 기준 main `fe6660b`(PR #30). PR #32의 conversation 연결을 전제로 한 **Backend 조회 보완**입니다. 프론트·AI/MCP·보고서 소스는 수정하지 않습니다. 새 API·DB 테이블·Kafka·자동 분석은 추가하지 않습니다.

## 바뀐 것

기존 `GET /api/incidents/{incidentId}/conversation`의 경로·인증·응답 envelope·items 종류·기존 필드·커서 순서를 유지합니다. 각 item에 **placement**, 회신 item에만 최소 **requestSummary**를 추가합니다. 기존 결과·업무 본문을 다른 배열에 새로 복제하지 않습니다.

| 기존 문제 | 변경 |
|---|---|
| 프론트가 rootJob/부모·결과·업무 연결을 추적해야 함 | 서버가 화면 묶음 blockId와 업무 흐름 flowId 제공 |
| 회신만 최신 페이지에 있으면 원 요청 설명 없음 | REPLY.requestSummary가 이전 페이지 여부와 무관하게 제공됨 |
| WorkItem 발송 승인이 원 업무에 직접 연결되지 않음 | 승인/요청/회신에 taskId·sourceResultId 보완, 같은 flowId 유지 |
| 같은 thread RESUME을 모두 같은 질문으로 묶기 쉬움 | 사람이 선택한 결과의 추가 분석은 독립 FOLLOWUP 블록. 요청서 버튼은 기준 분석 블록에 연결 |

## 추가 필드

```json
{
  "entryId": "reply:reply_01",
  "type": "REPLY",
  "occurredAt": "2026-10-23T00:10:00Z",
  "text": "필터 점검 기록 확인 완료. 실측값 대조가 필요합니다.",
  "links": {
    "parentEntryId": "request:req_01:sent",
    "sourceResultId": "res_01",
    "taskId": "task_01",
    "requestId": "req_01",
    "replyId": "reply_01",
    "approvalId": "apv_01"
  },
  "placement": {
    "blockId": "job:job_basic:requested",
    "blockType": "BASIC",
    "createdAt": "2026-10-23T00:00:00Z",
    "title": "기본 분석",
    "flowId": "work:task_01"
  },
  "requestSummary": {
    "question": "슬러리 공급 계통 점검 기록을 확인해 주세요.",
    "targetProcessCode": "CMP",
    "equipmentId": "CMP-PM3"
  }
}
```

예시는 필드 일부만 발췌했습니다. data.reply·sourceRef·analysisUse·job/currentJob 등 기존 필드도 유지합니다. 예시 ID는 설명용이며 클라이언트가 생성하지 않습니다.

### placement

| 필드 | 규칙 |
|---|---|
| blockId | 같은 분석/질문 블록의 안정 ID. 문자열을 해석하지 말고 비교·key에 사용 |
| blockType | BASIC / QUESTION / FOLLOWUP / DIRECT. 사건 상태나 JobKind를 대체하지 않음 |
| createdAt | **블록 시작 시각**. 새 회신이 와도 바뀌지 않음. item.occurredAt은 각 기록의 시각 |
| title | 최대 200 Unicode 문자인 제목 요약. 원 질문 전문은 QUESTION 등 원 항목에 유지 |
| flowId | 같은 업무·승인·요청의 안정 ID. 일반 질문/답처럼 업무 흐름이 없으면 null |

- WorkItem 제안·채택·상급자 결정·수행 결과·연결 요청·회신은 같은 work 흐름입니다.
- Agent 요청서 제안·승인·발송·회신은 같은 approval 흐름입니다. thread를 바꾸거나 Graph를 종료시키는 규칙이 아닙니다.
- 사람의 요청서 작성 버튼은 기준 sourceResultId의 분석 블록에 붙습니다. 내부 REQUEST Job을 새로운 사용자 질문으로 만들지 않습니다.
- 선택 결과 추가 분석은 checkpoint RESUME이어도 해당 analysisRequestId 기준 FOLLOWUP 블록입니다. 원 회신은 원 요청 블록에 남고, 새 분석이 선택 원본을 참조합니다.
- 출처 없는 수동 승인/업무/요청/작업은 DIRECT 블록입니다. 가까운 AI 답변에 추측해서 연결하지 않습니다.
- 끊긴/순환 Job 연결에서 placement를 확정할 수 없으면 null일 수 있습니다. 기존 links.linkStatus를 확인하고 독립 기록으로 보존합니다. 다른 사건 자료를 따라 조회하지 않습니다.

### requestSummary

- **REPLY**에서만 제공하며 원 요청을 같은 사건에서 확인할 수 없으면 null입니다.
- question은 공백을 정리한 최대 200자 요약입니다. 긴 내용은 …로 끝나며 전문은 기존 요청 상세에 보존합니다.
- 원 요청 전문·brief·전체 조사 내용·다른 회신을 반복해서 싣지 않습니다. 상세가 필요할 때 links.requestId로 기존 요청 화면을 엽니다.
- PR #32의 ‘현재 로드한 REQUEST_SENT 배열에서만 요청 내용을 찾기’ 대신 이 필드를 사용합니다. 이전 페이지를 전부 로드하거나 회신마다 요청 API를 추가 호출할 필요가 없습니다.

## 프론트 배치 지침 — PR #32에서 바꿀 부분

1. **conversationItems에서 업무·요청·작업 결과를 종류만으로 제거하지 않습니다.** 최신 entryId 값을 보존한 원본 items로 아래 묶음을 구성합니다.
2. placement.blockId로 블록을 만들고 placement.createdAt 순으로 표시합니다. 같은 시각은 최초 서버 순서/안정 ID로 고정합니다. 실제 질문 항목이 있으면 title 요약 대신 질문 전문을 표시합니다.
3. AI_ANSWER는 해당 블록에 표시합니다. WORK_PROPOSED는 links.sourceResultId로 해당 답변 바로 아래에 둡니다. 승인 전후 답은 previousResultId/revision 관계를 유지합니다.
4. placement.flowId로 제안·채택·승인·요청·회신·작업 결과를 **같은 카드에 연결**합니다. 제목/본문은 한 번, 단계별 시각·행위자·결정 사유와 결과는 하위에 표시합니다. 서로 다른 제안은 별도 카드입니다.
5. REQUEST_SENT/ACCEPTED/REJECTED/WITHDRAWN·REPLY는 links.requestId로 요청 단위를 구분합니다. flow 안에 요청이 여럿이면 하나로 합치지 않습니다. 서로 다른 상태 종류도 하나의 ‘완료’로 치환하지 않습니다.
6. 늦은 회신은 **원래 블록·요청 아래**에 추가합니다. 이후 질문을 다시 정렬하지 않고 ‘새 회신’ 표시와 위치 이동으로 알립니다.
7. 원 부모가 현재 페이지 밖이면 placement와 requestSummary로 ‘이전 조사 · 요청 요약’ 묶음을 즉시 표시합니다. 원 분석 본문은 더보기로 가져오고, 없는 원문·AI 답을 합성하지 않습니다. 더보기 후에는 같은 blockId/flowId로 기존 묶음을 채웁니다.
8. 회신·직접 작업 카드에 선택 체크박스를 둡니다. 선택은 기존 sourceRef로 관리하며 하단에는 **선택 개수·참조 제목·추가 질문·추가 분석 버튼**만 둡니다. ResultReview의 원문 목록을 다시 복제하지 않습니다. 원본 스냅샷·실제 인용 추적은 그대로 유지합니다.
9. 본문 전체 ProposedWorkList/IncidentApprovals/ResultReview를 한 번 더 붙이는 배치를 제거합니다. 별도 내 할 일/승인함 메뉴는 유지하며 카드의 기존 변경 API·권한·버전·actionHash 검증을 재사용합니다.
10. 질문 입력은 맨 아래에 하나 둡니다. 승인/회신만 기다릴 때 질문 가능, 실제 QUEUED/RUNNING 중 차단은 PR #32 기준을 유지합니다.

### 이미 사용한 결과의 선택 제한

- `analysisUse.successfulCount > 0`: 해당 결과를 선택 입력으로 사용한 추가 분석이 완료됨. **‘추가 분석 완료’로 표시하고 체크박스 비활성화**. 선택 목록에 남아 있다면 제거합니다.
- `analysisUse.activeRequestIds`가 비어 있지 않으면 분석 진행 중이므로 비활성화합니다.
- 성공한 분석도 진행 중인 분석도 없고 이전 시도만 실패했다면 다시 선택할 수 있습니다. 최초 미사용 결과도 선택 가능합니다.
- `citedInResult=false`라고 완료된 선택 입력을 미사용으로 되돌리지 않습니다. 선택 입력으로 분석한 사실과 AI 최종 답에서 실제 인용한 사실은 구분합니다.
- 프론트가 별도 로컬 완료 플래그를 저장하지 않고 서버의 analysisUse로 판단합니다. 이번 Backend 조회 보완이 followup 생성 API에 재사용 금지 검증을 추가한 것은 아닙니다. 위는 프론트 선택 UI 규칙이며 실제 프론트 반영은 재현 담당입니다.

## 더보기·SSE·페이지 밖 대기 카드

- 이번 변경은 **이벤트 단위 커서**를 유지합니다. 한 페이지가 완전한 조사 블록이라는 가정을 하면 안 됩니다. blockId는 여러 페이지에서 합칠 수 있습니다.
- 사건 SSE 후 기존 infinite query를 재검증해 이미 로드한 오래된 페이지도 최신 상태로 맞춥니다. 이벤트가 왔다고 최신 페이지만 교체하여 오래된 요청의 회신·승인을 놓치지 않습니다.
- 아직 로드하지 않은 페이지에 남은 승인·업무도 있으므로 기존 pendingApprovals/pendingWorkItems를 버리지 않습니다. 로드한 카드가 있으면 ID로 병합하고 중복 본문을 만들지 않습니다. 없는 카드는 ‘이전 조사에 대기 중’ 안내/이동을 유지하여 처리할 수 없게 숨기지 않습니다.
- 같은 계정 알림과 사건 SSE가 겹쳐도 entryId·resource ID로 교체합니다. 카드 위치는 placement, 현재 상태는 갱신된 DTO, 각 처리 시각은 item.occurredAt을 사용합니다.
- 과거 서버 응답/끊긴 이력의 placement=null은 시간순 독립 기록으로 남깁니다. 임의 날짜·thread 추측으로 잘못된 답에 연결하지 않습니다.

## 화면 예시

```text
기본 분석
  AI 답변 · 원인 후보 · 근거
    추천 1 — 직접 확인 [채택/미채택]
      채택됨 → 수행 결과
    추천 2 — 다른 팀 요청
      채택·발송 → 수락 → 회신 [추가 분석에 선택]

추가 질문
  질문 → AI 답변
    요청서 [승인/거절] → 발송 → 회신

선택 결과 추가 분석
  원본 참조(여러 개 가능) → 새 AI 분석 → 새 제안

선택한 결과 N건 + 추가 분석 버튼
추가 질문 입력
```

전체 요약 타임라인은 기존 조사 이력 탭에만 둡니다. 본문은 ‘무엇을 보고 무엇을 요청했고 어떤 결과를 받았는가’를 연결해서 봅니다. 승인·수락·회신·알림 읽음만으로 재분석하거나 조사 종료하지 않습니다.

## 검증

- 표적 ConversationApiTest 22건 통과(기존 17 + 신규 5), 실패·건너뜀 0. 기존 같은 thread 선택 결과 RESUME 테스트와 타 사건 부모 차단 테스트에도 placement 검증 추가.
- 신규: 회신만 최근 페이지에 있는 경우의 원 요청 요약/블록, Unicode 요약 길이, WorkItem→승인→요청→회신 동일 흐름, 직접 수행 결과 연결, REQUEST 버튼 기준 분석 블록, 출처 없는 수동 승인 DIRECT 블록.
- 기존 읽기 전용·사건 권한·커서·SQL 수·Swagger 테스트 유지. 실서버·서비스 DB·실제 Agent/프론트 소스는 변경하지 않습니다.
- `cd backend && ./gradlew test --offline`: **352건 중 350건 통과, 실패/오류 0, 기존 KnowledgeApiTest 2건 건너뜀**. 격리된 Testcontainers PostgreSQL 사용. 기존 SQL 수 회귀 테스트도 통과했습니다.
- 저장소 밖 HTML 화면 예시: 자체 포함 검사 통과. 별도 Chrome에서 채택/미채택·승인/거절·늦은 회신의 원 요청 연결·선택 추가 분석·이후 질문 순서·초기화·키보드·390px 가로 넘침 없음·브라우저 오류 0을 확인했습니다. 이는 가상 UI 예시 검증이며 실제 PR #32 화면과 Backend의 종단 간 검증은 아닙니다.

# 프론트 연결: 사용자별 통합 알림

브랜치: `feat/user-notifications`. PR #6이 병합된 main `3ae5e24`를 이 브랜치에 통합했다. 아래는 Backend 계약이며 프론트·AI·보고서 코드는 이번에 수정하지 않았다.

## 기존 흐름과 변경

- 사건별 SSE는 사건 상세의 분석 진행·업무 변경을 갱신한다. 그대로 유지하고 사건 조회 권한을 넓히지 않는다.
- 새 사용자별 SSE는 상단 종 아이콘/통합 알림 패널을 위한 것이다. 로그인 사용자 본인에게만 전달한다.
- 새 요청·회신·결정 알림을 받으면 필요한 목록/상세를 이벤트에 따라 재조회한다. 알림 확인용 주기적 폴링은 추가하지 않는다.
- 알림 읽음은 업무 수락·완료·추가 분석·사건 종료와 독립이다. 회신 도착 자체로 분석을 실행하지 않는다.

## API

기존 세션 쿠키 인증, 성공 응답은 `{data,meta}`. userId를 입력받지 않는다. 상세 스키마는 이 브랜치 실행 후 `/swagger-ui/index.html`, `/v3/api-docs`에서 확인한다.

| API | 응답/용도 |
|---|---|
| GET /api/notifications?unreadOnly=false&size=20&cursor=... | `{items:[NotificationView],nextCursor}`. size 1~100, 최신 seq 순 커서 페이지 |
| GET /api/notifications/unread-count | `{unreadCount,latestEventId}`. 최초 접속·복구 시 기준점 |
| POST /api/notifications/{notificationId}/read | `{notificationId,readAt,unreadCount}`. CSRF 필요, 반복 안전. 다른 사용자/없는 알림은 동일 404 |
| GET /api/notifications/stream | 사용자 SSE. `Last-Event-ID` 헤더 우선, `lastEventId` 쿼리도 지원 |

읽음은 동일 동작을 반복해도 최초 readAt을 유지하므로 별도 Idempotency-Key가 필요하지 않다.

`NotificationView` 필드: notificationId, eventId(문자열 seq), type, title, target, parent(nullable), incidentId, incidentDisplayId, actorUserId, createdAt, readAt, read.

알림 제목은 고정 문구다. 질문·회신·사유 원문을 넣지 않는다. `target:{type,id}`와 `parent:{type,id}`를 조합해 사건·업무·요청·회신 위치로 이동한다. REPLY의 parent는 CHECK_REQUEST이므로 요청 화면에서 해당 replyId로 이동한다. 알림 수신 자체로 원래 사건 전체 조회 권한을 주지 않는다.

## 수신자와 종류

| 종류(type) | 수신자 |
|---|---|
| INCIDENT_CREATED | 해당 공정의 조사 가능한 엔지니어들. 없으면 해당 공정 관리자 |
| CHECK_REQUEST_RECEIVED | 업무/확인 요청의 지정 ownerUserId |
| CHECK_REQUEST_ACCEPTED / CHECK_REQUEST_REJECTED | 해당 요청의 requesterUserId |
| CHECK_REQUEST_REPLIED | 해당 요청의 requesterUserId |
| WORK_APPROVAL_REQUESTED | 업무의 지정 approverUserId |
| WORK_APPROVED / WORK_REJECTED | 업무의 requestedBy |
| ANALYSIS_SUCCEEDED / ANALYSIS_FAILED | 분석 requestedBy. 재개 시 승인 결정자로 바꾸지 않는다 |

새 이상 사건은 자동 탐지 알림이다. 사람이 팀/담당자를 골라 보내는 업무 요청과 구분한다. 설비별 담당 매핑은 현재 모델에 없어 이번에는 구현하지 않았다. 팀 요청은 기존 대상 공정/업무 종류/담당자 선택·서버 적격 검증을 유지한다. 행위자 본인의 행동 알림은 생략하고, 비등록 시스템 ID는 수신자에서 제외한다.

분석 성공 알림은 실제 ANALYSIS_RESULT가 만들어진 경우에만 보낸다. 승인 거절/요청 발송만 끝난 작업을 분석 결과 완료로 표시하지 않는다. REPORT와 SUMMARY 알림은 이번 범위가 아니다. 분석 요청자의 현재 사건 조회 권한도 검사한다.

## SSE와 재연결

| event | id | data |
|---|---|---|
| ready | 현재 연결 기준 seq | `{latestEventId,unreadCount}` |
| notification | 알림 seq | `{notification:NotificationView,unreadCount}` |
| notification.read | 새 id 없음 | `{notificationId,readAt,unreadCount}` |
| notification.resync | 최신 seq | `{latestEventId,unreadCount}` |

1. 앱 공통 영역에서 사용자별 연결 하나를 유지한다. 같은 계정의 다른 탭은 각자 연결할 수 있다.
2. 연결을 열고 ready 수신 후 최초 목록을 GET한다. 목록과 SSE의 중복은 **notificationId**로 제거한다.
3. notification 수신 시 알림 목록·배지를 갱신하고 관련 화면 데이터만 재조회한다. notification.read도 목록·배지에 반영한다.
4. EventSource 자동 재접속은 Last-Event-ID를 사용한다. 새 객체를 수동 생성하면 마지막 수신 id를 쿼리로 전달한다. 로그아웃·사용자 전환 시 연결과 저장 기준점을 정리하고 다른 계정의 id를 재사용하지 않는다.
5. 서버는 DB의 기준 seq 다음 알림부터 재전송한다. 네트워크 경계에서 같은 알림을 다시 받을 수 있으므로 클라이언트 중복 제거를 유지한다.
6. 처리 오류 시 서버가 연결을 닫으면 마지막 id로 재접속한다. 알림은 DB에 보존된다.
7. 누락 500건 초과·잘못된 기준점은 resync를 보낸다. 최초 페이지와 미읽음 수를 다시 조회한다. 정기 폴링을 시작하지 않는다.

단일 Backend 서버 전제다. 15초 heartbeat 주석·30분 연결 만료를 사용한다. 실제 배포 nginx의 버퍼링/타임아웃과 브라우저 자동 재연결은 배포 환경에서 확인해야 한다.

## 업무 흐름과 함께 사용할 때

- OPEN 요청은 받은 요청함, ACCEPTED는 수행할 일, REPLIED는 회신 완료다.
- 수락/거절은 `/api/check-requests/{id}/decisions`. 수락 후 회신은 최신 version으로 제출한다.
- 결과 등록·회신·알림 읽음은 자동 분석을 만들지 않는다. 작업자가 결과를 선택해 `/api/incidents/{id}/followup-analysis-jobs`를 호출한다.
- 일반 승인 재개는 원래 분석 requestedBy를 유지한다. 새 수동 추가 분석은 버튼을 누른 요청자에게 결과를 알리고, 그 실행이 다시 승인을 거쳐도 요청자를 유지한다.
- 기존 API/권한·오류와 세부 업무 계약은 `work-followup-api.md`, 로그·프로필은 `backend-logging.md` 참고.


## 통합 검증 — 2026-10-22

- main `3ae5e24`를 `feat/user-notifications`에 병합했다.
- 수락·거절 알림 연결, 회신 전 수락 절차, 승인자가 달라도 분석 요청자를 유지하는 경로, 회신만으로 분석이 실행되지 않는 경로를 검증했다.
- 통합 대상 테스트 35건 통과.
- `./gradlew test --offline`: 총 217건 중 215건 통과, 실패/오류 0건, 기존 Knowledge 테스트 2건 건너뜀.
- `git diff --check` 통과. Testcontainers DB 사용. 실제 Python Agent·프론트/브라우저·배포 nginx 검증 및 서버 재배포는 하지 않았다.


업무 채택으로 보내는 승인/팀 확인 요청의 brief 보완은 [work-request-brief-frontend.md](work-request-brief-frontend.md) 참고. 알림에는 해당 내용을 넣지 않고 대상 요청/업무의 상세 조회로 확인한다.


## 실제 HTTP·nginx 추가 검증 — 2026-10-22

- 별도 임시 Postgres와 Backend(prod 로그 프로필, simulator 모드), 배포 nginx 설정의 복사본을 사용했다. 복사본은 upstream 주소만 임시 Backend로 바꿨다. 기존 서버·DB·프론트 소스는 변경하지 않았다.
- 실제 쿠키 로그인 후 HTTP SSE 클라이언트로 검증했다: ready 즉시 전달(3초 이내), 사건 접수 커밋 후 실시간 알림, 사용자 간 목록 격리와 타인 읽음 404, 읽음 SSE와 반복 호출 readAt 유지, Last-Event-ID 재연결 시 알림 재전송, 잘못된 기준점 resync. 모두 통과했다.
- nginx를 통한 짧은 연결에서 버퍼링으로 인한 전달 지연은 관측되지 않았다. 30분 연결 만료·장시간 idle·실제 배포 네트워크는 검증 범위가 아니다.
- 실제 브라우저 EventSource의 자동 재접속과 프론트 화면 이동·배지 갱신은 아직 검증하지 않았다. 프론트 연결 후 별도 확인한다.
- 임시 검증 컨테이너와 Backend는 종료했다. 제품 코드 수정은 없으며 검증 결과만 기록했다.

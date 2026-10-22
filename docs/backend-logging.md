# Backend 로그와 알림 복구

## 현재 서버와 이번 변경의 구분

2026-10-22 검토 시 실행 중인 `fab-deploy-backend-1`은 시작 로그에서 **default 프로필**을 사용했고, 프로필/로그 수준 환경변수는 없었다. 기존 앱 로그 수준은 INFO다. 이 문서의 dev/prod 설정은 `feat/user-notifications` 소스 변경이며, 서버 재시작·이미지 재빌드·배포는 하지 않았다.

## 로그 수준

| 실행 환경 | 앱(com.fabcontext) | Spring 등 기본(root) | 보이는 앱 로그 |
|---|---|---|---|
| default — 프로필 미지정 | INFO | INFO | INFO, WARN, ERROR |
| dev | DEBUG | INFO | DEBUG, INFO, WARN, ERROR |
| prod | INFO | INFO | INFO, WARN, ERROR |

`TRACE`는 기본적으로 출력하지 않는다. dev에서도 전체 프레임워크 DEBUG를 켜지 않는다. `org.hibernate.SQL=WARN`, `org.hibernate.orm.jdbc.bind=OFF`로 SQL 상세와 값 바인딩을 기본 출력에서 제외한다. 이는 새로 추가한 로그에 본문·쿠키·토큰을 남겨도 된다는 뜻이 아니다.

프로필은 **명시적으로** 선택한다. 로그 외 DB·시드·인증·Agent 동작 설정은 이 변경에서 바꾸지 않았다. prod 프로필 이름을 선택하는 것만으로 전체 서비스의 운영 설정이 준비되는 것은 아니다.

```sh
# 기존 DB/데이터 팩 등 환경변수를 유지한 상태에서 backend 디렉터리에서 실행
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun

# 필요할 때 앱 로그 수준만 명시적으로 변경
SPRING_PROFILES_ACTIVE=dev APP_LOG_LEVEL=INFO ./gradlew bootRun
```

Compose 실행은 **backend 컨테이너의 environment에** `SPRING_PROFILES_ACTIVE=dev` 또는 `prod`를 전달해야 한다. 호스트에만 변수를 export한다고 기존 compose 설정이 이를 자동 전달하지는 않는다. 이번 작업에서는 AI/인프라 소유 파일을 수정하거나 컨테이너를 재생성하지 않았다. `APP_LOG_LEVEL`, `ROOT_LOG_LEVEL`로 각 수준을 변경할 수 있다. `LOGGING_LEVEL_COM_FABCONTEXT` 같은 Spring 직접 설정을 함께 지정하면 직접 설정이 우선하므로 중복 지정을 피한다.

## 이벤트별 기준

| 수준 | 이벤트 |
|---|---|
| INFO | 정상 상태 변경 HTTP 요청의 method·route 템플릿·status·소요시간, 업무 상태, Job 상태, 알림 저장·최초 읽음, 재연결 복구 건수 |
| DEBUG | 조회 요청·SSE 접속, 정상 연결 끊김, 개별 알림 전달, 중복 저장 생략, 미등록 수신자 생략 |
| WARN | 알림 전달 처리 실패 후 재접속 유도, 대량 누락/잘못된 기준점의 resync |
| ERROR | 알림 저장 실패로 업무 트랜잭션 롤백, Job 최종 실패, 기존 공통 처리기의 예상하지 못한 서버 오류 |

HTTP 쓰기 성공 로그는 실제 성공 응답이 반환된 경우만 남긴다. SSE 로그의 duration은 연결 처리 시간이며 스트림 전체 수명이 아니다. 정상적인 제안 미채택·반려는 업무 상태 INFO이며 서버 오류로 기록하지 않는다. 예상된 입력 오류·상태 충돌은 HTTP DEBUG이므로 prod에서 모든 4xx가 경고로 출력되지는 않는다.

추가된 로그는 `event=... key=value` 형태의 텍스트이며 JSON 구조 로그 수집기를 새로 도입하지 않았다. 예시:

```text
INFO [requestId=req_...] event=http.request method=POST route=/api/work-items/{id}/decisions status=200 durationMs=12
INFO event=work.status requestId=req_... incidentId=inc_... taskId=task_... status=DECLINED
INFO event=notification.stored requestId=req_... notificationId=ntf_... incidentId=inc_... userId=USR-... type=CHECK_REQUEST_RECEIVED eventId=42
WARN event=notification.delivery.failed userId=USR-... lastEventId=41 errorType=DataAccessResourceFailureException recovery=reconnect
```

요청의 `X-Request-Id` 응답값을 MDC에 넣고 요청 처리 후 반드시 정리한다. 업무 트랜잭션의 성공 이벤트는 **커밋 후** 기록한다. 비동기 작업 스레드에는 HTTP MDC가 자동 전달되지 않으며 requestId가 null일 수 있다. 이 경로는 incidentId/jobId/notificationId/eventId로 연결한다. 질문·회신·거절 사유·본문·쿼리·세션 쿠키·토큰은 새 로그에 넣지 않는다. 알림 전달 실패는 예외 메시지/SQL을 출력하지 않고 예외 클래스만 기록한다.

## 알림 오류 복구

- 알림은 DB가 원본이다. 전달 중 DB 조회 등의 오류가 나면 WARN을 남기고 해당 SSE 연결을 닫는다. 업무 커밋을 되돌리거나 알림 행을 삭제하지 않는다.
- 브라우저 EventSource는 종료된 연결을 마지막 event id로 재접속한다. 수동으로 새 연결을 만들 때는 마지막 id를 쿼리로 전달한다.
- 서버는 해당 id 이후의 알림을 DB에서 다시 전송한다. 프론트는 재전송·목록 조회의 중복을 notificationId로 제거한다. 네트워크 전체의 exactly-once 처리를 주장하지 않는다.
- 500건 초과 누락이나 잘못된 기준점은 notification.resync 후 목록/배지 재조회로 복구한다. 정상적인 주기적 알림 폴링은 추가하지 않는다.
- 단일 Backend 인스턴스 전제다. 브라우저 실제 자동 재연결·배포 nginx 버퍼링·다중 인스턴스는 별도 검증 사항이다.

## 로그 확인과 보존

```sh
docker logs -f --tail 100 fab-deploy-backend-1
```

기존 Compose는 표준출력을 Docker json-file로 수집하며 서비스당 10MB × 3개로 회전한다. 이 저장 방식·보존량은 이번에 변경하지 않았다. 로그 파일을 저장소나 서비스 DB에 추가하지 않는다.


## 검증 — 2026-10-22

- `./gradlew test --offline --tests '*Notification*Test' --tests '*RequestIdFilterTest' --tests '*LoggingProfilesTest'`: 20건 통과.
- `./gradlew test --offline`: 총 201건 중 199건 통과, 실패/오류 0건, 기존 Knowledge 테스트 2건 건너뜀.
- `git diff --check` 통과.
- 실제 서버 프로필 조회만 수행했다. dev/prod 서버 부팅·재배포·브라우저 EventSource 자동 재접속은 수행하지 않았다.

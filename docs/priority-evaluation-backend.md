# 우선순위 최초 자동 평가 — Backend 연결

## 동작

```text
POST /api/events/anomalies
  └ 사건 + 최초 평가(PENDING) + BASIC AI Job을 같은 트랜잭션에 저장
       └ 커밋 후
           ├ 기존 Agent 경로: AI 원인 분석
           └ Backend 평가 Worker: 기존 룰 계산 → 우선순위 저장 → incident.updated SSE
```

- Agent·MCP·Frontend·보고서 코드는 변경하지 않는다. Backend 내부 계산 서비스를 직접 호출하며 자기 HTTP API를 호출하지 않는다.
- 탐지 severity와 우선순위는 별개다. 예를 들어 severity=LOW여도 유효 계측 이탈이면 HIGH가 될 수 있다.
- 사건당 최초 평가 하나를 저장한다. 같은 이벤트 재접수, 질문, 기본 분석 재실행, 결과 조회로 재평가하지 않는다.
- 기존 시드·기존 사건은 자동 보정하지 않는다. 저장값이 없으면 기존 계약대로 NOT_EVALUATED다.
- 평가 실패로 AI Job을 실패시키거나 승인·요청·사건 상태를 변경하지 않는다. 반대 방향도 마찬가지다.

## 정책 활성화

- PM 기준 `DEMO-1.2-DRAFT`의 임계값·관련 센서 그룹·판정 순서를 유지하고 `enabled:true`로 시연 자동 실행을 활성화한다.
- 계산 응답 mode는 활성화 시 `DEMO_ACTIVE`, 비활성화 시 `DRAFT_PREVIEW`다. 문서 status=DRAFT는 성숙도를 나타내며 실행 활성화와 별개다.
- `productionCalibrated:false`, `validatedForRealTool:false`, synthetic 표시는 유지한다. 실제 장비 검증 완료를 의미하지 않는다.
- 필수 근거 부족은 정상 계산된 MEDIUM + provisional이다. 기술 오류를 중/하로 대신하지 않는다.

## 입력·시간·복수 run

- 사건 접수의 runIds를 보존한다. 비어 있으면 실제 wafer 경로에서 사건 공정·설비·로트에 맞는 run을 찾는다. ID를 지어내지 않는다.
- runIds가 경로 전체이면 다른 공정 run은 직접 평가 대상에서 제외한다. 각 대상 run을 평가할 때 기존 도구가 공정 경로 계측을 함께 확인한다.
- 사건과 다른 설비·로트·wafer의 run은 근거로 사용하지 않는다. 알 수 없는 run·대상 없음·FOLLOWUP/미래 접근 등은 실패로 기록한다.
- 평가 시각은 접수 당시 시각과 데이터 팩 분석 가능 시각 중 이른 값으로 고정한다. 이 합성 데이터 서버는 팩의 asOf 이후 자료를 조회하지 않는다. 기술 재시도에서도 입력 runIds·정책 버전·asOf는 유지한다.
- 복수 run의 유효 판정 중 가장 높은 등급을 사용한다.
- 일부 run에 기술 오류가 있더라도 유효 HIGH가 있으면 최고 등급이 이미 확보됐으므로 유지하고 부분 실패를 기록한다.
- 유효 HIGH가 없고 일부 run을 평가하지 못했다면 사건 전체 최고 등급을 확정할 수 없으므로 FAILED로 표시한다. 누락 run을 정상/중/하로 추정하지 않는다.
- 각 run의 판정·규칙·근거·누락 정보는 평가 기록에 보존한다.

## 처리 상태·재시도

- 새 `priority_evaluation` 테이블: incidentId PK·FK, 고정 입력·정책·기준 시각, 상태·시도·다음 실행 시각·lease·완료 시각·오류 코드·run별 결과.
- 서버 내부 스케줄러가 커밋된 작업만 처리한다. 기본 1초 간격, tick당 최대 5건. 사용자 알림 조회 폴링을 추가하는 것이 아니다.
- 일시적 DB 접근 오류만 최대 3회 기술 재시도한다(2·4초 대기). 데이터가 나중에 추가돼 등급을 바꾸는 재평가는 아니다.
- RUNNING lease는 120초. 만료 작업은 새 attempt로 재확보하며 이전 attempt의 늦은 완료·중복 완료는 적용하지 않는다. 한도 만료는 FAILED로 정리한다.
- PENDING·RUNNING은 공개 상태 PENDING, 완료는 COMPLETED, 최종 실패는 FAILED다. FAILED에는 level이 없다.
- 정책 enabled=false이면 신규 접수·Worker 처리를 멈춘다. 기존 저장 판정을 조회 중 변경하지 않는다.
- Kafka·Agent JobKind·새 public 변경 API는 추가하지 않는다.

## AI와 동시 갱신

- 평가 접수는 사건 생성 트랜잭션에 포함한다. 평가 완료는 우선순위 4개 컬럼만 갱신한다.
- 완료는 사건의 workflow version·ORM rowVersion·status·activeJobId·latestJobStatus·타임라인을 변경하지 않는다. 평가 시각·오류는 평가 테이블에 보존한다.
- Incident는 DynamicUpdate를 사용해 AI가 읽어 둔 오래된 우선순위를 다른 조사 변경과 함께 덮어쓰지 않게 한다.
- 상태 저장과 완료/실패 SSE 발행은 같은 트랜잭션을 기준으로 하고 SSE·상태 로그는 커밋 후에만 전달한다.

## 프론트·C 전달

- 목록·상세의 기존 `investigationPriority:{status,level,provisional,reason}` 계약을 유지한다.
- 우선순위 저장 후 기존 사건 SSE의 `incident.updated`를 보낸다. data에는 version과 investigationPriority가 있다. 상세는 이벤트 수신 후 필요한 데이터만 재조회한다.
- AI 분석과 우선순위의 완료 순서는 다를 수 있다. '분석 중' 안내는 AI 종료를 기다린다는 의미로 사용하지 않는다.
- 사용자 알림 SSE·새 알림 종류·새 프론트 폴링은 추가하지 않는다. 사건 목록은 기존 조회 경로로 저장값을 읽는다.
- C는 우선순위 계산·필수 MCP 호출·결과 이벤트 전달을 구현할 필요 없다. AI의 원인 분석·업무 제안은 기존대로 동작한다.

## 로깅·환경

- 접수·실행·완료 상태는 INFO, 일시 재시도는 WARN, 최종 실패는 ERROR. incidentId·policyVersion·attempt·errorCode로 연결한다.
- 질문·회신 원문·예외 메시지·토큰은 로그에 넣지 않는다.
- `fab.priority.worker-enabled=false`로 Worker만 비활성화할 수 있다(평가 접수는 유지). `fab.priority.poll-delay-ms`로 서버 내부 처리 간격을 조정한다.
- 기존 `ddl-auto=update` 환경은 다음 기동 때 새 평가 테이블을 추가한다. 기존 사건 데이터·등급을 초기화하거나 자동 backfill하지 않는다.

## 검증 결과

- `cd backend && ./gradlew test --offline`: 최신 main `d253ee8` 병합 후 총 278건, 통과 276건, 실패/오류 0건, 기존 Knowledge 테스트 2건 건너뜀.
- 신규 계산/연결 테스트 14건: 최고 등급 집계, 기술 오류와 잠정 중 구분, run 범위, 실제 wafer 경로, 독립 실행, 커밋/롤백, 중복, SSE, 재시도·만료·늦은 완료, AI 오래된 스냅샷 저장 경합.
- 전체 테스트에서 발견한 우선순위/AI 갱신 충돌을 독립 컬럼 갱신과 DynamicUpdate로 수정한 뒤 전체 재검증했다.
- 별도 DB·8092 Backend에서 실제 HTTP 신규 사건 PENDING→HIGH(AI는 QUEUED), 이후 AI SUCCEEDED 및 HIGH 유지, 중복 이벤트 200/원래 사건 반환, 목록·상세 일치, 기존 시드 미평가 유지 확인.
- 실제 서비스 DB·배포·AI/Python·프론트·보고서 모듈은 변경하지 않았다.

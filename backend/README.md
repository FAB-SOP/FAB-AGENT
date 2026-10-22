# FAB CONTEXT API — Spring backend (하나뿐인 backend)

공개 API 51개(기존 38 + 모니터링 3 + 화면 업무 · 회신 목록 · 과거 분석 · 시연 계정 전환 10, 프론트 계약 `../data/backend-handoff-v2/combined.openapi.json`)와, `ai/`의 업무 MCP · Agent Worker가 부르는 내부 API(`/internal/**`)를 함께 연다.
**원칙: 공개 API = 프론트 계약, 내부 API · Agent 이벤트 = 팀 계약(원본은 팀 내부, 테스트용 사본 `../ai/contracts/`), 둘 사이 변환은 Spring이 한다.**
operationId · HTTP 메서드 · 경로는 프론트 계약과 1:1로 일치한다(`/v3/api-docs`, 대조 도구 `../ops/tools/openapi_diff.py`).

- Java 21 · Spring Boot 4.1 · Gradle · Spring Security(세션 + CSRF) · JPA + **PostgreSQL**(실행 · 테스트 모두 — 테스트는 Testcontainers)
- 데이터 팩은 **v2 조립본 `../data/fab-causal-pack-v2/`**(dataVersion `causal-pack-v2-scenarios`)를 읽는다 — `../data/build_pack_v2.py`가 v1 전체(`../data/fab-causal-pack-v1`)와 v2 CURRENT(`../data/backend-handoff-v2/data`, L006 CMP-PM3 한 run 수정 + sensorEnvelopes)를 합친다. 실제 원문 RAG는 동료 묶음의 `fab-rag-real-documents-v1/rag/source-chunks.jsonl`을 그대로 읽는다. `evaluation/`(정답) · 생성기는 어디에도 없다
- 공정 **CVD → ETCH → CMP**, 공정마다 PM 셋. run · 5위치 계측 · run 특징 · 동결 관리한계(I-chart, R1) · 센서 참고 범위 · 정비 이력 · 원인 매트릭스 · 원시 시계열(gz, DB에 넣지 않고 파일에서 읽음)
- Agent 자리는 `AGENT_MODE`로 고른다 — `simulator`(기본, 서버 안 **대역** — 화면 개발 · 흐름 테스트용, 원인을 판정하지 않는다) / `external`(`ai/`의 Agent Worker가 `/internal/jobs`로 가져간다). Kafka는 아직 없다

## 디렉터리 구조

```
src/main/java/com/fabcontext/api/
├─ common/        공통 응답({data, meta}) · 오류 · JSON · ID · 멱등 키 · 커서 · stream/IncidentStreamHub(사건별 SSE)
├─ config/        FabProperties — 토큰 · 승인 만료 · 시드 · Agent 모드 · 데모 경로 · 묶음 경로(pack-dir · rag-dir)
├─ auth/          로그인 · 세션 · CSRF · 서비스 토큰(/api/events → ROLE_SERVICE, /internal → ROLE_AGENT) · 공정 범위 권한 · 시연 계정 전환
├─ domain/        화면(공개 API /api/**) 기능 — 기능마다 Controller · Service · Entity · Repository · Dtos
│  ├─ incident/      P03 사건 목록 · 상세 · 관리자 상태 전이 · 타임라인
│  ├─ events/        이상 이벤트 접수 (탐지 서비스 → 사건 생성 + 기본 분석 작업)
│  ├─ processdata/   P04~P06 run · 계측 · 특징 · 센서 시계열 · 경로 · 변경 · 공정 비교
│  │                 PackStore(묶음 파일 읽기) · ProcessEvidenceService(서버 쪽 계산 — R1 · OOS · 정상 분포 대비 z · 참고 범위)
│  ├─ analysis/      P07 분석 결과 저장 · 조회
│  ├─ job/           분석 작업(BASIC · DEEP · RESUME · REPORT) 상태 · ActiveJobs(사건 화면의 진행 중 작업 — incident의 ActiveJobLookup 구현)
│  ├─ investigation/ P07 질문 · 기본 분석 재실행 · 사건 SSE 구독
│  ├─ approval/      P08 승인함 · 제안 · 승인 결정 · 승인된 변경 실행(executeApproved). 무엇을 실행하는지는 모른다 —
│  │                 ApprovedActionHandler(종류별 입력 검사 · 실행)를 ApprovedActionHandlers에서 골라 넘긴다
│  ├─ checkrequest/  P09 확인 요청(앞 공정 확인 CROSS_PROCESS · 정비 MAINTENANCE) · 회신 · 실행기 CrossProcessCheckHandler · MaintenanceRequestHandler
│  ├─ action/        P10 조치 기록 · 효과 확인 · 실행기 RecordActionHandler
│  ├─ report/        P11 보고서 초안 · 편집 · 확정 · 내보내기 (sections 다섯 키 — contract/ReportSections) · 실행기 ConfirmReportHandler
│  ├─ knowledge/     P12 지식 문서 검색 · 본문 (실제 원문 7종 · 팀 점검 문서 15 · 가상 사례 7)
│  ├─ monitoring/    정상 LOT · PM 모니터링 3개 (팩 JSON 그대로)
│  ├─ work/          화면 업무 — 확인 업무 · 변경 계획(관리자 승인) · 다른 팀 요청, WorkOperation(업무 코드 → 경로)
│  └─ dashboard/     P02 대시보드 집계
├─ internal/      내부 API(/internal/**) — 업무 MCP 도구 묶음과 1:1. 컨트롤러는 InternalApi 표시(InternalAdvice가 오류 모양을 맞춘다)
│  ├─ query/        InternalIncidentQuery(사건) · InternalProcessQuery(경로 · run · 특징 · 시계열 · 관리한계 · 변경) ·
│  │                InternalCollabQuery(담당자 · 확인 요청 · 조치 · 보고서 맥락) + 같은 단위의 Controller
│  ├─ action/       ApprovedActionService(승인된 변경 실행 · 승인 조회) + Controller
│  ├─ job/          AgentJobService(작업 맥락 · 대기열 · 확보 · 제안 저장과 받는 사람 검증 · 보고서 초안) + Controller(이벤트 수신 포함)
│  ├─ view/         InternalViews — 도구 결과 공통 형식(env · ref)과 사건 · 요청 · 회신 · 조치 · 보고서 · 승인 View
│  └─ AgentEventService(Worker 이벤트 → 작업 · 사건 상태) · DemoFaults · DemoController(장애 주입, DEMO_ROUTES=true만)
├─ contract/      계약 변환을 한곳에 — AgentResultMapper · ResultShape(Agent 결과 → 화면 결과), ActionTypeNames(승인 종류 공개 ↔ 내부 이름),
│                 ReportSections · ReportSection(보고서 sections 배열 ↔ 다섯 키 객체)
├─ simulator/     서버 안 대역 Agent — AgentSimulator · AgentWorker(상태 변화) · AnalysisFactory(판정 없는 결과: 공정 데이터 값 그대로 ·
│                 「시뮬레이터 결과 — 실제 분석 아님」 · 질문에는 고정 모양의 확인 요청 제안 하나). AGENT_MODE=external이면 작업을 잡지 않는다
└─ seed/          SeedCatalog(묶음 → DB) · SeedRunner(시연 사건) — DB가 비어 있을 때 한 번
src/main/resources/application.yml · schema.sql(엔티티 관계로 걸지 않는 외래 키)
src/test/java/…   api/(화면별 통합 테스트 + InternalApiTest) · domain/ · simulator/ · common/
scripts/smoke-test.sh    실행 중인 서버에 전체 흐름을 curl로 (시뮬레이터 모드)
Dockerfile               ai/compose.yaml이 빌드한다(묶음은 /bundle로 마운트)
```

의존 방향: `domain` ← `internal` · `simulator` · `seed`. `domain`은 `internal` · `simulator`를 모른다.

**패키지 순환 없음 (2026-10-20 리팩터링 1단계)** — `python3 ../ops/tools/java_pkgdeps.py src/main/java`가 표와 순환을 보여 준다(「순환: 없음」이어야 한다).
- `incident`는 `job`을 모른다 — 진행 중 작업은 `incident.ActiveJobLookup`(인터페이스)으로 받고 `job.ActiveJobs`가 구현한다. SSE 허브는 `common.stream`, 질문 · 재실행은 `investigation`
- `approval`은 실행 대상을 모른다 — 실행기(`ApprovedActionHandler`)가 `checkrequest` · `action` · `report`에 있고 그쪽이 `approval`을 안다
- 엔티티는 다른 도메인 패키지의 엔티티를 관계로 품지 않는다 — 필요하면 ID 문자열 + 외래 키(`schema.sql`)

## 실행

```bash
# 전체(권장) — Postgres · 이 backend · 업무 MCP · 지식 MCP · Agent Worker
(cd ../ai && FAB_LLM=rule docker compose up -d --build)          # backend는 http://localhost:8090

# 묶음 경로는 기본값이 없다(없으면 시작 실패 — 원본은 compose 환경 변수). 직접 띄울 때는 넘긴다(이 폴더 기준)
export FAB_PACK_DIR=../data/fab-causal-pack-v2 FAB_RAG_DIR=../data/fab-rag-real-documents-v1

# backend만 — compose의 postgres(호스트 55432)만 띄우고 붙는다. 테이블을 만들고 비어 있을 때만 시드
(cd ../ai && docker compose up -d postgres) && ./gradlew bootRun   # 아래 두 경로를 먼저 넘긴다

# 띄울 때마다 처음 데이터 — DDL_AUTO=create가 backend 테이블을 지우고 새로 만든 뒤 시드한다
DDL_AUTO=create ./gradlew bootRun
DDL_AUTO=create AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun   # ai/의 fab-eval · fab-demo용
```

compose의 `backend` 컨테이너가 떠 있으면 먼저 끈다(`docker compose stop backend agent business-mcp`) — 같은 `backend` 스키마와 포트 8090을 쓴다.

- 팩 경로는 `FAB_PACK_DIR` · `FAB_RAG_DIR` — **기본값이 없다**(없으면 원인을 밝히고 시작에 실패한다). 원본은 compose 환경 변수(`/bundle/…`)이고, 직접 띄울 때는 위처럼 넘긴다. 테스트는 `ApiTestSupport`가 넣는다. v2 팩은 `python3 ../data/build_pack_v2.py`로 다시 만든다(같은 입력이면 같은 바이트, 검사 포함)
- 다시 시드: `docker compose exec postgres psql -U fab -d fab -c "drop schema backend cascade"` 후 재시작. Agent 체크포인트도 같이 지우려면 `agent` 스키마도 지운다
- **스키마를 바꾼 뒤에는 반드시 다시 시드한다.** `ddl-auto: update`는 새 테이블 · 컬럼 · 제약은 더하지만, 컬럼 타입 변경(varchar → jsonb)과 이름 변경(`cd_value` → `value_a`)은 기존 테이블에 반영하지 못한다(옛 컬럼이 그대로 남거나 새 컬럼이 비어 있게 된다). 2026-10-20 스키마 변경 뒤 절차:

  ```bash
  cd ../ai
  docker compose stop backend agent business-mcp
  docker compose exec -T postgres psql -U fab -d fab -c "DROP SCHEMA backend CASCADE; DROP SCHEMA agent CASCADE;"
  FAB_LLM=rule docker compose up -d --build
  docker compose --profile tools run --rm --no-deps smoke
  ```

  `agent` 스키마도 함께 지우는 이유: Agent 체크포인트가 지운 backend의 작업 · 승인 ID를 기억하고 있어 재개가 어긋난다. `knowledge` 스키마(문서 벡터)는 그대로 둔다
- Tomcat이 시드보다 먼저 열린다. 준비 확인은 `/internal/incidents/EV-1009`가 200인지로 한다(compose healthcheck도 같다)
- 기본 포트 **8090**(`PORT`로 변경). Swagger UI http://localhost:8090/swagger-ui.html

| 계정 | userId | 역할 | 공정 범위 |
|---|---|---|---|
| `cmp.park` | USR-CMP-01 | ENGINEER | CMP (시연 사건 EV-1006 · 1007 · 1009 담당) |
| `etch.lee` | USR-ETCH-01 | ENGINEER | ETCH (EV-1009에서 앞 공정 확인 요청을 받는다) |
| `cvd.kim` | USR-CVD-01 | ENGINEER | CVD |
| `mnt.jung` | USR-MNT-01 | ENGINEER · 설비정비팀 | CVD, ETCH, CMP (정비 요청을 받는다) |
| `manager.choi` | USR-MGR-01 | MANAGER | CVD, ETCH, CMP |

비밀번호는 모두 `fab1234!`. 이벤트 접수 서비스 토큰은 `X-Service-Token: dev-simulator-token`, 내부 API 토큰은 `Authorization: Bearer dev-service-token`(`SERVICE_TOKEN`).

## 검증 (2026-10-22)

```bash
./gradlew test                     # 134개 — Testcontainers Postgres(pgvector/pgvector:pg16, compose와 같은 이미지). Docker 필요
./scripts/smoke-test.sh http://localhost:8090   # 107개 — 시뮬레이터 모드 서버에 curl 전체 흐름 (jq 필요)
python3 ../ops/tools/openapi_diff.py http://localhost:8090   # 서버 /v3/api-docs ↔ 프론트 계약 차이 표
```

통합 테스트는 컨텍스트와 Postgres 컨테이너 하나를 공유하므로 상태를 바꾸는 테스트는 `ApiTestSupport.newIncident()`로 자기 사건을 만든다. 설정이 다른 컨텍스트(`SlowAgentApiTest`, Agent 지연 1.5초)는 같은 컨테이너의 자기 스키마(`backend_slow`)에 따로 시드한다. 컨텍스트를 띄울 때마다 테이블을 새로 만든다(`ddl-auto=create`). 외부 Agent와의 연결은 `ai/` 쪽(pytest 43개 · `fab-eval` 규칙 모델 3/3 · 실제 모델 3/3 세 번 · compose 점검)에서 검증한다.

## 공통 규칙

| 항목 | 구현 |
|---|---|
| 응답 | `{ data, meta: { requestId, at } }`, 목록은 `data: { items, nextCursor }` |
| 오류 | `{ error: { code, message, details }, meta }` — 400 · 401 · 403 · 404 · 409 · 422 |
| 인증 | `GET /api/auth/csrf` → `POST /api/auth/login`(헤더 `X-CSRF-TOKEN`) → 세션 쿠키 `FABSESSION` |
| 쓰기 | 모든 POST · PATCH에 `X-CSRF-TOKEN`. 계약에 표시된 쓰기는 `Idempotency-Key`도 필수 |
| Idempotency | 같은 키 + 같은 본문 → 최초 응답 재생(`Idempotent-Replayed: true`), 같은 키 + 다른 본문 → 422 |
| 낙관적 버전 | `expectedVersion` · `expectedIncidentVersion` · `expectedContentRevision`이 다르면 409 → 다시 조회 |
| 권한 | 사건 · 데이터는 `processScopes` 기준, 확인 요청은 요청자 · 담당자 관계 기준, 상태 전이는 MANAGER만 |

## 화면별 흐름 (P01~P12)

1. **이벤트 접수** `POST /api/events/anomalies` → 사건 저장 즉시 202 + BASIC 작업 투입. 같은 `eventId`를 다시 보내면 200으로 기존 사건을 돌려줍니다.
2. **기본 분석** QUEUED → RUNNING → SUCCEEDED. 결과는 관측 사실 · 가설(지지/반대 근거) · 한계로 나뉘고, 원인을 확정하지 않습니다.
3. **추가 질문** `POST .../questions` → DEEP 작업. 증거가 앞 공정을 가리키면 Agent가 그 공정 담당자 확인 요청을, 현재 설비 센서를 가리키면 설비정비팀 정비 요청을 제안하고 `WAITING_APPROVAL`로 대기합니다. Agent는 설비 설정값을 처방하지 않습니다.
4. **승인** `POST /api/approvals/{id}/decisions` → 권한 · 만료 · 버전 · `actionHash`를 다시 검증합니다. 실행되는 것은 언제나 **저장된 payload**입니다. Agent가 기다리던 작업이면 승인만 기록하고 같은 `threadId`로 RESUME 작업이 생깁니다(`resumeJobId`) — 실행은 재개된 Agent가 승인 ID로 요청합니다(아래 「내부 API」).
5. **회신** 요청 담당자가 `POST .../replies` → 요청 REPLIED, 대기하던 작업에서 RESUME 작업 생성, 사건은 다시 INVESTIGATING.
6. **조치 · 효과** 실제 수행한 조치만 기록합니다(미래 시각이면 422). 효과를 기록해도 사건은 자동 종료되지 않습니다.
7. **보고서** 사용자가 요청할 때만 REPORT 작업으로 DRAFT를 만듭니다. 편집(`contentRevision`) → 확정(스냅샷 보존) → Markdown 내보내기. 확정 후 변경하려면 새 version을 만듭니다.
8. **관리자 전이** OPEN · INVESTIGATING · WAITING_REPLY → ON_HOLD, ON_HOLD → INVESTIGATING, 그 외 모든 상태 → CLOSED.

SSE `GET /api/incidents/{id}/stream` 이벤트: `snapshot`, `job.status`, `analysis.result`, `approval.created`, `approval.decided`, `approval.executed`, `work-item.updated`, `check-request.created`, `check-request.replied`, `action.recorded`, `action.verified`, `report.ready`, `report.confirmed`, `incident.updated`. 연결이 끊기면 `getJob`으로 상태를 복구합니다. 대시보드 전역 스트림은 계약에 없으므로 `GET /api/dashboard`를 다시 조회합니다.

## 시드 시나리오

| 사건 | 상태 | 내용 |
|---|---|---|
| **EV-1009** | 기동 직후 기본 분석 | **주 시연 사건.** `W-CURRENT-009-3`, CVD-PM1 → ETCH-PM2 → CMP-PM3. CMP 후 두께 10,140.4 Å로 UCL 위(규격 9000–11000 안 — OOC, OOS 아님). CMP 제거량은 정상 변동 안, Etch 제거량 4896.3 Å(같은 PM 정상 run 대비 z ≈ −2.8), ETCH-PM2 정비 이력 `DEMO-CHANGE-E02`. 기본 분석을 대기열(QUEUED)에 두어 Agent가 바로 처리한다 |
| EV-1006 | 기동 직후 기본 분석 | `W-CURRENT-006-3` CMP-PM3, `head_pressure` 참고 범위 이탈 — 현재 공정(정비 요청) |
| EV-1007 | 기동 직후 기본 분석 | `W-CURRENT-007-3` CMP-PM3, `slurry_flow` 참고 범위 이탈 — 현재 공정(정비 요청) |
| EV-1023 | INVESTIGATING | CVD-PM1 증착 속도 드리프트. `cvd.kim`이 만든 PENDING 수동 제안 1건(CMP 담당에게 확인 요청) |
| EV-1022 | ON_HOLD | CMP-PM2 패드 온도 드리프트, 관리자 보류 |
| EV-1021 | CLOSED | CMP-PM2 슬러리 유량 저하 — 조치(IMPROVED) + 확정 보고서 v1 |
| EV-1018~1020 | CLOSED | 추이 · 대시보드용 과거 사건(웨이퍼 데이터 없음) |

EV-10NN은 데이터 팩 현재 로트 `CURRENT-L0NN`에서 R1을 벗어난 run이다. 분석 시점은 팩의 `analysisAsOf`(2026-10-20 11:59 KST) — 그 뒤 공개된 계측과 FOLLOWUP run은 내부 API가 막는다(403).
시연 옵션(`fab.agent`): `step-delay-ms`(단계 지연, 기본 1500), `failure-rate`. 질문에 `[fail]`을 넣거나 이벤트 `metrics.simulateAgentFailure: true`로 첫 기본 분석을 실패시킬 수 있다.

## 내부 API (`/internal/**`)

업무 MCP(`ai/src/fab/business/server.py`)와 Agent Worker만 부른다. 응답은 `{data: {data, evidenceRefs, dataVersion, warnings, synthetic}}`, 오류는 `{error: {code, message, hint, retryable}}`.

| 묶음 | 경로 | 업무 MCP 도구 |
|---|---|---|
| 사건 | `GET /internal/incidents` · `/incidents/{id}` | `list_incidents` · `get_incident` |
| 공정 데이터 | `GET /internal/wafers/{w}/lineage` · `/runs/{runId}` · `/runs/{runId}/features` · `/runs/{runId}/traces/{sensorId}?step&maxPoints` · `/limits?processCode` · `/changes?equipmentId` | `get_wafer_lineage` · `get_run_and_metrology` · `get_run_features` · `get_sensor_trace` · `get_frozen_limits` · `get_change_events` |
| 협업 | `GET /internal/processes/{p}/owners`(PROCESS_OWNER · MAINTENANCE) · `/incidents/{id}/check-requests` · `/actions` · `/report-context` | `get_process_owner` · `get_check_requests` · `get_action_history` · `get_report_context` |
| 승인된 변경 | `POST /internal/approved-actions/{check-request \| maintenance-request \| action-record \| report-confirmation}` — `approvalId` · `idempotencyKey`만 | `create_cross_process_check_request` · `create_maintenance_request` · `confirm_report` |
| 작업 | `GET /internal/jobs`(QUEUED) · `GET …/jobs/{id}/context` · `POST …/claims` · `POST …/proposals` · `POST …/events` | Worker |
| 보조 | `GET /internal/approvals/{id}` · `/check-requests/{id}` · `POST /internal/incidents/{id}/report-drafts` · `/healthz` · `/internal/demo/faults`(DEMO_ROUTES) | Worker · 시연 |

숫자(R1 · OOS · 정상 분포 대비 z · 참고 범위 이탈 · 설정값 대비 차이)는 `ProcessEvidenceService`가 계산하고, Agent는 해석만 한다. BASELINE run은 원시 시계열이 없어 `get_run_features`로 안내한다(404 hint).

**승인 뒤 실행 (팀 결정)** — 저장은 backend, 승인은 사람, 실행 요청은 Agent(MCP), 검증과 실제 변경은 backend.
1. Agent가 `POST /internal/jobs/{jobId}/proposals`로 제안 → **받는 사람을 먼저 검증**(앞 공정 확인은 그 공정의 공정 담당자에게만, 정비 요청은 설비정비팀에게만, 질문한 본인에게는 안 됨 — 아니면 400과 hint) → 실행할 내용 · 해시 · 만료 저장(같은 `idempotencyKey`면 같은 승인)
2. 사람이 `POST /api/approvals/{id}/decisions` → **Agent가 기다리는 제안이면 승인만 기록**하고 RESUME 작업을 만든다(응답에 `executionRef` 없음). 거절도 외부 Agent 모드에서는 RESUME을 만들어 Agent가 거절 사유를 읽고 결과를 정리한다. 수동 제안은 그 자리에서 실행
3. 재개된 Agent가 업무 MCP 변경 도구 → `POST /internal/approved-actions/*`
4. `ApprovalService.executeApproved`가 상태 · 종류 · 만료를 다시 보고 **저장된 payload만** 그 종류의 실행기(`ApprovedActionHandler`)로 한 번 실행한다. 이미 실행했으면 같은 결과를 돌려준다. 시뮬레이터도 같은 메서드를 부른다

**외부 Agent 모드**(`AGENT_MODE=external`) — 시뮬레이터가 작업을 잡지 않는다. Worker가 QUEUED 작업을 가져가 `claims`로 확보하고(같은 attempt면 409), 이벤트(`contracts/schemas/agent-event.schema.json`)를 `POST /internal/jobs/{jobId}/events`로 돌려준다 — `APPROVAL_REQUIRED` · `WAITING_REPLY` · `RESULT_READY` · `REPORT_DRAFT_READY` · `RETRY_SCHEDULED`(attempt+1로 다시 QUEUED) · `JOB_FAILED`. Kafka를 붙이면 같은 이벤트를 소비자가 `AgentEventService.handle()`로 넘기면 된다.

## 계약과의 차이 · 결정 사항

- page-map에 컴포넌트 스키마 본문이 없어 응답 DTO는 화면 행의 `fieldPaths`를 기준으로 설계했다. 화면용 보조 필드(`displayId`, `qualitySummary`, `history` 등)를 일부 더했다
- `getResults`는 최신 결과를 최상위로, `latest=false`면 `history`를 함께 준다. 결과가 없으면 404 `ANALYSIS_NOT_READY`
- `getComparisons`는 프론트 유지 목록에 없어 바꾸지 않았다 — 앞 공정이 둘이라 `processCode`를 받고(UPSTREAM 기본은 바로 앞 ETCH), 응답은 `{incidentId, scope, processCode, runId, equipmentId, evidence, features, dataVersion, asOf}`(그 run의 서버 계산 증거)다. 프론트 계약의 `groupBy` · `groups` · `insufficientEvidence` · `matchedConditions` · `reasons` · `computedAt`(그룹 비율 비교)은 없다
- `jobs/{jobId}/context`는 계약 job-message가 추가 필드를 막아 `{job: <job-message>, question, scope, status, analysisAsOf}`로 감쌌다
### 프론트 계약에 맞춘 것 (2026-10-20, backend-handoff-v2)

| 항목 | 공개 API (프론트) | 내부 API · Agent (팀 계약) | DB |
|---|---|---|---|
| 승인 `actionType` | `CHECK_REQUEST` · **`MAINTENANCE_REQUEST`(새 값)** · `RECORD_ACTION` · `CONFIRM_REPORT` | `CREATE_CHECK_REQUEST` · `CREATE_MAINTENANCE_REQUEST` · … (`ApprovalActionType.internalName()`) | 공개 이름 하나 |
| 보고서 `sections` | 배열 `[{key, title, body}]` — key는 `overview` · `investigation` · `requestsAndReplies` · `actionsAndVerification` · `unresolved` 다섯 개, 이 순서만(아니면 422 `INVALID_SECTIONS`), 제목은 편집 가능 | 다섯 키 객체(Agent `REPORT_DRAFT_READY` · 내부 보고서 응답) — Spring이 기본 제목을 붙여 배열로 바꿔 저장 | 배열(jsonb) |
| sensor-data `baseline` | `null` + `baselineUnavailableReason` — 팩에 정상 기준 trace(BASELINE 원시 시계열)가 없다. run 평균 참고 분포(references) · 시계열 참조 범위(sensorEnvelopes)는 모니터링 상세에서 | — | — |
| `recipeStep` | `READY` · `PROCESS` · `END`(예전 `SHUTDOWN`) | 업무 MCP `get_sensor_trace`의 step도 `END` | — |
| sensor-data 기본 `limit` | 5000(최대 5000) — L006 head_pressure 3201점이 한 번에 와 `truncated=false`(프론트는 true면 구간 판정을 생략) | — | — |
| 분석 결과 | 아래 「분석 결과의 화면용 키」 | 이벤트 계약(candidateCauses additionalProperties=false)은 그대로 | 둘 다 저장 |

- 팀 계약 `public-openapi.json`(원본은 팀 내부, 사본 `ai/contracts/`)의 공개 actionType도 같은 네 값으로 고쳤다 — 공개 API 계약 문서가 둘이던 것을 하나로 맞췄다
- **dataVersion** — 결과 · 근거 · 화면 응답은 팩 `pack-manifest.json`의 `causal-pack-v2-scenarios`를 쓴다(예전 `dv-N` 카운터는 없앴다 — 이벤트 접수는 팩을 바꾸지 않는다)
- OpenAPI 대조(`../ops/tools/openapi_diff.py`) 결과 41개 operation이 모두 같고(2026-10-20 기준 — 지금은 업무 · 회신 · 시연 API가 더해져 51개), 차이는 `MAINTENANCE_REQUEST` 추가(승인 목록 · 상세 · 제안 요청 · 응답)와 위 comparisons뿐이다. 모니터링 3개의 문서 스키마는 프론트 계약을 그대로 붙였다(`resources/openapi/monitoring-contract.json`, `OpenApiConfig`) — 응답이 팩 JSON을 그대로 내리는 Map이라서다

### 모니터링 API 3개 (정상 LOT · PM)

| 경로 | 응답 `data` |
|---|---|
| `GET /api/monitoring/lots?processCode=` | `{items: [CURRENT-L001 … L010]}` |
| `GET /api/monitoring/lots/{lotId}/summary?processCode=` | `lotId · processCode · runs`(그 공정의 PM1/2/3 각 한 웨이퍼 run, 정상 run은 `incidentId: null`) — run마다 같은 웨이퍼 5위치 `sites` · 센서 `features`, `limits` · `sensorDefinitions` · `references` · `sensorEnvelopes` · `dataVersion` |
| `GET /api/monitoring/runs/{runId}/sensor-data?sensorId=&limit=` | `samples`(원본 간격 그대로, 보간 없음, 결측은 value=null · MISSING) · `totalCount` · `truncated` · `baseline: null` · `dataVersion` |

- run · 계측 · 특징 · 관리한계 · 센서 정의 · 참고 분포 · 참조 범위는 **팩 JSON을 그대로** 내린다(원본 필드 · 시각 문자열 유지). `incidentId`만 DB 사건에서 채운다. 대상은 CURRENT LOT뿐이다
- 상태: 200 / 401(세션 없음) / 403(공정 권한) / 404(없는 LOT · run · 센서) / 422(processCode · sensorId · limit). 규격이 없는 공정은 `limits.lsl · usl = null`(0이나 가짜 값을 만들지 않는다)
- `MonitoringApiTest`가 `../data/backend-handoff-v2/examples`(LOT 목록, L001 · L006 · L007 · L009 CMP 요약, L006 head_pressure 전체 시계열)와 필드 · 값 단위로 대조한다. 의도한 차이는 넷뿐이다 — `incidentId`(예시는 프론트 모의 ID `INC-RUN-…`, 서버는 실제 사건 ID `inc_…` — 유무만 비교), `sensorName`(팩에 센서 한국어 이름 목록이 없어 sensorId를 그대로 낸다 — 이름을 지어내지 않는다), `quality`(원본 `GOOD_SYNTHETIC` — 예시는 `GOOD`, 명세상 둘 다 정상 취득), `measuredAt`(서버 UTC `Z` · 예시 `+09:00` — 같은 시각)

### 분석 결과의 화면용 키

프론트 명세가 제안한 키와 지금까지의 키를 **함께** 내린다(`contract/ResultShape`). 프론트는 아직 모의 응답으로 화면을 그려 어느 키를 실제로 읽는지 확인되지 않았기 때문이다 — 확정되면 옛 키를 뺀다.

| 칸 | 화면용(새) | 옛 키(유지) |
|---|---|---|
| `observedFacts[]` | `label` · `value` · `unit` · `evidenceId` | `factId` · `statement` · `evidenceIds` |
| `candidateCauses[]` | `rank` · `cause` · `supportingEvidence[]` · `counterEvidence[]` · `check` · `action` · `verify` · `sourceIds[]` | `priority` · `hypothesis` · `supportingEvidenceIds` · `counterEvidenceIds` · `nextChecks` · `missingData` · `processCode` · `relation` · `status` · `matrixCauseIds` |
| 결과 | `dataVersion` · `threadId` | — |

- `action` · `sourceIds`는 원인 매트릭스의 `proposedResponse` · `sourceIds`에서, `verify`는 「조치 뒤 별도 확인 run에서 확인 — 그 전에는 해결로 보고하지 않음」으로 채운다. Agent 결과의 사실은 글뿐이라 `value` · `unit`은 null이다(숫자를 지어내지 않는다). 시뮬레이터 결과는 제거 · 증착량(Å) · 센서 평균을 넣는다
- **relation 버그를 고쳤다** — 예전에는 근거 ID 글자에 `-UPSTREAM-`이 있는지로 앞 공정을 판단해, 새 근거 ID(`E-RUN-RUN-W-CURRENT-009-3-ETCH`)에서는 EV-1009 1순위 「앞 공정 ETCH-PM2 …」가 processCode=CMP · relation=CURRENT로 저장됐다. 이제 근거가 가리키는 공정(run 근거의 공정 · 정비 이력 설비의 공정 · 점검 문서와 원인 ID의 매트릭스 공정 가운데 가장 많은 것)으로 정한다 — EV-1009 ETCH · UPSTREAM, EV-1006 · EV-1007 CMP · CURRENT

### Agent 근거용 시계열 구간 (공개 API에는 없음)

`/internal/runs/{runId}/traces/{sensorId}`(업무 MCP `get_sensor_trace`)가 그 센서의 `sensorEnvelope`와 DEMO-SEGMENT-V2 구간(`segments[]`: fromSeconds · toSeconds · confirmedAtSeconds · direction · mean, run 시작 t0 기준 초)을 함께 주고, 구간이 있으면 근거 `E-SEG-{runId}-{sensorId}`를 붙인다(`domain/processdata/SegmentDetector`). 규칙: READY · END와 PROCESS 처음 2초 제외, 끝점을 포함한 직전 2초 이동평균(창이 다 찬 뒤), envelope 밖으로 같은 방향 5초 이상, 결측은 끊는다, 구간 평균은 원본 값. 전달 묶음 `sensor-segment-validation.json`과 같다 — CURRENT 600 시리즈 가운데 L006 head_pressure 101.1–210.9초 LOW · L007 slurry_flow 14–309초 LOW 둘뿐(정상 LOT 오탐 0, `SegmentDetectorTest`). 명세(「프론트와 합의 전 anomalySegments 추가 금지」)대로 공개 API에는 넣지 않았다 — 지금 화면 구간 계산은 프론트 몫이다
- 설비 맵용 마스터 · 좌표 API는 계약에 없어 만들지 않았다. 429 · 503은 구현하지 않았다
- 실행 중이던 작업(QUEUED · RUNNING)은 재시작해도 시뮬레이터가 다시 잡지 않는다(외부 Worker는 QUEUED를 다시 가져간다)

### 요청 브리프 (2026-10-20) — 계약에 없는 필드

확인 요청을 받는 사람(다른 공정 담당 · 설비정비팀)은 CMP 사건의 분석 결과(P07)를 볼 권한이 없다(의도한 권한). 그래서 요청 문구 한 줄과 근거 ID만으로는 「왜 내 장비가 의심받는지 · 얼마나 벗어났는지 · 어떤 원인을 생각하는지」를 알 수 없었다. 제안 payload에 **요청 브리프**를 붙여 승인 화면과 확인 요청 화면까지 그대로 전달한다.

| 위치 | 더한 필드 |
|---|---|
| 제안 payload (`POST /internal/jobs/{id}/proposals`, `GET /api/approvals/{id}`의 `payload`) | `brief` · `causeIds` · `equipmentId` — 예전에는 `normalize`가 담당자 · 질문 · 대상 공정 · 기한 말고는 버려 causeIds가 저장되지 않았다 |
| 확인 요청 (`GET /api/check-requests/{id}`, 내부 `requestView`) | `brief` · `causeIds` · `equipmentId` · `requestType` (jsonb 컬럼 `check_request.brief` · `cause_ids`) |
| run 증거 (`/internal/runs/{runId}`) | `normalVariationSigma` (2.5) — 「정상 변동 밖」 판정 기준. 브리프의 `threshold`가 이 값을 옮긴다 |

`brief` 모양(`domain/checkrequest/RequestBrief`가 검증): `{reason, deviations[{runId, equipmentId, processCode, metric, value, unit, baselineMean, z, threshold, thresholdKind(ABS_Z_SAME_PM · REFERENCE_BAND), withinNormal, setpoint?, deviationFromSetpointPct?}], suspectedCauses[{causeId, candidate, confirmed:false, evidenceToCheck, limits}], relatedRecords[{evidenceId, summary, rootCauseConfirmed?}], askFor[]}`

- **승인 해시에 들어간다** — 브리프는 payload 안이라 `actionHash`(actionType · incidentId · payload의 정규 JSON SHA-256)가 덮는다. 승인자는 받는 사람이 볼 내용을 그대로 보고 승인하고, 브리프 한 글자만 달라도 해시가 다르다(`ACTION_HASH_MISMATCH`). 흐름(승인은 기록만 → 재개된 Agent가 승인 ID로 실행 → 저장본으로 한 번 실행)은 그대로다
- **원인 확정이 아니다** — `suspectedCauses[].confirmed`는 항상 false(true면 422 `INVALID_BRIEF`), 정비 이력은 `rootCauseConfirmed` 값을 그대로 둔다
- **숫자는 계산 결과에서** — `ai/`는 그래프의 `request_brief` 노드가 도구 결과에서, 시뮬레이터는 숫자 없는 고정 예시 브리프(`AnalysisFactory.exampleBrief`)를 쓴다. 모델(규칙 모델 포함)이 쓰는 것은 `reason` 문장뿐이고, Harness가 그 숫자 · 근거 ID · 확률 · 설정값 처방을 검사한다
- 수동 제안(시드 EV-1023 등)은 brief가 없을 수 있다 — 저장 · 화면 모두 null로 둔다
- 받는 사람은 brief에 든 것만 본다. 사건 분석 결과 · 센서 원본 조회 권한은 그대로 없다(`InternalApiTest.requestBriefIsHashedCopiedAndShownToRecipient`)

### DB 스키마 (2026-10-20)

**외래 키 — String ID 필드는 두고, 같은 컬럼에 읽기 전용 연관을 붙여 제약만 건다.** 엔티티를 `@ManyToOne`으로 갈아엎으면 서비스 · DTO 코드가 ID 대신 객체를 다루게 되고, 지연 로딩 · 직렬화를 손봐야 공개 · 내부 API 응답 모양을 지킬 수 있다. 대신 `@ManyToOne(fetch = LAZY) @JoinColumn(insertable = false, updatable = false, foreignKey = @ForeignKey(name = …))`를 `xxxRef` 필드로 하나 더 두었다(getter 없음). 값은 지금처럼 `incidentId` 같은 String으로 쓰고 읽고, Hibernate는 이 연관을 보고 이름 붙은 외래 키를 만든다 — `schema.sql`을 따로 두지 않아도 된다. `@JoinColumn(name)`은 물리 이름(`incident_id`)이 아니라 필드 이름(`incidentId`)을 써야 한다(같은 컬럼을 두 이름으로 보면 `DuplicateMappingException`).

| 테이블.컬럼 | → 참조 | 제약 이름 | 비고 |
|---|---|---|---|
| `job.incident_id` | `incident` | `fk_job_incident` | |
| `analysis_result.incident_id` · `job_id` | `incident` · `job` | `fk_analysis_result_incident` · `fk_analysis_result_job` | |
| `approval.incident_id` · `job_id` | `incident` · `job` | `fk_approval_incident` · `fk_approval_job` | `job_id`는 수동 제안이면 null |
| `check_request.incident_id` · `approval_id` | `incident` · `approval` | `fk_check_request_incident` · `fk_check_request_approval` | `approval_id`는 null 허용. **`fk_check_request_approval`은 `schema.sql`이 건다** — 엔티티 관계로 두면 checkrequest가 approval 엔티티를 품어 패키지 순환이 생긴다(2026-10-20 리팩터링). Hibernate가 표를 만든 뒤(`defer-datasource-initialization`) 없을 때만 추가한다 |
| `check_reply.incident_id` · `request_id` | `incident` · `check_request` | `fk_check_reply_incident` · `fk_check_reply_request` | |
| `incident_action.incident_id` | `incident` | `fk_incident_action_incident` | |
| `report.incident_id` | `incident` | `fk_report_incident` | |
| `metrology.run_id` · `run_feature.run_id` | `route_run.run_id` | `fk_metrology_run` · `fk_run_feature_run` | `route_run.run_id`는 PK가 아니라서 유니크 제약 `uk_route_run_run_id`를 먼저 건다 |
| `route_run.upstream_run_id` | `route_run.run_id` | `fk_route_run_upstream` | 자기 참조. 팩 `process-runs.json`은 1,017 run 모두 앞 공정 run이 먼저 나와 시드 순서가 어기지 않는다 |
| `incident_timeline.incident_id` · `action_verification.action_id` | `incident` · `incident_action` | Hibernate 자동 이름 | 원래 있던 `@ElementCollection` |

외래 키를 **걸지 않은** 참조와 이유:
- 대상 테이블이 바뀌는 참조 — `job.result_ref_*` · `waiting_ref_*`(분석 결과 · 승인 · 확인 요청 · 보고서), `approval.execution_ref_*`, 타임라인 `ref_*`. 하나의 컬럼이 여러 테이블을 가리켜 외래 키 하나로 표현할 수 없다(종류 컬럼 `*_type`과 함께 서비스가 검증한다)
- `incident.wafer_ids` · `run_ids` — JSON 배열이고, 과거 사건(EV-1018~1023)은 웨이퍼 · run이 없다
- `check_request.waiting_job_id`(→ job) · `incident_action.approval_id`(→ approval) — 실제 관계지만 이번 작업의 관계 목록 밖이라 두었다(걸 때는 같은 방식으로 한 줄씩 더하고 테스트로 순서를 확인한다)
- 시드 순서: 사용자 → 문서 → run(IDENTITY라 한 건씩 차례로 INSERT) → 계측 · 특징 → 사건 → 작업 · 결과 · 승인. 삭제 경로는 없다

**JSON 컬럼 — `AttributeConverter`(JSON 문자열 → varchar)를 `@JdbcTypeCode(SqlTypes.JSON)`으로 바꿨다.** Hibernate 7.4가 Postgres에서 `jsonb`를 고른다. `common/JsonConverters`는 지웠다. 목록 안의 값으로 찾는 조회는 jsonb 포함 연산자(`@>`)로 DB가 거른다 — 공정 담당자(`process_scopes`, `UserAccountRepository.findEngineersByProcess`), 웨이퍼가 든 사건(`wafer_ids`, `IncidentRepository.existsByProcessCodeInAndWafer`), run별 사건(`run_ids`를 `jsonb_array_elements_text`로 펼침, `findByRunIds`). 전에는 전부 읽어 Java에서 걸렀다. 네이티브 쿼리는 테이블 앞에 `{h-schema}`를 붙여 기본 스키마를 따른다. 상태 · 장비처럼 조건에 쓰는 값은 일반 컬럼 그대로다.

| 테이블 | jsonb 컬럼 |
|---|---|
| `incident` | `wafer_ids` · `run_ids` · `sensor_ids` · `metrics` |
| `approval` | `payload` · `evidence_ids` |
| `analysis_result` | `observed_facts` · `candidate_causes` · `evidence_refs` · `next_actions` · `limitations` |
| `report` | `sections` · `evidence_ids` |
| `check_request` · `check_reply` · `incident_action` · `action_verification` · `change_record` | `evidence_ids` |
| `knowledge_document` | `sections` |
| `job` | `input` · `metrics`(외부 Agent의 작업 요약 — 모델 호출 · 토큰 · 실제 응답 모델 · 도구 · 재시도 · Harness 거부 · ms, 재시도 전 시도는 `previousAttempts`. agent-event의 선택 필드 `metrics`에서 받는다. 공개 API에는 내지 않는다) |
| `user_account` | `process_scopes` |

예전 변환기는 DB 값이 null이면 빈 목록 · 맵으로 읽었다. JSON 타입은 null을 그대로 돌려주므로, 생성자를 거치지 않고 Builder로 만드는 엔티티(분석 결과 · 회신 · 정비 이력 · 지식 문서 · 효과 확인)는 null이면 빈 값을 돌려주는 getter를 직접 두었다(나머지는 생성자가 이미 null을 빈 값으로 바꾼다).

**컬럼 이름 — `metrology.cd_value` → `value_a`.** 옛 PHOTO 공정(CD 계측) 시절 이름이었고 지금은 위치 두께(Å)가 들어 있다. API의 JSON 필드 이름(`thicknessA` 등)은 계약을 따르므로 그대로다.

`domain/SchemaTest`가 테스트 Postgres에서 외래 키 14개 · jsonb 컬럼 26개(json 0개) · jsonb 연산자 · `value_a`를 확인하고, 없는 run을 가리키는 계측 INSERT가 거절되는지 본다.

### 검토·점검·조치와 분석 항목 연결

`POST /api/incidents/{id}/actions`의 선택 필드 `relatedSource`로 연결한다. 예: `{"type":"CAUSE","refId":"분석 resultId","itemId":"causeId"}`. `NEXT_ACTION`은 `itemId`에 분석의 `nextActions` 인덱스 문자열을, `PROPOSAL`은 `refId`에 승인 제안 ID를 넣고 `itemId`는 생략한다. 필드를 생략하거나 null이면 별도 기록이다. 기존 요청은 그대로 호환된다.

서버가 같은 사건의 원본을 검증하고 제목·내용의 스냅샷을 `incident_action.related_source` JSONB에 저장한다(기존 DB에는 nullable 컬럼이 추가됨). 연결은 제안 승인이나 원인 확정을 의미하지 않는다. `actionType`으로 `REVIEW`(검토), `INSPECTION`(점검), `MANUAL_MAINTENANCE`(조치), `OTHER`를 구분하고 같은 항목에 여러 기록을 남길 수 있다. 각 기록의 확인·효과 이력은 기존 verification API를 사용한다. 관련 정보는 내부 조치 조회와 보고서 맥락에도 포함된다.

과거 분석의 전체 항목은 `GET /api/incidents/{id}/analysis-results?resultId=...`로 조회한다. 같은 사건의 결과만 반환한다.

### 사건 조회와 운영 권한

세션의 `processScopes`는 조회 범위, `investigationProcessScopes`는 사건 운영 범위다. 공정 담당자는 자기 공정, 관리자는 부여받은 모든 공정에서 분석·질문·제안/승인·조치·보고서를 변경할 수 있다. 기존 계정 정책의 `설비정비팀`은 세 공정을 조회하지만 사건 운영 범위는 비어 있다. 정비 및 타 공정 담당자는 자신에게 배정된 확인 요청에 회신한다. 타 공정 사건 전체를 추가로 공개하지 않고 받은 요청의 사건 정보·브리프로 협업한다. 서버의 모든 사용자 사건 쓰기는 `loadForWrite`로 검사하며 내부 Agent 작업·배정 요청 회신 경로와 분리한다.


## 확인·변경 계획·다른 팀 요청

업무는 사용자가 분석 결과의 「분석에서 업무 제안」으로 등록한다(`POST /api/incidents/{id}/work-items`). 결과 이벤트에 `workSuggestions`가 오면 같은 `PROPOSED` 업무로 저장하지만, 이 필드는 호환용이고 현재 Agent는 보내지 않는다(Agent의 요청은 위 「승인 뒤 실행」 흐름이다). 담당자가 채택하기 전에는 할 일·요청·수행 기록이 생기지 않는다. 채택하지 않은 제안은 `DECLINED`로 보존한다.

| 분류 | 업무 코드 | 채택 이후 |
|---|---|---|
| 내 확인 업무 | REVIEW_SENSOR_DATA, REVIEW_PROCESS_HISTORY, COMPARE_METROLOGY | READY → 담당자가 수행 결과 기록 → COMPLETED |
| 관리자 승인 | CHANGE_SETPOINT, CHANGE_RECIPE, REPLACE_COMPONENT, RESTART_EQUIPMENT, REVIEW_REQUIRED | WAITING_MANAGER → APPROVED 또는 REJECTED. 승인 후 담당자가 실제 결과를 기록해야 COMPLETED |
| 다른 팀 요청 | EQUIPMENT_INSPECTION, FACILITY_INSPECTION, UPSTREAM_CHECK | 수신 담당자 선택·발송 → REQUESTED → 담당자 회신 → COMPLETED |

서버의 `WorkOperation` 정책이 경로를 결정한다. AI는 업무 코드를 제안하며 승인 여부를 결정하지 않는다. 확인·이관 제목에 변경 표현이 섞이면 `REVIEW_REQUIRED`로 올린다. 문구 검사만으로 모든 자연어의 변경 의도를 판별할 수는 없으므로 담당자는 구체적인 업무 종류와 내용을 검토한다. 이 서비스는 실제 설비 제어 명령을 실행하지 않는다.

현재 전체 관리자는 기존 `manager.choi` 계정을 사용한다. 관리자 후보는 MANAGER 역할과 사건 공정 권한으로 찾으며, 담당 공정 범위가 좁은 관리자를 우선한다. 따라서 추후 CMP 등 공정별 관리자를 등록하면 해당 공정 승인에 우선 배정된다. 지정 관리자만 승인·반려 가능하며 자기 승인은 금지한다. 반려에는 사유가 필요하다.

`UPSTREAM_CHECK`는 PM 번호 추정 대신 해당 웨이퍼의 실제 공정 경로에서 대상 설비를 찾는다. 장비·시설 요청은 등록된 정비 담당자에게만 보낼 수 있다. 회신과 기존 중단 작업이 연결되어 있으면 기존 RESUME을 유지한다. 새 업무 요청은 분석이 진행 중이지 않을 때 회신 내용을 반영하는 새 DEEP 분석을 시작한다. 이미 분석 중이면 회신을 저장하고 후속 분석 조회 자료로 제공한다.

### 추가 공개 API

- `GET /api/work-policy`: 업무 종류·경로·등록 담당자 목록
- `GET /api/incidents/{id}/work-items`: 사건의 제안과 채택 업무
- `GET /api/work-items?box=mine|approval`: 내 업무 또는 지정 관리자 승인 대기
- `POST /api/incidents/{id}/work-items`: 분석 결과 근거로 제안 등록
- `POST /api/work-items/{id}/adoptions`: 채택·관리자 요청·다른 팀 발송
- `POST /api/work-items/{id}/decisions`: DECLINE / APPROVE / REJECT
- `POST /api/work-items/{id}/completions`: 직접 확인한 실제 수행 결과 기록

쓰기에는 세션·CSRF·Idempotency-Key·버전 검사를 적용한다. 다른 공정 담당자와 정비 담당자의 사건 변경 권한은 기존 제한을 유지한다. 관리자 승인과 실제 수행 기록은 별도 상태로 관리하며, 보고서에는 미수행 업무가 실행 결과로 포함되지 않는다.


### 회신·중복 제안·시연 계정 전환

`GET /api/incidents/{id}/check-requests`는 사건 조회 공정 권한을 확인하고, 기존 요청을 포함해 요청·회신 전체를 반환한다. 프론트의 검토·조치 화면은 이 원본의 REPLIED 상태와 회신 내용으로 완료 표시를 만든다. 회신 완료와 사건 해결·효과 검증은 구분한다.

새 업무는 저장 시 사건 잠금 안에서 기존 업무와 비교한다. 같은 업무 종류의 동일 제목, 공백·문장부호만 다른 제목, 대상·센서·숫자가 같고 문구가 매우 유사한 제안은 기존 업무를 재사용한다. 다른 센서나 변경 수치가 다른 제안은 별도 업무로 보존한다. 다른 팀 업무는 기존 요청 제목과도 비교한다. 기존 이력은 삭제하지 않는다. 자연어 의미가 크게 다른 표현의 중복까지 문구 검사만으로 보장하지는 않는다. Agent는 업무 목록을 보지 않는다 — 조회할 수 있는 것은 확인 요청 · 회신(`get_check_requests`)이다.

새 보고서에는 DB 기록에서 요청자, 수신 담당자, 발송 검토자, 회신자, 수행자, 지정 승인자 및 시각을 덧붙인다. 실제 LLM 보고서에도 서버에서 이 이력을 붙인다. 확정된 과거 보고서는 유지하므로 새 이력이 필요하면 새 초안을 생성한다.

시연에서만 `FAB_DEMO_PERSONA_SWITCH=true`를 설정하면 로그인 이후 `GET/POST /api/auth/demo-personas`를 사용한다. 최초 로그인과 CSRF 검사는 유지하고, 전환할 때 세션 ID·인증 주체·권한을 바꾼다. 전환 가능 계정은 시드 계정 5개로 제한하며 비밀번호를 프론트에 전달하지 않는다. 기본값은 false다. 도커 배포에서는 `DEPLOY_DEMO_PERSONA_SWITCH=true`로 명시적으로 켠다. 공개 운영에서는 비활성화한다.

새 이상 알림은 로그인 사용자·선택 공정 기준으로 5초마다 사건 목록을 확인한다. 첫 목록은 기준으로 삼고 이후 새 사건만 닫기 전까지 토스트에 유지한다. 토스트의 로트·설비 링크는 `/dashboard?lot=...&pm=...`로 이동하며 화면을 자동 전환하지 않는다.

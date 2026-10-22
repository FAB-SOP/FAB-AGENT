# FAB CONTEXT 아키텍처 구조도

**Feature**: [spec.md](./spec.md) · **Created**: 2026-10-21 · **근거**: 저장소 현재 코드(`feat/architecture` 브랜치)

이 문서는 저장소에 이미 구현된 구조를 정리한 것이다. 사용자 관점의 시나리오와 요구사항은
[spec.md](./spec.md)에 있다.

**표기**: 실선 = 현재 구현(As-Is). 점선 테두리 · 「예정」 = 팀 결정 대기 중인 목표 구조(To-Be) — 구현 약속이 아니다.

---

## 1. 기술 아키텍처 구조도

![FAB CONTEXT 기술 아키텍처](images/tech-architecture.png)

계층(프레젠테이션 · 애플리케이션/AI · 데이터/외부 · 빌드/테스트)별 기술 스택과 연결 프로토콜을 한 장에 그린 그림이다
(원본 `images/src/tech.html`, `python3 images/src/build.py`로 다시 생성). 아래 1.1–1.6은 같은 내용을 Mermaid · 표로 자세히 적은 것이다.

### 1.1 컴포넌트 · 런타임 배치

```mermaid
flowchart LR
  subgraph Client["사용자 브라우저"]
    WEB["fab-context-web<br/>React 19 · TypeScript · Vite 7<br/>TanStack Query · React Router 7<br/>ECharts · Tailwind 4 · Zod"]
  end

  subgraph Edge["web 컨테이너 (127.0.0.1:8088)"]
    NGX["Nginx<br/>정적 자원 서빙<br/>/api/** 프록시<br/>/api/incidents/*/stream SSE(버퍼링 끔)"]
  end

  subgraph Backend["backend 컨테이너 (8090)"]
    SPRING["fab-context-api<br/>Java 21 · Spring Boot 4.1<br/>Spring Security(세션 + CSRF)<br/>JPA/Hibernate 7 · springdoc"]
    PUB["공개 API /api/** (41 ops)<br/>프론트 계약"]
    INT["내부 API /internal/**<br/>팀 계약 · Bearer 서비스 토큰"]
    SIM["simulator<br/>AGENT_MODE=simulator일 때 대역 Agent"]
    SPRING --- PUB
    SPRING --- INT
    SPRING --- SIM
  end

  subgraph AgentSide["fab-agent (Python 3.12 · uv)"]
    BMCP["business-mcp :9101<br/>FastMCP · 업무 MCP<br/>읽기 12 · 변경 3(승인 ID 필수)"]
    RMCP["rag-mcp :9102<br/>FastMCP · 지식 MCP<br/>원문 100 · 점검 문서 15 · 사례 7"]
    WRK["agent (fab-worker)<br/>LangGraph + Harness<br/>OpenAI gpt-4.1-mini / 대체 gpt-4o-mini<br/>또는 규칙 모델(rule-policy-v2)"]
  end

  subgraph Data["데이터 계층"]
    PG[("PostgreSQL 16 + pgvector<br/>schema backend · agent · knowledge")]
    PACK[/"데이터 팩 v2 (읽기 전용 마운트)<br/>run · 계측 · 특징 · 관리한계 · 정비 이력<br/>원인 매트릭스 · 원시 시계열 gz"/]
    RAG[/"실제 원문 RAG 조각<br/>source-chunks.jsonl (읽기 전용)"/]
  end

  OPENAI(["OpenAI API<br/>Chat · text-embedding-3-small"])
  DET(["이상 탐지 서비스<br/>X-Service-Token"])

  WEB -->|HTTPS · 세션 쿠키 FABSESSION · X-CSRF-TOKEN| NGX
  NGX -->|/api/**| PUB
  DET -->|POST /api/events/anomalies| PUB
  WRK -->|GET /internal/jobs · claims · proposals · events| INT
  WRK -->|MCP streamable HTTP| BMCP
  WRK -->|MCP streamable HTTP| RMCP
  BMCP -->|/internal/** 만 호출| INT
  SPRING -->|JPA| PG
  SPRING -->|파일 읽기| PACK
  SPRING -->|파일 읽기| RAG
  WRK -->|체크포인트| PG
  RMCP -->|벡터 색인 · 검색| PG
  RMCP -->|색인 원본| PACK
  RMCP -->|색인 원본| RAG
  WRK -.->|FAB_LLM=openai| OPENAI
  RMCP -.->|EMBEDDER=openai| OPENAI

  KAFKA["예정 · Kafka 작업 · 이벤트 토픽<br/>(Worker 소스 코드만 있음, Spring 발행 없음)"]
  SAUTH["예정 · 서비스 간 인증 방식<br/>(현재 고정 Bearer 토큰)"]
  EXT["예정 · 외부 공개 배포<br/>도메인 · HTTPS · 운영 인증정보"]
  SPRING -.->|예정: 작업 발행| KAFKA
  KAFKA -.->|예정: WORKER_SOURCE=kafka| WRK
  SAUTH -.- INT
  EXT -.- NGX

  classDef planned stroke-dasharray: 5 5,fill:none
  class KAFKA,SAUTH,EXT planned
```

### 1.2 기술 스택 요약

| 계층 | 컴포넌트 | 기술 | 비고 |
|---|---|---|---|
| 프레젠테이션 | `fab-context-web` | React 19, TypeScript 5.9, Vite 7, TanStack Query 5, React Router 7, react-hook-form + Zod, ECharts 6, Tailwind 4, Vitest | 화면: 대시보드 · 이상 조사 · 확인/정비 요청 · 제안 승인 · 보고서 · 참고 문서 |
| 에지 | Nginx | 정적 자원 · `/api` 리버스 프록시 · SSE 무버퍼링 | 배포 구성(`compose.web.yaml`)에서만 |
| 업무 backend | `fab-context-api` | Java 21, Spring Boot 4.1, Spring Security, JPA/Hibernate 7(jsonb), springdoc OpenAPI, Gradle, Testcontainers | 유일한 backend. 공개 API · 내부 API · 승인 · 시드 · 시뮬레이터 |
| 업무 MCP | `fab.business.server` | FastMCP 4, httpx | Spring `/internal/**` 어댑터(규칙 · DB 없음) |
| 지식 MCP | `fab.rag` | FastMCP 4, pgvector, OpenAI 임베딩 / 해시 n-gram 임베딩 | 증분 색인(chunkId · contentHash), 시점 필터 |
| Agent | `fab.agent` | LangGraph 1.x, LangChain, langchain-openai, langgraph-checkpoint-postgres | Harness 8요소(지침 · 도구 · 루프 · 맥락 · 상태 · 가드 · 복구 · 관측) |
| 데이터 | PostgreSQL 16 + pgvector | 스키마 `backend` · `agent` · `knowledge` | 단일 DB, 스키마 분리 |
| 데이터 원본 | 데이터 팩 v2 · 원문 RAG | JSON · JSONL · gz | `data/build_pack_v2.py`로 결정적 조립 |
| 외부 | OpenAI | Chat Completions · Embeddings | 선택. 없으면 규칙 모델 · 해시 임베딩 |
| 실행 · 배포 | Docker Compose v2 | `fab-agent/compose.yaml` + `compose.web.yaml`(`scripts/deploy.sh`) | 웹만 외부 포트, 나머지는 내부망 |

### 1.3 Spring backend 내부 구조 (패키지 의존)

```mermaid
flowchart TB
  subgraph api["com.fabcontext.api"]
    AUTH["auth<br/>로그인 · 세션 · CSRF · 서비스 토큰 · AccessPolicy"]
    COMMON["common<br/>응답 봉투 · 오류 · 멱등 · 커서 · IncidentStreamHub(SSE)"]
    CONTRACT["contract<br/>AgentResultMapper · ResultShape<br/>ActionTypeNames · ReportSections"]
    subgraph domain["domain (공개 API /api/**)"]
      INC["incident"] --- EVT["events"]
      PD["processdata<br/>PackStore · ProcessEvidenceService · SegmentDetector"]
      AN["analysis"] --- JOB["job"]
      INV["investigation"]
      APR["approval<br/>ApprovedActionHandler(s)"]
      CR["checkrequest<br/>CrossProcess · Maintenance 실행기"]
      ACT["action"] --- REP["report"]
      KN["knowledge"] --- DASH["dashboard"] --- MON["monitoring"]
    end
    INTERNAL["internal (/internal/**)<br/>query · action · job · view · AgentEventService"]
    SIMU["simulator"]
    SEED["seed"]
  end
  INTERNAL --> domain
  SIMU --> domain
  SEED --> domain
  CR --> APR
  ACT --> APR
  REP --> APR
  INC -. ActiveJobLookup .-> JOB
  domain --> CONTRACT
  domain --> COMMON
```

- `domain`은 `internal` · `simulator` · `seed`를 모른다. `approval`은 실행 대상을 모르고 각 도메인의 실행기가
  `approval`을 안다(의존 역전). 패키지 순환 없음(`tools/java_pkgdeps.py`).

### 1.4 Agent 그래프 (LangGraph) · Harness

```mermaid
stateDiagram-v2
  [*] --> start
  start --> required_evidence: 사건 · 경로 · 단계별 run/계측 · 현재 run 특징 선조회
  required_evidence --> agent
  agent --> tools: 읽기 도구 호출 / 결과 제출
  agent --> request_brief: 제안 도구 호출
  agent --> finalize: 상한 도달(stopReason)
  agent --> agent: nudge
  request_brief --> tools: 도구 결과에서 브리프 작성 · Guard 검사
  tools --> approval_wait: 제안 저장 → APPROVAL_REQUIRED
  tools --> agent: 도구 결과 반환
  tools --> finalize: 결과 · 보고서 초안 확정
  approval_wait --> reply_wait: 승인 → 승인 ID로 변경 도구 실행 → WAITING_REPLY
  approval_wait --> agent: 거절 → 사유 반영
  reply_wait --> agent: 회신 수신(RESUME)
  finalize --> [*]: RESULT_READY / REPORT_DRAFT_READY
```

| Harness 요소 | 내용 |
|---|---|
| Instructions | 팩 지침 기반 프롬프트(BASIC · DEEP · REPORT 모드), 버전 해시를 결과에 기록 |
| Tools | 읽기 + 제안 · 제출만 노출, 변경 도구는 승인 ID로 Harness만 호출, 결과 6000자 제한 |
| Loop limits | 모델 판단 8회 · 같은 도구 3회 · 활성 120초 · LLM 30초 · MCP 10초 |
| Context | 필수 근거 선조회, 원시 시계열 대신 특징 · 요약, 6만 자 초과 시 오래된 결과 축약 |
| State | thread = 사건, Postgres `agent` 스키마 체크포인트 → Worker 재시작 후 재개 |
| Guardrails | 근거 ID · 확률 금지 · 원인 ID 15개 · nextStep 일치 · 제안 상한 2 · 반복 금지 · 브리프 숫자 검사 |
| Recovery | 일시 오류 2회 재시도 → 대체 모델 → RETRY_SCHEDULED(attempt<3) → JOB_FAILED, claim 409 → SKIPPED |
| Observability | trace_id 단위 node · tool · 지연 · 토큰, JSON 줄 파일 · LangSmith(선택) |

### 1.5 배포 토폴로지

| 구성 | 명령 | 노출 포트 | 모델 · 임베딩 기본값 |
|---|---|---|---|
| 로컬 배포(프론트 포함) | `./scripts/deploy.sh up -d --build --wait` | `127.0.0.1:8088`(web)만 | rule · hash (API 비용 없음) |
| 개발 compose | `cd fab-agent && FAB_LLM=rule docker compose up -d --build` | 8090(backend), 55432(Postgres) | rule · openai |
| Spring 단독 | `./gradlew bootRun` (+ compose postgres) | 8090 | 시뮬레이터 Agent |
| 프론트 개발 | `npm run dev -- --port 8771` | 8771 (Vite proxy → 8090) | — |
| 예정 · 외부 공개 | 미정 | 도메인 · HTTPS | 운영 인증정보 별도 설정 |

### 1.6 예정 항목 (To-Be, 팀 결정 대기)

| 항목 | 현재 상태 (As-Is) | 예정 (To-Be) | 결정 대기 사유 |
|---|---|---|---|
| 작업 전달 | Worker가 `GET /internal/jobs`를 폴링(`WORKER_SOURCE=http`) | Kafka 토픽으로 작업 · 이벤트 전달 — 소비자는 같은 `AgentEventService.handle()`로 넘긴다 | Kafka를 어디까지 쓸지 팀이 정하지 않았다. Worker 쪽 `sources/kafka.py`만 있고 Spring 발행 쪽은 없다 |
| 서비스 간 인증 | 고정 Bearer 토큰(`SERVICE_TOKEN`) · 이벤트 접수 `X-Service-Token` | 운영용 서비스 인증 방식 | 방식 잠정 보류(팀 계약 #20) |
| ID · 근거 형식 | 현재 이름(`E-RUN-*`, `E-SEG-*` 등) | 팀 확정 형식 | 다른 동료들이 확정 중 |
| 외부 배포 | 로컬 compose, 웹만 `127.0.0.1:8088` | 도메인 · HTTPS · 운영 인증정보 | 캡스톤 범위는 로컬 시연 |

---

## 2. 서비스 아키텍처 구조도

![FAB CONTEXT 서비스 아키텍처](images/service-architecture.png)

배포 단위(컨테이너) · 연결 프로토콜 · 저장소를 한 장에 그린 그림이다(원본 `images/src/service.html`, `python3 images/src/build.py`로 다시 생성).
실선 = 현재 구현, 점선 = 선택(OpenAI) · 예정(Kafka).

### 2.1 서비스 컨텍스트 — 행위자와 서비스 경계

```mermaid
flowchart TB
  DET(["이상 탐지 서비스<br/>(관리한계 이탈 이벤트)"])
  ENG["사건 담당 엔지니어<br/>(예: CMP 공정)"]
  UP["앞 공정 담당자<br/>(예: ETCH · CVD)"]
  MNT["설비정비팀"]
  MGR["운영 관리자"]

  subgraph FAB["FAB CONTEXT 서비스"]
    S1["사건 관리<br/>접수 · 목록 · 상세 · 타임라인 · 상태 전이"]
    S2["공정 데이터 조회<br/>LOT · run · 계측 · 센서 · 경로 · 정비 이력 · 모니터링"]
    S3["AI 조사<br/>기본 분석 · 추가 질문(심층) · 결과 이력"]
    S4["제안 · 승인<br/>승인함 · 결정 · 승인된 변경 실행"]
    S5["협업 요청<br/>앞 공정 확인 · 정비 요청 · 회신"]
    S6["조치 · 효과 기록"]
    S7["보고서<br/>초안 · 편집 · 확정 · 내보내기"]
    S8["참고 지식<br/>기술 원문 · 점검 문서 · 과거 사례"]
    S9["대시보드 · 실시간 알림"]
  end

  DET --> S1
  ENG --> S1 & S2 & S3 & S4 & S6 & S7 & S8 & S9
  UP --> S5
  MNT --> S5
  MGR --> S1 & S4 & S9
  S1 --> S3
  S3 --> S4
  S4 --> S5
  S4 --> S7
  S5 --> S3
  S6 --> S7
  S3 --> S8
  S3 --> S2
```

### 2.2 서비스 구성 · 책임

| 서비스 | 책임 | 주 사용자 | 공개 API 영역 | Agent 쪽 대응 |
|---|---|---|---|---|
| 사건 관리 | 이상 이벤트로 사건 생성(중복 eventId는 기존 사건), 상태 OPEN → INVESTIGATING ⇄ WAITING_REPLY → ON_HOLD/CLOSED(승인 대기는 작업 상태 WAITING_APPROVAL) | 엔지니어 · 관리자 · 탐지 서비스 | incidents · events | `get_incident` · `list_incidents` |
| 공정 데이터 | CVD → ETCH → CMP 경로의 run · 5위치 계측 · 특징 · 관리한계 · 센서 시계열 · 정비 이력. 서버 계산 증거(R1 · OOS · z · 참고 범위) | 엔지니어 | processdata · monitoring | `get_wafer_lineage` · `get_run_and_metrology` · `get_run_features` · `get_sensor_trace` · `get_frozen_limits` · `get_change_events` |
| AI 조사 | BASIC(자동) · DEEP(질문) · RESUME(승인/회신 후) · REPORT 작업, 결과를 사실 · 가설 · 한계로 저장 | 엔지니어 | investigation · analysis · job | LangGraph Worker |
| 제안 · 승인 | 제안 저장(받는 사람 검증 · 해시 · 만료), 사람 결정, 승인된 변경의 1회 실행 | 엔지니어 · 관리자 | approvals | 제안 도구 · 변경 도구(승인 ID) |
| 협업 요청 | 앞 공정 확인(CROSS_PROCESS) · 정비(MAINTENANCE) 요청과 브리프 전달, 회신 시 조사 재개 | 앞 공정 담당 · 설비정비팀 | check-requests | `create_cross_process_check_request` · `create_maintenance_request` · `get_check_requests` |
| 조치 · 효과 | 실제 수행한 검토 · 점검 · 정비 기록과 효과 확인, 분석 항목과 연결 | 엔지니어 | actions | `get_action_history` |
| 보고서 | 요청 시 초안 생성, 다섯 섹션 편집, 확정 스냅샷, Markdown 내보내기 | 엔지니어 | reports | `get_report_context` · `confirm_report` |
| 참고 지식 | 실제 원문 · 팀 점검 문서 · 가상 사례 검색 | 엔지니어 · Agent | knowledge | `search_technical_documents` · `search_applicable_playbooks` · `search_past_cases` |
| 대시보드 · 알림 | 공정별 집계, 사건별 SSE 이벤트(job · 결과 · 승인 · 요청 · 회신 · 보고서) | 전체 | dashboard · stream | — |

### 2.3 핵심 업무 흐름 — 이상 접수에서 보고서 확정까지

```mermaid
sequenceDiagram
  autonumber
  actor DET as 이상 탐지 서비스
  actor ENG as 담당 엔지니어(CMP)
  participant BE as Spring backend
  participant W as Agent Worker
  participant BMCP as 업무 MCP
  participant RMCP as 지식 MCP
  actor UP as 앞 공정 담당자(ETCH)

  DET->>BE: 이상 이벤트 접수
  BE-->>ENG: 새 사건 알림(화면이 5초마다 조회)
  W->>BMCP: 사건 · 경로 · run · 계측 조회
  BMCP->>BE: /internal/** (서버 계산 수치)
  W->>RMCP: 점검 문서 · 과거 사례 검색
  W->>BE: 기본 분석 결과(사실 · 가설 · 한계)
  ENG->>BE: 추가 질문
  W->>BE: ETCH 담당자 확인요청 제안(브리프 포함)
  ENG->>BE: 요청 발송 승인
  W->>BMCP: 변경 도구(승인 ID)
  BMCP->>BE: 저장된 요청 1회 실행
  BE-->>UP: ETCH-PM2 제거량 확인요청 + 브리프
  UP->>BE: 점검 결과 회신
  W->>BE: 회신 반영 결과
  ENG->>BE: 보고서 요청 · 확정
```

### 2.4 신뢰 경계 · 권한 모델

| 경계 | 인증 | 권한 |
|---|---|---|
| 브라우저 → 공개 API | 세션 쿠키 `FABSESSION` + CSRF 토큰, 쓰기에 `Idempotency-Key` | `processScopes`(조회) · `investigationProcessScopes`(사건 운영) · MANAGER(상태 전이) · 요청 수신자(회신) |
| 탐지 서비스 → 이벤트 접수 | `X-Service-Token` → ROLE_SERVICE | 이벤트 접수만 |
| Worker · 업무 MCP → 내부 API | `Authorization: Bearer` 서비스 토큰 → ROLE_AGENT (예정: 운영용 서비스 인증으로 교체, 1.6) | 읽기 · 작업 처리 · 제안 저장 · 승인 ID 기반 실행만 |
| Agent → 외부 모델 | `.env`의 API 키(이미지 · 로그 미포함) | 선택 사항 |
| 정답 · 저작물 | 어떤 컨테이너에도 마운트하지 않음 | 평가 스크립트만 실행 중 읽음 |

---

## 3. 유스케이스별 시퀀스 다이어그램

[spec.md](./spec.md)의 사용자 시나리오(US1–US6)마다 핵심 메시지만 그린다. 공개 API는 `/api/**`(세션 + CSRF),
내부 API는 `/internal/**`(서비스 토큰)이다. Agent Worker는 업무 MCP · 지식 MCP 도구로만 데이터를 읽고, 업무 MCP는 Spring 내부 API만 부른다.
백엔드는 Worker에게 작업을 보내지 않는다 — Worker가 2초마다 `GET /internal/jobs`로 QUEUED 작업을 가져가 확보(claim)한다(Kafka는 예정, 1.6).
이 시작 과정은 3.1에만 그리고, 3.2–3.4의 DEEP · RESUME · REPORT 작업도 같은 방식으로 시작된다. 멱등 키 같은 공통 처리는 생략했다(2.4 참고).

### 3.1 US1 — 이상 사건 자동 접수와 기본 분석

```mermaid
sequenceDiagram
  autonumber
  participant DET as 이상 탐지 서비스
  participant BE as Spring backend
  participant W as Agent Worker
  participant BMCP as 업무 MCP
  participant RMCP as 지식 MCP
  actor ENG as 담당 엔지니어

  DET->>BE: POST /api/events/anomalies
  BE-->>DET: 202 사건 생성 + 기본 분석 작업(동시에 대기열)
  par 엔지니어 알림 (5초 주기 조회)
    ENG->>BE: GET /api/incidents?processCode=CMP
    BE-->>ENG: 새 사건 → 화면 알림
  and AI 기본 분석
    W->>BE: GET /internal/jobs (2초 주기 — QUEUED 작업 조회)
    BE-->>W: BASIC 작업
    W->>BE: POST /internal/jobs/{jobId}/claims (작업 확보, 중복이면 409)
    W->>BE: GET /internal/jobs/{jobId}/context (질문 · 범위 · analysisAsOf)
    W->>BMCP: get_incident · get_wafer_lineage · get_run_and_metrology · get_run_features
    BMCP->>BE: GET /internal/** (숫자는 서버 계산)
    W->>RMCP: search_applicable_playbooks · search_past_cases
    W->>BE: RESULT_READY
  end
  ENG->>BE: 알림 클릭 → GET /api/incidents/{id}/analysis-results
  BE-->>ENG: 관측 사실 · 원인 후보 · 한계 (근거 ID)
```

### 3.2 US2 — 추가 질문 · 제안 · 승인

```mermaid
sequenceDiagram
  autonumber
  actor ENG as 담당 엔지니어
  participant BE as Spring backend
  participant W as Agent Worker
  participant BMCP as 업무 MCP
  participant RMCP as 지식 MCP

  ENG->>BE: POST /api/incidents/{id}/questions
  W->>BMCP: get_sensor_trace · get_change_events · get_process_owner
  BMCP->>BE: GET /internal/**
  W->>RMCP: search_technical_documents
  W->>BE: 정비요청 / 앞 공정 확인요청 제안 — 받는 사람 검증
  BE-->>ENG: 발송 승인 대기 (요청 0건)
  alt 승인
    ENG->>BE: 요청 발송 승인 — POST /api/approvals/{id}/decisions
    W->>BMCP: create_maintenance_request / create_cross_process_check_request (승인 ID)
    BMCP->>BE: POST /internal/approved-actions/*
    BE->>BE: 저장된 payload만 1회 실행
    BE-->>ENG: 요청 1건 생성
  else 거절
    ENG->>BE: 요청 발송 거절
    W->>BE: 요청 없이 결과 정리
  end
```

### 3.3 US3 — 다른 공정 · 설비정비팀의 요청 확인과 회신

```mermaid
sequenceDiagram
  autonumber
  actor RCV as 받는 사람(앞 공정 · 설비정비팀)
  participant BE as Spring backend
  participant W as Agent Worker
  participant BMCP as 업무 MCP
  actor ENG as 담당 엔지니어

  RCV->>BE: GET /api/check-requests/{id}
  BE-->>RCV: 요청 브리프 (원 사건 분석은 403)
  RCV->>BE: 점검 결과 회신 — POST /api/check-requests/{id}/replies
  W->>BMCP: get_check_requests (회신 읽기)
  BMCP->>BE: GET /internal/incidents/{id}/check-requests
  W->>BE: 회신 반영 결과
  BE-->>ENG: 갱신된 결과
```

### 3.4 US4 — 조치 · 효과 기록과 보고서 확정

```mermaid
sequenceDiagram
  autonumber
  actor ENG as 담당 엔지니어
  participant BE as Spring backend
  participant W as Agent Worker
  participant BMCP as 업무 MCP

  ENG->>BE: POST /api/incidents/{id}/actions (조치 · 효과)
  ENG->>BE: POST /api/incidents/{id}/report-jobs
  W->>BMCP: get_report_context · get_action_history
  BMCP->>BE: GET /internal/incidents/{id}/report-context · /actions
  W->>BE: 보고서 초안(다섯 섹션)
  ENG->>BE: PATCH /api/reports/{id} 편집
  ENG->>BE: 보고서 확정 — POST /api/reports/{id}/confirmations
  BE-->>ENG: 확정 스냅샷 · Markdown 내보내기
```

### 3.5 US5 — 공정 모니터링과 참고 지식 탐색

```mermaid
sequenceDiagram
  autonumber
  actor ENG as 담당 엔지니어
  participant BE as Spring backend

  ENG->>BE: GET /api/dashboard
  ENG->>BE: GET /api/monitoring/lots/{lotId}/summary
  ENG->>BE: GET /api/monitoring/runs/{runId}/sensor-data
  BE-->>ENG: 계측 · 센서 시계열 (다른 공정은 403)
  ENG->>BE: GET /api/knowledge/documents?q=
  BE-->>ENG: 원문 · 점검 문서 · 가상 사례
```

### 3.6 US6 — 관리자의 사건 상태 관리

```mermaid
sequenceDiagram
  autonumber
  actor MGR as 운영 관리자
  participant BE as Spring backend

  MGR->>BE: POST /api/incidents/{id}/transitions ON_HOLD
  MGR->>BE: transitions INVESTIGATING
  MGR->>BE: transitions CLOSED
  BE-->>MGR: 200 (엔지니어가 시도하면 403)
```

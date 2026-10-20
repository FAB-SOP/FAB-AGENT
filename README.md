# FAB CONTEXT — 반도체 공정 이상 조사 · 협업 AI 서비스 (SKALA AI Agent Capstone)

CMP 두께 관리한계 이탈 같은 공정 이상을 **Agent가 데이터 · 문서 근거로 조사**하고, 다른 공정 담당자 · 설비정비팀에게 보낼 확인 요청을 **제안**하면, **사람이 승인**한 뒤에만 실행되는 서비스다. 모든 운전 데이터는 시연용 합성 데이터다.

```
React(프론트, 별도 저장소) ──/api/**──▶ Spring backend (fab-context-api)  ◀──/internal/**── 업무 MCP ◀─┐
                                          │  Postgres(backend 스키마)                              Agent Worker (LangGraph + Harness)
                                          │  데이터 팩 · 원시 시계열(파일)          지식 MCP(pgvector) ◀─┘   └ Postgres(agent 스키마: 체크포인트)
```

## 저장소 구성 (monorepo)

| 폴더 | 무엇 | 문서 |
|---|---|---|
| `fab-context-api/` | **Spring backend** — 공개 API 41개(프론트 계약) · 내부 API(Agent 전용) · 승인 · 확인 요청 · 보고서 · 모니터링 · 시뮬레이터 | [README](fab-context-api/README.md) |
| `fab-agent/` | **업무 MCP · 지식 MCP · 조사 Agent(LangGraph) · Harness**, compose(전체 실행), 평가 | [README](fab-agent/README.md) · [계약 변경](fab-agent/docs/contract-changes.md) |
| `data/` | 데이터 팩 v2 조립본 `fab-causal-pack-v2/`(dataVersion `causal-pack-v2-scenarios`)와 조립 스크립트 `build_pack_v2.py` | [README](data/fab-causal-pack-v2/README.md) |
| `FAB-실제문서-선별RAG-센서데이터-통합/` | 동료 납품 원본 — 데이터 팩 v1(`fab-causal-pack-v1`), 실제 원문 RAG 정책 · 출처 목록(`fab-rag-real-documents-v1`) — 원문 조각 · 정답은 공개 저장소에 없다(아래) | 폴더 안 README |
| `backend-handoff-v2/` | 프론트 전달 묶음 가운데 코드가 쓰는 것 — 프론트 계약 OpenAPI(`combined.openapi.json`, 계약 대조 기준), 응답 예시(`examples/`, 모니터링 테스트), 데이터 v2 CURRENT(`data/`, 팩 조립 입력) | — |
| `tools/` | `openapi_diff.py` — 서버 `/v3/api-docs`와 프론트 계약 대조 | — |

**공개 저장소에 없는 것** — 팀 내부 채널로 받아 **같은 경로에** 둔다(`.gitignore`가 계속 빼 준다)

| 무엇 | 경로 | 왜 | 없으면 |
|---|---|---|---|
| API 키 | `fab-agent/.env` (`.env.example`을 복사) | 비밀 | 규칙 모델로만 돈다 |
| 실제 원문 발췌 100조각 | `FAB-실제문서-…/fab-rag-real-documents-v1/rag/source-chunks.jsonl` | 출판자 저작물 — 공개 다운로드가 재배포 허가가 아니다(그 폴더 README 6절) | 지식 MCP의 기술 문서 검색 · 화면의 실제 원문 문서가 비고(팀 점검 문서 · 가상 사례는 그대로), 원문 테스트는 건너뛴다 |
| 원문 PDF | 같은 폴더의 `originals/` · `selected/` | 저작물 | 영향 없음(열람용) |
| 정답(평가자 전용) | `fab-causal-pack-v1/evaluation/` · `generate.py` · `validate.py` · `README.md`, `backend-handoff-v2/시나리오-문제-원인-조치.md`, `fab-agent/evaluation/ground_truth.json` | 평가 정답 · 생성기 잠재 원인 | `fab-eval`과 정답표 테스트만 건너뛴다. 실행 · 다른 테스트는 그대로 |
| 강의자료 | `강의자료/` | 저작물 | 영향 없음 |
| 설계 · 전달 문서 | `fab-flow-atlas/`, `fab-team-plan_draft/`(팀 계획 · 화면 시안 · 계약 원본 · 정답표), `backend-handoff-v2/`의 명세 · 검증 문서 | 팀 내부 문서 — 공개 저장소에는 실행 · 테스트에 쓰는 것만 둔다 | 영향 없음 |

정답은 저장소에 있든 없든 Agent 프롬프트 · RAG 색인 · 컨테이너에 넣지 않는다(조립본 `data/fab-causal-pack-v2`와 compose 마운트에는 없다).

## 실행 방법

### 준비물

| 도구 | 버전 | 용도 |
|---|---|---|
| Docker Desktop (compose v2) | 최신 | 전체 실행(권장) |
| JDK | 21 | Spring을 Docker 없이 실행 · 테스트 |
| [uv](https://docs.astral.sh/uv/) · Python | uv 0.11 · Python 3.12 | fab-agent 실행 · 테스트 |
| jq · curl | — | Spring 스모크 스크립트 |

```bash
git clone <원격 저장소 주소> fab && cd fab
cp fab-agent/.env.example fab-agent/.env      # 실제 모델을 쓸 때만 OPENAI_API_KEY를 채운다. 비워 두면 규칙 모델로 돈다
```

### 1) 한 번에 실행 — Docker (권장)

```bash
cd fab-agent
FAB_LLM=rule docker compose up -d --build              # Postgres · Spring · 업무 MCP · 지식 MCP · Agent Worker
docker compose --profile tools run --rm --no-deps smoke   # 전체 흐름 점검: 질문 → 제안 → 승인 → 실행 → 회신 → 보고서 (「실패 0건」)
```

| 주소 | 무엇 |
|---|---|
| http://localhost:8090/swagger-ui.html | 공개 API 문서(41개) |
| http://localhost:8090/api/** | 프론트가 붙는 공개 API — 개발 시 Vite proxy `/api` → 8090 |
| localhost:55432 | Postgres (`fab` / `fab`, DB `fab`) — `backend` · `agent` · `knowledge` 스키마 |

- `FAB_LLM=rule`은 키 없이 도는 **규칙 모델**(결정적 · 시연 · 점검용)이다. 실제 모델은 `.env`에 `OPENAI_API_KEY`를 넣고 `FAB_LLM=openai docker compose up -d`(기본 `gpt-4.1-mini`, 대체 `gpt-4o-mini`). `.env`의 `FAB_LLM`이 compose에도 전달되므로, 결정적으로 돌릴 때는 앞에 `FAB_LLM=rule`을 붙인다
- 데이터를 처음부터 다시 넣으려면(스키마 · 데이터 팩을 바꾼 뒤에는 반드시):
  ```bash
  docker compose stop backend agent business-mcp
  docker compose exec -T postgres psql -U fab -d fab -c "DROP SCHEMA backend CASCADE; DROP SCHEMA agent CASCADE;"
  FAB_LLM=rule docker compose up -d --build
  ```
- 끄기: `docker compose down` (데이터는 `pgdata` 볼륨에 남는다. 지우려면 `down -v`)

### 2) 로그인 계정 (비밀번호 모두 `fab1234!`)

| 계정 | 역할 · 공정 | 시연에서 |
|---|---|---|
| `cmp.park` | ENGINEER · CMP | 사건 EV-1006 · EV-1007 · EV-1009 담당, 질문 · 승인 |
| `etch.lee` | ENGINEER · ETCH | EV-1009 앞 공정 확인 요청을 받고 회신 |
| `mnt.jung` | 설비정비팀 · 전 공정 | 정비 요청(EV-1006 · EV-1007)을 받는다 |
| `cvd.kim` | ENGINEER · CVD | — |
| `manager.choi` | MANAGER · 전 공정 | 사건 상태 전이(보류 · 종료) |

로그인 순서: `GET /api/auth/csrf` → `POST /api/auth/login`(헤더 `X-CSRF-TOKEN`) → 세션 쿠키 `FABSESSION`.

### 3) Docker 없이 — 개발 · 테스트

```bash
# Spring만 (인메모리 H2, 띄울 때마다 새로 시드). 기본은 서버 안 시뮬레이터 Agent
cd fab-context-api
SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun                       # http://localhost:8090
./gradlew test                                                    # 테스트 (h2)
./scripts/smoke-test.sh http://localhost:8090                     # 실행 중인 서버에 curl 전체 흐름

# 실제 Agent를 붙여 돌리기 — Spring을 외부 Agent 모드로 띄운 뒤
SPRING_PROFILES_ACTIVE=h2 AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun
cd ../fab-agent && uv sync
uv run fab-demo --incident EV-1009        # 질문 → 제안 → 승인 → 회신 → 결과 → 보고서 (사람 역할 + Agent를 한 프로세스에서)
uv run fab-eval                           # 첫 판단을 정답표와 대조 — 평가자 전용 정답표가 있을 때만 (h2는 재기동하면 처음 데이터)
uv run pytest -q                          # Spring bootJar를 빌드해 띄우고 붙는다(JDK 21 필요)
```

`fab-demo` · `fab-eval`은 compose의 `agent` 컨테이너와 같이 띄우면 작업을 서로 가져가니 둘 중 하나만 쓴다.

### 4) 데이터 팩 다시 만들기 · 계약 대조

```bash
python3 data/build_pack_v2.py                     # v1 + v2 → data/fab-causal-pack-v2 (같은 입력이면 같은 바이트, 검사 22개)
python3 tools/openapi_diff.py http://localhost:8090   # 서버 OpenAPI ↔ 프론트 계약 차이 표
```

## 계약 원칙

- **공개 API = 프론트 계약**(`backend-handoff-v2/combined.openapi.json`), **내부 API · Agent 이벤트 = 팀 계약**(원본은 팀 내부, 테스트용 사본 `fab-agent/contracts/`), 둘 사이 변환은 Spring이 한다(예: 승인 종류 `CHECK_REQUEST` ↔ `CREATE_CHECK_REQUEST`)
- **승인 뒤 실행** — Agent는 제안만 한다. 사람이 승인하면 Spring은 기록만 하고, 재개된 Agent가 승인 ID로 MCP 변경 도구를 부르면 Spring이 저장본으로 한 번만 실행한다. 거절도 같은 흐름으로 재개된다
- 바뀐 계약은 [fab-agent/docs/contract-changes.md](fab-agent/docs/contract-changes.md)에 모은다

## 검증 현황 (2026-10-20)

| 점검 | 결과 |
|---|---|
| `fab-context-api` `./gradlew test` | 108개 통과 |
| `fab-context-api` `smoke-test.sh` (h2) | 107개 통과 |
| `fab-agent` `uv run pytest -q` | 29개 통과 |
| `fab-agent` `uv run fab-eval` (규칙 모델, 정답표가 있는 로컬) | 3/3 |
| compose 스모크 (Postgres, 재시드) | 실패 0건 |
| OpenAPI 대조 | 41개 operation 일치 — 차이는 `MAINTENANCE_REQUEST` 추가, comparisons 유지뿐 |

## 협업 규칙

- `.env`는 절대 커밋하지 않는다(루트 `.gitignore`). 키는 각자 `fab-agent/.env`에 둔다
- 원문 PDF · 강의자료는 커밋하지 않는다(저작물)
- 작업 하나가 위 검증을 통과하면 커밋한다. 커밋 메시지는 「무엇을 · 왜」를 한 줄로

# FAB CONTEXT — 반도체 공정 이상 조사 · 협업 AI 서비스 (SKALA AI Agent Capstone)

CMP 두께 관리한계 이탈 같은 공정 이상을 **Agent가 데이터 · 문서 근거로 조사**하고, 다른 공정 담당자 · 설비정비팀에게 보낼 확인 요청을 **제안**한다. 요청은 **사람이 승인한 뒤에만** 나간다. 공정 · 계측 · 센서 데이터는 모두 시연을 위해 만든 합성 데이터다.

![FAB CONTEXT 서비스 이용 흐름](docs/images/service-flow.png)

## 화면

| 공정 대시보드 — L009 OOC 확인 | 기본 분석 — 원인 후보와 근거 | 다른 팀 요청 — 회신 확인 |
|---|---|---|
| ![공정 대시보드](docs/images/screen-dashboard.jpg) | ![기본 분석](docs/images/screen-analysis.jpg) | ![요청 회신](docs/images/screen-reply.jpg) |

## 아키텍처

![FAB CONTEXT 기술 아키텍처](docs/images/architecture.png)

### 조사 Agent와 MCP

![조사 Agent Graph](docs/images/agent-graph.png)

조사 Agent는 LangGraph `StateGraph`다. 모델(LLM)은 도구를 고르고 결과를 쓰며, 실행 · 한도 · 승인 · 기록과 답 검사는 Harness 코드가 맡는다. 회신이 오면 사람이 추가 분석을 요청할 때 같은 분석을 이어 가고, 회신 대기 생략 설정이면 요청을 보낸 뒤 바로 정리한다.

청록 테두리의 `agent` · `tools`는 Phase 2에서 Harness의 검사 · 교정 로직을 보강한 노드다. 검사는 LLM이 아니라, 이번에 조회한 도구 결과 · 원문 글과 답 문장을 코드로 대조한다.

| 보강한 것 | 동작 |
|---|---|
| 데이터와 다른 내용 · 근거 없는 주장 | 조회 데이터와 반대인 단정(정상화 · 규격 이탈), 인용한 원문에 없는 관계는 한 번 돌려보내고, 다시 나오면 해당 문장만 빼고 채택 |
| 조회하지 않은 대상 | 도구 결과에 없는 로트 · 웨이퍼 · run을 말하면 반려 |
| 형식 실수 | 근거 ID 위치 · 줄여 쓴 ID · 반증된 1순위는 반려하지 않고 코드가 바로잡음 |
| 판단 한도 | 마지막 호출에서는 제출 도구를 강제해 빈 응답을 줄임 |
| 요청서 누락 | 원인 질문의 답에 요청서가 없으면 Harness가 초안을 냄 — 전송은 사람 승인 뒤 |

![MCP 구조](docs/images/mcp-structure.png)

Write 도구(확인 요청 · 정비 요청 · 보고서 확정)는 모델에게 보이지 않고, 사람이 승인한 뒤 Harness만 승인 ID와 멱등 키를 붙여 부른다. 업무 MCP의 Read 도구에는 사건의 지난 질문 · 답 · 회신 이력을 보는 `get_incident_history`가 있다. 자세한 것은 [ai/README](ai/README.md).

## 저장소 구성

| 폴더 | 무엇 | 문서 |
|---|---|---|
| `frontend/` | React 프론트 | — |
| `backend/` | Spring 백엔드 — 공개 API · Agent 전용 내부 API · 시뮬레이터 | [README](backend/README.md) |
| `ai/` | 업무 MCP · 지식 MCP · 조사 Agent · Harness, 개발용 compose, 평가 | [README](ai/README.md) |
| `data/` | 데이터 — 조립본 `fab-causal-pack-v2/`(실행에 쓰는 것) · 원본 팩 `fab-causal-pack-v1/` · 실제 원문 RAG `fab-rag-real-documents-v1/` · 프론트 전달 묶음 `backend-handoff-v2/`(OpenAPI · 응답 예시 · 데이터 v2) · 조립 스크립트 `build_pack_v2.py` | [README](data/README.md) |
| `ops/` | 배포 · 도구 — `deploy/`(프론트까지 포함한 로컬 배포: `deploy.sh` · `compose.web.yaml` · `.env.example`) · `tools/`(공개 API 대조 `openapi_diff.py` · Java 패키지 의존 검사 `java_pkgdeps.py`) | — |
| `docs/` | 문서 — `images/`(README 그림) | — |

**공개 저장소에 없는 것**

| 무엇 | 경로 | 없으면 |
|---|---|---|
| API 키 | `ai/.env` (`ai/.env.example`을 복사) | 키 없이 실행한다 — 아래 「API 키」 |
| 실제 원문 발췌 100조각 | `data/fab-rag-real-documents-v1/rag/source-chunks.jsonl` | 원문 문서 검색이 비어 있고, 원문 테스트는 건너뛴다 |
| 정답(평가자 전용) | `ai/evaluation/ground_truth.json` · `data/fab-causal-pack-v1/evaluation/` 등 | `fab-eval`과 정답표 테스트만 건너뛴다 |

## 실행 방법

### 준비물

| 도구 | 버전 |
|---|---|
| Docker Desktop (compose v2) | 최신 |
| JDK | 21 |
| [uv](https://docs.astral.sh/uv/) · Python | uv 0.11 · Python 3.12 |
| Node.js · npm | 22 |

### API 키

| 구성 | 키 없이 | 실제 API를 쓸 때 |
|---|---|---|
| 배포 구성(`ops/deploy/deploy.sh`) | 기본값 — 규칙 모델 + 해시 임베딩 | `ops/deploy/.env`에 `DEPLOY_LLM=openai` · `DEPLOY_EMBEDDER=openai` · `DEPLOY_OPENAI_API_KEY` |
| 개발 구성(`ai/compose.yaml`) | `EMBEDDER=hash FAB_LLM=rule`을 앞에 붙인다 | `ai/.env`에 `OPENAI_API_KEY`, `FAB_LLM=openai` |

### 1) Docker로 실행 (권장)

```bash
./ops/deploy/deploy.sh up -d --build --wait
# http://127.0.0.1:8088
./ops/deploy/deploy.sh --profile tools run --rm --build --no-deps smoke   # 전체 흐름 점검
./ops/deploy/deploy.sh down                                               # 끄기 (DB 볼륨 보존)
```

### 2) 개발용 구성 — 프론트 없이 백엔드 · MCP · Agent만

```bash
cd ai
EMBEDDER=hash FAB_LLM=rule docker compose up -d --build   # http://localhost:8090/swagger-ui.html
docker compose --profile tools run --rm --no-deps smoke   # 마지막에 「실패 0건」이 나오면 정상
```

### 3) 프론트 개발

백엔드가 `http://127.0.0.1:8090`에서 실행 중이어야 한다.

```bash
cd frontend
npm ci
npm run dev -- --port 8771             # http://127.0.0.1:8771
```

Spring만 직접 띄우는 방법은 [backend/README](backend/README.md#실행), Agent를 Docker 없이 돌리는 방법은 [ai/README](ai/README.md#docker-없이-개발--평가).

## 로그인 계정 (비밀번호 모두 `fab1234!`)

| 계정 | 역할 · 공정 | 시연에서 |
|---|---|---|
| `cmp.park` | ENGINEER · CMP | 사건 EV-1006 · EV-1007 · EV-1009 담당, 질문 · 승인 |
| `etch.lee` | ENGINEER · ETCH | EV-1009 앞 공정 확인 요청을 받고 회신 |
| `mnt.jung` | 설비정비팀 · 전 공정 | 정비 요청(EV-1006 · EV-1007)을 받는다 |
| `cvd.kim` | ENGINEER · CVD | — |
| `manager.choi` | MANAGER · 전 공정 | 상급자 업무 승인(사건 상태는 바꾸지 않음 — 사건 종료는 보고서 확정으로만) |

## 팀

| 이름 | 역할 |
|---|---|
| 전혜민 | PM · QA |
| 유아름 | 백엔드 — 업무 API |
| 권주현 | AI 백엔드 — Agent · Harness |
| 정선웅 | 백엔드 — 업무 MCP · 분석 |
| 성재현 | 백엔드 / 프론트엔드 — 업무 API |
| 황의진 | AI 백엔드 — Agent · Harness |

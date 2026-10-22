# ai — 업무 MCP · 지식 MCP · 조사 Agent · Harness

팀 역할 5번(지식 MCP · 데이터 · 인프라)과 6번(Agent · Harness), 그리고 업무 MCP 어댑터.
backend는 **Spring 하나**(`../backend`)이고, 데이터 팩은 **v2 조립본 `../data/fab-causal-pack-v2/`**(dataVersion `causal-pack-v2.1-scenarios`, `../data/build_pack_v2.py`가 v1 전체와 `../data/backend-handoff-v2/data`의 CURRENT를 합치고 `../data/priority-addon-l011-l012-v1`의 L011 · L012를 덧붙인다), 실제 원문 RAG는 동료 묶음 `../FAB-실제문서-선별RAG-센서데이터-통합/fab-rag-real-documents-v1`이다.
계약 기준은 팀 계약(원본은 팀 내부, 테스트가 쓰는 사본 3개는 [contracts/](contracts/README.md)), 바꿔야 할 점은 [docs/contract-changes.md](docs/contract-changes.md)에 모았다.

## 한 번에 띄우기 (Docker)

```bash
FAB_LLM=rule docker compose up -d --build                                     # Postgres · Spring backend · 업무 MCP · 지식 MCP · Agent Worker (.env에 OPENAI_API_KEY 필요 — 임베딩)
EMBEDDER=hash FAB_LLM=rule docker compose up -d --build                       # 키 없이 점검(시험용 해시 임베딩)
docker compose --profile tools run --rm --no-deps smoke                       # EV-1009 질문 → 제안 → 승인 → 실행 → 회신 → 보고서
docker compose --profile tools run --rm --no-deps -e SMOKE_MODE=real smoke    # 실제 모델(FAB_LLM=openai)로 띄웠을 때 — 원인 후보는 ETCH_WALL 포함, 회신 뒤 새 제안은 거절하고 진행
docker compose --profile tools build smoke                                    # 점검 코드(evaluation/)를 바꿨으면 — up --build는 tools 프로필 이미지를 다시 만들지 않는다
docker compose --profile tools run --rm --no-deps smoke python evaluation/restart_smoke.py before
docker compose restart agent                                                  # 승인 대기 중 Worker 재시작
docker compose --profile tools run --rm --no-deps smoke python evaluation/restart_smoke.py after
docker compose down
```

| 서비스 | 하는 일 |
|---|---|
| `postgres` (호스트 55432) | 한 DB에 `backend`(Spring) · `agent`(LangGraph 체크포인트) · `knowledge`(문서 벡터) 스키마 |
| `backend` (호스트 8090) | Spring. 공개 API `/api/**`, Agent 전용 내부 API `/internal/**`. `AGENT_MODE=external` — 작업은 Worker가 가져간다 |
| `business-mcp` | 업무 MCP. Spring `/internal/**`만 부르는 어댑터(DB · 규칙 없음) |
| `rag-mcp` | 지식 MCP. 실제 원문 청크 · 팀 점검 문서 · 가상 사례. OpenAI 임베딩(키 없으면 시작 실패 → `agent`도 안 뜬다) |
| `agent` | Worker. `GET /internal/jobs`(QUEUED)를 가져가 처리하고 이벤트를 `POST /internal/jobs/{id}/events`로 돌려준다 |

**정답은 어느 컨테이너에도 붙이지 않는다.** v2 조립본(`../data/fab-causal-pack-v2` — 정답 · 생성기가 없다)과 원문 RAG의 `fab-rag-real-documents-v1/rag`만 읽기 전용으로 마운트한다 — `evaluation/` · 생성기(`generate.py`) · 원문 PDF(`originals/` · `selected/`)는 보이지 않는다. `.env`(API 키)는 이미지에 들어가지 않는다(`.dockerignore`).
`.env`의 `FAB_LLM=openai`가 compose에도 전달되므로, 결정적으로 점검할 때는 앞에 `FAB_LLM=rule`을 붙인다. 데이터를 처음부터 다시 넣으려면 `backend` · `agent` 스키마를 지우고 다시 올린다.

## 지식 MCP 임베딩 — 의미 검색

**compose · 시연은 OpenAI 임베딩(`EMBEDDER=openai`, `text-embedding-3-small`, 1536차원)이다 — `OPENAI_API_KEY`가 필요하다(`.env`).** 키는 `.env` → compose 환경 변수로만 들어가고 이미지 · 로그 · 오류 메시지에는 남지 않는다.

| 상황 | 결과 |
|---|---|
| 키가 있다 | 첫 기동에 122조각을 64개씩 묶어 임베딩(`knowledge.chunks_d1536`). 다시 띄우면 `embedded 0`(chunkId · contentHash · embed_model이 같으면 건너뛴다) |
| 키가 없다 · 틀렸다 · 네트워크 오류 · 429가 계속된다 | 지식 MCP가 **시작에 실패**한다(종료 코드 2, 로그에 원인만: 「OPENAI_API_KEY가 없습니다」 · 「인증 실패(401)」 · 「네트워크 오류」 · 「요청 한도 초과(429)」). healthcheck가 실패해 compose는 `agent`를 띄우지 않는다. 해시로 조용히 바꾸지 않는다 |
| 키 없이 점검 | `EMBEDDER=hash docker compose up -d` — 시험용 글자 n-gram 해시(`hash-ngram-v1`, 768차원, `knowledge.chunks_d768`). pytest도 hash로 돈다 |

- `.env`의 `EMBEDDER` 줄이 compose 기본값보다 앞선다. 예전 `.env.example`을 복사했다면 `EMBEDDER=hash`가 들어 있으니 `openai`로 고친다(지금 `.env.example`은 `openai`)
- 문서는 `embed_documents`(묶음), 질의는 `embed_query`. 429 · 연결 오류 · 5xx는 0.5 · 1.5 · 3초 뒤 세 번 다시 시도한다
- 차원이 다르면 표가 따로 생긴다. 검색은 **지금 임베더의 표 · embed_model 행만** 본다. 쓰지 않는 표(예: hash 시절 `chunks_d768`)는 남겨 두고 `/healthz`의 `otherTables`로 알린다 — `EMBEDDER=hash`로 다시 띄우면 그대로 쓰인다. 지우려면:
  ```bash
  PG_DSN=postgresql://fab:fab@localhost:55432/fab uv run fab-rag-index status        # 표 · 모델 · 행 수
  PG_DSN=postgresql://fab:fab@localhost:55432/fab EMBEDDER=openai uv run fab-rag-index prune   # 지금 모델이 없는 표를 지운다(확인을 묻는다)
  ```
- 확인: `docker compose exec rag-mcp python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:9102/healthz').read().decode())"` → `embedModel` · `dim` · `embedded` · `table` · `otherTables`

### 검색 평가 (`evaluation/retrieval_eval.py`, 수동 실행 — openai는 키가 필요하다)

```bash
uv run python evaluation/retrieval_eval.py                  # hash · openai 둘 다(메모리 색인, compose DB를 건드리지 않는다)
uv run python evaluation/retrieval_eval.py --embedder hash  # 키 없이
uv run python evaluation/retrieval_eval.py --details        # 질의마다 순위 · 상위 3개
```

질의 25개(`evaluation/retrieval_queries.json` — 원문 10 · 점검 문서 10 · 사례 5, 그중 6개는 문서 낱말을 피해 바꿔 쓴 질의). 기대 조각은 원인 매트릭스 · 팀 점검 문서 · 원문 조각 내용으로 정했고 정답 파일 · 시나리오 문서는 쓰지 않았다. 원문 질의에는 같은 뜻을 영어 용어와 함께 쓴 `agentQuery`를 따로 잰다. 2026-10-20 결과(Hit@3 / MRR@10):

| 도구 | hash-ngram-v1 | text-embedding-3-small |
|---|---|---|
| `search_technical_documents` 한국어만 | 0.70 / 0.558 | 0.70 / 0.670 |
| └ 영어 용어 병기(`agentQuery`) | 0.90 / 0.864 | **1.00 / 1.000** |
| `search_applicable_playbooks` | 0.90 / 0.870 | 0.90 / 0.863 |
| `search_past_cases` | 1.00 / 0.867 | 0.80 / 0.850 |

- **openai가 hash보다 낮은 곳의 원인 — 한국어 전문 용어의 언어 간 대응이 약하다.** 「질량유량계 교정 오차」(DOC03)는 10위 밖인데 「질량유량제어기(MFC) 교정 오차」 · 「mass flow controller calibration error」는 1위, 「관리한계 이탈과 규격 이탈 차이」(DOC07)도 영어 용어를 병기하면 1위다. 한국어끼리도 같은 개념의 다른 말(「연마액」 ↔ 슬러리, 「누르는 힘」 ↔ 헤드 압력)은 순위가 내려간다(사례 C05 4위, 점검 문서 P07 8위). hash는 질의와 문서가 같은 낱말을 쓸 때 강하다 — 이 질의셋의 한국어 질의 다수가 점검 문서 · 사례와 낱말이 겹친다
- 그래서 **Agent 프롬프트 · `search_technical_documents` 설명에 「원문은 영어 — 영어 용어 · 약어를 함께 쓴다」를 넣었다**(규칙 모델은 이미 영어로 찾는다). 원문 조각 앞에 제목 · 절 범위를 붙여 색인하는 것도 재 보았지만 openai MRR이 0.670 → 0.601로 내려 하지 않았다
- **Hybrid(BM25 + 벡터, RRF)는 넣지 않았다.** 원문 쪽 실패는 한국어 질의 ↔ 영어 원문이라 낱말 검색이 도울 수 없고, 점검 문서는 두 임베더가 같으며, 사례는 5개 중 1개 차이(C05, 동의어)다. 질의셋이 커져 사례 · 점검 문서에서 차이가 벌어지면 다시 잰다

## Docker 없이 (개발 · 평가)

```bash
uv sync
# 묶음 경로(FAB_PACK_DIR · FAB_RAG_DIR)는 기본값이 없다 — .env에 두 줄(.env.example 참고, 이 폴더 기준 상대 경로 가능). pytest는 테스트 설정이 넣는다
uv run pytest -q          # 43개 — 테스트용 Postgres 컨테이너(testcontainers, backend · agent 스키마를 비움) 위에 Spring bootJar를 외부 Agent 모드로 띄우고 붙는다(JDK 21 · Docker)

docker compose stop backend agent business-mcp; docker compose up -d postgres   # compose에서는 postgres만
(cd ../backend && DDL_AUTO=create AGENT_MODE=external DEMO_ROUTES=true ./gradlew bootRun)   # 띄울 때마다 처음 데이터
uv run fab-eval                          # EV-1006 · EV-1007 · EV-1009 첫 판단을 정답표와 대조
uv run fab-demo --incident EV-1009       # 질문 → 제안 → 승인 → 회신 → 결과 → 보고서
uv run fab-demo --incident EV-1006 --reject
```

`fab-demo` · `fab-eval`은 사람 역할(공개 API)과 Agent(업무 · 지식 MCP를 같은 프로세스에)를 한 프로세스에서 돌린다(`devtools/spring.py`). compose의 `agent`와 같이 띄우면 작업을 서로 가져가니 둘 중 하나만 쓴다.
키가 없으면 **규칙 모델**(`FAB_LLM=rule`, `rule-policy-v2`)로 돈다 — 그래프 · 도구 · 승인/재개 배선을 결정적으로 검증하는 용도다. 판단 품질은 실제 모델로 잰다: `FAB_LLM=openai uv run fab-eval`, 모델 비교는 `evaluation/compare_models.py`.

### 모델 — 기본 gpt-4.1-mini, 대체 gpt-4o-mini (가용성용)

`FAB_MODEL=gpt-4.1-mini` · `FAB_FALLBACK_MODEL=gpt-4o-mini`(compose · `.env.example` 기본값). 2026-10-20 실제 모델 평가에서 gpt-4.1-mini는 정답표 E01–E03을 세 번 모두 3/3, 시험 E04–E08 자동 판정 4/5였고, gpt-4o-mini(대체 모델 끔)는 2/3 · 1/5로 판단이 확연히 약했다 — 근거와 맞지 않는 정비 요청 · 대상이 틀린 확인 요청. 그래서 gpt-4o-mini는 기본 모델이 타임아웃 · 장애로 응답하지 못할 때만 쓰는 대체 모델로 둔다.

### 실제 모델 평가 (`evaluation/`, 수동 실행 — 키 · 비용이 든다)

compose와 **따로** 평가용 Postgres(docker `fab-eval-pg`, 호스트 55433)와 Spring(포트 8097, 외부 Agent 모드, 띄울 때마다 처음 시드)을 띄우고 끝나면 지운다(`eval_env.py`). 평가 쪽은 `SPRING_URL`을 그 Spring으로, `PG_DSN`은 비워 메모리로 둔다. trace · 결과 원문은 `evaluation/runs/`(git 제외).

```bash
uv run python evaluation/real_eval.py --runs 3                 # 정답표 E01–E03 반복 (gpt-4.1-mini · 대체 gpt-4o-mini · EMBEDDER=openai)
uv run python evaluation/real_eval.py --runs 1 --embedder hash # 같은 조건, 해시 임베딩
uv run python evaluation/scenario_eval.py                      # 시험 E04–E08 — 질문 → 결과(제안은 거절), 자동 판정 + 사람이 볼 보고서
uv run python evaluation/compare_models.py gpt-4.1-mini gpt-4o-mini   # 대체 모델 끔, E01–E08 · 토큰 · 시간 · 타임아웃
uv run python evaluation/trace_stats.py evaluation/runs/…/eval_1.jsonl   # LLM 호출 · apiModel · 대체 · 타임아웃 · 토큰 · 비용 어림
# 비용 없이 도구만 시험: --llm rule
```

시험 문항(E01–E08)은 평가자 전용 파일을 **평가 스크립트만** 실행 중에 읽는다 — 프롬프트 · 도구 설명 · 색인 · 컨테이너 · 팩에는 넣지 않는다. trace의 llm 기록은 `modelName`(고른 모델)과 `apiModel`(API가 실제로 답한 스냅샷, 규칙 모델은 같은 이름)을 함께 남긴다.

## 시연 사건 (데이터 팩 CURRENT 로트)

| 사건 | 웨이퍼 · 장비 | 데이터가 보여 주는 것 |
|---|---|---|
| **EV-1009** (주 시연) | `W-CURRENT-009-3` · CVD-PM1 → ETCH-PM2 → CMP-PM3 | CMP 두께 UCL 위(규격 안). CMP 제거량은 정상 변동 안, Etch 제거량 4896.3 Å(같은 PM 대비 z ≈ −2.7), ETCH-PM2 정비 이력 `DEMO-CHANGE-E02` |
| EV-1006 | `W-CURRENT-006-3` · CMP-PM3 | `head_pressure` 참고 범위 이탈 |
| EV-1007 | `W-CURRENT-007-3` · CMP-PM3 | `slurry_flow` 참고 범위 이탈 |

기대 결론(정답표)은 평가자 전용 `evaluation/ground_truth.json`에 있고 공개 저장소에는 올리지 않는다. 없으면 `fab-eval`과 정답표 테스트는 이유를 알리고 건너뛴다.

분석 시점은 팩의 `analysisAsOf`(2026-10-20 11:59 KST)다. 그 뒤에 공개된 계측과 FOLLOWUP run은 backend가 막는다(403). 관리한계는 동결 I-chart이고 규칙은 R1뿐이며, OOC(관리한계 이탈)와 OOS(규격 이탈)는 다르다.

## 구성

| 경로 | 무엇 |
|---|---|
| `src/fab/business/server.py` | **업무 MCP** — 읽기 12개(`get_incident` · `list_incidents` · `get_wafer_lineage` · `get_run_and_metrology` · `get_run_features` · `get_sensor_trace` · `get_frozen_limits` · `get_change_events` · `get_process_owner` · `get_check_requests` · `get_action_history` · `get_report_context`), 변경 3개(`create_cross_process_check_request` · `create_maintenance_request` · `confirm_report`, `destructiveHint`). 숫자는 Spring이 계산하고 MCP는 전달만 한다 |
| `src/fab/rag/` | **지식 MCP** — `search_technical_documents`(실제 원문 100청크) · `search_applicable_playbooks`(팀 점검 문서 15) · `search_past_cases`(가상 사례 7, `availableAt ≤ asOf`만). Resource `doc://{chunkId}` · `case://{caseId}`. 원문 검색은 적재 정책의 조건(`process` · `retrievalTier` · `documentId` · `version`)으로 좁힌다. PG_DSN이 있으면 pgvector(`chunkId` · `contentHash` 증분 색인 — 바뀐 조각만 다시 임베딩), 없으면 메모리 |
| `src/fab/agent/graph/` | LangGraph — start → 필수 근거(사건 → 경로 → 단계별 run · 계측 → 현재 run 특징) → agent ⇄ tools → (제안이면 **request_brief** → tools) → 승인 대기 → 회신 대기 → finalize — `state.py`(상태 · 실행 자원) · `nodes/`(노드 하나에 파일 하나, 파일 이름 = 노드 이름: `start` · `required_evidence` · `agent` · `request_brief` · `tools` · `approval_wait` · `reply_wait` · `finalize`. `tools`는 제출 처리 / 제안 저장 / 도구 실행 세 함수) · `routes.py` · `build.py` |
| 시계열 구간 근거 | `get_sensor_trace`가 `sensorEnvelope`와 DEMO-SEGMENT-V2 구간(`segments`, 근거 `E-SEG-*`)을 준다(Spring `SegmentDetector`). 구간은 관측이지 고장 시점 · 원인 확정이 아니다 — DEEP 프롬프트에 적었고, 구간 근거를 인용한 결과에는 Harness가 「4σ · 2초 · 5초는 시연 선택값」 한계를 반드시 붙인다(`graph._segment_limit`) |
| `src/fab/agent/brief.py` | **요청 브리프** — 제안 저장 전에 받는 사람이 볼 편차 수치(run 제거량 · 센서) · 원인 후보(확정 아님) · 정비 이력 · 요청 항목을 도구 결과와 원인 매트릭스에서 옮긴다. 모델은 `reason` 문장만 쓰고, `guard.validate_brief`가 그 숫자 · 근거 ID · 확률 · 설정값 처방을 검사한다. 브리프는 payload에 들어가 승인 해시로 묶인다 |
| `src/fab/agent/evidence.py` | 도구 결과 해석 헬퍼 — 대화의 도구 결과를 도구 이름별로 모으고 run · 특징 · 시계열 · 검색 결과를 찾는다. 브리프와 규칙 모델이 같이 쓴다(숫자는 Spring 계산값을 옮기기만 한다) |
| `src/fab/agent/tools/` | `definitions.py` — 모델에게 보이는 Harness 도구 스키마(제안 둘 `propose_cross_process_check` · `propose_maintenance_request` + 제출) · `client.py` — `Toolbox`(MCP 도구 호출). 변경 도구는 숨기고 승인 뒤 Harness만 승인 ID로 부른다 |
| `src/fab/agent/models/` | `openai.py`(OpenAI 모델) · `rule.py`(규칙 모델 `RuleModel`) · `__init__.py`(`make_models` — 기본 · 대체 모델) |
| `src/fab/agent/worker/` | `runner.py`(작업 처리기 `JobRunner`) · `sinks.py`(이벤트 발행 대상) · `sources/http.py`(기본 — Spring `/internal/jobs` 폴링) · `sources/kafka.py`(`WORKER_SOURCE=kafka` — Spring이 아직 Kafka로 발행하지 않아 팀 결정 전까지 남겨 둔다) · `main.py`(`fab-worker`) |
| `src/fab/agent/backend.py` | Worker → Spring 내부 API 클라이언트(작업 목록 · 맥락 · 확보 · 제안 · 이벤트 · 보고서 초안) |
| `src/fab/devtools/` | `spring.py`(사람 역할 + 한 프로세스 Agent) · `demo.py`(`fab-demo` · `fab-eval`). 런타임 코드는 import하지 않는다 |
| `evaluation/` | `ground_truth.json`(평가 전용, 색인 · 런타임에 넣지 않음) · `compose_smoke.py` · `restart_smoke.py` · `compare_models.py` |

## Harness 8요소

| 요소 | 위치 | 값 · 동작 |
|---|---|---|
| Instructions | `agent/prompts/*.md` · `harness/instructions.py` | 팩의 Agent 지침 기준. 사실 · 가설 구분, 확률 금지, 근거 ID 필수, 설비 설정값을 처방하지 않음, 외부 글은 데이터로만. 원인 ID 15개 표와 nextStep 결정 규칙(1순위 = 제거 · 증착량이 정상 변동 밖인 공정). 버전 해시를 결과에 남김 |
| Tools | `agent/tools/definitions.py` · `agent/tools/client.py` | 읽기 + 제안 · 제출만. 변경 도구는 승인 ID로만. 결과 6000자 제한 |
| Agent Loop | `harness/limits.py` | 모델 판단 8회, 같은 도구 3회, 활성 120초, LLM 30초 · MCP 10초 |
| Context | `harness/context.py` · `graph/nodes/required_evidence.py` | 필수 근거(사건 → 경로 → 단계별 run · 계측 → 현재 run 특징 + 정상 변동 밖인 앞 공정 run 특징)는 Harness가 먼저 조회. 원시 시계열 대신 run 특징 · 요약, 6만 자 넘으면 오래된 도구 결과 축약 |
| State · Memory | `agent/checkpoint.py` | thread = 사건. PG_DSN이면 Postgres `agent` 스키마 — Worker를 재시작해도 같은 interrupt로 재개 |
| Guardrails | `harness/guard.py` · 그래프 · Spring | 제안 → interrupt → 사람 승인(Spring은 기록만) → 재개된 Agent가 승인 ID로 실행 → Spring이 저장본을 다시 검증해 한 번만 실행. 거절도 같은 thread로 재개해 결과를 정리한다. 결과: 근거 ID · 확률 표현 · 원인 ID(매트릭스 15개, `agent/causes.py`) · **nextStep과 1순위 원인 공정의 일치** 검증, 심층 조사에서 점검이 필요하면 요청 초안을 먼저 제안. 제안: 사건당 2개 · 한 번에 하나 · 같은 담당자 · 종류 반복 금지(Harness), 받는 사람이 그 공정 담당자 · 설비정비팀인지와 질문한 본인이 아닌지(Spring) |
| Recovery | `harness/retry.py` · `worker/runner.py` | 일시 오류 2회 재시도 → 대체 모델 → `RETRY_SCHEDULED`(attempt < 3) → `JOB_FAILED`. 같은 attempt 중복은 확보(claim)에서 409 → `SKIPPED` |
| Observability | `harness/trace.py` · `worker/runner.py` · Spring `job.metrics` | trace_id 하나로 node · tool · 지연 · 재시도 · 토큰 · Harness 거부. 컨테이너는 `FAB_TRACE_STDOUT=true`(compose 기본) — 기록마다 JSON 한 줄을 표준출력으로(키 · 토큰 모양은 가림), `[job]` 줄에 작업 요약. 작업을 끝내거나 멈추는 이벤트의 `metrics`로 요약을 보내 Spring이 `job.metrics`(jsonb)에 저장. `FAB_TRACE_FILE`은 로컬 평가용 파일, `LANGSMITH_TRACING=true`면 LangSmith(compose는 넘기지 않는다) |

trace 보기 — 로그는 Docker가 서비스마다 10MB × 3개에서 회전한다(`x-logging`):

```bash
docker compose logs -f agent | grep '\[job\]'        # [job] job_… DEEP → DONE llm=3 tok=31071/585 model=gpt-4.1-mini-… tools=10 reject=0 retry=0 ms=6655
docker compose logs --no-log-prefix agent | grep '"jobId": "job_…"'   # 그 작업의 JSON trace 줄
```

```sql
-- 작업별 요약 (DBeaver · Grafana의 Postgres 데이터 소스)
select job_id, kind, status, metrics->>'modelCalls' as calls, (metrics->>'inputTokens')::int as tokens_in,
       (metrics->>'outputTokens')::int as tokens_out, metrics->'apiModels'->>0 as model,
       (metrics->>'guardRejects')::int as rejects, (metrics->>'ms')::numeric as ms
from backend.job where metrics is not null order by created_at desc;
```

결과의 `nextStep`(`UPSTREAM_CHECK` · `CURRENT_CHECK` · `OBSERVE` · `INSUFFICIENT_EVIDENCE` · `NEEDS_DEEP_ANALYSIS`)과 `causeIds`(팩 원인 매트릭스 15개)는 계약 `AnalysisResult`가 추가 필드를 막아 Worker 반환값 `analysis`에만 둔다(`FAB_RESULT_EXTENSIONS=true`면 이벤트에 합친다).

## 검증 (2026-10-20 — pytest는 2026-10-21)

- `uv run pytest -q` 43개 통과(정답표 · 원문 조각이 없는 공개 클론에서는 그 테스트만 건너뛴다) — trace의 apiModel(가짜 스냅샷 모델 · 규칙 모델), 임베딩(가짜 임베더 · 가짜 OpenAI 클라이언트로 문서 · 질의 분리, 64개 묶음, 429 · 연결 오류 재시도, 오류 메시지에 키 없음, `openai`인데 키 없음 → 시작 실패, Postgres 증분 색인 · 차원별 표), 시계열 구간(L006 head_pressure 101.1–210.9초 · L007 slurry_flow 14–309초 LOW, L009 · 정상 LOT 구간 없음, 규칙 모델이 구간을 관측 사실로 인용하고 「4σ · 2초 · 5초는 시연 선택값」 한계를 남김, 결과 dataVersion = 팩 버전), 요청 브리프(EV-1009 ETCH 제거량 · z · ETCH_WALL · 정비 이력 rootCauseConfirmed=false, EV-1006 head_pressure · CMP_PRESSURE, 조회하지 않은 숫자 · 근거 반려, 승인 해시가 브리프를 덮음), 도구 목록 · 설명, 데이터 팩 수치(EV-1009 Etch 제거량 · R1 · 참고 범위), 분석 시점 차단, 지식 검색 필터, 승인 전 변경 0건 · 거절 뒤 재개 · 같은 승인 중복 실행 1건, 회신 뒤 두 번째 제안이 재개 작업에 붙음, 제안 상한 · 반복 금지, 원인 ID가 팩 매트릭스와 같음, 없는 원인 ID · nextStep 불일치 반려, 이벤트 · 작업 메시지 JSON Schema
- `fab-eval`은 **첫 판단**을 채점한다 — ① 기본 분석의 nextStep · 1순위 causeIds ② 팩 질문(E01–E03)에 대한 첫 제안의 종류 · 대상 공정. 회신 뒤 판단은 회신 내용에 따라 달라 정답표로 채점하지 않는다
  - 규칙 모델 3/3
  - **실제 모델(gpt-4.1-mini) 3/3을 세 번 연속**(회당 모델 17–19회 · 약 16–18만 토큰). 30초 제한에 걸린 호출은 대체 모델로 넘어가 끝까지 처리됐다
- compose(OpenAI 임베딩): `/healthz` text-embedding-3-small · 1536차원 · 첫 기동 122 → 다시 띄우면 0, 키 없이 `EMBEDDER=openai` → rag-mcp 종료 코드 2 · agent 미기동, `compose_smoke.py` 실패 0건, `restart_smoke.py`(승인 대기 중 Worker 재시작 → 재개 → 회신 → 보고서) 실패 0건, 컨테이너에 `evaluation/` · `.env` 없음 확인

## 아직 하지 않은 것

- 실제 LLM으로 팩 평가 E04–E08(정상 로트 질문 · 설정값 처방 요구 · 확정 보고 요구 · 이중 트렌드) — 정답표에 아직 없다
- 채점이 느슨한 곳: 1순위 causeIds에 정답이 「들어 있으면」 통과다(한 번은 ETCH 원인 다섯 개를 모두 나열했다)
- Kafka — 어디까지 쓸지 정하면 Spring 발행 쪽과 함께 붙인다
- 서비스 인증 방식(지금은 고정 Bearer 토큰), ID · 근거 형식은 팀 확정을 기다린다


## 요청 제안과 화면 업무의 경계

Agent는 `e98df45` 직전의 요청 흐름을 사용한다. 심층 조사에서 `propose_cross_process_check` 또는 `propose_maintenance_request`로 요청을 제안하고, `approval_wait`에서 사람의 결정을 기다린다. 승인되면 Harness가 승인 ID로 업무 MCP의 변경 도구를 호출하고, `reply_wait`에서 회신을 기다린 뒤 같은 사건의 조사를 재개한다. 거절도 같은 thread에서 사유를 반영한다.

`submit_result.workSuggestions` 자동 업무 카드는 생성하지 않는다. Frontend·Backend의 기존 업무 목록·관리자 변경 승인·담당자 선택·회신·보고서 이력 기능은 유지되며, 사용자는 화면의 「분석에서 업무 제안」으로 업무를 직접 만들 수 있다. 업무 MCP 도구 목록도 이전 목록으로 복구했다. `FAB_LEGACY_REQUEST_PROPOSALS`는 더 이상 필요하지 않으며 동작을 바꾸지 않는다.

이후 추가된 Docker 구조화 로그와 작업별 metrics는 유지한다. 기존 DB와 체크포인트는 자동 삭제·변경하지 않는다. 새 동작 검증에는 새 사건을 사용하고, 변경 기간에 만들어진 기존 thread는 저장된 지시문·제안·대기 상태를 확인한 뒤 사용한다.

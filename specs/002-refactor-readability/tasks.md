---

description: "동작을 보존하는 코드 가독성 리팩터링 — 작업 목록"
---

# Tasks: 동작을 보존하는 코드 가독성 리팩터링

**Input**: `specs/002-refactor-readability/`의 설계 문서 · **기준 커밋**: `ee171d2`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 새 테스트는 만들지 않는다(FR-006a). 동작 보존은 기존 테스트 · 스모크 · 계약 대조 · DOM 스냅샷으로 확인한다
([contracts/gates.md](./contracts/gates.md)). 테스트 파일의 기대값은 어떤 작업에서도 바꾸지 않는다.

**모든 리팩터링 작업 공통 규칙** — 각 작업은 아래를 지킨 뒤 끝난다:

1. [contracts/preserved-surface.md](./contracts/preserved-surface.md)의 이름 · 문자열 · 순서는 바꾸지 않는다
2. 새 의존성 · 새 기능 · 새 테스트를 넣지 않는다. 발견한 버그는 고치지 않고 `records/deferred.md`에 적는다
3. 끝나면 해당 구성요소의 빠른 점검(gates.md)을 돌려 기준선과 같은지 보고, `records/steps.md`에 한 행(번호 · 파일 · 무엇을 ·
   왜 · 점검 결과)을 적는다. 다르면 그 작업의 변경만 되돌린다
4. 40줄 넘는 함수를 남기거나 이름을 바꾸지 않은 곳은 `records/exceptions.md`에 이유를 적는다

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 작업에 의존하지 않음)
- 경로 약어: `FEAT` = `specs/002-refactor-readability`, `WEB` = `fab-context-web/src`, `AG` = `fab-agent/src/fab`,
  `API` = `fab-context-api/src/main/java/com/fabcontext/api`

---

## Phase 1: Setup

- [X] T001 작업 브랜치를 기준 커밋에서 만든다: `git switch -c refactor/readability ee171d2` (현재 브랜치에 커밋되지 않은 변경이 있으면 먼저 사용자에게 알린다). `.specify/`, `specs/` 같은 추적되지 않은 파일은 그대로 따라온다
- [X] T002 [P] 기록 폴더 `FEAT/records/`를 만들고 `baseline.md` · `steps.md` · `exceptions.md` · `deferred.md`를 [data-model.md](./data-model.md)의 필드를 머리말로 갖는 빈 표로 만든다. `FEAT/records/dom/`도 만든다

---

## Phase 2: Foundational (점검 도구 — 모든 이야기의 전제)

- [X] T003 [P] 가독성 지표 스크립트 `FEAT/tools/metrics.py`를 작성한다(research R4): ① `git ls-files fab-context-web/src`의 `.ts/.tsx` 파일별 가장 긴 줄 · 160자 초과 줄 수 ② `fab-agent/src` · `fab-agent/evaluation` · `data` · `tools`의 Python 함수 중 본문 40줄 초과(ast, `end_lineno-lineno+1`) ③ `API` 하위 Java 메서드 중 40줄 초과(선언 줄이 `{`로 끝나고 class/record/interface/enum이 아닌 줄부터 중괄호 짝) ④ Python 한두 글자 할당 이름 수(허용 `i j k v e _`) 파일별. 출력은 정렬된 텍스트(같은 코드면 같은 바이트). 표준 라이브러리만 사용
- [X] T004 [P] DOM 스냅샷 스크립트 `FEAT/tools/dom-snapshot.mjs`를 작성한다(research R3): 인자 `before|after`. `puppeteer-core`를 `npx -y -p puppeteer-core@23 node …` 방식으로 쓰고 Chrome 경로는 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`. `http://127.0.0.1:8088`에서 계정 `cmp.park` · `etch.lee` · `mnt.jung` · `manager.choi`(비밀번호 `fab1234!`)로 로그인 화면을 통해 로그인한 뒤 라우트 `/dashboard` · `/incidents` · `/work-items` · `/approvals` · `/check-requests` · `/reports` · `/knowledge`와 사건 상세(EV-1009 · EV-1006 · EV-1023), 확정 보고서 상세(EV-1021)를 연다. 네트워크가 잠잠해진 뒤 `document.body.innerText`와 정규화 DOM(모든 요소의 태그 · class · id · aria-* · role · href · placeholder · title · type, 텍스트 노드는 그대로, `inc_*` · `job_*` · `apr_*` · `chk_*` · `rep_*` 같은 생성 ID와 ISO 시각 · 「n분 전」 표현은 토큰으로 치환)을 `FEAT/records/dom/<before|after>/<계정>__<경로>.{txt,html}`로 저장한다. 로그인 전 `/login` 화면도 저장한다. 같은 상태에서 두 번 돌려 결과가 같아야 한다
- [X] T005 T003을 기준 커밋에서 실행해 `FEAT/records/metrics-before.txt`를 만들고, 값이 명세 수치(화면 최대 2,711자 · 160자 초과 파일 22개 · Python 40줄 초과 13개 · Java 9개 · 짧은 이름 432곳)와 같은지 확인한다. 다르면 스크립트의 측정 규칙을 명세 측정과 맞춘다

**Checkpoint**: 점검 도구가 준비됐다

---

## Phase 3: User Story 1 — 동작 기준선 확보 (Priority: P1) 🎯 MVP

**Goal**: 리팩터링 전 동작을 일곱 가지 점검으로 기록하고 재현성을 확인한다

**Independent Test**: 코드 변경 없이 기준선을 두 번 만들어 결과가 같다

- [X] T006 [US1] 자동 테스트 기준선: `cd fab-context-api && ./gradlew test`, `cd fab-agent && uv run pytest -q`, `cd fab-context-web && npm ci && npm test && npm run build`를 실행해 통과 · 건너뜀 · 실패 수와 건너뛴 테스트 이름을 `FEAT/records/baseline.md`에 적는다(기대 109+2 · 39+2 · 33)
- [X] T007 [US1] 배포를 처음 시드 상태로 올린다: `./scripts/deploy.sh down -v`는 DB를 지우므로 **실행 전 사용자 확인**을 받고, `./scripts/deploy.sh up -d --build --wait` 후 6개 서비스 healthy를 `FEAT/records/baseline.md`에 적는다
- [X] T008 [US1] 시드 직후 `node FEAT/tools/dom-snapshot.mjs before`로 화면 기준선을 만들고, 곧바로 한 번 더 찍어(`before2`) `diff -r`이 0인지로 재현성을 확인한 뒤 `before2`는 지운다. 결과를 `FEAT/records/baseline.md`에 적는다
- [X] T009 [US1] 계약 대조 기준선: [quickstart.md](./quickstart.md) 1의 명령으로 `FEAT/records/openapi-before.txt`를 만든다(41 · 41 · 공통 41, 차이 `MAINTENANCE_REQUEST` · comparisons). MCP 도구 목록 기준선도 만든다 — `cd fab-agent && uv run python -c` 로 업무 MCP · 지식 MCP의 `list_tools()` 결과(이름 · 설명 · 인자 스키마, 순서 그대로)를 `FEAT/records/mcp-tools-before.json`에 저장한다
- [X] T010 [US1] 업무 흐름 기준선: `./scripts/deploy.sh --profile tools run --rm --build --no-deps smoke`(16 check), 재시작 스모크(`… smoke python evaluation/restart_smoke.py before` → `./scripts/deploy.sh restart agent` → `… after`)를 실행해 결과를 `FEAT/records/baseline.md`에 적는다
- [X] T011 [US1] `python3 tools/java_pkgdeps.py fab-context-api/src/main/java` 결과(「순환: 없음」)와 `python3 data/build_pack_v2.py`를 임시 출력 경로로 돌린 결과가 `data/fab-causal-pack-v2/SHA256SUMS.txt`와 같은지 `FEAT/records/baseline.md`에 적는다. 저장소의 `data/fab-causal-pack-v2/`는 덮어쓰지 않는다(덮어쓰는 스크립트면 실행 후 `git diff --stat data/`가 0인지 확인)

**Checkpoint**: 기준선 완료 — 이후 모든 단계는 이 기록과 비교한다

---

## Phase 4: User Story 2 — 화면 코드 펼치기 · 분리 (Priority: P1)

**Goal**: 화면 22개 파일의 160자 초과 줄을 없애고 큰 화면을 나눈다 — 보이는 것은 그대로

**Independent Test**: 화면 테스트 · 빌드 통과 + DOM 스냅샷 diff 0

공통: research R5 규칙(1칸 들여쓰기 · 홑따옴표 · 세미콜론 · 연산자 주변 공백 없음 · 태그 사이에서만 줄 나누기 · 원래 공백은 `{' '}`로 명시 · 문자열 그대로). 각 작업 뒤 `npm test && npm run build`

### 4a. 펼치기 (줄만 나눔 — 구조 변경 없음)

- [X] T012 [P] [US2] 작은 파일의 긴 줄 나누기: `WEB/api/incident-notifications.ts` · `WEB/api/monitoring.ts` · `WEB/main.tsx` · `WEB/domain/conversation.ts` · `WEB/auth.tsx` · `WEB/domain/actionSources.ts` · `WEB/api/client.ts` · `WEB/api/sensor-observation.ts` · `WEB/api/types.ts`
- [X] T013 [P] [US2] 컴포넌트 펼치기: `WEB/components/ui.tsx` · `WEB/components/DemoPersonaSelect.tsx` · `WEB/components/IncidentNotifications.tsx`
- [X] T014 [P] [US2] 컴포넌트 펼치기: `WEB/components/AnalysisRequestStatus.tsx` · `WEB/components/ActionWorkspace.tsx`(160자 초과 21줄)
- [X] T015 [P] [US2] 컴포넌트 펼치기: `WEB/components/SensorPanel.tsx`(16줄) · `WEB/components/InvestigationContext.tsx`(1,446자) · `WEB/components/InvestigationConversation.tsx`(1,237자)
- [X] T016 [P] [US2] 컴포넌트 펼치기: `WEB/components/WorkItems.tsx`(31줄 · 1,105자)
- [X] T017 [P] [US2] 페이지 펼치기: `WEB/pages/Login.tsx`(2,527자) · `WEB/pages/Dashboard.tsx`(1,795자)
- [X] T018 [US2] 페이지 펼치기: `WEB/pages/Workflow.tsx`(36줄 · 2,711자) — 파일이 커서 export된 컴포넌트 하나씩 펼치고 매번 `npm test`
- [X] T019 [US2] 앱 펼치기: `WEB/App.tsx`(2,681자 — `App` · `ProcessPage` · `Shell` · `Knowledge`)
- [X] T020 [US2] 펼치기 게이트: `./scripts/deploy.sh down -v`(사용자 확인) → `up -d --build --wait` → `node FEAT/tools/dom-snapshot.mjs after` → `diff -r FEAT/records/dom/before FEAT/records/dom/after`가 0인지 확인. 다르면 차이 난 경로의 파일을 찾아 원인(공백 · 문자열) 수정 후 다시

### 4b. 큰 화면 분리 (펼치기 게이트 통과 후)

- [X] T021 [US2] `WEB/pages/Workflow.tsx`를 export 단위(`IncidentList` · `IncidentDetail` · `Approvals` · `Requests` · `ReportList` · `ReportEditor` 등 현재 export 그대로)로 같은 `pages/` 폴더의 파일로 나누고, `Workflow.tsx`는 같은 이름들을 다시 export해 `App.tsx`의 import가 바뀌지 않게 한다. 파일 사이에서 공유하던 내부 함수는 `WEB/pages/workflowShared.tsx`로 옮긴다. 컴포넌트 계층 · key · 훅 순서는 그대로
- [X] T022 [US2] `WEB/App.tsx`의 `Shell`(레이아웃) · `Knowledge`(참고 문서)를 `WEB/components/Shell.tsx` · `WEB/pages/Knowledge.tsx`로 옮긴다. `lazy` 경계(`Dashboard`)와 라우트 정의 순서는 그대로
- [X] T023 [US2] 함수 하나가 40줄을 넘는 화면 컴포넌트(`WEB/pages/Dashboard.tsx` · `WEB/components/WorkItems.tsx` · `WEB/components/SensorPanel.tsx` · `WEB/components/ActionWorkspace.tsx`)를 같은 파일 안에서 화면 영역 단위 하위 컴포넌트로 나눈다(정의 위치는 모듈 최상위 — 렌더 안에서 새로 정의하지 않는다)
- [X] T024 [US2] 분리 게이트: T020과 같은 방법으로 DOM 스냅샷 diff 0 확인 + 사건 상세에서 질문 입력 중 다른 패널을 열고 닫아도 입력이 남는지 수동 확인. 결과를 `FEAT/records/steps.md`에 적는다

**Checkpoint**: 화면 완료 — 160자 초과 줄 0, 보이는 것 동일

---

## Phase 5: User Story 3 — 조사 보조자 · MCP 정리 (Priority: P2)

**Goal**: 40줄 넘는 함수 11개 분리, 짧은 이름 정리 — 도구 · 노드 · 이벤트 그대로

**Independent Test**: `uv run pytest -q` 39+2 · MCP 도구 목록 diff 0 · 스모크 · 재시작 스모크

공통: research R6(같은 모듈 `_보조함수`, 공개 이름 · 시그니처 · 반환 모양 · 도구 등록 순서 · docstring 유지). 각 작업 뒤 `uv run pytest -q`

### 5a. 긴 함수 분리

- [X] T025 [US3] `AG/business/server.py`의 `build_server`(143줄): 읽기 도구 등록과 변경 도구 등록을 `_register_read_tools(mcp, …)` · `_register_change_tools(mcp, …)`로 나누고 등록 순서 · 도구 함수 이름 · docstring · 인자를 그대로 둔다. 끝나면 T009 방식으로 도구 목록을 다시 뽑아 `FEAT/records/mcp-tools-before.json`과 diff 0
- [X] T026 [US3] `AG/rag/server.py`의 `build_server`(74줄): 검색 도구 등록과 리소스 등록을 나눈다. 도구 목록 diff 0
- [X] T027 [P] [US3] `AG/rag/index.py`의 `load_corpora`(43줄): 코퍼스 종류(원문 · 점검 문서 · 사례)별 읽기 함수로 나눈다
- [X] T028 [US3] `AG/agent/models/rule.py`의 `ainvoke`(113줄): 결정 단계(어떤 도구를 부를지 · 제안할지 · 결과를 낼지)별 보조 함수로 나누고 분기 순서를 그대로 둔다(규칙 모델은 결정적 — 순서가 바뀌면 판단이 바뀐다)
- [X] T029 [P] [US3] `AG/agent/graph/nodes/required_evidence.py`의 `required_evidence`(69줄): 사건 · 경로 · 단계별 run · 현재 run 특징 조회를 단계 함수로 나눈다. 노드 함수 이름 그대로
- [X] T030 [P] [US3] `AG/agent/graph/nodes/tools.py`의 `save_proposal`(44줄) · `AG/agent/graph/nodes/finalize.py`의 `finalize`(41줄): 검증 · 저장 · 상태 갱신 단계로 나눈다
- [X] T031 [P] [US3] `AG/agent/brief.py`의 `build`(43줄): 편차 · 원인 후보 · 관련 기록 · 확인 항목 만들기로 나눈다
- [X] T032 [P] [US3] `AG/agent/harness/guard.py`의 `validate_result`(43줄): 검사 항목(근거 ID · 확률 표현 · 원인 ID · nextStep 일치)별 함수로 나누고 검사 순서 · 오류 메시지를 그대로 둔다
- [X] T033 [P] [US3] `AG/devtools/demo.py`의 `evaluate`(52줄) · `AG/devtools/spring.py`의 `drive`(50줄): 단계별로 나눈다

### 5b. 짧은 이름 정리

- [X] T034 [P] [US3] 이름 정리: `AG/agent/models/rule.py`(28) · `AG/agent/harness/guard.py`(15) · `AG/agent/harness/trace.py`(13) · `AG/agent/evidence.py`(10) — trace 레코드 키 문자열은 그대로
- [X] T035 [P] [US3] 이름 정리: `AG/rag/index.py`(38) · `AG/rag/server.py`(7)
- [X] T036 [P] [US3] 이름 정리: `AG/agent/graph/nodes/`의 `finalize.py`(14) · `required_evidence.py`(9) · `tools.py`(6) · `request_brief.py`(5) · `agent.py` · `approval_wait.py` · `start.py`, `AG/agent/graph/routes.py` · `build.py` · `state.py` — `build.py`의 노드 이름 문자열은 그대로
- [X] T037 [P] [US3] 이름 정리: `AG/devtools/demo.py`(17) · `AG/devtools/spring.py`(10)
- [X] T038 [P] [US3] 이름 정리: `AG/agent/worker/runner.py`(6) · `AG/agent/worker/sources/kafka.py` · `AG/agent/worker/sources/http.py` · `AG/agent/brief.py`(5) · `AG/agent/tools/client.py`(5) · `AG/agent/tools/definitions.py` · `AG/agent/harness/context.py` · `AG/agent/backend.py` · `AG/agent/models/__init__.py` · `AG/business/server.py` · `AG/common/env.py`
- [X] T039 [US3] 조사 보조자 게이트: [contracts/gates.md](./contracts/gates.md) 전체 게이트 중 1 · 3 · 4 · 5 · 7 + MCP 도구 목록 diff 0을 확인해 `FEAT/records/steps.md`에 적는다(스모크는 `down -v` 재시드 후, 사용자 확인)

**Checkpoint**: 조사 보조자 완료

---

## Phase 6: User Story 4 — 업무 시스템 긴 메서드 분리 (Priority: P2)

**Goal**: 40줄 넘는 메서드 9개를 같은 클래스 private 메서드로 나눈다

**Independent Test**: `./gradlew test` 109+2 · 계약 대조 동일 · 패키지 순환 없음

공통: research R7(같은 클래스 private 메서드만 · `@Transactional` 진입 메서드 유지 · 예외 종류 · 메시지 · **검사 순서** 유지 · 다른 bean으로 이동 금지 · 탭 들여쓰기 등 주변 관례). 각 작업 뒤 `./gradlew test`

- [X] T040 [P] [US4] `API/domain/dashboard/DashboardService.java`의 `get`(66줄, `@Transactional(readOnly = true)`): 기간 해석 → 집계 → 응답 조립 단계로 나눈다
- [X] T041 [P] [US4] `API/domain/approval/ApprovalService.java`의 `decide`(65줄, `@Transactional`): 권한 · 만료 · 버전 · 해시 검증 → 결정 기록 → 재개 작업/즉시 실행 분기로 나눈다. 검증 순서를 그대로 둔다
- [X] T042 [P] [US4] `API/domain/checkrequest/CheckRequestService.java`의 `reply`(64줄, `@Transactional`): 입력 · 권한 검증 → 회신 저장 → 재개 작업 생성 → 사건 · 요청 상태 갱신으로 나눈다
- [X] T043 [P] [US4] `API/internal/query/InternalProcessQuery.java`의 `trace`(58줄): 조회 · 분석 시점 차단 → 다운샘플 → 구간 감지 · 근거 → 응답 조립으로 나눈다
- [X] T044 [P] [US4] `API/seed/SeedRunner.java`의 `seed`(57줄): 시드 순서(사용자 → 문서 → run → 계측 · 특징 → 사건 → 작업 · 결과 · 승인)를 그대로 둔 채 데이터 종류별 메서드로 나눈다
- [X] T045 [P] [US4] `API/domain/processdata/ProcessEvidenceService.java`의 `runEvidence`(56줄): 계측 공개 여부 · R1 · OOS · 같은 PM z · 참고 범위 계산을 각각 메서드로 나눈다(계산식 · 반올림 그대로)
- [X] T046 [P] [US4] `API/domain/processdata/SegmentDetector.java`의 `detect`(52줄): 대상 구간 선택 → 이동평균 → 연속 이탈 구간 찾기 → 결과 만들기로 나눈다. `SegmentDetectorTest` 기대값 그대로
- [X] T047 [P] [US4] `API/internal/AgentEventService.java`의 `handle`(50줄): 이벤트 종류별 처리 메서드로 나누고 종류 판별 순서를 그대로 둔다
- [X] T048 [P] [US4] `API/contract/AgentResultMapper.java`의 `toResult`(47줄): 관측 사실 · 원인 후보 · 다음 확인 · 한계 변환을 각각 메서드로 나눈다
- [X] T049 [US4] 업무 시스템 게이트: 전체 게이트 1 · 2 · 3 · 4 · 7을 확인해 `FEAT/records/steps.md`에 적는다

**Checkpoint**: 업무 시스템 완료

---

## Phase 7: User Story 5 — 스크립트 정리 (Priority: P3)

**Goal**: 평가 · 데이터 조립 · 점검 스크립트를 같은 기준으로 정리 — 출력 그대로

**Independent Test**: 데이터 팩 해시 동일 · 점검 스크립트 출력 diff 0 · 스모크 통과

- [X] T050 [US5] `data/build_pack_v2.py`: `check`(61줄)를 검사 항목별 함수로 나누고 짧은 이름 59곳을 정리한다. 실행 결과가 `data/fab-causal-pack-v2/SHA256SUMS.txt`와 같은지 T011 방법으로 확인
- [X] T051 [P] [US5] `fab-agent/evaluation/compose_smoke.py`: `approve_to_end`(42줄)를 승인 · 회신 · 보고서 단계로 나누고 이름 21곳 정리. check 라벨 문자열은 그대로(기준선 비교 대상)
- [X] T052 [P] [US5] 이름 정리: `fab-agent/evaluation/scenario_eval.py`(37) · `retrieval_eval.py`(23) · `trace_stats.py`(14) · `compare_models.py`(11) · `real_eval.py`(8) · `eval_env.py`(6) · `restart_smoke.py`(1) — 출력 형식 그대로
- [X] T053 [P] [US5] 이름 정리: `tools/openapi_diff.py`(22) · `tools/java_pkgdeps.py`(21) — 같은 대상으로 실행해 `FEAT/records/openapi-before.txt` · T011의 순환 결과와 출력 diff 0
- [X] T054 [US5] 스크립트 게이트: 스모크 · 재시작 스모크를 다시 돌려 기준선과 같은지 `FEAT/records/steps.md`에 적는다

**Checkpoint**: 모든 이야기 완료

---

## Phase 8: Polish & 최종 확인

- [X] T055 최종 전체 게이트([contracts/gates.md](./contracts/gates.md) 1–7)를 처음 시드 상태에서 한 번에 돌려 `FEAT/records/baseline.md` 옆에 `FEAT/records/final.md`로 적는다
- [X] T056 `python3 FEAT/tools/metrics.py > FEAT/records/metrics-after.txt` 후 before와 비교해 SC-006(최대 줄 ≤ 160 · 초과 파일 0) · SC-007(40줄 초과 0 또는 exceptions) · SC-008(짧은 이름 0)을 `FEAT/records/final.md`에 적는다
- [X] T057 `git diff ee171d2 --stat -- 'fab-context-api/src/test' 'fab-agent/tests' 'fab-context-web/tests'`로 테스트 변경을 확인하고, 바뀐 줄이 있으면 기대값이 아닌 이름 정리뿐인지 근거를 `FEAT/records/final.md`에 적는다(SC-005)
- [X] T058 [P] `FEAT/records/exceptions.md` · `FEAT/records/deferred.md`를 정리하고, 보류 목록이 있으면 사용자에게 요약해 보고한다
- [X] T059 [P] `.specify/memory/constitution.md` 원칙 VIII와 결과를 대조해 위반이 없는지 `FEAT/records/final.md`에 적는다
- [X] T060 `./scripts/deploy.sh down`(`-v` 금지). 커밋은 사용자가 요청할 때만, 구성요소별로 나눠 제안한다 — 예: `refactor(web): 압축된 화면 코드를 펼치고 큰 화면을 나눔`, `refactor(agent): 긴 함수 분리 · 변수 이름 정리`, `refactor(api): 긴 서비스 메서드를 단계별로 분리`, `refactor(scripts): 평가 · 점검 스크립트 정리`

---

## Dependencies & Execution Order

### 단계 의존

- **Phase 1 → Phase 2 → Phase 3(US1 기준선)** — 기준선 없이는 어떤 리팩터링도 시작하지 않는다
- **US2 · US3 · US4 · US5**는 US1 이후 서로 독립(다른 구성요소). 다만 전체 게이트의 스모크 · DOM 스냅샷은 한 배포를 쓰므로 **게이트 작업(T020 · T024 · T039 · T049 · T054)은 동시에 돌리지 않는다**
- **Phase 8**은 모든 이야기 이후

### 이야기 안의 순서

- US2: 펼치기(T012–T019) → 펼치기 게이트(T020) → 분리(T021–T023) → 분리 게이트(T024). T018 → T021, T019 → T022
- US3: 긴 함수(T025–T033) → 이름(T034–T038) → 게이트(T039). 같은 파일을 만지는 T028 → T034, T029 · T030 → T036, T031 → T038, T033 → T037, T026 · T027 → T035, T025 → T038
- US4: T040–T048 서로 다른 파일 → 병렬, 끝나고 T049
- US5: T050 · T051–T053 병렬 → T054

### 병렬 기회

- T002 · T003 · T004
- 화면 펼치기 T012–T017(서로 다른 파일)
- 조사 보조자 T027 · T029–T033, 이름 정리 T034–T038(파일 겹침 없을 때)
- 업무 시스템 T040–T048 전부
- 스크립트 T051–T053

## Parallel Example: US4

```text
T040 DashboardService.get    T041 ApprovalService.decide    T042 CheckRequestService.reply
T043 InternalProcessQuery.trace    T044 SeedRunner.seed    T045 ProcessEvidenceService.runEvidence
T046 SegmentDetector.detect    T047 AgentEventService.handle    T048 AgentResultMapper.toResult
→ 모두 끝나면 T049 게이트
```

## Implementation Strategy

### MVP 먼저 (US1 + US2 펼치기)

1. Phase 1–3: 도구 · 기준선 — 여기까지면 「동작 불변을 증명하는 장치」가 생긴다
2. US2 4a(펼치기) → T020 게이트 — 가장 큰 가독성 효과, 구조 변경 없는 안전한 단계
3. **멈추고 확인**: DOM diff 0 · 테스트 통과면 첫 커밋 제안

### 점진 진행

1. US2 4b(분리) → US3 → US4 → US5, 이야기마다 게이트 후 커밋 제안
2. Phase 8 최종 확인 · 지표 비교

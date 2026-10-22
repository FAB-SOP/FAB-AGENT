---

description: "FAB CONTEXT 아키텍처 정리 및 핵심 사용자 시나리오 — 작업 목록 (검증 범위 US1 · US2)"
---

# Tasks: FAB CONTEXT 아키텍처 정리 및 핵심 사용자 시나리오

**Input**: `specs/001-architecture-user-scenarios/`의 설계 문서

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**범위**: 핵심 시나리오 2개 — **US1 기본 분석** · **US2 제안 · 승인**(plan.md, research R7). US3–US6은 보류로, 이 목록에 작업이 없다.

**Tests**: 새 테스트 코드는 만들지 않는다(문서 기능). 수용 기준 12개(US1 5 + US2 7)는 기존 자동 테스트 결과와 quickstart 4의
사람 확인으로 검증하고, 결과는 `specs/001-architecture-user-scenarios/verification.md`에 남긴다.

**Organization**: 시나리오별 단계마다 「자동 검증 결과 대조 → 화면에서 확인 → 기록 → 문서와 다르면 문서 수정」. 런타임 코드
(`fab-context-web` · `fab-context-api` · `fab-agent` 소스)는 바꾸지 않는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 작업에 의존하지 않음)
- **[Story]**: 해당 사용자 시나리오(US1 · US2)
- 경로 약어: `FEAT` = `specs/001-architecture-user-scenarios`

---

## Phase 1: Setup (기록 파일 · 검증 환경)

- [X] T001 `FEAT/verification.md`를 만든다 — 제목 「검증 기록」, 머리말에 날짜 · 커밋 해시(`git rev-parse --short HEAD`) · 모드(rule · hash) · 범위(US1 · US2), 섹션 「자동 검증」「US1」「US2」「후속」. 「US1」「US2」에는 `FEAT/contracts/scenario-traceability.md`의 해당 표 수용 기준 번호를 행으로 하는 표(기준 · 검증 방법 · 결과 ✅/❌ · 메모)를 비워 둔다
- [X] T002 로컬 배포를 띄운다: 저장소 루트에서 `./scripts/deploy.sh up -d --build --wait` 후 `./scripts/deploy.sh ps`로 postgres · backend · business-mcp · rag-mcp · agent · web이 모두 healthy인지 확인해 `FEAT/verification.md`「자동 검증」에 적는다. `fab-deploy` 볼륨에 이전 데이터가 있어 EV-1006 · EV-1007 · EV-1009가 처음 상태가 아니면, `down -v`(DB 삭제) 전에 반드시 사용자에게 묻는다

---

## Phase 2: Foundational (두 시나리오 검증의 전제)

**⚠️ 이 단계가 끝나야 시나리오 검증을 시작한다** — 자동 테스트 결과가 수용 기준의 근거다.

- [X] T003 [P] backend 테스트: `cd fab-context-api && ./gradlew test` 실행, 통과 · 실패 수를 `FEAT/verification.md`「자동 검증」에 적는다(기대 105개 통과). 실패가 있으면 실패한 테스트 이름과 첫 오류 줄을 함께 적고, 범위 테스트(EventApiTest · AnalysisShapeApiTest · ApprovalApiTest · InternalApiTest)인지 표시한다
- [X] T004 [P] Agent 테스트: `cd fab-agent && uv run pytest -q` 실행, 통과 · 실패 · 건너뜀 수를 `FEAT/verification.md`「자동 검증」에 적는다(정답표가 없으면 `test_eval_all_incidents_pass`가 건너뛰어진다 — 그 사실을 적는다)
- [X] T005 [P] 프론트 테스트: `cd fab-context-web && npm ci && npm test` 실행, 결과를 `FEAT/verification.md`「자동 검증」에 적는다(US1 수용 기준 4의 `analysis-request-status.test.tsx` · `incident-notifications.test.ts` 포함 여부)
- [X] T006 compose 스모크: T002 직후 처음 시드된 상태에서 `./scripts/deploy.sh --profile tools run --rm --build --no-deps smoke` 실행, 「실패 0건」 여부와 check 라벨별 결과를 `FEAT/verification.md`「자동 검증」에 적는다. 이 스모크가 EV-1009의 질문 → 승인 → 회신 → 보고서를 소비하므로 수동 시연(T009 이후)보다 먼저, 한 번만 실행한다
- [X] T007 [P] 구조도 · 코드 대조: quickstart.md 2의 표 다섯 줄을 실행하고(공개 API 수는 `python3 tools/openapi_diff.py http://127.0.0.1:8088`), `FEAT/architecture.md` 1.1 · 1.3 · 1.4 · 1.5 · 1.6과 다르면 코드에 맞게 `FEAT/architecture.md`를 고친다(research R3 우선순위). 결과를 `FEAT/verification.md`「자동 검증」에 적는다
- [X] T008 [P] Mermaid 렌더링: quickstart.md 1의 명령으로 `FEAT/architecture.md` · `FEAT/data-model.md`를 렌더링해 8/8 성공을 확인한다. 실패한 블록은 해당 파일에서 고친다

**Checkpoint**: 자동 검증 · 구조도 대조 · 렌더링 결과가 기록되었다 — US1 · US2를 시작할 수 있다

---

## Phase 3: User Story 1 — 이상 사건 자동 접수와 기본 분석 (Priority: P1) 🎯 MVP

**Goal**: 이벤트 → 사건 → 기본 분석 결과(관측 사실 · 원인 후보 · 한계 · 근거 ID)가 spec.md US1대로 동작함을 보인다

**Independent Test**: `cmp.park`로 EV-1009 상세를 열어 결과 구조 · 근거 ID · 경로를 확인한다(스모크 이후에도 기본 분석 결과는 이력에 남아 있다)

- [X] T009 [US1] http://127.0.0.1:8088 에서 `cmp.park`(비밀번호 `fab1234!`)로 이상 조사 → EV-1009를 열어 수용 기준 3을 확인한다: 웨이퍼 경로 CVD-PM1 → ETCH-PM2 → CMP-PM3, 기본 분석 결과가 관측 사실 · 원인 후보(지지 · 반대 근거) · 한계로 나뉨, 모든 사실 · 후보에 근거 ID, 「확률」「%」 · 「원인 확정」 표현 없음. 스모크가 만든 이후 결과와 구분해 **기본 분석(BASIC)** 결과를 본다. 결과를 `FEAT/verification.md`「US1」 표 3행에 적는다
- [X] T010 [US1] 수용 기준 4(실시간 갱신 · 끊김 복구): EV-1006 상세를 연 채 「기본 분석 다시 실행」으로 작업을 만들어 새로 고침 없이 진행 상태가 바뀌는지 보고, 진행 중에 `./scripts/deploy.sh restart web`을 실행한 뒤 화면이 작업 상태를 다시 조회해 복구되는지 확인한다. EV-1006을 쓰는 이유: EV-1009는 스모크가 썼고, 재실행이 T012의 질문을 막으면 안 되므로 재실행 작업이 끝난 뒤 T012로 넘어간다. 결과를 `FEAT/verification.md`「US1」 표 4행에 적는다
- [X] T011 [US1] 수용 기준 1 · 2 · 5를 T003 · T004 결과(`EventApiTest.newEventCreatesIncidentAndQueuesBasicAnalysis`, `EventApiTest.duplicateEventReturnsExistingIncident`, `InternalApiTest.analysisTimeAndBaselineRulesAreEnforced`, `test_errors_are_readable_and_followup_is_blocked`)로 `FEAT/verification.md`「US1」 표에 채운다. T009 · T010에서 화면이 `FEAT/spec.md` US1 설명 · 수용 기준과 다른 점이 있으면 spec.md US1을 실제 동작에 맞게 고치고, 무엇을 왜 고쳤는지 `FEAT/verification.md`「후속」에 적는다

**Checkpoint**: US1 검증 완료 — 「AI가 근거 기반 첫 판단을 준다」를 문서와 실제가 같은 상태로 시연할 수 있다(MVP)

---

## Phase 4: User Story 2 — 추가 질문 · 제안 · 승인 (Priority: P1)

**Goal**: 「AI는 제안만, 사람이 승인한 것만 정확히 한 번 실행」(헌법 I)을 화면과 자동 검증으로 확인한다

**Independent Test**: EV-1006 질문 → 승인 대기 → 요청 0건 → 승인 → 요청 1건 · 브리프 일치, EV-1007 거절 → 재개

- [X] T012 [US2] 승인 흐름(수용 기준 1 · 2 · 7): `cmp.park`로 EV-1006에 질문 「헤드 압력 이상이 두께에 영향을 줬는가? 누구에게 무엇을 확인해야 하나?」를 남기고 제안이 나올 때까지 기다린다. 제안 검토 · 승인 화면에서 ① 종류 = 정비 요청, 받는 사람 = 설비정비팀(`mnt.jung`) ② 브리프에 head_pressure 편차 수치 · 「확정 아님」 원인 후보(CMP_PRESSURE 계열) · 확인 항목 ③ 설정값 변경 처방 없음을 확인하고, 확인 · 정비 요청 목록에 EV-1006 요청이 **0건**인지 본다. 제안이 나오지 않거나 종류 · 대상이 다르면 질문 문구와 실제 결과를 그대로 `FEAT/verification.md`「US2」 1행에 ❌로 적고 T013으로 넘어가지 말고 사용자에게 알린다(EV-1006 질문에 규칙 모델이 정비 요청을 제안하는지는 아직 실제로 돌려 보지 않았다)
- [X] T013 [US2] 승인 실행(수용 기준 4): T012의 제안을 승인하고, 조사가 재개되어 요청이 만들어질 때까지 기다린 뒤 확인 · 정비 요청 목록에 EV-1006 요청이 **정확히 1건**이고 요청 상세의 브리프 · 원인 후보 · 대상 설비가 승인 화면에서 본 내용과 같은지 비교한다. 같은 승인 화면에서 다시 승인 버튼을 누를 수 없거나 눌러도 요청이 늘지 않는지 본다. 결과를 `FEAT/verification.md`「US2」 4행에 적는다
- [X] T014 [US2] 거절 흐름(수용 기준 6): `cmp.park`로 EV-1007에 질문 「슬러리 유량 저하가 원인인가?」 → 제안이 나오면 사유 「현장에서 이미 점검 중」과 함께 거절한다. 조사가 재개되어 요청 없이 결과가 정리되고(확인 · 정비 요청 목록에 EV-1007 요청 0건), 같은 사건에 새 질문을 남길 수 있는지 확인해 `FEAT/verification.md`「US2」 6행에 적는다
- [X] T015 [US2] 수용 기준 3 · 5와 1 · 2 · 4 · 6 · 7의 자동 근거를 T003 · T004 · T006 결과(`ApprovalApiTest.decisionGuardsVersionHashStateAndScope`, `ApprovalApiTest.expiredApprovalCannotBeExecuted`, `InternalApiTest.proposalRecipientIsCheckedByBackend`, `InternalApiTest.agentProposalIsRecordedOnApprovalAndExecutedOnceByApprovalId`, `InternalApiTest.briefCannotConfirmACause`, `test_proposal_must_match_evidence`, `test_no_change_before_approval_and_reject_path`, `test_action_hash_covers_brief`, 스모크 「승인 전 확인 요청 0건」「확인 요청은 정확히 1건(멱등)」)로 `FEAT/verification.md`「US2」 표에 채운다. 화면이 `FEAT/spec.md` US2와 다르면 spec.md US2를 고치고 `FEAT/verification.md`「후속」에 적는다

**Checkpoint**: US2 검증 완료 — 핵심 시나리오 2개가 모두 검증되었다

---

## Phase 5: Polish & 마무리

- [X] T016 [P] `README.md` 루트의 ASCII 구성도 코드 블록 바로 아래에 한 줄 추가: `상세 구조도 · 사용자 시나리오: [architecture.md](specs/001-architecture-user-scenarios/architecture.md) · [spec.md](specs/001-architecture-user-scenarios/spec.md)` (research R6)
- [X] T017 [P] `fab-context-api/README.md` 「시드 시나리오」 표의 EV-1009 「z ≈ −2.8」을 「z ≈ −2.7」로 고친다(research R3 — 서버 계산 −2.74, 검증 중 정정)
- [X] T018 `FEAT/contracts/scenario-traceability.md`의 US1 · US2 표에서 「수동」 표시가 있는 행(US1-4 끊김 복구) 옆에 `verification.md` 결과(✅/❌)를 덧붙이고, 검증 중 이름이 다르게 드러난 테스트가 있으면 표의 테스트 이름을 고친다
- [X] T019 `FEAT/verification.md`「후속」에 ❌ 항목 · 원인 · 제안 후속 작업과 「다음 반복: US3(회신) → US4(보고서)」를 정리하고, `FEAT/plan.md` 「후속」 목록의 완료 항목에 ✅를 붙인다
- [X] T020 `FEAT/checklists/requirements.md`를 다시 검토해 T011 · T015의 spec.md 수정으로 깨진 항목이 없는지 확인한다(상태가 바뀐 줄의 체크 표시만 바꾼다)
- [X] T021 `./scripts/deploy.sh down`으로 정리한다(`-v` 금지 — DB 보존). 커밋은 사용자가 요청할 때만 한다 — 제안 메시지: `docs: 아키텍처 구조도 · 핵심 시나리오(US1 · US2) 명세와 검증 기록 추가`

---

## Dependencies & Execution Order

### 단계 의존

- **Phase 1 (T001–T002)** → **Phase 2 (T003–T008)** → **Phase 3 US1 (T009–T011)** → **Phase 4 US2 (T012–T015)** → **Phase 5 (T016–T021)**
- T006(스모크)은 T002 직후 · T009 전에 한 번만 — 시드 상태(EV-1009)를 소비한다
- T016 · T017은 다른 작업과 무관해 언제든 할 수 있다

### 시나리오 의존

- **US1 (P1)**: Phase 2 뒤 바로. EV-1009(결과 확인) · EV-1006(재실행 · 복구 확인)
- **US2 (P1)**: Phase 2 뒤 시작 가능, US1에 기능 의존은 없다. 단 T010이 EV-1006에 재실행 작업을 만들므로 **T010 작업이 끝난 뒤** T012를 시작한다(진행 중 작업이 있으면 질문이 막힌다)
- US2 안: T012 → T013(같은 제안), T014는 EV-1007이라 T012 · T013과 독립

### 병렬 기회

- T003 · T004 · T005 · T007 · T008 (서로 다른 도구 · 파일)
- T014(EV-1007 거절)는 T012 · T013(EV-1006)과 동시에 진행 가능 — 화면 탭을 나눠서
- T016 · T017 (서로 다른 README)

## Parallel Example: Phase 2

```bash
(cd fab-context-api && ./gradlew test) &
(cd fab-agent && uv run pytest -q) &
(cd fab-context-web && npm ci && npm test) &
wait
```

## Parallel Example: US2

```text
탭 1 (EV-1006): T012 질문 → 제안 확인 → T013 승인 → 요청 1건 확인
탭 2 (EV-1007): T014 질문 → 제안 거절 → 재개 확인
```

## Implementation Strategy

### MVP 먼저 (US1)

1. Phase 1 → Phase 2(자동 검증 · 구조도 대조 · 렌더링)
2. Phase 3(US1) → `verification.md`에 기록
3. **멈추고 확인**: 구조도와 US1이 실제와 맞으면 문서를 공유할 수 있는 최소 상태

### 이어서 (US2)

1. Phase 4(US2) — 승인 게이트까지 검증하면 이번 범위 완료
2. Phase 5 정리 — README 링크 · 수치 정정 · 추적표 · 체크리스트
3. 다음 반복: US3(회신) → US4(보고서)를 plan.md에 다시 넣고 `/speckit-tasks` 재실행

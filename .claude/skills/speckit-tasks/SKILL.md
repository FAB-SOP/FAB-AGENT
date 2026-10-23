---
name: "speckit-tasks"
description: "Generate an actionable, dependency-ordered tasks.md for the feature based on available design artifacts."
argument-hint: "Optional task generation constraints"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/tasks.md"
user-invocable: true
disable-model-invocation: false
---


## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## 산출물 위치 (프로젝트 규칙 — 아래 Outline의 경로보다 우선)

speckit 명령이 만들거나 고치는 문서는 **별도 파일을 만들지 않고, 그 명령 스킬 폴더의 `SKILL.md` 끝
「Feature Artifacts」 섹션 안에 블록으로** 저장한다. `<기능>`은 `NNN-short-name` 형식이며
`.specify/feature.json`의 `feature_directory` 값과 같다.

```markdown
<!-- ARTIFACT:<기능>:<문서> BEGIN -->
(문서 내용)
<!-- ARTIFACT:<기능>:<문서> END -->
```

| 문서 | 저장할 SKILL.md |
|------|-----------------|
| 헌법 | `.claude/skills/speckit-constitution/SKILL.md`의 「Project Constitution」 섹션 (`CONSTITUTION:BEGIN/END` 표식) |
| `spec.md` · `checklists/requirements.md` (`/speckit-specify`가 만들고 `/speckit-clarify`가 수정) | `.claude/skills/speckit-specify/SKILL.md` |
| `plan.md` · `research.md` · `data-model.md` · `quickstart.md` · `contracts/<이름>.md` | `.claude/skills/speckit-plan/SKILL.md` |
| `tasks.md` (`/speckit-converge`가 덧붙이고 `/speckit-implement`가 체크) | `.claude/skills/speckit-tasks/SKILL.md` |
| `checklists/<도메인>.md` (`/speckit-checklist`) | `.claude/skills/speckit-checklist/SKILL.md` |
| 분석 보고서를 남길 때 `analysis.md` (`/speckit-analyze`) | `.claude/skills/speckit-analyze/SKILL.md` |
| 구현 메모를 남길 때 `implementation-notes.md` (`/speckit-implement`) | `.claude/skills/speckit-implement/SKILL.md` |

- 스크립트 JSON의 `FEATURE_SPEC` · `IMPL_PLAN` · `TASKS` 등은 `<SKILL.md 절대 경로>#<기능>:<문서>` 형식의
  **블록 참조**다. `#` 앞 파일을 열고 해당 BEGIN/END 표식 사이만 읽고 고친다. `FEATURE_DIR`는 기능 이름이다.
- Outline의 `FEATURE_DIR/plan.md`, `FEATURE_DIR/tasks.md`, `FEATURE_DIR/checklists/` 같은 경로는 위 표의
  블록으로 바꿔 읽는다. "파일을 만든다 · 쓴다"는 "블록이 없으면 SKILL.md 끝에 덧붙이고, 있으면 그 블록 안을
  고친다"로 읽는다. 블록 밖(스킬 지침 · 다른 기능의 블록)은 고치지 않는다.
- 블록이 없을 때는 「Feature Artifacts」 섹션 끝에 덧붙인다. 섹션이 없으면 파일 끝에 `---`와
  `## Feature Artifacts`를 먼저 만든다.
- 저장소 루트의 `specs/`와 `.specify/memory/constitution.md`는 더 이상 쓰지 않는다.

## Pre-Execution Checks

**Check for extension hooks (before tasks generation)**:
- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_tasks` key
- If the YAML cannot be parsed or is invalid, skip hook checking silently and continue normally
- Filter out hooks where `enabled` is explicitly `false`. Treat hooks without an `enabled` field as enabled by default.
- For each remaining hook, do **not** attempt to interpret or evaluate hook `condition` expressions:
  - If the hook has no `condition` field, or it is null/empty, treat the hook as executable
  - If the hook defines a non-empty `condition`, skip the hook and leave condition evaluation to the HookExecutor implementation
- When constructing slash commands from hook command names, replace dots (`.`) with hyphens (`-`). For example, `speckit.git.commit` → `/speckit-git-commit`.
- For each executable hook, output the following based on its `optional` flag:
  - **Optional hook** (`optional: true`):
    ```
    ## Extension Hooks

    **Optional Pre-Hook**: {extension}
    Command: `/{command}`
    Description: {description}

    Prompt: {prompt}
    To execute: `/{command}`
    ```
  - **Mandatory hook** (`optional: false`):
    ```
    ## Extension Hooks

    **Automatic Pre-Hook**: {extension}
    Executing: `/{command}`
    EXECUTE_COMMAND: {command}

    Wait for the result of the hook command before proceeding to the Outline.
    ```
    After emitting the block above you MUST actually invoke the hook and wait for it to finish before continuing. Run it the same way you would run the command yourself in this agent/session (the invocation may differ from the literal `{command}` id shown above, e.g. a skills-mode agent runs it as `/skill:speckit-...` or `$speckit-...`). Emitting the block alone does not run the hook.
- If no hooks are registered or `.specify/extensions.yml` does not exist, skip silently

## Outline

1. **Setup**: Run `.specify/scripts/bash/setup-tasks.sh --json` from repo root and parse FEATURE_DIR, TASKS_TEMPLATE, and AVAILABLE_DOCS list. `FEATURE_DIR` and `TASKS_TEMPLATE` must be absolute paths when provided. `AVAILABLE_DOCS` is a list of document names/relative paths available under `FEATURE_DIR` (for example `research.md` or `contracts/`). For single quotes in args like "I'm Groot", use escape syntax: e.g 'I'\''m Groot' (or double-quote if possible: "I'm Groot").

2. **Load design documents**: Read from FEATURE_DIR:
   - **Required**: plan.md (tech stack, libraries, structure), spec.md (user stories with priorities)
   - **Optional**: data-model.md (entities), contracts/ (interface contracts), research.md (decisions), quickstart.md (test scenarios)
   - **IF EXISTS**: Load `.claude/skills/speckit-constitution/SKILL.md`의 「Project Constitution」 섹션 for project principles and governance constraints
   - Note: Not all projects have all documents. Generate tasks based on what's available.

3. **Execute task generation workflow**:
   - Load plan.md and extract tech stack, libraries, project structure
   - Load spec.md and extract user stories with their priorities (P1, P2, P3, etc.)
   - If data-model.md exists: Extract entities and map to user stories
   - If contracts/ exists: Map interface contracts to user stories
   - If research.md exists: Extract decisions for setup tasks
   - Generate tasks organized by user story (see Task Generation Rules below)
   - Generate dependency graph showing user story completion order
   - Create parallel execution examples per user story
   - Validate task completeness (each user story has all needed tasks, independently testable)

4. **Generate tasks.md**: Read the tasks template from TASKS_TEMPLATE (from the JSON output above) and use it as structure. If TASKS_TEMPLATE is empty, fall back to `.specify/templates/tasks-template.md`. Fill with:
   - Correct feature name from plan.md
   - Phase 1: Setup tasks (project initialization)
   - Phase 2: Foundational tasks (blocking prerequisites for all user stories)
   - Phase 3+: One phase per user story (in priority order from spec.md)
   - Each phase includes: story goal, independent test criteria, tests (if requested), implementation tasks
   - Final Phase: Polish & cross-cutting concerns
   - All tasks must follow the strict checklist format (see Task Generation Rules below)
   - Clear file paths for each task
   - Dependencies section showing story completion order
   - Parallel execution examples per story
   - Implementation strategy section (MVP first, incremental delivery)

## Mandatory Post-Execution Hooks

**You MUST complete this section before reporting completion to the user.**

Check if `.specify/extensions.yml` exists in the project root.
- If it does not exist, or no hooks are registered under `hooks.after_tasks`, skip to the Completion Report.
- If it exists, read it and look for entries under the `hooks.after_tasks` key.
- If the YAML cannot be parsed or is invalid, skip hook checking silently and continue to the Completion Report.
- Filter out hooks where `enabled` is explicitly `false`. Treat hooks without an `enabled` field as enabled by default.
- For each remaining hook, do **not** attempt to interpret or evaluate hook `condition` expressions:
  - If the hook has no `condition` field, or it is null/empty, treat the hook as executable
  - If the hook defines a non-empty `condition`, skip the hook and leave condition evaluation to the HookExecutor implementation
- When constructing slash commands from hook command names, replace dots (`.`) with hyphens (`-`). For example, `speckit.git.commit` → `/speckit-git-commit`.
- For each executable hook, output the following based on its `optional` flag:
  - **Mandatory hook** (`optional: false`) — **You MUST emit `EXECUTE_COMMAND:` for each mandatory hook**:
    ```
    ## Extension Hooks

    **Automatic Hook**: {extension}
    Executing: `/{command}`
    EXECUTE_COMMAND: {command}
    ```
    After emitting the block above you MUST actually invoke the hook and wait for it to finish before continuing. Run it the same way you would run the command yourself in this agent/session (the invocation may differ from the literal `{command}` id shown above, e.g. a skills-mode agent runs it as `/skill:speckit-...` or `$speckit-...`). Emitting the block alone does not run the hook.
  - **Optional hook** (`optional: true`):
    ```
    ## Extension Hooks

    **Optional Hook**: {extension}
    Command: `/{command}`
    Description: {description}

    Prompt: {prompt}
    To execute: `/{command}`
    ```

## Completion Report

Output path to generated tasks.md and summary:
- Total task count
- Task count per user story
- Parallel opportunities identified
- Independent test criteria for each story
- Suggested MVP scope (typically just User Story 1)
- Format validation: Confirm ALL tasks follow the checklist format (checkbox, ID, labels, file paths)

Context for task generation: $ARGUMENTS

The tasks.md should be immediately executable - each task must be specific enough that an LLM can complete it without additional context.

## Task Generation Rules

**CRITICAL**: Tasks MUST be organized by user story to enable independent implementation and testing.

**Tests are OPTIONAL**: Only generate test tasks if explicitly requested in the feature specification or if user requests TDD approach.

### Checklist Format (REQUIRED)

Every task MUST strictly follow this format:

```text
- [ ] [TaskID] [P?] [Story?] Description with file path
```

**Format Components**:

1. **Checkbox**: ALWAYS start with `- [ ]` (markdown checkbox)
2. **Task ID**: Sequential number (T001, T002, T003...) in execution order
3. **[P] marker**: Include ONLY if task is parallelizable (different files, no dependencies on incomplete tasks)
4. **[Story] label**: REQUIRED for user story phase tasks only
   - Format: [US1], [US2], [US3], etc. (maps to user stories from spec.md)
   - Setup phase: NO story label
   - Foundational phase: NO story label
   - User Story phases: MUST have story label
   - Polish phase: NO story label
5. **Description**: Clear action with exact file path

**Examples**:

- ✅ CORRECT: `- [ ] T001 Create project structure per implementation plan`
- ✅ CORRECT: `- [ ] T005 [P] Implement authentication middleware in src/middleware/auth.py`
- ✅ CORRECT: `- [ ] T012 [P] [US1] Create User model in src/models/user.py`
- ✅ CORRECT: `- [ ] T014 [US1] Implement UserService in src/services/user_service.py`
- ❌ WRONG: `- [ ] Create User model` (missing ID and Story label)
- ❌ WRONG: `T001 [US1] Create model` (missing checkbox)
- ❌ WRONG: `- [ ] [US1] Create User model` (missing Task ID)
- ❌ WRONG: `- [ ] T001 [US1] Create model` (missing file path)

### Task Organization

1. **From User Stories (spec.md)** - PRIMARY ORGANIZATION:
   - Each user story (P1, P2, P3...) gets its own phase
   - Map all related components to their story:
     - Models needed for that story
     - Services needed for that story
     - Interfaces/UI needed for that story
     - If tests requested: Tests specific to that story
   - Mark story dependencies (most stories should be independent)

2. **From Contracts**:
   - Map each interface contract → to the user story it serves
   - If tests requested: Each interface contract → contract test task [P] before implementation in that story's phase

3. **From Data Model**:
   - Map each entity to the user story(ies) that need it
   - If entity serves multiple stories: Put in earliest story or Setup phase
   - Relationships → service layer tasks in appropriate story phase

4. **From Setup/Infrastructure**:
   - Shared infrastructure → Setup phase (Phase 1)
   - Foundational/blocking tasks → Foundational phase (Phase 2)
   - Story-specific setup → within that story's phase

### Phase Structure

- **Phase 1**: Setup (project initialization)
- **Phase 2**: Foundational (blocking prerequisites - MUST complete before user stories)
- **Phase 3+**: User Stories in priority order (P1, P2, P3...)
  - Within each story: Tests (if requested) → Models → Services → Endpoints → Integration
  - Each phase should be a complete, independently testable increment
- **Final Phase**: Polish & Cross-Cutting Concerns

## Done When

- [ ] tasks.md generated with all phases, task IDs, and file paths
- [ ] Extension hooks dispatched or skipped according to the rules in Mandatory Post-Execution Hooks above
- [ ] Completion reported to user with task count, story breakdown, and MVP scope

---

## Feature Artifacts

speckit 명령이 만든 산출물이다. 블록은 `<!-- ARTIFACT:<기능>:<문서> BEGIN/END -->` 표식으로 구분한다.

<!-- ARTIFACT:001-report-4m-classification:tasks.md BEGIN -->
# Tasks: 조사 보고서 4M 원인 분류

**Input**: `001-report-4m-classification` 블록들 — spec(`.claude/skills/speckit-specify/SKILL.md`), plan · research · data-model ·
contracts/public-api · quickstart(`.claude/skills/speckit-plan/SKILL.md`)

**Prerequisites**: plan의 「기존 코드 수정 목록」 M1–M13 · M8b 사용자 동의 완료(2026-10-22). 목록 밖 기존 코드는 고치기 전에 다시 확인받는다(원칙 X).
`ai/` 폴더는 수정하지 않는다.

**Tests**: 헌법 품질 게이트가 승인 · 권한 · 계약에 닿는 변경의 테스트를 요구하므로 테스트 작업을 포함한다.

**Paths**: `B` = `backend/src/main/java/com/fabcontext/api`, `BT` = `backend/src/test/java/com/fabcontext/api`, `F` = `frontend/src`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일 · 앞 작업 의존 없음 → 병렬 가능
- **[Story]**: US1 · US2 · US3 (spec 사용자 스토리)

---

## Phase 1: Setup (기준선)

- [X] T001 기준선 확인 — 변경 전 `cd backend && ./gradlew test`, `cd frontend && npm test && npm run build`, `cd ai && uv run pytest -q`를 돌려 통과 여부 · 실패 목록을 `.claude/skills/speckit-implement/SKILL.md`의 `001-report-4m-classification:implementation-notes.md` 블록에 기록(기존 실패는 이번 기능과 구분)

---

## Phase 2: Foundational (모든 스토리가 쓰는 분류 모델)

**⚠️ 이 단계가 끝나야 US1 · US2 · US3를 시작한다**

- [X] T002 [P] `ReportCauseCategory` enum(MATERIAL · MAN · METHOD · MACHINE · HOLD, `requiresEquipmentParameters()`는 MACHINE만 true) 작성 in `B/domain/report/ReportCauseCategory.java`
- [X] T003 [P] `CauseClassificationInput` 요청 record(`category` String 필수, `equipmentParameters` List<String> 선택 · `@Size(max=20)`) 작성 in `B/domain/report/CauseClassificationInput.java`
- [X] T004 `ReportCauseClassification` @Embeddable 값 객체(필드 `causeCategory` · `causeEquipmentParameters`(jsonb, `@JdbcTypeCode(SqlTypes.JSON)`) · `classifiedBy` · `classifiedAt`, 정적 생성자만 · 세터 없음, 파라미터 목록은 불변 복사) 작성 in `B/domain/report/ReportCauseClassification.java` (T002 의존)
- [X] T005 `CauseClassificationPolicy` @Component 작성 in `B/domain/report/CauseClassificationPolicy.java` — `PackStore.sensors()`로 공정별 센서 ID를 읽고 `validate(CauseClassificationInput, processCode, actorId, Instant)` → `ReportCauseClassification`. 규칙 · 오류 코드는 research R4 그대로(`CAUSE_CLASSIFICATION_REQUIRED` · `INVALID_CAUSE_CATEGORY` · `EQUIPMENT_PARAMETER_REQUIRED` · `INVALID_EQUIPMENT_PARAMETER`(hint에 허용 목록)), MACHINE 외 범주는 파라미터를 버리고, 파라미터는 중복 제거 · 카탈로그 순서. 422는 기존 `ApiException.unprocessable` 사용 (T002–T004 의존)
- [X] T006 [P] `CauseClassificationPolicyTest` 단위 테스트 in `BT/domain/report/CauseClassificationPolicyTest.java` — 다섯 범주 성공, 누락 · 알 수 없는 범주, MACHINE 파라미터 없음, 다른 공정 센서, 비-MACHINE 파라미터 무시, 중복 · 순서 정규화 (T005 의존)
- [X] T007 [P] 화면 분류 도메인 작성 in `F/domain/causeClassification.ts` — 범주 목록 · 라벨(「Material (자재)」 … 「보류 (원인 미확정)」), `isClassificationComplete(draft)`, `toClassificationRequest(draft)`(MACHINE 외 파라미터 제거), 사건 관련 센서 우선 정렬 `orderEquipmentOptions(sensors, incidentSensorIds)`
- [X] T008 [P] 분류 도메인 테스트 in `frontend/tests/cause-classification.test.ts` (T007 의존)
- [X] T009 [P] 공용 타입 · 라벨 추가 in `F/api/types.ts` — `CauseCategory` 타입, `ReportClassification` 인터페이스(category · equipmentParameters · classifiedBy · classifiedAt), `labels`에는 손대지 않고 새 상수만 export (M11)

**Checkpoint**: 분류 모델 · 검증 · 화면 도메인 로직이 단위 테스트로 통과

---

## Phase 3: User Story 1 — 확정할 때 원인 분류를 지정한다 (P1) 🎯 MVP

**Goal**: 직접 확정 시 4M · 보류 중 하나를 골라야 확정되고, 분류가 확정본 · 화면 · 목록 · Markdown · 이력에 남는다

**Independent Test**: 초안을 Method로 확정 → 다시 열면 Method(읽기 전용), Markdown에 원인 분류 줄, 검토만 체크하면 확정 불가

### Tests for User Story 1

- [X] T010 [P] [US1] `ReportClassificationApiTest` 확정 경로 테스트 작성 in `BT/api/ReportClassificationApiTest.java` — 분류 누락 422, 다섯 범주 중 METHOD · HOLD 확정 성공 · 응답 `classification`, 확정 후 `ReportView` · `ReportSummary`의 `classification`, Markdown `> 원인 분류:` 줄, 이력 문구, 도입 전 보고서 `classification: null`, 검사 순서(판본 충돌이 분류 오류보다 먼저) — 기존 `ApiTestSupport` 헬퍼 사용
- [X] T011 [US1] **M13(동의됨)** 기존 테스트 성공 경로 요청 본문에 `"classification":{"category":"METHOD"}` 추가 in `BT/api/ReportApiTest.java` 89행(확정). 기대 응답 · 다른 줄(85 · 87 · 97행 실패 경로)은 바꾸지 않는다

### Implementation for User Story 1

- [X] T012 [US1] **M1** `Report`에 `@Embedded private ReportCauseClassification classification` 추가, `confirm(String userId, Instant at, ReportCauseClassification classification)`로 확장(기존 시그니처 호출부는 T014에서 함께 수정) in `B/domain/report/Report.java`
- [X] T013 [US1] **M2** `ReportConfirm`에 `@Valid CauseClassificationInput classification`(검증은 정책에서), `ReportView` · `ReportSummary`에 `ClassificationView classification`(null 허용) 추가 in `B/domain/report/ReportDtos.java`
- [X] T014 [US1] **M3** `ReportService.confirm`이 검토 확인 → `doConfirm`에서 `requireDraft` · `checkRevision` 뒤 `CauseClassificationPolicy.validate`(사건 `processCode`) → `report.confirm(..., classification)` 순서로 처리, `REPORT_CONFIRMED` 이력 문구에 분류 덧붙임(research R6). `confirmFromApproval`은 시그니처에 분류 인자를 받도록만 바꾸고(US2에서 연결) in `B/domain/report/ReportService.java`
- [X] T015 [US1] **M4** `ReportComposer.markdown`에 머리 인용 줄 다음 `> 원인 분류: {라벨}[ — {센서 ID들}]` 추가(분류 있을 때만) in `B/domain/report/ReportComposer.java`
- [X] T016 [US1] 분류 선택 컴포넌트 작성 in `F/components/CauseClassificationSelect.tsx` — 범주 `<select>`(빈 선택 + 다섯 범주), MACHINE이면 장비 파라미터 체크 목록 영역(옵션은 props로 받음, US1에서는 빈 목록이면 「장비 파라미터 목록을 불러오지 못했습니다」 안내), `disabled` 지원, 기존 `ui.tsx`의 `Field` · 클래스 관례 따름
- [X] T017 [US1] **M9** `ConfirmPanel`에 `CauseClassificationSelect` 추가, 확정 버튼 조건에 `isClassificationComplete`, 미선택 안내 「원인 분류를 선택해 주세요」, 확정 요청 본문에 `classification: toClassificationRequest(draft)`, 확정본은 분류를 읽기 전용으로 표시 in `F/pages/Reports.tsx` (`useReportDraft`에 분류 상태 추가 — 판본 · 상태가 바뀌면 초기화)

- [X] T041 [US1] **M14(동의됨, 옵션 A)** 시드 보고서 확정 요청에 `new CauseClassificationInput("MACHINE", List.of("slurry_flow"))` 추가 in `backend/src/main/java/com/fabcontext/api/seed/SeedRunner.java` 203행 (T013 의존 — 구현 중 발견해 추가한 작업)

**Checkpoint**: US1 단독으로 Material · Man · Method · 보류 확정이 끝까지 동작(Machine은 US2에서 완성)

---

## Phase 4: User Story 2 — Machine이면 공정별 장비 파라미터를 고른다 · Agent 제안 승인 시 분류 (P1)

**Goal**: Machine 선택 시 사건 공정 센서 목록(센서 시계열과 같은 출처 · 이름)에서 1개 이상 고르고, Agent가 제안한 보고서 확정은 승인자가 승인할 때 같은 드롭다운으로 분류한다(spec US2 시나리오 1–7)

**Independent Test**: CMP 초안에서 Machine → CMP 센서 5개만 · 사건 관련 센서 우선, 미선택이면 확정 불가. 「보고서 확정」 승인 대기 건을 Machine · 헤드 압력으로 승인하면 보고서 분류 = 승인자 선택

### Tests for User Story 2

- [X] T018 [P] [US2] 센서 정의 조회 테스트 추가 in `BT/api/MonitoringApiTest.java` — CMP 5개 · ETCH 9개 · CVD 6개, 범위 밖 공정 403, 알 수 없는 공정 422 (기존 테스트 메서드는 바꾸지 않고 새 메서드만)
- [X] T019 [P] [US2] `ReportClassificationApiTest`에 Machine · 승인 경로 테스트 추가 in `BT/api/ReportClassificationApiTest.java` — Machine 파라미터 없음 422, 다른 공정 센서 422, Machine 확정 성공, CONFIRM_REPORT 승인 시 분류 누락 422 · 승인 성공 시 보고서 분류 = 입력 · `ApprovalView.decisionInput`, 거절은 분류 없이 성공, 다른 종류(CHECK_REQUEST) 승인은 분류 없이 기존처럼 성공
- [X] T020 [US2] **M13(동의됨)** 기존 테스트 승인 결정 요청 본문에 `"classification":{"category":"METHOD"}` 추가 in `BT/api/ReportApiTest.java` 124행(`confirmThroughApprovalProposal`). 기대 응답은 그대로

### Implementation for User Story 2

- [X] T021 [US2] **M8** `MonitoringService.sensorDefinitions(AuthUser, processCode)`(`requireScope` → 알 수 없는 공정 422 `INVALID_PROCESS_CODE` → `byProcess(pack.sensors(), processCode)`) 추가 in `B/domain/monitoring/MonitoringService.java`
- [X] T022 [US2] **M8** `GET /api/monitoring/sensor-definitions` 메서드(`operationId = listSensorDefinitions`) 추가 in `B/domain/monitoring/MonitoringController.java` (T021 의존, 기존 메서드 무변경)
- [X] T023 [US2] **M8b** 새 경로 · 응답 스키마(`MonitoringSensorDefinitionList` · `…Envelope`, 원소는 기존 `MonitoringSensorDefinition` 참조) 추가 in `backend/src/main/resources/openapi/monitoring-contract.json`
- [X] T024 [US2] **M6** `ApprovedActionHandler`에 `default Map<String, Object> normalizeDecisionInput(Map<String, Object> raw, Incident incident) { return null; }` 추가(Javadoc: 승인자 입력 검사 · 정규화, null = 입력 없음) in `B/domain/approval/ApprovedActionHandler.java`
- [X] T025 [US2] **M7** `ApprovalDecision`에 `Map<String, Object> classification`(선택), `ApprovalView`에 `decisionInput` 추가 in `B/domain/approval/ApprovalDtos.java`
- [X] T026 [US2] **M7** `Approval`에 `decisionInput` jsonb 열 · `recordDecisionInput(Map)`(PENDING에서만) 추가 in `B/domain/approval/Approval.java`
- [X] T027 [US2] **M7** `ApprovalService.decide`에서 `requireDecidable` 뒤 APPROVE일 때 핸들러 `normalizeDecisionInput(body.classification(), incident)` 결과를 `approval.recordDecisionInput`으로 **실행 · 재개 전에** 저장(거절 · 다른 종류는 기존 흐름 그대로) in `B/domain/approval/ApprovalService.java` (T024–T026 의존)
- [X] T028 [US2] **M5** `ConfirmReportHandler.normalizeDecisionInput` 재정의(입력 → `CauseClassificationInput` → `CauseClassificationPolicy` 검증, 정규화된 `{category, equipmentParameters}` 반환 — 누락이면 422), `execute`가 `approval.getDecisionInput()`과 결정자(`actorId`)로 분류를 만들어 `confirmFromApproval`에 넘김 in `B/domain/report/ConfirmReportHandler.java`; `ReportService.confirmFromApproval`이 받은 분류로 `doConfirm` 호출 in `B/domain/report/ReportService.java` (T014 · T027 의존)
- [X] T029 [P] [US2] **M11** `monitoring.sensorDefinitions(process)` 추가 in `F/api/monitoring.ts`
- [X] T030 [US2] `CauseClassificationSelect`가 `processCode` · `incidentSensorIds`로 `useQuery(['sensor-definitions', process])` 센서 목록을 불러와 `orderEquipmentOptions`로 정렬, 표시 이름은 `sensorNames[id] || id` + 단위, 사건 관련 센서에 「사건 관련」 표시, 조회 실패 시 Machine 확정만 막는 안내 in `F/components/CauseClassificationSelect.tsx` (T016 · T029 의존)
- [X] T031 [US2] **M9** `ReportEditor`가 사건 `processCode` · `sensorIds`를 `ConfirmPanel` → `CauseClassificationSelect`에 전달, 범주를 Machine 외로 바꾸면 파라미터 초기화 in `F/pages/Reports.tsx`
- [X] T032 [US2] **M10** `ApprovalReviewModal`에서 `actionType === 'CONFIRM_REPORT'`이고 결정 가능할 때 `CauseClassificationSelect` 표시(사건은 `approval.incidentId`로 조회), 승인 버튼 조건에 `isClassificationComplete`, `decideApproval` 본문에 APPROVE일 때만 `classification` 추가, 승인된 건은 `decisionInput` 읽기 전용 표시 in `F/pages/Approvals.tsx`

**Checkpoint**: US1 + US2로 직접 확정 · 승인 확정 모두 분류(4M · 보류 · Machine 파라미터)가 끝까지 동작 — **MVP 완성**

---

## Phase 5: User Story 3 — 분류 · 이상 발생 기간으로 보고서를 골라 본다 (P2)

**Goal**: 보고서 목록을 분류(전체 · 4M · 보류 · 분류 없음) · Machine 파라미터 · 「이상 발생 기간」으로 거른다

**Independent Test**: 분류 · 감지일이 다른 보고서들 중 Machine + 10/1~10/31 지정 시 해당 보고서만, 기간 비우면 전체, 시작 > 종료면 안내 후 미적용

### Tests for User Story 3

- [X] T033 [P] [US3] 필터 순수 함수 테스트 in `frontend/tests/report-filters.test.ts` — 분류 · 파라미터 · 분류 없음, 기간(한국 시간, 종료일 포함, 한쪽만, 시작 > 종료), 동시 적용

### Implementation for User Story 3

- [X] T034 [P] [US3] 필터 함수 작성 in `F/domain/reportFilters.ts` — `ReportFilter` 타입(category · equipmentParameter · detectedFrom · detectedTo), `isValidDetectedRange`, `filterReports(rows, filter)`(행 = 보고서 요약 + 사건 `detectedAt`)
- [X] T035 [US3] **M9** `ReportList`에 분류 · 파라미터(Machine일 때, 센서 목록은 `monitoring.sensorDefinitions(process)`) · 이상 발생 기간(시작일 · 종료일 `type="date"`) 필터 UI, 보고서마다 사건 `detectedAt` 결합 · 각 행에 이상 발생일 · 분류 배지 표시, 범위 오류 안내 in `F/pages/Reports.tsx` (T034 의존)

**Checkpoint**: 설비정비팀 계정으로 Machine · 파라미터 · 기간 필터가 동작

---

## Phase 6: Polish & Cross-Cutting

- [X] T036 **M12** 공개 계약 갱신 in `data/backend-handoff-v2/combined.openapi.json` — `ReportConfirm.classification` · `ApprovalDecision.classification` · `ReportView`/`ReportSummary.classification` · `ApprovalView.decisionInput` · `GET /api/monitoring/sensor-definitions`(contracts/public-api.md 1–5). `ai/contracts/public-openapi.json`은 건드리지 않는다
- [X] T037 계약 대조 — 서버 실행 후 `python3 ops/tools/openapi_diff.py http://localhost:8096`에서 계약 문서 5개 외 차이 0건, `python3 ops/tools/java_pkgdeps.py` 「순환: 없음」 확인 · 결과를 implementation-notes 블록에 기록
- [X] T042 **M16(동의됨, 옵션 A)** 스모크 보고서 확정 요청에 `"classification":{"category":"METHOD"}` 추가 in `backend/scripts/smoke-test.sh` 162행 (구현 중 발견해 추가한 작업)
- [X] T038 전체 검증 — `backend ./gradlew test`, `frontend npm test && npm run build`, `ai uv run pytest -q`(회귀만), compose 스모크(`backend/scripts/smoke-test.sh`) 실패 0건, T001 기준선과 비교해 implementation-notes에 기록
- [ ] T039 quickstart 수동 시나리오 1–6 확인(스키마 변경 뒤 재시드) · 결과 기록
- [X] T040 [P] 검증 현황 갱신 in `backend/README.md` 「## 검증」(날짜 2026-10-22 · `./gradlew test` 134개) — 루트 README에는 구조 정리 뒤 해당 섹션이 없어 대상을 바꿈. **사용자 동의(2026-10-22)**

---

## Dependencies & Execution Order

- **Setup(T001)** → **Foundational(T002–T009)** → US1 · US2 · US3
- **US1(T010–T017)**: Foundational 뒤 시작. 다른 스토리 의존 없음
- **US2(T018–T032)**: Foundational 뒤 백엔드 조회(T021–T023) · 승인 확장(T024–T027)은 병행 가능. T028은 US1의 T014, T030 · T031은 US1의 T016 · T017에 의존(같은 파일)
- **US3(T033–T035)**: Foundational 뒤 시작 가능. `ReportSummary.classification`(T013)과 센서 조회(T029)를 쓰므로 실제 연결은 US1 · US2 뒤가 자연스럽다
- **Polish(T036–T040)**: 모든 스토리 뒤

### 같은 파일 순서 (병렬 금지)

- `ReportService.java`: T014 → T028 · `Reports.tsx`: T017 → T031 → T035 · `CauseClassificationSelect.tsx`: T016 → T030 · `ReportClassificationApiTest.java`: T010 → T019 · `ReportApiTest.java`: T011 → T020

## Parallel Examples

```text
# Foundational
T002 ReportCauseCategory · T003 CauseClassificationInput · T007 causeClassification.ts · T009 types.ts

# US2 백엔드
T018 MonitoringApiTest · T019 ReportClassificationApiTest(승인) · T021→T022→T023 모니터링 · T024→T025→T026→T027 승인 · T029 monitoring.ts

# US3
T033 report-filters.test.ts · T034 reportFilters.ts
```

## Implementation Strategy

1. **MVP = US1 + US2** (둘 다 P1): Phase 1–4 완료 → 직접 확정 · 승인 확정에서 분류가 끝까지 동작하는지 quickstart 1–4로 확인
2. **US3** 추가 → quickstart 5
3. **Polish**: 계약 · 전체 검증 · 수동 시나리오 · README(확인 후)
4. 각 체크포인트마다 `./gradlew test` · `npm test` 통과 상태 유지(원칙 X), 리팩터링 섞지 않기(원칙 VIII)
<!-- ARTIFACT:001-report-4m-classification:tasks.md END -->

<!-- ARTIFACT:002-report-title-search:tasks.md BEGIN -->
# Tasks: 조사 보고서 제목 검색 · 서버 목록 조회

**Input**: `002-report-title-search` 블록들 — spec(`.claude/skills/speckit-specify/SKILL.md`), plan · research · data-model ·
contracts/public-api · quickstart(`.claude/skills/speckit-plan/SKILL.md`)

**Prerequisites**: plan 「기존 코드 수정 목록」 S1(`schema.sql`) · S2(`Reports.tsx`) · S3(`combined.openapi.json`) 사용자 동의 완료(2026-10-22).
목록 밖 기존 코드는 고치기 전에 다시 확인받는다(원칙 X). 기존 `ReportController` · `ReportService` · `ReportRepository` ·
`GET /api/incidents/{id}/reports`는 고치지 않는다. `ai/` 폴더는 수정하지 않는다.

**Tests**: 헌법 품질 게이트(권한 · 계약에 닿는 변경은 테스트로 보인다)에 따라 테스트 작업을 포함한다.

**Paths**: `B` = `backend/src/main/java/com/fabcontext/api`, `BT` = `backend/src/test/java/com/fabcontext/api`, `F` = `frontend/src`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일 · 앞 작업 의존 없음 → 병렬 가능
- **[Story]**: US1(제목 검색) · US2(검색 + 필터) · US3(대량 데이터 성능)

---

## Phase 1: Setup (기준선)

- [X] T001 기준선 확인 — 변경 전 `cd backend && ./gradlew test`, `cd frontend && npm test && npm run build`를 돌려 결과를 `.claude/skills/speckit-implement/SKILL.md`의 `002-report-title-search:implementation-notes.md` 블록에 기록(001 반영 후 기준: backend 134 · frontend 43)

---

## Phase 2: Foundational (서버 목록 조회 뼈대 — 공정 · 정렬 · 페이지)

**목표**: `GET /api/reports?processCode=&page=&size=`가 보고서 + 사건 조인 SQL 한 번으로 최신순 Slice를 돌려주고, 화면 목록이
사건별 호출 대신 이 API + 「더 보기」를 쓴다. 검색어 · 필터는 다음 단계에서 붙인다.

**⚠️ 이 단계가 끝나야 US1 · US2 · US3를 시작한다**

- [X] T002 [P] `ReportSearchDtos` 작성 in `B/domain/report/ReportSearchDtos.java` — `ReportListItem`(reportId · incidentId · incidentDisplayId · processCode · version · status · title · contentRevision · createdAt · confirmedAt · detectedAt · classification: 기존 `ReportDtos.ClassificationView`) · `ReportSlice`(items · page · size · hasNext)
- [X] T003 [P] `ReportSearchCondition` 값 객체 작성 in `B/domain/report/ReportSearchCondition.java` — 이 단계는 `processCode` 검증(CVD · ETCH · CMP 외 422 `INVALID_PROCESS_CODE`) · `page`(≥0) · `size`(1–100, 기본 20, 범위 밖 400 `INVALID_PAGE_SIZE`)만. 정적 팩토리 + 불변 필드(다음 단계 필드는 null 허용으로 자리만 둠)
- [X] T004 `ReportSearchQuery` @Repository 작성 in `B/domain/report/ReportSearchQuery.java` — `EntityManager` 네이티브 쿼리로 `{h-schema}report r join {h-schema}incident i on i.incident_id = r.incident_id where r.process_code = :processCode order by i.detected_at desc, r.report_id limit :limit offset :offset`(limit = size+1), 행 → `ReportListItem` 매핑(분류 열 `cause_category` · `cause_equipment_parameters` · `classified_by` · `classified_at` 포함), WHERE 절은 조건 목록으로 조립하는 구조로 만들어 다음 단계에서 절만 추가 (T002 · T003 의존)
- [X] T005 `ReportSearchService` 작성 in `B/domain/report/ReportSearchService.java` — `AccessPolicy.requireScope(user, processCode)` → `ReportSearchQuery` 조회 → size+1번째 행으로 `hasNext` 판단 → `ReportSlice` (`@Transactional(readOnly = true)`) (T004 의존)
- [X] T006 `ReportSearchController` 작성 in `B/domain/report/ReportSearchController.java` — `GET /api/reports`, `@Operation(operationId = "searchReports")`, `@Tag(name = "reports")`, `@PageableDefault(size = 20) Pageable`(정렬 파라미터는 무시 · 고정 정렬), `ApiResponse.of(slice)` (T005 의존)
- [X] T007 [P] 조건 단위 테스트 in `BT/domain/report/ReportSearchConditionTest.java` — 공정 검증 · page/size 경계 (T003 의존)
- [X] T008 목록 API 테스트 작성 in `BT/api/ReportSearchApiTest.java` — 비로그인 401, 권한 밖 공정 403, 잘못된 공정 422, size 101 → 400, 응답 모양(items · page · size · hasNext), 최신순 정렬, size=1로 `hasNext`/다음 페이지가 이어지고 중복 없음, 행의 `detectedAt` · `classification`(시드 MACHINE · slurry_flow 보고서) (T006 의존)
- [X] T009 [P] 화면 API 클라이언트 작성 in `F/api/reports.ts` — `searchReports(params, page)` → `GET /api/reports`(빈 값 파라미터 제외), 응답 타입 `ReportSlice` · `ReportListItem`
- [X] T010 **S2** `ReportList`의 데이터 출처를 사건별 `useQueries` → `useInfiniteQuery(['reports', process, …])` + 「더 보기」(`hasNextPage`일 때만, `isFetchingNextPage` 표시)로 교체, 행의 이상 발생일은 `item.detectedAt` 사용, 기존 화면 필터 적용(`filterReports`)은 이 단계에서는 유지해 동작을 지금과 같게 둠 in `F/pages/Reports.tsx` (T009 의존)

**Checkpoint**: 목록 진입 시 `/api/reports` 1번, 「더 보기」로 끝까지 조회(100건 한계 해소). 기존 필터는 그대로 동작

---

## Phase 3: User Story 1 — 제목 일부로 보고서를 찾는다 (P1) 🎯 MVP

**Goal**: 검색칸에 제목 일부를 입력하면 서버가 제목 부분 일치(대소문자 무시, 기호 글자 그대로)로 찾아 준다

**Independent Test**: 「PM3」 입력 → 제목에 PM3가 든 보고서만, 비우면 전체, `cmp-pm3`도 찾고 `%`는 글자 그대로

### Tests for User Story 1

- [X] T011 [P] [US1] 검색어 정규화 테스트 추가 in `BT/domain/report/ReportSearchConditionTest.java` — 앞뒤 공백 제거 · 빈 값 null · 100자 초과 422 `INVALID_KEYWORD` · 패턴 이스케이프(`50%_off\` → `%50\%\_off\\%`)
- [X] T012 [P] [US1] 검색 API 테스트 추가 in `BT/api/ReportSearchApiTest.java` — 사건 표시번호로 검색 시 그 보고서만, 대소문자 무시, 일치 없음 → 빈 items · `hasNext=false`, `%` · `_` 검색이 와일드카드로 동작하지 않음, 권한 밖 공정 보고서는 검색어가 맞아도 안 나옴

### Implementation for User Story 1

- [X] T013 [US1] `ReportSearchCondition`에 `titleKeyword`(정규화 · 길이 검증)와 `titleLikePattern()`(`\` · `%` · `_` 이스케이프 + 양쪽 `%`) 추가 in `B/domain/report/ReportSearchCondition.java`
- [X] T014 [US1] 검색어가 있을 때만 `r.title ILIKE :titlePattern ESCAPE '\'` 절 추가 in `B/domain/report/ReportSearchQuery.java`, 컨트롤러에 `q` 파라미터 연결 in `B/domain/report/ReportSearchController.java` (T013 의존)
- [X] T015 [P] [US1] 디바운스 훅 작성 in `F/hooks/useDebouncedValue.ts` — 값 · 지연(기본 300ms), 즉시 반영 함수(Enter용) 함께 반환
- [X] T016 [US1] **S2** 필터 줄 맨 앞에 「제목 검색」 입력칸(최대 100자, Enter 즉시 검색) 추가, 디바운스한 검색어를 쿼리 키 · 요청에 반영, 검색어가 있고 결과가 없으면 「검색 결과가 없습니다」, 오류 시 안내 + 이전 결과 유지(`placeholderData: keepPreviousData`) in `F/pages/Reports.tsx` (T010 · T015 의존)

**Checkpoint**: 제목 검색이 서버 조회로 동작

---

## Phase 4: User Story 2 — 검색과 분류 · 기간 필터를 함께 쓴다 (P1)

**Goal**: 원인 분류 · 장비 파라미터 · 이상 발생 기간을 서버 조건으로 옮겨, 검색 결과에 대해서만 필터가 적용되게 한다

**Independent Test**: 「CMP」 + Machine + 10월 기간 → 세 조건 모두 만족하는 보고서만, 조건 바꾸면 첫 페이지부터

### Tests for User Story 2

- [X] T017 [P] [US2] 필터 조건 테스트 추가 in `BT/domain/report/ReportSearchConditionTest.java` — 분류(5종 · `UNCLASSIFIED` · 알 수 없는 값 422), 파라미터는 MACHINE일 때만 유지, 기간 → `[from 00:00 KST, to+1 00:00 KST)` Instant 경계 · 한쪽만 · 시작 > 종료 422 `INVALID_DETECTED_RANGE`
- [X] T018 [P] [US2] 검색 + 필터 API 테스트 추가 in `BT/api/ReportSearchApiTest.java` — 직접 만든 보고서들을 METHOD · HOLD · MACHINE(파라미터)로 확정한 뒤: 분류만, 분류 + 파라미터, `UNCLASSIFIED`(초안), 검색어 + 분류 교집합, 기간 경계(종료일 포함 · 한국 시간)
- [X] T019 [P] [US2] 요청 파라미터 변환 테스트 in `frontend/tests/report-search.test.ts` — `ALL` · 빈 값 제외, MACHINE이 아니면 파라미터 제외, 기간이 거꾸로면 기간 제외(화면 안내와 같은 규칙)

### Implementation for User Story 2

- [X] T020 [US2] `ReportSearchCondition`에 `category`(enum 또는 `UNCLASSIFIED`) · `equipmentParameter` · `detectedFrom/To`(LocalDate → Instant 경계) 추가 in `B/domain/report/ReportSearchCondition.java`
- [X] T021 [US2] 조건이 있을 때만 절 추가 in `B/domain/report/ReportSearchQuery.java` — `r.cause_category = :category` / `r.cause_category is null`, `r.cause_equipment_parameters @> jsonb_build_array(cast(:parameter as text))`, `i.detected_at >= :from`, `i.detected_at < :toExclusive`; 컨트롤러에 `category` · `equipmentParameter` · `detectedFrom` · `detectedTo`(`@DateTimeFormat(iso = DATE)`) 연결 in `B/domain/report/ReportSearchController.java` (T020 의존)
- [X] T022 [P] [US2] 필터 상태 → 요청 파라미터 변환 함수 작성 in `F/domain/reportSearch.ts` — `toSearchParams(process, keyword, filter)` (기존 `ReportFilter` 타입 재사용)
- [X] T023 [US2] **S2** 필터 값을 화면 거르기(`filterReports`) 대신 `toSearchParams`로 요청에 넣도록 변경 — 필터 변경 시 쿼리 키가 바뀌어 첫 페이지부터, 기간 역전 안내는 유지, `reportFilters.ts`는 지우지 않음(목록에서 import만 제거) in `F/pages/Reports.tsx` (T016 · T022 의존)

**Checkpoint**: 검색 · 분류 · 파라미터 · 기간이 모두 서버에서 한 번에 적용

---

## Phase 5: User Story 3 — 대량 데이터에서도 검색 · 목록이 빠르다 (P2)

**Goal**: 임시 DB 10만 건으로 인덱스 전후 `EXPLAIN (ANALYZE, BUFFERS)`를 측정하고, 효과가 확인된 인덱스만 `schema.sql`에 넣는다

**Independent Test**: 측정 표에서 ②③ 검색이 Seq Scan → Bitmap Index Scan으로 바뀌고 10배 이상 · 100ms 이내, ① 첫 페이지 100ms 이내

- [X] T024 [P] [US3] 측정 스크립트 작성 in `ops/tools/report_search_explain.sh` — 인자 DB URL, `psql`(없으면 `docker exec`)로: `setseed(0.42)` + `generate_series`로 사건 2만(공정 3 · 감지일 2년 분포) · 보고서 10만(제목에 표시번호 · 설비 · 현상 단어, 분류 · 파라미터 · NULL 섞음) 삽입 → 대표 쿼리 ①–④(research R6)를 인덱스 없이 측정 → 후보 인덱스 생성 · `ANALYZE` → 다시 측정 → 쿼리별 계획 노드 · Execution Time 표 출력. 쿼리 문장은 `ReportSearchQuery`가 만드는 SQL과 같게
- [X] T025 [US3] 측정 실행 — 임시 컨테이너(`pgvector/pgvector:pg16`, 포트 55434)에 백엔드를 `DDL_AUTO=create`로 한 번 띄워 스키마 생성 후 스크립트 실행, 결과 표 · 판단(채택/미채택 인덱스와 이유, 깊은 offset 관찰)을 implementation-notes 블록에 기록, 컨테이너 정리 (T021 · T024 의존)
- [X] T026 [US3] **S1** 측정으로 확정한 것만 `DO $$ … END $$` 블록 안(기존 외래 키 뒤)에 추가 in `backend/src/main/resources/schema.sql` — `CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;` · `CREATE INDEX IF NOT EXISTS idx_report_title_trgm ON report USING gin (title public.gin_trgm_ops);` · (채택 시) `idx_incident_process_detected` (T025 의존)
- [X] T027 [US3] 인덱스 존재 테스트 추가 in `BT/api/ReportSearchApiTest.java` — `pg_indexes`에 채택 인덱스가 현재 스키마에 있음, 서버 재시작(스크립트 재실행) 상황을 `schema.sql` 재실행으로 흉내 내도 오류 없음 (T026 의존)

**Checkpoint**: 측정 기록과 인덱스가 반영되고 전체 테스트 통과

---

## Phase 6: Polish & Cross-Cutting

- [X] T028 **S3** 공개 계약에 `GET /api/reports`(파라미터 · `ReportSlice` · `ReportListItem` 스키마) 추가 — 서버 `/v3/api-docs`에서 실제 스키마를 옮겨 일치시킴 in `data/backend-handoff-v2/combined.openapi.json`
- [X] T029 계약 대조 — 검증 서버(임시 DB · 포트 8096)로 `python3 ops/tools/openapi_diff.py http://localhost:8096` 실행, `searchReports` 관련 새 차이 0건 · 기존 차이 목록 불변 확인, `java_pkgdeps.py`에 새 순환 없음 확인, implementation-notes 기록
- [X] T030 전체 검증 — `backend ./gradlew test`, `frontend npm test && npm run build`, `ai uv run pytest -q`(회귀), 새로 시드한 서버에 스모크 `backend/scripts/smoke-test.sh` 실패 0건, T001 기준선과 비교 기록
- [ ] T031 quickstart 수동 시나리오 1–5 확인(네트워크 탭에서 목록 진입 요청 1번 포함) · 결과 기록 — 사용자 화면 확인 필요
- [ ] T032 [P] 검증 현황 · 운영 메모 갱신 in `backend/README.md` 「## 검증」(테스트 수 · 날짜) + 기존 대량 DB에 처음 적용할 때 `CREATE INDEX CONCURRENTLY` 권장 한 줄 — **기존 문서 수정이라 반영 전 사용자 확인**

---

## Dependencies & Execution Order

- **Setup(T001)** → **Foundational(T002–T010)** → US1(T011–T016) → US2(T017–T023) → US3(T024–T027) → Polish(T028–T032)
- US1과 US2는 백엔드 조건 · 쿼리 파일을 함께 고치므로 순서대로(US1 → US2). US3 측정(T025)은 최종 쿼리 모양이 정해진 US2 뒤
- 측정 스크립트 작성(T024)은 Foundational 뒤 언제든 병행 가능

### 같은 파일 순서 (병렬 금지)

- `ReportSearchCondition.java`: T003 → T013 → T020 · `ReportSearchQuery.java`: T004 → T014 → T021 · `ReportSearchController.java`: T006 → T014 → T021
- `ReportSearchApiTest.java`: T008 → T012 → T018 → T027 · `ReportSearchConditionTest.java`: T007 → T011 → T017 · `Reports.tsx`: T010 → T016 → T023

## Parallel Examples

```text
# Foundational
T002 ReportSearchDtos · T003 ReportSearchCondition · T009 api/reports.ts

# US1
T011 조건 테스트 · T012 API 테스트 · T015 useDebouncedValue

# US2 / US3
T017 · T018 · T019 · T022  ||  T024 측정 스크립트
```

## Implementation Strategy

1. **Foundational**만으로도 가치가 있다 — N+1 호출 제거 · 100건 한계 해소(기존 필터 동작 그대로)
2. **MVP = US1 + US2** (둘 다 P1): 서버 제목 검색 + 서버 필터
3. **US3**: 측정 → 근거 있는 인덱스만 반영
4. 각 체크포인트마다 `./gradlew test` · `npm test` 통과 유지(원칙 X), 리팩터링 섞지 않기(원칙 VIII)
<!-- ARTIFACT:002-report-title-search:tasks.md END -->

<!-- ARTIFACT:003-report-confirm-summary:tasks.md BEGIN -->
# Tasks: 보고서 확정 · 사건 자동 종료 · AI 요약 연결

**Input**: `003-report-confirm-summary` 블록들 — spec(`.claude/skills/speckit-specify/SKILL.md`), plan · research · data-model ·
contracts/public-api · contracts/internal-snapshot · quickstart(`.claude/skills/speckit-plan/SKILL.md`)

**Prerequisites**: plan 「기존 코드 수정 목록」 P1–P20 **사용자 동의 완료(2026-10-23, 전체)** · 공개 계약 변경 **확인됨(2026-10-23, 반영)** ·
화면 범위 **포함(2026-10-23)** → Phase 8 화면 작업 추가. 목록 밖 기존 코드는 고치기 전에 다시 확인받는다(원칙 X).
`ai/` 폴더는 수정하지 않는다. P6–P10(공통 작업 · 이벤트 · 알림 · 시뮬레이터)은 아름 담당 파일 — SUMMARY 부분만 넣고 아름 리뷰.

**Tests**: 헌법 품질 게이트(승인 · 권한 · 계약에 닿는 변경은 테스트로 보인다)에 따라 테스트 작업을 포함한다.

**Paths**: `B` = `backend/src/main/java/com/fabcontext/api`, `BT` = `backend/src/test/java/com/fabcontext/api`, `F` = `frontend/src`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일 · 앞 작업 의존 없음 → 병렬 가능
- **[Story]**: US1(확정 = 종료) · US2(남은 일 정리) · US3(AI 요약) · US4(관리자 수동 종료 제거)

---

## Phase 1: Setup (기준선)

- [X] T001 기준선 확인 — 변경 전 `cd backend && ./gradlew test`, `cd frontend && npm test && npm run build`, `cd ai && uv run pytest -q`, 새로 시드한 임시 서버(임시 DB 컨테이너, `fab-deploy-*` 미사용)에 `backend/scripts/smoke-test.sh`를 돌려 결과를 `.claude/skills/speckit-implement/SKILL.md`의 `003-report-confirm-summary:implementation-notes.md` 블록에 기록(기존 스모크 실패 5건은 이번 기능과 구분)

---

## Phase 2: Foundational (모든 스토리가 쓰는 모델 · 스키마 · 작업 격리)

**⚠️ 이 단계가 끝나야 US1–US4를 시작한다**

- [X] T002 **P6** `JobKind`에 `SUMMARY` 추가 in `B/domain/job/JobKind.java`
- [X] T003 **P13** `schema.sql` DO 블록 안(기존 `check_request_status_check` 재생성 방식과 같게)에 추가 in `backend/src/main/resources/schema.sql` — ① `ALTER TABLE job DROP CONSTRAINT IF EXISTS job_kind_check` → `ADD CONSTRAINT job_kind_check CHECK (kind IN ('BASIC','DEEP','RESUME','REPORT','SUMMARY'))` ② `work_item_status_check`를 `WorkItem.Status` 전체 + `CLOSED_UNPERFORMED`로 재생성 ③ 사건당 확정 1개 부분 유니크 인덱스 `uq_report_confirmed_per_incident ON report (incident_id) WHERE status = 'CONFIRMED'` — `IF NOT EXISTS (… group by incident_id having count(*) > 1)`일 때만 만들고 아니면 `RAISE NOTICE`. 재실행 안전
- [X] T004 **P7** SUMMARY 작업을 조사 상태와 격리 in `B/domain/job/JobService.java` — `enqueue`: SUMMARY는 `assertNoRunningJob` 제외 · `incident.setActiveJobId` · `setLatestJobStatus` 안 함. `sync`: SUMMARY는 `latestJobStatus` 갱신 안 함. `succeed` · `fail`: SUMMARY는 `notificationEvents.analysisFinished` 안 보냄. 사건 잠금 · 작업 저장 · `JobQueuedEvent` · SSE 발행은 공통 그대로. 판별은 `JobKind`에 `isInvestigation()` 같은 의도 드러나는 메서드를 두고 쓴다(T002 의존)
- [X] T005 [P] `ReportSummaryStatus` enum(PENDING · RUNNING · READY · FAILED) 작성 in `B/domain/report/ReportSummaryStatus.java`
- [X] T006 `ReportSummary` @Embeddable 값 객체 작성 in `B/domain/report/ReportSummary.java` — 열 `summary_status`(EnumType.STRING) · `summary_lines`(jsonb) · `summary_evidence_ids`(jsonb) · `summary_generated_by` · `summary_job_id` · `summary_updated_at` · `summary_error`. 정적 생성자 `pending(jobId, at)`과 상태 메서드 `markRunning(jobId, at)` · `complete(jobId, lines, evidenceIds, generatedBy, at)` · `fail(jobId, message, at)` · `restart(newJobId, at)`(FAILED에서만) · `accepts(jobId)`(현재 작업이고 PENDING · RUNNING인지). 세터 없음, 목록은 불변 복사. 줄 수 1~2 · 줄당 120자 검사는 `complete`에서(위반 시 422 `INVALID_SUMMARY`) (T005 의존)
- [X] T007 **P2** `Report`에 `snapshot`(`Map<String,Object>`, jsonb, 확정 때 한 번만 설정) · `@Embedded ReportSummary summary` 필드와 `attachSnapshot(Map)` · `summary()` 접근 추가 in `B/domain/report/Report.java` — 기존 `confirm(…)` 시그니처 · 동작은 바꾸지 않는다 (T006 의존)
- [X] T008 **P17** jsonb 열 수 `hasSize(30)` → `hasSize(33)`, 확인 목록에 `report.snapshot` · `report.summary_lines` · `report.summary_evidence_ids` 추가 in `BT/domain/SchemaTest.java` 49행. 「JSON 열은 모두 jsonb」 규칙은 그대로 (T007 의존)
- [X] T009 [P] `ConfirmationBlocker` record(`kind` enum: ANALYSIS_IN_PROGRESS · APPROVAL_PENDING · MANAGER_APPROVAL_PENDING · REQUEST_UNANSWERED · WORK_UNPERFORMED, `id`, `label`) 작성 in `B/domain/report/ConfirmationBlocker.java`
- [X] T010 [P] `ConfirmationContext` record(`actorId`, `path`: DIRECT · APPROVAL, `excludedApprovalId`, `excludedJobIds`) + 정적 팩토리 `direct(actorId)` · `fromApproval(actorId, approvalId, jobIds)` 작성 in `B/domain/report/ConfirmationContext.java`
- [X] T011 **P5(일부)** `Incident.closeByConfirmedReport(Instant at, String reportId)` 추가(상태 `CLOSED` · 종료 시각 · 사유 「보고서 확정」 기록 — 기존 종료 필드 사용) in `B/domain/incident/Incident.java`. 기존 전이 메서드는 이 단계에서 바꾸지 않는다(US4)

**Checkpoint**: `./gradlew test` 통과(SchemaTest 새 기대값 포함). 동작 변화 없음 — 새 열은 null, SUMMARY는 아직 등록되지 않음

---

## Phase 3: User Story 1 — 보고서를 확정하면 사건이 닫힌다 (P1) 🎯 MVP

**Goal**: 최신 판본 확정 한 번이 확정 · 확정본 저장 · 사건 종료를 한 트랜잭션으로 처리하고, 종료 뒤 변경은 409 `INCIDENT_CLOSED`

**Independent Test**: 남은 일 없는 CMP 사건 → 초안 → Method 확정 → 사건 `CLOSED` · 이력 「보고서 확정」·「사건 종료」 → 초안 생성 · 편집 · 질문 409. 동시 확정 10회 중 200 1건

### Tests for User Story 1

- [X] T012 [P] [US1] `ReportConfirmCloseApiTest` 작성 in `BT/api/ReportConfirmCloseApiTest.java` — 확정 → 사건 `CLOSED` · 이력 두 줄, 옛 판본 확정 409 `NOT_LATEST_DRAFT`, 이미 확정된 사건의 다른 판본 확정 409 `REPORT_ALREADY_CONFIRMED`(details.reportId), 같은 보고서 동시 확정 10회(스레드 풀 + `CountDownLatch`) → 200 정확히 1건, 확정 중 실패(분류 422) 시 보고서 DRAFT · 사건 열림 유지, 종료 뒤 초안 생성 · 편집 · 확정 · 질문 409 `INCIDENT_CLOSED`, 종료 뒤 조회 · 내려받기 200, 기존 오류 순서(검토 미체크 422 → 판본 충돌 409 → 분류 422) 유지 — 기존 `ApiTestSupport` 헬퍼 사용
- [X] T013 [US1] **P16** 기대값 변경 in `BT/api/ReportApiTest.java` — 확정 뒤 v2 초안 생성을 기대하던 부분을 409 `INCIDENT_CLOSED` 기대로 바꾸고, v2가 필요한 이후 단언은 확정 전에 v2를 만드는 순서로 옮긴다. 확정 · 승인 성공 경로 기대 응답은 그대로
- [X] T014 [US1] **P16** 기대값 변경 in `BT/api/ReportClassificationApiTest.java` — HOLD 확정 뒤 사건 상태 기대를 `CLOSED`로, 같은 사건에서 여러 범주를 차례로 확정하던 테스트는 범주마다 새 사건을 쓰도록(사건당 확정 1회). 분류 검증 단언은 그대로

### Implementation for User Story 1

- [X] T015 [P] [US1] `ReportSnapshotFactory` @Component 작성 in `B/domain/report/ReportSnapshotFactory.java` — `create(Report, Incident)` → `LinkedHashMap`(reportId · reportVersion · contentRevision · snapshotRef(기존 `snapshot://reports/{id}/v{n}/r{rev}` 형식, 기존 생성 코드 재사용) · title · incidentDisplayId · sections(`ReportSections.toMap()` 5칸 문자열) · classification{category, equipmentParameters} 또는 null · evidenceIds · confirmedBy · confirmedAt) — contracts/internal-snapshot.md 필드 그대로
- [X] T016 [US1] **P1** 확정 흐름 재구성 in `B/domain/report/ReportService.java` — `confirm` · `confirmFromApproval`이 `ConfirmationContext`를 만들어 한 메서드로 모임: ① 사건 `entityManager.lock(incident, PESSIMISTIC_WRITE)` + `requireNotClosed` ② 기존 검토 · `requireDraft` · `checkRevision` ③ 그 사건의 최신 판본인지(`ReportRepository`에 최신 판본 · 확정본 존재 조회 메서드 추가) — 아니면 409 `NOT_LATEST_DRAFT`, 확정본이 있으면 409 `REPORT_ALREADY_CONFIRMED` ④ (US2 자리 — 차단 검사 호출 지점만 주석 없이 비워 둠) ⑤ 분류(001) ⑥ `report.confirm(…)` + `report.attachSnapshot(snapshotFactory.create(…))` + 기존 Markdown 스냅샷 ⑦ `incident.closeByConfirmedReport(at, reportId)` + 이력 「사건 종료」 ⑧ (US3 자리). `generate` · `edit`에 `requireNotClosed` 추가. 유니크 인덱스 위반(`DataIntegrityViolationException`)은 409 `REPORT_ALREADY_CONFIRMED`로 바꿈. 단계별 private 메서드로 나눠 각 40줄 이내(원칙 VIII) (T007 · T011 · T015 의존)
- [X] T017 [US1] **P14(일부)** 시드에서 보고서 확정 뒤 `close(incident, manager, …)` 호출 삭제(확정이 종료까지 함) in `B/seed/SeedRunner.java` 201행. 종료 사유 문구는 확정 이력으로 대체됨을 확인 (T016 의존)

**Checkpoint**: US1 단독으로 확정 = 종료가 동작. `./gradlew test` 통과(P16 반영)

---

## Phase 4: User Story 2 — 확정 전에 남은 일을 정리한다 (P1)

**Goal**: 남은 일이 있으면 409 `CONFIRMATION_BLOCKED` + 목록, 요청자 철회 · 업무 미수행 닫기로 정리 후 확정. 승인 경로는 자기 승인 · 재개 작업에 막히지 않음

**Independent Test**: 미회신 요청이 있는 사건 확정 → 409(요청 ID) → 철회 → 확정 성공. READY 업무 → 미수행 닫기 → 확정. Agent 「보고서 확정」 제안 승인 → 확정 · 종료

### Tests for User Story 2

- [X] T018 [P] [US2] `ReportConfirmationGuardTest` 단위 테스트 in `BT/domain/report/ReportConfirmationGuardTest.java` — 종류별 차단(작업 QUEUED · RUNNING · WAITING_APPROVAL · WAITING_REPLY, 승인 PENDING, 업무 WAITING_MANAGER, 요청 OPEN · ACCEPTED, 업무 READY · APPROVED · REQUESTED), 막지 않는 것(REPORT · SUMMARY 작업, 업무 PROPOSED · DECLINED · REJECTED · COMPLETED · CLOSED_UNPERFORMED, 요청 REJECTED · REPLIED · CANCELLED), 승인 경로 제외(그 승인 · 재개 작업), 남은 일 여러 개면 모두 목록
- [X] T019 [P] [US2] `ReportConfirmBlockersApiTest` 작성 in `BT/api/ReportConfirmBlockersApiTest.java` — 409 `CONFIRMATION_BLOCKED` 응답 모양(`details.blockers[{kind,id,label}]`), 요청 철회(요청자 성공 · 다른 사람 403 · REPLIED 409 · 사유 누락 422 · 종료 사건 409 · 응답 `withdrawnBy/At/Reason` · 이력 「확인 요청 철회」), 미수행 닫기(READY · APPROVED · REQUESTED 성공 · PROPOSED 409 · 사유 누락 422 · 이력 「업무 미수행 종결」), 정리 뒤 확정 성공, 요청 철회가 연결 업무를 바꾸지 않음(FR-009), CONFIRM_REPORT 승인 경로 확정 성공 · 사건 `CLOSED`

### Implementation for User Story 2

- [X] T020 [US2] `ReportConfirmationGuard` @Component 작성 in `B/domain/report/ReportConfirmationGuard.java` — `findBlockers(Incident, ConfirmationContext)` → `List<ConfirmationBlocker>`, `requireNone(…)`(있으면 409 `CONFIRMATION_BLOCKED`, `details.blockers`). 각 도메인 저장소를 **읽기만**(필요한 조회 메서드가 없으면 저장소에 파생 쿼리 메서드 추가 — 기존 메서드 무변경). 종류별 수집을 private 메서드로 나눔 (T009 · T010 의존)
- [X] T021 [US2] **P1** 확정 흐름 ④ 자리에 `confirmationGuard.requireNone(incident, context)` 연결 in `B/domain/report/ReportService.java` (T016 · T020 의존)
- [X] T022 [US2] **P4** `ConfirmReportHandler.execute`가 실행 중 승인 ID와 그 승인을 기다리던 작업 · 재개 작업 ID(`approval.getJobId()` · `approval.getResumeJobId()` 등 기존 필드)를 `confirmFromApproval`에 넘김 in `B/domain/report/ConfirmReportHandler.java`; `confirmFromApproval`이 `ConfirmationContext.fromApproval(…)` 사용 in `B/domain/report/ReportService.java` (T021 의존)
- [X] T023 [US2] **P11** 철회 상태 전이 `CheckRequest.withdraw(actorId, reason, at)`(OPEN · ACCEPTED에서만, 아니면 409 `CHECK_REQUEST_NOT_WITHDRAWABLE`) · 열 `withdrawn_by` · `withdrawn_at` · `withdraw_reason`(≤500) 추가 in `B/domain/checkrequest/CheckRequest.java`; 요청 DTO `CheckRequestWithdrawal{reason @NotBlank @Size(max=500), expectedVersion}` · 응답에 세 필드 in `B/domain/checkrequest/CheckRequestDtos.java`
- [X] T024 [US2] **P11** `CheckRequestService.withdraw(requestId, user, body)` — 요청자 본인 또는 사건 운영 권한자(`investigationProcessScopes`), `requireNotClosed`, 낙관적 버전, 사건 이력 「확인 요청 철회」, 기존 SSE 발행 관례 in `B/domain/checkrequest/CheckRequestService.java`; `POST /api/check-requests/{requestId}/withdrawals`(`operationId = withdrawCheckRequest`, Idempotency-Key 관례 따름) in `B/domain/checkrequest/CheckRequestController.java` (T023 의존)
- [X] T025 [US2] **P12** `WorkItem.Status`에 `CLOSED_UNPERFORMED` 추가, `closeUnperformed(actorId, reason, at)`(READY · APPROVED · REQUESTED에서만, 아니면 409 `WORK_NOT_CLOSABLE`) · 열 `closed_by` · `closed_at` · `close_reason`(≤500) in `B/domain/work/WorkItem.java`; 요청 DTO `WorkUnperformedClosure{reason, expectedVersion}` · 응답 세 필드 in `B/domain/work/WorkDtos.java` (T003 의존 — 체크 제약)
- [X] T026 [US2] **P12** `WorkService.closeUnperformed(id, user, body)` — 담당자 또는 사건 운영 권한자, `requireNotClosed`, 낙관적 버전, 이력 「업무 미수행 종결」 in `B/domain/work/WorkService.java`; `POST /api/work-items/{id}/unperformed-closures`(`operationId = closeWorkItemUnperformed`) in `B/domain/work/WorkController.java` (T025 의존)

**Checkpoint**: US1 + US2로 확정 조건 · 정리 · 승인 경로가 끝까지 동작 — **MVP 완성**

---

## Phase 5: User Story 3 — 확정본을 AI가 요약해 보여 준다 (P2)

**Goal**: 확정과 같은 트랜잭션에서 요약 `PENDING` + SUMMARY 작업 등록, Agent · 시뮬레이터 결과를 대조 후 저장, 실패 시 재요청, 확정자 알림

**Independent Test**: 시뮬레이터 모드에서 확정 → `summary.status` PENDING → 1분 안에 READY(rule) · 확정자 알림. 실패 주입 → FAILED → 재요청 → PENDING. 옛 작업의 늦은 결과 → 무시

### Tests for User Story 3

- [X] T027 [P] [US3] `ReportSummaryApiTest` 작성 in `BT/api/ReportSummaryApiTest.java` — 확정 응답 `summary.status = PENDING` · SUMMARY 작업 1건(`input.summaryRef`), 사건 `activeJobId` · `latestJobStatus` 불변, 내부 `GET /internal/reports/{id}/snapshot`(서비스 토큰 200 · 필드 모양 · 초안 404 · 토큰 없음 401), 내부 이벤트로 `JOB_STARTED` → RUNNING · `SUMMARY_READY` → READY(줄 · 근거 · generatedBy) · 작업 SUCCEEDED, 줄 3개 · 121자 거부, 다른 `snapshotRef` · 옛 작업 결과 → 저장 안 함 · 그 작업 FAILED(`STALE_RESULT`), 이미 READY에 결과 → 무시, `JOB_FAILED` → FAILED · 사건 이력에 `ANALYSIS_FAILED` 없음, 재요청(FAILED 202 · PENDING/RUNNING/READY 409 `SUMMARY_NOT_RETRYABLE` · 권한 없음 403 · 종료 사건에서도 허용), 동시 재요청 → 접수 1건, 알림 `REPORT_SUMMARY_READY` · `REPORT_SUMMARY_FAILED` 수신자 = 확정자, 기능 도입 전 확정본 `summary: null`

### Implementation for User Story 3

- [X] T028 [US3] **P3** `ReportView` · `ReportSummary`(목록 요약 DTO — 이름 충돌 주의: 값 객체는 `domain.report.ReportSummary`이므로 DTO 안에서는 `SummaryView` record로 표현)에 `SummaryView summary`(status · lines · evidenceIds · generatedBy · updatedAt · error, 없으면 null) 추가 in `B/domain/report/ReportDtos.java`. 값 객체 이름이 DTO와 겹쳐 읽기 어렵다면 값 객체를 `ReportAiSummary`로 짓고 T006 · T007에 반영(구현 첫 단계에서 결정해 implementation-notes에 기록)
- [X] T029 [US3] `ReportSummaryService` 작성 in `B/domain/report/ReportSummaryService.java` — `requestFor(Report, Incident, actorId)`(SUMMARY 작업 `jobService.enqueue(…, input={summaryRef})` → `summary = pending(jobId)`), `markStarted(Job)`, `applyResult(Job, payload)`(대조: reportId · snapshotRef · `accepts(jobId)` → `complete` + `jobService.succeed`, 불일치 → `jobService.fail(job, …, "STALE_RESULT", …)`), `fail(Job, payload)`(FAILED + 짧은 안내, 이력 기록 없음), `retry(reportId, user)`(확정 권한 · 사건 잠금 · FAILED에서만 → 새 작업 · `restart`). 완료 · 실패 알림은 이벤트 발행 → AFTER_COMMIT 리스너에서 `NotificationEvents` 호출 (T004 · T006 · T007 의존)
- [X] T030 [US3] **P1** 확정 흐름 ⑧ 자리에 `reportSummaryService.requestFor(report, incident, actorId)` 연결 — 같은 트랜잭션(실패 시 확정 · 종료 함께 롤백) in `B/domain/report/ReportService.java` (T021 · T029 의존)
- [X] T031 [P] [US3] `ReportSummaryController` 작성 in `B/domain/report/ReportSummaryController.java` — `POST /api/reports/{reportId}/summary-jobs`(`operationId = retryReportSummary`, `@Tag(name = "reports")`, 202 + `ReportView`, Idempotency-Key 관례) (T029 의존)
- [X] T032 [P] [US3] `InternalReportSnapshotController` 작성 in `B/internal/query/InternalReportSnapshotController.java` — `GET /internal/reports/{reportId}/snapshot`, 기존 내부 컨트롤러의 서비스 토큰 · 응답 관례(`InternalIncidentController` 참고), 확정 전 · 없는 보고서 404, 저장된 `snapshot` 그대로 반환. 보고서 조회는 `domain.report`의 공개 메서드로(내부 → 도메인 방향 유지) (T007 의존)
- [X] T033 [US3] **P8** 이벤트 연결 in `B/internal/AgentEventService.java` — `case "SUMMARY_READY" -> reportSummaryService.applyResult(job, payload)`, `JOB_STARTED`에서 SUMMARY면 `markStarted` 추가 호출, `JOB_FAILED`에서 `job.getKind() == SUMMARY`면 `reportSummaryService.fail`(기존 `recordFailure`의 이력 기록 생략), 그 외 기존 그대로. 공통 앞단 검사(끝난 작업 무시 · metrics)는 그대로 (T029 의존)
- [X] T034 [US3] **P9** 시뮬레이터 `case SUMMARY -> completeSummary(job)` in `B/simulator/AgentWorker.java` — 저장된 확정본의 `overview` · `unresolved` 첫 문장(각 120자 자름, 빈 칸이면 생략, 최소 1줄) · `evidenceIds` · `generatedBy: "rule"`로 `reportSummaryService.applyResult`와 같은 반영 경로 호출. 기존 `DemoFaults` 실패 주입 방식이 있으면 SUMMARY에도 적용 가능하게 (T029 의존)
- [X] T035 [US3] **P10** `NotificationType`에 `REPORT_SUMMARY_READY("REPORT", "확정한 보고서의 요약이 완료되었습니다")` · `REPORT_SUMMARY_FAILED("REPORT", "확정한 보고서의 요약이 실패했습니다")` in `B/domain/notification/NotificationType.java`; `NotificationEvents.reportSummaryReady/Failed(report)`(수신자 = `report.confirmedBy`, 링크 `/reports/{id}`, 기존 「합의 필요」 주석 갱신) in `B/domain/notification/NotificationEvents.java` (T029와 병행 가능)

**Checkpoint**: 시뮬레이터 모드로 확정 → 요약 READY까지 키 없이 동작. 늦은 결과 · 재요청 규칙 통과

---

## Phase 6: User Story 4 — 관리자는 보고서 없이 사건을 닫지 않는다 (P2)

**Goal**: 관리자 전이는 `ON_HOLD → INVESTIGATING`만, 보류 · 종료는 409 `TRANSITION_REMOVED`. 옛 보류 · 보고서 없는 종료 시드는 이력 데이터로 보존

**Independent Test**: 열린 사건 보류 · 종료 → 409, 시드 EV-1022(보류) 해제 → 200, EV-1018–1020은 보고서 없이 `CLOSED` 그대로

### Tests for User Story 4

- [X] T036 [US4] **P15** 기대값 변경 in `BT/api/IncidentApiTest.java` — 관리자 보류 · 종료 성공을 기대하던 테스트를 409 `TRANSITION_REMOVED`로, 기존 보류 해제(ON_HOLD → INVESTIGATING) 200 테스트 추가 · 유지, 비관리자 403 단언은 그대로

### Implementation for User Story 4

- [X] T037 [US4] **P5** `IncidentService.transition` — 대상 `INVESTIGATING`이고 현재 `ON_HOLD`일 때만 허용, 대상 `ON_HOLD` · `CLOSED`는 409 `TRANSITION_REMOVED`(「사건은 보고서 확정으로만 종료할 수 있습니다」), MANAGER 검사 · 응답 모양 그대로 in `B/domain/incident/IncidentService.java`; 더는 쓰이지 않는 `Incident`의 수동 종료 · 보류 전이 메서드는 시드(T038)가 쓰는 이력 설정용으로만 남기거나 이름을 드러나게 바꾼다(`restoreLegacyState` 등, 결정 내용 implementation-notes 기록) in `B/domain/incident/Incident.java`
- [X] T038 [US4] **P14(나머지)** 시드의 옛 상태를 관리자 전이 API 대신 엔티티 직접 설정(이력 데이터)으로 — EV-1018 · 1019 · 1020(보고서 없이 종료, 112 · 116 · 120행)과 EV-1022(보류, 132행), private `close(…)`(377행)는 직접 설정 방식으로 교체. 시드 결과(각 사건 상태 · 종료 시각 · 이력 문구)는 지금과 같게 in `B/seed/SeedRunner.java` (T017 · T037 의존)

**Checkpoint**: 모든 스토리 동작. `./gradlew test` 통과

---

## Phase 7: Polish & Cross-Cutting

- [X] T039 **P18** 스모크의 상태 전이 구간(188–193행)을 새 규칙으로(보류 · 종료 409 `TRANSITION_REMOVED`, 보류 해제 200), 확정 뒤 사건 `CLOSED` · `summary.status` 확인(PENDING 또는 READY, 시뮬레이터면 짧은 대기 후 READY) 추가 in `backend/scripts/smoke-test.sh`
- [X] T040 **P19** 공개 계약 갱신 — 서버 `/v3/api-docs`에서 실제 스키마를 옮겨 contracts/public-api.md 1–7 반영(`summary` · `SummaryView`, `retryReportSummary`, `withdrawCheckRequest`, `closeWorkItemUnperformed`, 업무 상태 enum, 확정 409 오류 코드 설명) in `data/backend-handoff-v2/combined.openapi.json`. `ai/contracts/`는 건드리지 않는다
- [X] T041 계약 · 구조 대조 — 검증 서버(임시 DB · 포트 8096)로 `python3 ops/tools/openapi_diff.py http://localhost:8096`(1–7 외 새 차이 0건 · 기존 차이 목록 불변), `python3 ops/tools/java_pkgdeps.py backend/src/main/java`(기존 `[action, checkrequest, work]` 외 새 순환 없음) 결과를 implementation-notes에 기록
- [X] T042 **P20** 문서 갱신 — `README.md` 101행 · `backend/README.md` 20 · 126 · 137행의 관리자 보류 · 종료 설명을 「종료 = 보고서 확정, 관리자는 보류 해제만」으로, 001 spec 블록(`.claude/skills/speckit-specify/SKILL.md#001-report-4m-classification:spec.md`)의 「보류 확정 후 새 판본으로 재분류」 엣지 케이스 삭제(헌법 2.0.0 Sync Impact Report ⚠ 항목 해소 표시)
- [X] T043 전체 검증 — `backend ./gradlew test`, `frontend npm test && npm run build`(회귀), `ai uv run pytest -q`(회귀), 새로 시드한 임시 서버에 스모크(기존 main 실패 5건 외 0건), T001 기준선과 비교해 implementation-notes에 기록
- [X] T044 quickstart 시나리오 1–7을 API로 확인(화면은 재현 담당 — 화면 확인은 범위 밖) · 결과 기록, 임시 컨테이너 · 서버 정리
- [X] T045 [P] 재현 · 아름 · 의진 전달 메모 작성 in `.claude/skills/speckit-implement/SKILL.md`의 implementation-notes 블록 — 화면 계약 변경 요약(재현), P6–P10 리뷰 포인트(아름), 계약 변경 기록 초안 #29(의진 · 계약 주인)

---

## Phase 8: 화면 (사용자 결정 2026-10-23 「화면 포함」 — 기존 화면 파일 수정은 P21로 동의 범위에 포함)

- [X] T046 [P] 공용 타입 · API 함수 추가 in `F/api/types.ts` · `F/api/reports.ts` 등 기존 API 모듈 — `ReportSummaryView`(status · lines · evidenceIds · generatedBy · updatedAt · error), `ConfirmationBlocker`, 업무 상태 `CLOSED_UNPERFORMED` 라벨, `retryReportSummary` · `withdrawCheckRequest` · `closeWorkItemUnperformed` 호출(기존 클라이언트 관례)
- [X] T047 [P] 확정 차단 목록 · 요약 표시 순수 함수 in `F/domain/reportConfirmation.ts` + 테스트 `frontend/tests/report-confirmation.test.ts` — 409 `CONFIRMATION_BLOCKED` 응답 → 종류별 안내 문구 · 이동 링크, 요약 상태 → 표시 문구 · 재요청 가능 여부
- [X] T048 [US1] [US2] [US3] **P21** 보고서 화면 in `F/pages/Reports.tsx` — 확정 실패 시 「확정 전 정리 목록」(종류 · 라벨 · 해당 화면 링크), 확정 성공 뒤 사건 종료 안내, 확정본에 요약 패널(대기 · 진행 · 완료 1~2줄 · 근거 · 생성 방식 · 실패 안내 + 「요약 다시 요청」), 종료 사건이면 초안 생성 · 편집 버튼 숨김. SSE `report.summary` 이벤트(있으면) 또는 대기 · 진행 중 짧은 주기 재조회
- [X] T049 [US4] **P21** 사건 화면 in `F/pages/IncidentDetail.tsx` — 관리자 「보류」 · 「종료」 버튼 제거, 보류 상태일 때만 「조사 재개」, 종료 사건 안내 「보고서 확정으로 종료됨」
- [X] T050 [US2] **P21** 요청 · 업무 화면 in `F/pages/Requests.tsx` · `F/pages/Workflow.tsx`(업무 목록 위치 확인 후) — 보낸 요청 OPEN · ACCEPTED에 「철회」(사유 입력), 채택 업무 READY · APPROVED · REQUESTED에 「미수행으로 닫기」(사유 입력), 철회 · 미수행 상태 표시
- [ ] T051 화면 검증 — `npm test && npm run build`, 서버 띄워 시나리오 1 · 3 · 5 · 6 화면 확인(사용자 확인 포함)

---

## Dependencies & Execution Order

- **Setup(T001)** → **Foundational(T002–T011)** → **US1(T012–T017)** → **US2(T018–T026)** → **US3(T027–T035)** · **US4(T036–T038)** → **Polish(T039–T045)**
- US1 → US2: 같은 확정 흐름(`ReportService`)의 ④ 자리를 채운다. US2의 철회 · 미수행 닫기(T023–T026)는 US1과 병행 가능
- US3: 확정 흐름 ⑧ 자리(T030)가 T021 뒤. 요약 서비스 · 내부 조회 · 알림(T029 · T031–T035)은 Foundational 뒤 병행 가능
- US4: Foundational 뒤 독립. 시드(T038)만 T017 뒤(같은 파일)
- 기존 테스트 기대값 변경(T008 · T013 · T014 · T036)은 해당 동작 변경과 **같은 체크포인트** 안에서 함께 반영해 테스트가 항상 통과 상태로 유지되게 한다

### 같은 파일 순서 (병렬 금지)

- `ReportService.java`: T016 → T021 → T022 → T030 · `Report.java`: T007 · `JobService.java`: T004
- `SeedRunner.java`: T017 → T038 · `ReportDtos.java`: T028 · `ReportSummary.java`: T006(→ T028에서 이름 결정 시 변경)
- `CheckRequest*.java`: T023 → T024 · `Work*.java`: T025 → T026 · `Incident.java`: T011 → T037

## Parallel Examples

```text
# Foundational
T002 JobKind · T005 ReportSummaryStatus · T009 ConfirmationBlocker · T010 ConfirmationContext

# US1
T012 ReportConfirmCloseApiTest · T015 ReportSnapshotFactory

# US2 (US1과 겹쳐 진행 가능)
T018 Guard 단위 테스트 · T019 Blockers API 테스트 · T023→T024 요청 철회 · T025→T026 업무 미수행 닫기

# US3
T027 Summary API 테스트 · T031 ReportSummaryController · T032 InternalReportSnapshotController · T035 알림
```

## Implementation Strategy

1. **MVP = US1 + US2** (둘 다 P1): 확정 = 종료 + 남은 일 차단 · 정리 + 승인 경로. quickstart 1–4로 확인
2. **US3**: 요약 — 시뮬레이터로 키 없이 끝까지(quickstart 5)
3. **US4**: 관리자 수동 종료 제거 · 시드 이력 보존(quickstart 6)
4. **Polish**: 스모크 · 계약 · 문서 · 전체 검증 · 전달 메모
5. 각 체크포인트마다 `./gradlew test` 통과 유지(원칙 X), 리팩터링 섞지 않기(원칙 VIII). 목록(P1–P20) 밖 기존 코드 수정이 필요하면 멈추고 확인
<!-- ARTIFACT:003-report-confirm-summary:tasks.md END -->

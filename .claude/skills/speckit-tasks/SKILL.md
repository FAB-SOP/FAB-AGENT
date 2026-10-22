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

---
name: "speckit-plan"
description: "Execute the implementation planning workflow using the plan template to generate design artifacts."
argument-hint: "Optional guidance for the planning phase"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/plan.md"
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

**Check for extension hooks (before planning)**:
- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_plan` key
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

1. **Setup**: Run `.specify/scripts/bash/setup-plan.sh --json` from repo root and parse JSON for FEATURE_SPEC, IMPL_PLAN, SPECS_DIR, BRANCH. For single quotes in args like "I'm Groot", use escape syntax: e.g 'I'\''m Groot' (or double-quote if possible: "I'm Groot").

2. **Load context**: Read FEATURE_SPEC and `.claude/skills/speckit-constitution/SKILL.md`의 「Project Constitution」 섹션. Load IMPL_PLAN template (already copied).

3. **Execute plan workflow**: Follow the structure in IMPL_PLAN template to:
   - Fill Technical Context (mark unknowns as "NEEDS CLARIFICATION")
   - Fill Constitution Check section from constitution
   - Evaluate gates (ERROR if violations unjustified)
   - Phase 0: Generate research.md (resolve all NEEDS CLARIFICATION)
   - Phase 1: Generate data-model.md, contracts/, quickstart.md
   - Re-evaluate Constitution Check post-design

## Mandatory Post-Execution Hooks

**You MUST complete this section before reporting completion to the user.**

Check if `.specify/extensions.yml` exists in the project root.
- If it does not exist, or no hooks are registered under `hooks.after_plan`, skip to the Completion Report.
- If it exists, read it and look for entries under the `hooks.after_plan` key.
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

Command ends after Phase 1 design. Report branch, IMPL_PLAN path, and generated artifacts.

## Phases

### Phase 0: Outline & Research

1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:

   ```text
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

### Phase 1: Design & Contracts

**Prerequisites:** `research.md` complete

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Define interface contracts** (if project has external interfaces) → `/contracts/`:
   - Identify what interfaces the project exposes to users or other systems
   - Document the contract format appropriate for the project type
   - Examples: public APIs for libraries, command schemas for CLI tools, endpoints for web services, grammars for parsers, UI contracts for applications
   - Skip if project is purely internal (build scripts, one-off tools, etc.)

3. **Create quickstart validation guide** → `quickstart.md`:
   - Document runnable validation scenarios that prove the feature works end-to-end
   - Include prerequisites, setup commands, test/run commands, and expected outcomes
   - Use links or references to contracts and data model details instead of duplicating them
   - Do not include full implementation code, model/service/controller bodies, migrations, or complete test suites
   - Keep this artifact as a validation/run guide; implementation details belong in `tasks.md` and the implementation phase

**Output**: data-model.md, /contracts/*, quickstart.md

## Key rules

- Use absolute paths for filesystem operations; use project-relative paths for references in documentation
- ERROR on gate failures or unresolved clarifications

## Done When

- [ ] Plan workflow executed and design artifacts generated
- [ ] Extension hooks dispatched or skipped according to the rules in Mandatory Post-Execution Hooks above
- [ ] Completion reported to user with branch, plan path, and generated artifacts

---

## Feature Artifacts

speckit 명령이 만든 산출물이다. 블록은 `<!-- ARTIFACT:<기능>:<문서> BEGIN/END -->` 표식으로 구분한다.

<!-- ARTIFACT:001-report-4m-classification:plan.md BEGIN -->
# Implementation Plan: 조사 보고서 4M 원인 분류

**Branch**: `001-report-4m-classification` | **Date**: 2026-10-22 | **Spec**: `.claude/skills/speckit-specify/SKILL.md#001-report-4m-classification:spec.md`

**Input**: Feature specification block `001-report-4m-classification:spec.md` in `.claude/skills/speckit-specify/SKILL.md`

## Summary

보고서를 확정하는 순간(직접 확정 · Agent 제안 승인)에 사람이 **원인 분류**(Material · Man · Method · Machine ·
보류)를 하나 고르고, Machine이면 그 사건 공정의 **장비 파라미터(센서)** 를 하나 이상 고른다. 분류는 확정본에
고정되어 보고서 화면 · 목록 · 내려받은 Markdown · 사건 이력에 남는다. 보고서 목록은 분류 · 파라미터 ·
「이상 발생 기간」으로 거른다.

기술 접근: 기존 확정 경로(`ReportService.doConfirm`)와 승인 경로(`ApprovalService.decide` →
`ConfirmReportHandler`)를 **바꾸지 않고 확장**한다. 분류는 값 객체(`ReportCauseClassification`)로
보고서에 붙이고, 승인 경로에서는 승인자가 고른 값을 승인 기록의 `decisionInput`에 저장한 뒤 실행 때 그 값만
쓴다. 장비 파라미터 목록 · 검증은 공정 대시보드 센서 시계열과 같은 출처(`PackStore.sensors()`)를 쓴다.
목록 필터는 기존 목록 응답에 분류를 더하고 화면에서 거른다(새 목록 API 없음).

## Technical Context

**Language/Version**: Java 21 · Spring Boot (`backend`), TypeScript · React 18 (`frontend`)
**Primary Dependencies**: Spring Web · Data JPA · Validation, TanStack Query, react-router — 새 의존성 없음
**Storage**: PostgreSQL `backend` 스키마 — `report` · `approval` 테이블에 빈 값 허용 열 추가(`ddl-auto: update`)
**Testing**: `./gradlew test`(MockMvc API 테스트 · 도메인 단위 테스트), `npm test`(vitest), compose 스모크
**Target Platform**: 로컬 compose(Linux 컨테이너) · 최신 브라우저
**Project Type**: web-service(Spring) + web-app(React). Agent(`ai`)는 변경 없음 — **`ai/` 폴더는 코드 · 계약 사본 · 문서 모두 수정하지 않는다(사용자 결정)**
**Performance Goals**: 분류 선택 UI 즉시 반응, 센서 목록 1회 조회(공정당 ≤ 9개) — 기존 화면 응답 수준 유지
**Constraints**: 헌법 I(승인 뒤 실행) · II(Agent는 원인 확정 안 함) · IV(계약 변경 기록) · VI(공정 범위) · X(기존 동작 보존)
**Scale/Scope**: 공정 3 · 센서 20 · 사건당 보고서 판본 수 개 · 목록은 기존대로 공정별 사건 ≤ 100

## Constitution Check

*GATE: Phase 0 전 통과 · Phase 1 설계 뒤 재확인*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. 승인 뒤 실행 | ✅ | Agent 제안 payload(보고서 · 판본)와 `actionHash` 검증은 그대로. 분류는 **승인자 본인이 결정 시 입력**하고 승인 기록 `decisionInput`에 저장된다. 수동 승인 즉시 실행 · Agent 재개 후 실행 모두 저장된 값만 쓴다. 내부 실행 API(`/internal/approved-actions/*`)는 승인 ID만 받아 Agent가 분류를 넣을 길이 없다 |
| II. 근거 기반 판단 | ✅ | Agent는 분류를 정하거나 제안하지 않는다. 「보류 (원인 미확정)」으로 미확정을 명시할 수 있다 |
| III. 숫자는 서버 계산 | ✅ | 수치 판정 없음 |
| IV. 계약 우선 | ✅ | 공개 API에 필드 3곳 · 조회 1개 추가 → `data/backend-handoff-v2/combined.openapi.json` · `backend/src/main/resources/openapi/monitoring-contract.json` 갱신, `ops/tools/openapi_diff.py`로 의도한 차이만 확인. **공개 계약 변경 확인됨(2026-10-22, 사용자)**. 팀 계약(내부 API · Agent 이벤트)은 변경 없음. `ai/docs/contract-changes.md` 기록은 `ai/` 미수정 결정에 따라 하지 않고, 같은 내용을 `contracts/public-api.md` 「변경 기록 초안」으로 계약 주인에게 넘긴다(Complexity Tracking) |
| V. 정답 · 저작물 격리 | ✅ | 해당 없음 |
| VI. 공정 범위 권한 | ✅ | 확정 · 승인은 기존 `loadForWrite`, 센서 목록은 `requireScope(processCode)`, 장비 파라미터는 사건 공정 센서만 허용 |
| VII. 결정적 검증 | ✅ | API 키 없이 API 테스트 · vitest로 검증 |
| VIII. 읽기 쉬운 코드 | ✅ | 리팩터링 아님. 새 이름은 역할을 드러낸다(`ReportCauseClassification` 등) |
| IX. 객체지향 · 구조 | ✅ | 값 객체 · enum · 검증기 분리, 승인 입력은 `ApprovedActionHandler` 기본 메서드로 **확장**(다른 핸들러 무변경). 파일은 기존 package-by-feature 위치 |
| X. 기존 동작 보존 · 수정 전 확인 | ✅ | 아래 「기존 코드 수정 목록」(M1–M16, M8b 포함)에 **사용자 동의(2026-10-22)**. 목록 밖의 기존 코드를 고쳐야 하면 다시 확인받는다. 분류 도입 전 보고서(`classification = null`)는 그대로 열린다 |

**게이트 결과**: 위반 없음. X 동의 · IV 계약 확인 완료(2026-10-22).

### 기존 코드 수정 목록 (원칙 X — 2026-10-22 사용자 동의)

| # | 파일 | 바꾸는 것 | 왜 추가만으로 안 되는가 | 기존 동작 영향 · 확인 방법 |
|---|------|-----------|------------------------|---------------------------|
| M1 | `domain/report/Report.java` | `@Embedded ReportCauseClassification classification` 필드, `confirm(...)`에 분류 인자 | 분류는 확정본에 고정돼야 하므로 엔티티에 있어야 한다 | 기존 행은 null → 응답 `classification: null`. `ReportTest` |
| M2 | `domain/report/ReportDtos.java` | `ReportConfirm`에 `classification`, `ReportView` · `ReportSummary`에 `classification` | 공개 요청 · 응답 모양 | 필드 추가만. API 테스트 |
| M3 | `domain/report/ReportService.java` | `confirm` · `confirmFromApproval` · `doConfirm`이 분류를 검증 · 기록, 이력 문구에 분류 | 확정 경로가 여기뿐 | 판본 충돌 · 멱등 · 스냅샷 동작 유지. `ReportApiTest` |
| M4 | `domain/report/ReportComposer.java` | `markdown()`에 분류 한 줄(분류 있을 때만) | 확정 스냅샷 · 내려받기에 표시(FR-008) | 초안 · 이전 보고서 출력 불변 |
| M5 | `domain/report/ConfirmReportHandler.java` | `normalizeDecisionInput` 재정의, `execute`가 저장된 분류 사용 | 승인 경로 확정이 이 핸들러에서만 실행된다 | 기존 payload 검증 불변 |
| M6 | `domain/approval/ApprovedActionHandler.java` | `default Map normalizeDecisionInput(raw, incident)` 추가 | 승인 시 입력을 종류별로 검사할 확장 지점이 없다 | 기본 구현이 「입력 없음」이라 다른 3개 핸들러 무변경 |
| M7 | `domain/approval/ApprovalDtos.java` · `Approval.java` · `ApprovalService.java` | `ApprovalDecision.classification`(선택), `Approval.decisionInput` 열, `decide`가 승인 전에 핸들러로 검사 · 저장, `ApprovalView.decisionInput` | 승인자 입력을 저장 · 실행에 넘길 곳 | 거절 · 다른 종류 승인은 입력 없이 기존과 같다. `ApprovalApiTest` |
| M8 | `domain/monitoring/MonitoringController.java` · `MonitoringService.java` | `GET /api/monitoring/sensor-definitions?processCode=` 메서드 추가 | 보고서 · 승인 화면에는 로트가 없어 기존 로트 요약으로 센서 목록을 받을 수 없다 | 기존 메서드 무변경. `MonitoringApiTest`에 추가 |
| M8b | `backend/src/main/resources/openapi/monitoring-contract.json` | 새 경로 · 응답 스키마 추가 | 모니터링 API의 서버 문서(`/v3/api-docs`)는 주석이 아니라 이 파일을 `OpenApiConfig`가 붙여 만든다. 빠지면 `openapi_diff`에서 차이로 잡힌다 | 기존 세 경로 · 스키마 무변경 |
| M9 | `frontend/src/pages/Reports.tsx` | `ConfirmPanel`에 분류 선택, 확정 요청에 분류, 보고서 화면 분류 표시, `ReportList` 필터 · 이상 발생일 | 화면이 여기뿐 | 기존 검토 체크 · 저장 · 내려받기 유지 |
| M10 | `frontend/src/pages/Approvals.tsx` | `CONFIRM_REPORT` 승인 모달에 분류 선택, 결정 요청에 분류 | 승인 화면이 여기뿐 | 다른 종류 승인 · 거절 화면 불변 |
| M11 | `frontend/src/api/monitoring.ts` · `api/types.ts` | `sensorDefinitions(process)` 함수, 분류 라벨 · 타입 | API 클라이언트 · 공용 라벨 위치 | 추가만 |
| M12 | `data/backend-handoff-v2/combined.openapi.json` | 스키마 · 조회 추가 | 원칙 IV(프론트 계약 = 공개 API) | `ops/tools/openapi_diff.py` |
| M13 | `src/test/.../api/ReportApiTest.java` (2곳) | 성공하는 확정 · 승인 요청 본문에 `classification` 추가 | 서버가 분류를 필수로 검사하면(FR-005 · FR-010) 기존 요청은 422가 된다 | **기대 응답은 그대로**, 요청 본문만 늘린다. 실패 경로(검토 미체크 · 판본 충돌) 기대값 불변 |
| M14 | `backend/src/main/java/com/fabcontext/api/seed/SeedRunner.java` 203행 | 시드 보고서 확정 요청에 분류 `MACHINE` · `slurry_flow` 추가 | 시드가 확정 API(`ReportService.confirm`)를 직접 불러, 분류 필수 검사 뒤에는 서버 시작 시 시드가 실패한다(구현 중 발견) | 그 사건에 기록된 조치(슬러리 공급 라인 점검 · 필터 교체)에 맞는 분류. 데모 데이터만 바뀜. **사용자 동의(2026-10-22, 옵션 A)** |
| M15 | `backend/src/test/java/com/fabcontext/api/domain/SchemaTest.java` 49행 | jsonb 컬럼 수 `hasSize(26)` → `hasSize(28)`, 확인 목록에 `report.cause_equipment_parameters` · `approval.decision_input` 추가 | 새 jsonb 컬럼 2개로 고정 개수가 맞지 않는다(구현 중 발견). 「JSON 열은 모두 jsonb」 규칙은 그대로 | 기존 테스트 **기대값 변경**. **사용자 동의(2026-10-22, 옵션 A)** |
| M16 | `backend/scripts/smoke-test.sh` 162행 | 보고서 확정 요청에 `"classification":{"category":"METHOD"}` 추가(기대 200 그대로) | 스모크가 분류 없이 확정해 422 → 이어지는 2건까지 실패(103 통과 · 3 실패, 구현 중 발견). 헌법 품질 게이트는 스모크 실패 0건 | **사용자 동의(2026-10-22, 옵션 A)** |

> M13은 「기존 테스트를 고치는 것」이라 따로 확인받았다(동의 · 기대 응답 불변 조건). 대안은 서버가 분류 없는 확정을 허용(화면에서만
> 강제)하는 것인데, 그러면 FR-005 · FR-010(서버 검사)과 SC-001(새 확정 100% 분류)을 보장할 수 없어 채택하지 않았다.

## Project Structure

### Documentation (this feature)

각 문서는 명령 스킬의 `SKILL.md` 안 `<!-- ARTIFACT:001-report-4m-classification:<문서> -->` 블록이다.

```text
.claude/skills/
├── speckit-specify/SKILL.md   # spec.md, checklists/requirements.md
├── speckit-plan/SKILL.md      # plan.md(이 문서), research.md, data-model.md, quickstart.md, contracts/public-api.md
└── speckit-tasks/SKILL.md     # tasks.md (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/src/main/java/com/fabcontext/api/
├── domain/report/
│   ├── ReportCauseCategory.java          # 새: MATERIAL · MAN · METHOD · MACHINE · HOLD
│   ├── ReportCauseClassification.java    # 새: @Embeddable 값 객체(범주 · 장비 파라미터 · 분류자 · 시각)
│   ├── CauseClassificationInput.java     # 새: 요청 DTO(범주 · 파라미터) — 확정 · 승인 요청 공용
│   ├── CauseClassificationPolicy.java    # 새: 검증(범주 · Machine 파라미터 · 사건 공정 센서)
│   ├── Report.java · ReportDtos.java · ReportService.java · ReportComposer.java · ConfirmReportHandler.java  # 수정 M1–M5
├── domain/approval/
│   └── ApprovedActionHandler.java · ApprovalDtos.java · Approval.java · ApprovalService.java  # 수정 M6–M7
└── domain/monitoring/
    └── MonitoringController.java · MonitoringService.java  # 수정 M8(메서드 추가)
backend/src/main/resources/openapi/monitoring-contract.json  # 수정 M8b

backend/src/test/java/com/fabcontext/api/
├── domain/report/CauseClassificationPolicyTest.java   # 새
├── api/ReportClassificationApiTest.java               # 새: 확정 · 승인 · 목록 · 내려받기 · 오류
└── api/ReportApiTest.java · MonitoringApiTest.java    # 수정 M13 · 조회 테스트 추가

frontend/src/
├── domain/causeClassification.ts          # 새: 라벨 · 완료 판정 · 요청 변환
├── domain/reportFilters.ts                # 새: 분류 · 파라미터 · 이상 발생 기간 필터(순수 함수)
├── components/CauseClassificationSelect.tsx  # 새: 4M+보류 드롭다운 + 장비 파라미터 선택
├── pages/Reports.tsx · pages/Approvals.tsx   # 수정 M9–M10
└── api/monitoring.ts · api/types.ts          # 수정 M11
frontend/tests/
├── cause-classification.test.ts           # 새
└── report-filters.test.ts                 # 새
```

**Structure Decision**: 기존 package-by-feature 구조를 따른다. 분류는 보고서의 일부라 `domain/report`에,
승인 입력 확장점은 `domain/approval`에, 센서 목록 조회는 센서 정의를 이미 내려 주는 `domain/monitoring`에 둔다.
Web은 화면(`pages`) · 재사용 UI(`components`) · 도메인 로직(`domain`) · 서버 호출(`api`) 구분을 따른다.

## Complexity Tracking

| 추가 개념 | 필요한 이유 | 더 단순한 대안을 버린 이유 |
|-----------|-------------|--------------------------|
| 계약 변경 기록을 `ai/docs/contract-changes.md`에 쓰지 않음 | 사용자가 `ai/` 폴더 미수정을 결정(2026-10-22). 헌법 IV는 기록을 요구한다 | 기록 자체를 생략하지 않고 `contracts/public-api.md` 「변경 기록 초안」으로 남겨 계약 주인이 반영하게 한다 |
| 승인 기록의 `decisionInput`(승인자 입력) | 사용자가 「Agent 제안 확정은 승인할 때 분류를 정한다」로 결정. 실행은 저장된 값만 써야 한다(원칙 I) | Agent payload에 분류를 넣으면 Agent가 원인을 정하게 된다(원칙 II 위반). 승인 뒤 별도 분류 단계는 승인 ≠ 실행 내용이 되어 원칙 I과 충돌 |
<!-- ARTIFACT:001-report-4m-classification:plan.md END -->

<!-- ARTIFACT:001-report-4m-classification:research.md BEGIN -->
# Research: 조사 보고서 4M 원인 분류

Technical Context에 NEEDS CLARIFICATION은 없었다. 아래는 설계 결정과 근거다.

## R1. 분류를 저장할 곳

- **Decision**: `Report`에 `@Embedded ReportCauseClassification`(열 4개: `cause_category` · `cause_equipment_parameters`(jsonb) · `classified_by` · `classified_at`), 모두 null 허용.
- **Rationale**: 분류는 판본 하나에 하나이고 확정과 함께 고정된다(spec Q1 · FR-007). 같은 행에 있으면 확정 트랜잭션 하나로 끝나고 스냅샷과 어긋날 수 없다. `ddl-auto: update`라 마이그레이션 없이 열이 생기고 기존 행은 null.
- **Alternatives**: 별도 `report_classification` 테이블 — 1:1이라 조인 · 정합성 비용만 늘어난다. `sections` JSON에 섞기 — 본문 다섯 키 고정 규칙(계약 #16)을 깨고 목록 필터가 어려워진다.

## R2. Agent 제안 경로에서 승인자 분류를 넘기는 방법

- **Decision**: `ApprovalDecision`에 선택 필드 `classification`. `ApprovalService.decide`가 승인(APPROVE)일 때 `ApprovedActionHandler.normalizeDecisionInput(raw, incident)`을 불러 검사 · 정규화하고 `Approval.decisionInput`(jsonb)에 **실행 전에** 저장한다. `ConfirmReportHandler.execute`는 `approval.getDecisionInput()`만 읽는다. 기본 구현은 빈 입력을 돌려줘 다른 핸들러는 무변경.
- **Rationale**: 수동 승인은 `approveAndExecute`에서 즉시 실행, Agent 대기 승인은 재개 뒤 `executeApproved`에서 실행된다. 두 경우 모두 실행 시점에 저장된 값을 읽으려면 승인 기록에 있어야 한다. 원칙 I의 「저장된 것만 실행」을 지키고, `ApprovalService`는 보고서 도메인을 모르는 기존 구조(핸들러 위임)를 유지한다.
- **Alternatives**: `ApprovalService`에서 `CONFIRM_REPORT`를 분기 처리 — 승인 도메인이 보고서 규칙을 알게 되어 기존 의존 방향을 깬다. 승인 후 보고서 화면에서 따로 분류 — 승인과 실행 사이 사람 입력이 생겨 원칙 I과 충돌.

## R3. 장비 파라미터 목록의 출처 · 화면 조회

- **Decision**: 새 조회 `GET /api/monitoring/sensor-definitions?processCode=` — `MonitoringService`가 이미 쓰는 `byProcess(pack.sensors(), processCode)`를 그대로 돌려준다. 화면 표시 이름은 센서 시계열과 같은 `sensorNames[sensorId] || sensorId`.
- **Rationale**: FR-003(센서 시계열과 같은 출처 · 같은 표시 이름). 보고서 · 승인 화면에는 로트 문맥이 없어 기존 로트 요약(`getMonitoringSummary`)으로는 받을 수 없다. 응답이 작다(공정당 ≤ 9개).
- **Alternatives**: 사건의 `lotId`로 로트 요약 재사용 — 런 · 특징값까지 내려와 무겁고, 승인 화면에서 사건을 한 번 더 불러야 한다. 화면에 센서 목록 하드코딩 — 데이터 팩과 어긋날 수 있고 서버 검증과 출처가 달라진다.

## R4. 서버 검증 규칙

- **Decision**: `CauseClassificationPolicy.validate(input, processCode, actor, now)` → `ReportCauseClassification`.
  - 범주 필수, 다섯 값 중 하나 → 아니면 422 `INVALID_CAUSE_CATEGORY`
  - `MACHINE`이면 파라미터 1개 이상 → 아니면 422 `EQUIPMENT_PARAMETER_REQUIRED`
  - 파라미터는 사건 공정 센서 ID만, 중복 제거 · 카탈로그 순서로 정렬 → 아니면 422 `INVALID_EQUIPMENT_PARAMETER`(hint: 허용 목록)
  - `MACHINE`이 아니면 파라미터를 버린다(빈 목록) — spec US2-3
  - 분류 객체 누락 → 422 `CAUSE_CLASSIFICATION_REQUIRED`
- **Rationale**: FR-005 · FR-006 · 엣지 케이스. 검증을 한 클래스에 모아 확정 · 승인 두 경로가 같은 규칙을 쓴다(원칙 IX 단일 책임).
- **검사 순서**: 기존 `REVIEW_CONFIRMATION_REQUIRED`(422) → 기존 초안 여부 `REPORT_CONFIRMED`(409) · 판본 `REVISION_CONFLICT`(409) → **분류 검사**(422). 분류를 맨 뒤에 두어 기존 실패 경로 테스트(`ReportApiTest` 85 · 87 · 97행)의 기대값이 그대로 유지되고, M13 수정은 성공 경로 2곳(89행 확정 · 124행 승인 결정)뿐이다.

## R5. 목록 필터 위치

- **Decision**: 화면에서 거른다. `ReportSummary`에 `classification`을 더하고, `ReportList`가 이미 불러오는 사건(`detectedAt`)과 합쳐 `domain/reportFilters.ts` 순수 함수로 분류 · 파라미터 · 이상 발생 기간을 적용한다.
- **Rationale**: 현재 목록은 공정별 사건 ≤ 100건 × 사건별 보고서를 화면이 모아 보여 준다. 새 서버 목록 API 없이 필드 추가만으로 FR-009 · FR-009a를 만족하고 계약 변경이 최소다.
- **Alternatives**: `GET /api/reports?category=&from=&to=` 서버 필터 — 계약 · 권한 · 페이지네이션 설계가 커진다. 규모가 커지면 그때 옮긴다.
- **기간 규칙**: 한국 시간(Asia/Seoul) 날짜로 비교, 종료일 포함, 한쪽만 지정 가능, 시작 > 종료면 필터 미적용 + 안내.

## R6. 이력 · 내려받기 표시

- **Decision**: `REPORT_CONFIRMED` 이력 문구 `보고서 v{n} 확정 · 원인 분류: {라벨}[({파라미터들})]`. Markdown 머리 인용 줄 다음에 `> 원인 분류: Machine (설비) — head_pressure, slurry_flow` 한 줄(분류 있을 때만).
- **Rationale**: FR-008 · FR-012. 분류 없는 초안 · 이전 확정본 출력은 그대로라 기존 내려받기 테스트(`startsWith("# 수정한 제목")` 등) 기대값이 유지된다.
- **센서 이름**: 데이터 팩에는 센서 표시 이름이 없고 화면(`sensorNames`)에만 있다. 서버가 쓰는 Markdown · 이력에는 **센서 ID**(`head_pressure`)를 그대로 적고, 한글 이름은 화면에서만 붙인다 — 서버에 이름 표를 새로 지어내지 않는다(원칙 III 취지).
<!-- ARTIFACT:001-report-4m-classification:research.md END -->

<!-- ARTIFACT:001-report-4m-classification:data-model.md BEGIN -->
# Data Model: 조사 보고서 4M 원인 분류

## ReportCauseCategory (enum, 새)

| 값 | 화면 라벨 | 장비 파라미터 |
|----|-----------|---------------|
| `MATERIAL` | Material (자재) | 없음 |
| `MAN` | Man (사람) | 없음 |
| `METHOD` | Method (방법) | 없음 |
| `MACHINE` | Machine (설비) | **1개 이상 필수** |
| `HOLD` | 보류 (원인 미확정) | 없음 · 사건 상태 「보류」와 무관 |

## ReportCauseClassification (@Embeddable 값 객체, 새 — `Report`에 포함)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `category` | `ReportCauseCategory` | 확정본이면 필수(도입 전 보고서는 null) |
| `equipmentParameters` | `List<String>` (jsonb) | `MACHINE`일 때만 1개 이상, 사건 공정 센서 ID, 중복 없음 · 카탈로그 순서. 그 외 범주는 빈 목록 |
| `classifiedBy` | `String` | 직접 확정이면 확정한 사람, 승인 경로면 승인자 |
| `classifiedAt` | `Instant` | 확정 시각과 같다 |

- 불변: 생성 후 바뀌지 않는다(세터 없음). `Report.confirm(userId, at, classification)`에서만 설정.
- `Report`가 CONFIRMED면 분류도 고정(기존 `requireDraft()`가 이후 변경을 막는다).

## Report (기존 엔티티, 수정)

- 추가: `classification: ReportCauseClassification` (null 허용)
- 상태 전이(기존 유지): `DRAFT` —(편집 · contentRevision++)→ `DRAFT` —(확정 + 분류)→ `CONFIRMED`
- 원인이 나중에 밝혀지면 새 판본(`baseReportVersion`)을 만들어 그 판본에 새 분류 — 판본마다 독립

## Approval (기존 엔티티, 수정)

- 추가: `decisionInput: Map<String, Object>` (jsonb, null 허용) — 승인자가 결정 시 입력한 값
  - `CONFIRM_REPORT` 승인: `{"category": "MACHINE", "equipmentParameters": ["head_pressure"]}` (정규화된 값)
  - 거절 · 다른 종류: null
- `actionHash`는 제안 payload만 덮는다(기존). `decisionInput`은 승인자 · 승인 시각과 함께 감사 기록이 된다.

## CauseClassificationInput (요청 DTO, 새)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `category` | string | 필수, enum 값 |
| `equipmentParameters` | string[] | 선택, 최대 20개 |

## 장비 파라미터(Equipment Parameter) — 읽기 전용, 저장 안 함

데이터 팩 `data/sensor-catalog.json` 항목: `sensorId` · `process` · `unit` 등. 공정별 개수 CVD 6 · ETCH 9 · CMP 5.

## 화면 필터 상태 (저장 안 함)

| 필드 | 값 |
|------|----|
| `category` | `ALL` · 다섯 범주 · `UNCLASSIFIED` |
| `equipmentParameter` | 센서 ID 또는 비움(`category = MACHINE`일 때만) |
| `detectedFrom` · `detectedTo` | `YYYY-MM-DD`(한국 시간), 비움 가능, 종료일 포함 |
<!-- ARTIFACT:001-report-4m-classification:data-model.md END -->

<!-- ARTIFACT:001-report-4m-classification:contracts/public-api.md BEGIN -->
# Contract: 공개 API 변경 (프론트 계약 `data/backend-handoff-v2/combined.openapi.json`)

모두 **추가**다. 기존 필드 · 경로 · operationId는 바꾸지 않는다. 팀 계약(내부 API · Agent 이벤트)은 변경 없음.

## 1. `POST /api/reports/{reportId}/confirmations` (confirmReport) — 요청 필드 추가

```json
{
  "expectedContentRevision": 2,
  "reviewConfirmed": true,
  "classification": { "category": "MACHINE", "equipmentParameters": ["head_pressure", "slurry_flow"] }
}
```

- `classification` 필수(서버 검사). 오류(422): `CAUSE_CLASSIFICATION_REQUIRED` · `INVALID_CAUSE_CATEGORY` · `EQUIPMENT_PARAMETER_REQUIRED` · `INVALID_EQUIPMENT_PARAMETER`
- 기존 오류 유지: `REVIEW_CONFIRMATION_REQUIRED`(422) · `REVISION_CONFLICT`(409) · `REPORT_CONFIRMED`(409)

## 2. `POST /api/approvals/{approvalId}/decisions` (decideApproval) — 요청 필드 추가

```json
{ "decision": "APPROVE", "comment": "", "expectedVersion": 1, "actionHash": "…",
  "classification": { "category": "HOLD", "equipmentParameters": [] } }
```

- `actionType = CONFIRM_REPORT`이고 `APPROVE`일 때만 필수 · 검사(오류 코드 위와 같음). 다른 종류 · `REJECT`는 무시.

## 3. `ReportView` · `ReportSummary` — 응답 필드 추가

```json
"classification": {
  "category": "MACHINE",
  "equipmentParameters": ["head_pressure"],
  "classifiedBy": "USR-CMP-01",
  "classifiedAt": "2026-10-22T05:10:00Z"
}
```

- 도입 전 · 초안 보고서는 `null`.

## 4. `ApprovalView` — 응답 필드 추가

- `decisionInput`: 승인자가 입력한 값(없으면 `null`).

## 5. 새 조회 `GET /api/monitoring/sensor-definitions?processCode={CVD|ETCH|CMP}` (listSensorDefinitions)

```json
{ "data": { "processCode": "CMP", "items": [ { "sensorId": "head_pressure", "process": "CMP", "unit": "psi", "...": "..." } ] },
  "meta": { "...": "..." } }
```

- `items`의 원소는 기존 `MonitoringSensorDefinition` 스키마를 재사용(로트 요약 `sensorDefinitions`와 같은 모양).
- 권한: `processScopes`에 그 공정이 없으면 403. 알 수 없는 공정은 422 `INVALID_PROCESS_CODE`.

## 반영 절차 (원칙 IV)

1. `data/backend-handoff-v2/combined.openapi.json`에 위 스키마 · 경로 추가, 모니터링 조회(5)는
   `backend/src/main/resources/openapi/monitoring-contract.json`에도 추가(서버 문서용)
2. `ai/` 폴더(`ai/contracts/public-openapi.json` · `ai/docs/contract-changes.md`)는 수정하지 않는다(사용자 결정).
   `ai/contracts/public-openapi.json`은 `ai/tests/conftest.py`가 Agent 이벤트 검증에 읽는 팀 사본이며 Agent는
   바뀌는 API를 쓰지 않는다. 변경 기록은 아래 초안을 계약 주인에게 넘긴다
3. 서버 실행 후 `python3 ops/tools/openapi_diff.py http://localhost:8096` — 위 5개 외 차이 0건
4. 계약 주인 확인 — 완료(2026-10-22, 사용자). 변경 기록 초안은 `ai/` 반영용으로 전달

## 변경 기록 초안 (계약 주인 전달용 — `ai/docs/contract-changes.md` 형식)

| # | 위치 | 전 | 제안(지금 구현) | 이유 |
|---|---|---|---|---|
| 27 | 공개 API `confirmReport` · `decideApproval` 요청, `ReportView` · `ReportSummary` · `ApprovalView` 응답, `GET /api/monitoring/sensor-definitions` | 없음 | `classification`{category: MATERIAL·MAN·METHOD·MACHINE·HOLD, equipmentParameters[]} 요청 · 응답 필드, `decisionInput`, 공정별 센서 정의 조회 추가(모두 추가만) | 보고서 4M 원인 분류(`001-report-4m-classification`). 팀 계약 · Agent 변경 없음 |
<!-- ARTIFACT:001-report-4m-classification:contracts/public-api.md END -->

<!-- ARTIFACT:001-report-4m-classification:quickstart.md BEGIN -->
# Quickstart: 조사 보고서 4M 원인 분류 검증

세부 모양은 `contracts/public-api.md`, 필드 규칙은 `data-model.md`를 본다.

## 준비

- 로컬 compose 실행(README 「실행」), 스키마 변경 뒤 재시드(헌법 · `backend/README.md`)
- 계정: CMP 공정 담당(`USR-CMP-01`), 설비정비팀(`USR-MNT-01`)

## 자동 검증

```bash
cd backend && ./gradlew test               # CauseClassificationPolicyTest · ReportClassificationApiTest · 기존 전부
cd ai && uv run pytest -q                    # 변경 없음 — 회귀 확인
cd frontend && npm test && npm run build
python3 ops/tools/openapi_diff.py http://localhost:8096   # 계약 문서의 5개 변경 외 차이 0건
python3 ops/tools/java_pkgdeps.py                         # 「순환: 없음」
```

## 수동 시나리오 (spec 수락 시나리오 대응)

1. **직접 확정 · 4M**(US1): CMP 사건 → 「초안 생성 요청」 → 보고서 열기 → 검토 체크만 하면 확정 버튼 비활성 →
   Method 선택 → 확정 → 보고서 · 목록 · Markdown에 「Method (방법)」.
2. **Machine 파라미터**(US2): 다른 CMP 초안 → Machine → CMP 센서 5개만 보이고 사건 관련 센서가 위에 →
   파라미터 미선택이면 확정 불가 → 헤드 압력 선택 후 확정 → 표시 확인. Method로 바꾸면 파라미터 칸이 사라짐.
3. **보류**(US1-4): 「보류 (원인 미확정)」 → 파라미터 칸 없이 확정 → 사건 상태는 바뀌지 않음.
4. **Agent 제안 승인**(US2-5~7): 「보고서 확정」 승인 대기 건 → 승인 모달에 같은 드롭다운 → 분류 없이 승인 불가 →
   Machine · 헤드 압력으로 승인 → 보고서 분류 = 승인자 선택, 승인 상세 `decisionInput` 같음. 거절은 분류 없이 가능.
5. **목록 필터**(US3): 설비정비팀 계정 → 조사 보고서 → 분류 Machine → 파라미터 헤드 압력 → 이상 발생 기간 지정 →
   해당 보고서만. 기간 비우면 전체. 시작 > 종료면 안내 후 미적용. 「분류 없음」에 도입 전 보고서.
6. **회귀**: 도입 전 확정 보고서 열기 · 내려받기 정상, 다른 종류 승인(확인 요청 · 정비 요청) 기존과 같음.

## 기대 결과

- 새 확정 보고서 100%가 분류를 가진다(SC-001), 파라미터 선택지가 센서 시계열 목록과 일치(SC-003), 기존 검증 전부 통과(SC-005).
<!-- ARTIFACT:001-report-4m-classification:quickstart.md END -->

<!-- ARTIFACT:002-report-title-search:plan.md BEGIN -->
# Implementation Plan: 조사 보고서 제목 검색 · 서버 목록 조회

**Branch**: `002-report-title-search` | **Date**: 2026-10-22 | **Spec**: `.claude/skills/speckit-specify/SKILL.md#002-report-title-search:spec.md`

**Input**: Feature specification block `002-report-title-search:spec.md` in `.claude/skills/speckit-specify/SKILL.md`

## Summary

조사 보고서 목록을 **서버 조회 한 번**으로 바꾼다. 새 `GET /api/reports`가 공정 · 제목 검색어 · 원인 분류 · 장비
파라미터 · 이상 발생 기간을 받아 **보고서와 사건을 조인한 SQL 한 번**으로 조회하고, `Pageable`(page · size)로
20건씩 나눠 `Slice`(다음 페이지 여부만) 모양으로 돌려준다. 화면은 사건마다 보고서를 부르던 N+1 호출(사건 수 + 1번,
사건 100건 한계)을 없애고 검색칸 + 「더 보기」로 바뀐다.

제목 부분 일치(`ILIKE '%검색어%'`)는 앞에 `%`가 붙어 B-tree 인덱스를 못 쓰므로 **`pg_trgm` GIN 인덱스**를,
목록 정렬(이상 감지일 최신순)에는 **정렬용 인덱스**를 둔다. 인덱스는 임시 DB에 보고서 10만 건을 넣고
`EXPLAIN (ANALYZE, BUFFERS)`로 전후를 측정해 효과가 확인된 것만 `schema.sql`에 넣는다.

## Technical Context

**Language/Version**: Java 21 · Spring Boot 4.1 (`backend`), TypeScript · React 18 (`frontend`)
**Primary Dependencies**: Spring Web · Data JPA(Hibernate) — `Pageable` · `Slice`, TanStack Query v5(`useInfiniteQuery`) — 새 의존성 없음
**Storage**: PostgreSQL 16(pgvector 이미지, `pg_trgm` 포함) `backend` 스키마 — 새 열 없음. 확장 1개 · 인덱스 추가(`schema.sql`)
**Testing**: `./gradlew test`(Testcontainers MockMvc), `npm test`(vitest), 스모크, `ops/tools/openapi_diff.py`, 측정 스크립트(임시 DB)
**Target Platform**: 로컬 compose · 최신 브라우저
**Project Type**: web-service + web-app. **`ai/` 변경 없음**
**Performance Goals**: 보고서 10만 건에서 세 글자 이상 검색 · 첫 페이지 조회가 DB 기준 100ms 이내, 개선 전 대비 10배 이상(spec SC-002 · SC-006). 목록 진입 시 서버 요청 1번
**Constraints**: 헌법 IV(공개 계약 추가 기록) · VI(공정 범위) · VII(결정적 검증) · X(기존 동작 보존 · 수정 전 동의). 기존 `/api/incidents/{id}/reports`(사건 화면이 씀)는 그대로 둔다
**Scale/Scope**: 측정 기준 보고서 10만 · 사건 2만. 실제 시드는 수십 건

## Constitution Check

*GATE: Phase 0 전 통과 · Phase 1 설계 뒤 재확인*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I · II · III · V | ✅ | 승인 · Agent 판단 · 수치 계산 · 정답과 무관한 조회 기능 |
| IV. 계약 우선 | ✅ | 공개 조회 `GET /api/reports`(`searchReports`) 추가 → `data/backend-handoff-v2/combined.openapi.json` 반영, `openapi_diff` 차이 0건. **공개 계약 변경 확인됨(2026-10-22, 사용자)**. `ai/` 미수정 결정에 따라 변경 기록은 `contracts/public-api.md` 초안으로 넘긴다(001과 같음) |
| VI. 공정 범위 권한 | ✅ | `processCode` 필수 + `AccessPolicy.requireScope`. 권한 밖 공정 403. 결과는 그 공정 보고서만 |
| VII. 결정적 검증 | ✅ | API 키 없이 API 테스트 · vitest. 측정은 임시 컨테이너 + 고정 시드(`setseed`)로 재현 가능 |
| VIII · IX. 읽기 쉬운 코드 · 객체지향 | ✅ | 조회 조건 값 객체(`ReportSearchCondition`), SQL 조립(`ReportSearchQuery`), 서비스, 컨트롤러를 **새 클래스로 분리**(단일 책임). 기존 `ReportController` · `ReportService`는 고치지 않고 확장 |
| X. 기존 동작 보존 · 수정 전 확인 | ✅ | 아래 「기존 코드 수정 목록」 S1–S3에 **사용자 동의(2026-10-22)**. 목록 밖 기존 코드를 고쳐야 하면 다시 확인받는다 |

### 기존 코드 수정 목록 (원칙 X — 2026-10-22 사용자 동의)

| # | 파일 | 바꾸는 것 | 왜 추가만으로 안 되는가 | 기존 동작 영향 · 확인 |
|---|------|-----------|------------------------|----------------------|
| S1 | `backend/src/main/resources/schema.sql` | 끝에 `CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public`과 인덱스 `CREATE INDEX IF NOT EXISTS …` 추가(측정으로 확정한 것만) | 표를 만든 뒤 매번 도는 스키마 보조 스크립트가 이것뿐이다(헌법 관례: 여러 번 돌려도 같은 결과). JPA `@Index`로는 GIN · 연산자 클래스를 표현할 수 없다 | 기존 외래 키 블록은 그대로. 재시작 시 `IF NOT EXISTS`로 중복 없음. 전체 테스트 · 스모크 |
| S2 | `frontend/src/pages/Reports.tsx` | `ReportList`의 데이터 출처를 사건별 호출 → `GET /api/reports` 무한 조회로, 필터 영역에 검색칸 추가, 「더 보기」 | 목록 화면이 여기뿐 | 보고서 상세 · 확정 화면(같은 파일의 `ReportEditor`)은 그대로. vitest · 수동 확인 |
| S3 | `data/backend-handoff-v2/combined.openapi.json` | `GET /api/reports` 경로 · 응답 스키마 추가 | 원칙 IV | `openapi_diff` |

- 기존 목록 필터 함수(`frontend/src/domain/reportFilters.ts`)는 **지우지 않는다** — 테스트와 함께 남아 서버 조건의
  의미(분류 없음 · 한국 시간 · 종료일 포함)를 대조하는 기준이 된다. 목록은 더 이상 이 함수로 거르지 않는다.
- 기존 API `GET /api/incidents/{id}/reports` · `ReportController` · `ReportService` · `ReportRepository`는 고치지 않는다.

**게이트 결과**: 위반 없음. X 동의 · IV 계약 확인 완료(2026-10-22).

## Project Structure

### Documentation (this feature)

```text
.claude/skills/
├── speckit-specify/SKILL.md   # 002 spec.md · checklists/requirements.md
├── speckit-plan/SKILL.md      # 002 plan.md(이 문서) · research.md · data-model.md · quickstart.md · contracts/public-api.md
└── speckit-tasks/SKILL.md     # 002 tasks.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/fabcontext/api/domain/report/
├── ReportSearchCondition.java    # 새: 조회 조건 값 객체(검색어 정규화 · 기간 → 한국 시간 경계 · 검증)
├── ReportSearchQuery.java        # 새: 조건에 있는 것만 WHERE에 넣는 네이티브 SQL 조립 · 실행(보고서 JOIN 사건 1번)
├── ReportSearchService.java      # 새: 권한 검사 → 조회 → Slice 응답
├── ReportSearchController.java   # 새: GET /api/reports (Pageable)
└── ReportSearchDtos.java         # 새: ReportListItem · ReportSlice
backend/src/main/resources/schema.sql   # 수정 S1

backend/src/test/java/com/fabcontext/api/
├── api/ReportSearchApiTest.java              # 새: 검색 · 필터 · 페이지 · 권한 · 기호 · 인덱스 존재
└── domain/report/ReportSearchConditionTest.java  # 새: 정규화 · 기간 경계 · 검증

frontend/src/
├── api/reports.ts                # 새: searchReports(params, page)
├── domain/reportSearch.ts        # 새: 필터 상태 → 요청 파라미터 변환(빈 값 제외)
├── hooks/useDebouncedValue.ts    # 새: 입력 0.3초 멈춤
└── pages/Reports.tsx             # 수정 S2
frontend/tests/report-search.test.ts   # 새

ops/tools/report_search_explain.sh   # 새: 임시 DB에 10만 건 생성 → 인덱스 전후 EXPLAIN ANALYZE 표 출력
```

**새 측정 도구**: `ops/tools/report_search_explain.sh` 위치 사용자 확인(2026-10-22).

**Structure Decision**: 보고서 목록 조회는 보고서 기능의 일부라 `domain/report`에 두되, 기존 클래스를 고치지 않도록
검색 전용 클래스(컨트롤러 · 서비스 · 쿼리 · 조건 · DTO)를 나눠 새로 만든다. 측정 스크립트는 기존 점검 도구 위치
`ops/tools/`에 둔다. 측정 데이터는 임시 컨테이너에만 생기고 저장소에 남지 않는다(spec FR-012).

## Complexity Tracking

| 추가 개념 | 필요한 이유 | 더 단순한 대안을 버린 이유 |
|-----------|-------------|--------------------------|
| 조건별로 WHERE를 조립하는 네이티브 SQL(`ReportSearchQuery`) | 제목 `ILIKE … ESCAPE` · jsonb 포함(`@>`)은 JPQL로 표현하기 어렵고, 빈 조건을 `(:q IS NULL OR …)`로 한 문장에 넣으면 PostgreSQL 일반 계획(prepared statement)에서 인덱스를 못 쓸 수 있다 | Spring Data 메서드 이름 쿼리 · 단일 `@Query` — 위 이유로 인덱스 효과를 보장할 수 없다. Criteria API — jsonb · ESCAPE를 함수 호출로 우회해야 해 읽기 어렵다 |
| 변경 기록을 `ai/docs/contract-changes.md`에 쓰지 않음 | `ai/` 미수정(사용자 결정, 001과 같음) | 초안으로 남겨 계약 주인이 반영 |
<!-- ARTIFACT:002-report-title-search:plan.md END -->

<!-- ARTIFACT:002-report-title-search:research.md BEGIN -->
# Research: 조사 보고서 제목 검색 · 서버 목록 조회

## R1. 조회 방식 — 한 번의 조인 쿼리 (N+1 제거)

- **Decision**: `report r JOIN incident i ON i.incident_id = r.incident_id`를 한 문장으로 조회하고, 필요한 칸만 뽑아
  DTO로 만든다(fetch join과 같은 효과 — 보고서마다 사건을 따로 읽지 않음).
- **Rationale**: 기간 필터 · 정렬 · 목록 표시에 사건의 `detected_at`이 필요하다. 지금 화면은 사건 수 + 1번 HTTP를
  보내고, 서버에서 엔티티 연관(`Report.incidentRef`, LAZY)을 반복문에서 꺼내면 DB N+1이 생긴다. 조인 한 번이면 둘 다 없다.
  보고서 → 사건은 다대일이라 페이징과 조인이 충돌하지 않는다.
- **Alternatives**: `default_batch_fetch_size` — 쿼리 2번으로 줄지만 기간 조건이 사건 테이블에 있어 어차피 조인이 필요하고,
  전역 설정이라 다른 기능 쿼리에도 영향. JPQL `join fetch` — 아래 R3의 ILIKE ESCAPE · jsonb 조건을 표현하기 어렵다.

## R2. 페이지 — `Pageable` + `Slice` (전체 건수 없음)

- **Decision**: 컨트롤러가 `Pageable`(page 0부터 · size 기본 20 · 최대 100)을 받고, SQL은 `LIMIT size+1 OFFSET page*size`로
  한 건을 더 읽어 다음 페이지 여부를 판단한다. 응답 `ReportSlice{items, page, size, hasNext}`.
  정렬은 클라이언트가 바꾸지 못하고 `i.detected_at DESC, r.report_id ASC`로 고정(spec FR-007a).
- **Rationale**: 사용자 결정(A). COUNT 쿼리가 없어 대량에서 빠르다. Spring Data의 `PageImpl`/`SliceImpl`을 그대로
  JSON으로 내보내면 모양이 불안정하다는 경고가 있어 응답은 전용 record로 만든다.
- **Alternatives**: 기존 커서 형식(`{items, nextCursor}`) — 사용자 결정으로 미채택. 키셋 커서 — 깊은 페이지가 빠르지만
  「더 보기」 용도에는 과하고 구현이 복잡. 깊은 offset(수천 페이지)은 느려질 수 있음을 측정 기록에 남긴다.

## R3. 조건 조립 — 있는 조건만 WHERE에 넣는 네이티브 SQL

- **Decision**: `ReportSearchQuery`가 조건 객체를 보고 해당 절만 붙인다(값은 모두 바인딩 파라미터 — SQL 주입 없음).
  - 공정: `r.process_code = :processCode` (필수)
  - 검색어: `r.title ILIKE :titlePattern ESCAPE '\'` — 패턴은 `%` + (`\` · `%` · `_` 이스케이프한 검색어) + `%`
  - 분류: `r.cause_category = :category`, 분류 없음은 `r.cause_category IS NULL`
  - 장비 파라미터: `r.cause_equipment_parameters @> jsonb_build_array(cast(:parameter as text))`(기존 저장소 관례와 같은 식)
  - 기간: `i.detected_at >= :fromInstant`, `i.detected_at < :toExclusive`(종료일 다음 날 00:00 KST)
  - 테이블 이름은 기존 네이티브 쿼리 관례대로 `{h-schema}report` · `{h-schema}incident`
- **Rationale**: 빈 조건을 `(:q IS NULL OR …)`로 섞으면 PostgreSQL이 일반 계획을 쓸 때 인덱스를 못 탈 수 있다. 실제로
  붙는 조건만 넣어야 EXPLAIN으로 측정한 계획이 운영에서도 같다.

## R4. 제목 부분 일치 인덱스 — `pg_trgm` GIN

- **Decision**: `CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;`
  `CREATE INDEX IF NOT EXISTS idx_report_title_trgm ON report USING gin (title public.gin_trgm_ops);`
- **Rationale**: `LIKE '%…%'`은 B-tree를 못 쓴다. trigram GIN은 `LIKE` · `ILIKE`의 중간 일치를 지원한다. 연결 기본 스키마가
  `backend`(테스트는 `backend_slow`도)라 확장을 `public`에 두고 연산자 클래스를 `public.gin_trgm_ops`로 지정해 어느 스키마의
  표에서도 같은 문장이 통한다. 이미지(pgvector/pg16)에 contrib가 포함되어 있다.
- **한계**: 세 글자 미만 검색어는 trigram을 만들 수 없어 인덱스 효과가 거의 없다(결과는 정확) — spec이 속도 목표를 세 글자 이상에만 둔 이유.
- **Alternatives**: `lower(title)` 식 인덱스 + `lower(...) LIKE` — ILIKE와 효과가 같고 식을 맞춰야 해 실수 여지가 크다.
  전문 검색(tsvector) — 부분 일치(「PM3」 같은 조각)에 맞지 않는다.

## R5. 정렬 · 공정 필터 인덱스 — 측정으로 확정

- **후보**: (a) `incident (process_code, detected_at DESC, incident_id)` — 검색어 없는 첫 페이지(최신순 20건)에서 정렬 없이
  앞에서부터 읽기, (b) 기존 `report(incident_id)` 인덱스(엔티티 `@Index`)로 조인.
- **Decision**: (a)를 1순위 후보로 측정한다. 측정 결과 개선이 없으면 넣지 않는다 — **효과가 확인된 인덱스만** `schema.sql`에 둔다.
- **Rationale**: 인덱스는 쓰기 비용 · 공간이 든다. spec FR-010은 측정 근거로 개선 조치를 고르라고 한다.

## R6. 측정 방법 — 임시 DB · 10만 건 · 전후 비교

- **Decision**: `ops/tools/report_search_explain.sh <DB_URL>` — 임시 컨테이너(빈 DB)에 백엔드가 만든 스키마를 띄운 뒤
  `generate_series` + `setseed(0.42)`로 사건 2만 · 보고서 10만(공정 3 · 분류 · 파라미터 · 날짜 분포)을 넣고,
  대표 쿼리 4개(① 검색어 없는 첫 페이지 ② 「PM3」 검색 ③ 검색 + Machine + 기간 ④ 결과 0건 검색)를
  `EXPLAIN (ANALYZE, BUFFERS)`로 **인덱스 없음 → 인덱스 생성 → `ANALYZE` → 다시 측정**. 실행 계획 노드 · 실행 시간을
  표로 출력하고 구현 메모에 기록.
- **Rationale**: 시드 데이터(수십 건)로는 플래너가 전체 탐색을 고른다. 고정 시드로 누구나 같은 결과를 재현한다(원칙 VII).
  데이터는 컨테이너와 함께 사라져 시드 · 운영 데이터와 저장소에 남지 않는다(spec FR-012).

## R7. 화면 — 무한 조회 · 디바운스

- **Decision**: `useInfiniteQuery(['reports', 조건])`, `getNextPageParam = hasNext ? page+1 : undefined`, 「더 보기」로
  `fetchNextPage`. 검색칸은 0.3초 디바운스 + Enter 즉시. 조건이 바뀌면 쿼리 키가 바뀌어 첫 페이지부터 다시 받는다.
  검색 실패 시 이전 데이터 유지(`placeholderData: keepPreviousData`) + 안내.
- **Rationale**: spec FR-006 · 엣지 케이스. TanStack Query v5에 이미 있는 기능이라 새 의존성이 없다.
<!-- ARTIFACT:002-report-title-search:research.md END -->

<!-- ARTIFACT:002-report-title-search:data-model.md BEGIN -->
# Data Model: 조사 보고서 제목 검색 · 서버 목록 조회

새 테이블 · 열은 없다. 조회용 값 객체 · 응답 DTO와 인덱스만 추가한다.

## ReportSearchCondition (값 객체, 새)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `processCode` | String | 필수, `CVD` · `ETCH` · `CMP`, 사용자 `processScopes` 안 |
| `titleKeyword` | String? | 앞뒤 공백 제거, 비면 null, 최대 100자(초과 422 `INVALID_KEYWORD`). SQL 패턴 만들 때 `\` · `%` · `_` 이스케이프 |
| `category` | `ReportCauseCategory`? 또는 `UNCLASSIFIED` | 알 수 없는 값 422 `INVALID_CAUSE_CATEGORY` |
| `equipmentParameter` | String? | `category = MACHINE`일 때만 의미, 그 외엔 무시 |
| `detectedFrom` · `detectedTo` | LocalDate? | 한국 시간 날짜. 시작 > 종료면 422 `INVALID_DETECTED_RANGE`. SQL에는 `[from 00:00 KST, to+1 00:00 KST)` Instant 경계로 |
| `page` · `size` | int | page ≥ 0, size 1–100(기본 20). 범위 밖 400 `INVALID_PAGE_SIZE`(기존 코드와 같은 이름) |

## ReportListItem (응답 행, 새)

`reportId` · `incidentId` · `incidentDisplayId` · `processCode` · `version` · `status` · `title` · `contentRevision` ·
`createdAt` · `confirmedAt` · `detectedAt` · `classification`(001의 `ClassificationView`, 없으면 null)

## ReportSlice (응답, 새)

`items: ReportListItem[]` · `page` · `size` · `hasNext`

## 인덱스 (schema.sql, 측정으로 확정)

| 이름 | 대상 | 목적 |
|------|------|------|
| `idx_report_title_trgm` | `report USING gin (title public.gin_trgm_ops)` | 제목 중간 일치(ILIKE) |
| `idx_incident_process_detected` (후보) | `incident (process_code, detected_at DESC, incident_id)` | 검색어 없는 목록의 최신순 첫 페이지 — 측정에서 효과가 있을 때만 |
<!-- ARTIFACT:002-report-title-search:data-model.md END -->

<!-- ARTIFACT:002-report-title-search:contracts/public-api.md BEGIN -->
# Contract: 공개 API 추가 (프론트 계약 `data/backend-handoff-v2/combined.openapi.json`)

추가만 한다. 기존 경로 · 스키마는 바꾸지 않는다. 팀 계약 · Agent 변경 없음.

## `GET /api/reports` (operationId `searchReports`)

| 파라미터 | 필수 | 설명 |
|----------|------|------|
| `processCode` | ✅ | `CVD` · `ETCH` · `CMP` |
| `q` | | 제목 부분 일치(대소문자 무시, 최대 100자, `%` `_` 글자 그대로) |
| `category` | | `MATERIAL` · `MAN` · `METHOD` · `MACHINE` · `HOLD` · `UNCLASSIFIED` |
| `equipmentParameter` | | 센서 ID — `category=MACHINE`일 때만 적용 |
| `detectedFrom` · `detectedTo` | | `YYYY-MM-DD`(한국 시간, 종료일 포함, 한쪽만 가능) |
| `page` · `size` | | page 0부터, size 기본 20 · 최대 100 |

```json
{ "data": {
    "items": [ { "reportId": "rpt_…", "incidentId": "inc_…", "incidentDisplayId": "EV-1009", "processCode": "CMP",
                 "version": 1, "status": "CONFIRMED", "title": "EV-1009 CMP-PM3 … 조사 보고서", "contentRevision": 2,
                 "createdAt": "…", "confirmedAt": "…", "detectedAt": "…",
                 "classification": { "category": "MACHINE", "equipmentParameters": ["slurry_flow"], "classifiedBy": "…", "classifiedAt": "…" } } ],
    "page": 0, "size": 20, "hasNext": true },
  "meta": { "…": "…" } }
```

- 정렬 고정: 이상 감지일 최신순 → 보고서 ID. 전체 건수 없음.
- 오류: 401 · 403(권한 밖 공정) · 422(`INVALID_PROCESS_CODE` · `INVALID_KEYWORD` · `INVALID_CAUSE_CATEGORY` · `INVALID_DETECTED_RANGE`) · 400(`INVALID_PAGE_SIZE`)

## 변경 기록 초안 (계약 주인 전달용)

| # | 위치 | 전 | 제안 | 이유 |
|---|---|---|---|---|
| 28 | 공개 API `GET /api/reports` | 없음(화면이 사건마다 `listReports` 호출) | 공정 · 제목 검색 · 분류 · 파라미터 · 기간 조건 + page/size, 응답 `{items, page, size, hasNext}` | 보고서 목록 N+1 · 사건 100건 한계 해소, 제목 검색(`002-report-title-search`) |
<!-- ARTIFACT:002-report-title-search:contracts/public-api.md END -->

<!-- ARTIFACT:002-report-title-search:quickstart.md BEGIN -->
# Quickstart: 조사 보고서 제목 검색 · 서버 목록 조회 검증

## 자동 검증

```bash
cd backend && ./gradlew test        # ReportSearchApiTest · ReportSearchConditionTest + 기존 전부
cd frontend && npm test && npm run build
cd ai && uv run pytest -q             # 회귀만
python3 ops/tools/openapi_diff.py http://localhost:8096   # searchReports 외 새 차이 0건
```

## 성능 측정 (임시 DB, research R6)

```bash
docker run -d --rm --name fab-explain-pg -e POSTGRES_DB=fab -e POSTGRES_USER=fab -e POSTGRES_PASSWORD=fab \
  -p 127.0.0.1:55434:5432 pgvector/pgvector:pg16
# 백엔드를 이 DB로 한 번 띄워 스키마 생성(DDL_AUTO=create) 후 종료
ops/tools/report_search_explain.sh 'postgresql://fab:fab@127.0.0.1:55434/fab'
docker stop fab-explain-pg
```

기대: 쿼리 ①–④의 인덱스 전 · 후 계획 노드(Seq Scan → Bitmap Index Scan / Index Scan)와 실행 시간 표.
②③은 10배 이상 · 100ms 이내(SC-002), ①은 100ms 이내(SC-006).

## 수동 시나리오

1. `cmp.park` → 조사 보고서: 네트워크 탭에서 목록 진입 시 `/api/reports` 1번(사건별 호출 없음).
2. 검색칸에 `PM3` → 0.3초 뒤 제목에 PM3가 든 보고서만. 비우면 전체. `cmp-pm3`(소문자)도 찾음. `%` 입력 시 글자 그대로.
3. 검색 + 분류 Machine + 기간 → 세 조건 모두 만족. 조건 바꾸면 첫 페이지부터.
4. 결과가 20건 넘을 때 「더 보기」로 이어 붙음, 끝에서 버튼 사라짐.
5. 회귀: 보고서 상세 · 확정 · 사건 화면의 보고서 탭 그대로.
<!-- ARTIFACT:002-report-title-search:quickstart.md END -->

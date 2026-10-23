---
name: "speckit-implement"
description: "Execute the implementation plan by processing and executing all tasks defined in tasks.md"
argument-hint: "Optional implementation guidance or task filter"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/implement.md"
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

**Check for extension hooks (before implementation)**:
- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_implement` key
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

1. Run `.specify/scripts/bash/check-prerequisites.sh --json --require-tasks --include-tasks` from repo root and parse FEATURE_DIR and AVAILABLE_DOCS list. All paths must be absolute. For single quotes in args like "I'm Groot", use escape syntax: e.g 'I'\''m Groot' (or double-quote if possible: "I'm Groot").

2. **Check checklists status** (if any `<!-- ARTIFACT:<기능>:checklists/… BEGIN -->` block exists in `.claude/skills/speckit-specify/SKILL.md` or `.claude/skills/speckit-checklist/SKILL.md` — scan both):
   - Scan all checklist files in the checklists/ directory
   - For each checklist, count:
     - Total items: All lines matching `- [ ]` or `- [X]` or `- [x]`
     - Completed items: Lines matching `- [X]` or `- [x]`
     - Incomplete items: Lines matching `- [ ]`
   - Create a status table:

     ```text
     | Checklist | Total | Completed | Incomplete | Status |
     |-----------|-------|-----------|------------|--------|
     | ux.md     | 12    | 12        | 0          | ✓ PASS |
     | test.md   | 8     | 5         | 3          | ✗ FAIL |
     | security.md | 6   | 6         | 0          | ✓ PASS |
     ```

   - Calculate overall status:
     - **PASS**: All checklists have 0 incomplete items
     - **FAIL**: One or more checklists have incomplete items

   - **If any checklist is incomplete**:
     - Display the table with incomplete item counts
     - **STOP** and ask: "Some checklists are incomplete. Do you want to proceed with implementation anyway? (yes/no)"
     - Wait for user response before continuing
     - If user says "no" or "wait" or "stop", halt execution
     - If user says "yes" or "proceed" or "continue", proceed to step 3

   - **If all checklists are complete**:
     - Display the table showing all checklists passed
     - Automatically proceed to step 3

3. Load and analyze the implementation context:
   - **REQUIRED**: Read tasks.md for the complete task list and execution plan
   - **REQUIRED**: Read plan.md for tech stack, architecture, and file structure
   - **IF EXISTS**: Read data-model.md for entities and relationships
   - **IF EXISTS**: Read contracts/ for API specifications and test requirements
   - **IF EXISTS**: Read research.md for technical decisions and constraints
   - **IF EXISTS**: Read the 「Project Constitution」 section of `.claude/skills/speckit-constitution/SKILL.md` for governance constraints
   - **IF EXISTS**: Read quickstart.md for integration scenarios

4. **Project Setup Verification**:
   - **REQUIRED**: Create/verify ignore files based on actual project setup:

   **Detection & Creation Logic**:
   - Check if the following command succeeds to determine if the repository is a git repo (create/verify .gitignore if so):

     ```sh
     git rev-parse --git-dir 2>/dev/null
     ```

   - Check if Dockerfile* exists or Docker in plan.md → create/verify .dockerignore
   - Check if .eslintrc* exists → create/verify .eslintignore
   - Check if eslint.config.* exists → ensure the config's `ignores` entries cover required patterns
   - Check if .prettierrc* exists → create/verify .prettierignore
   - Check if .npmrc or package.json exists → create/verify .npmignore (if publishing)
   - Check if terraform files (*.tf) exist → create/verify .terraformignore
   - Check if .helmignore needed (helm charts present) → create/verify .helmignore

   **If ignore file already exists**: Verify it contains essential patterns, append missing critical patterns only
   **If ignore file missing**: Create with full pattern set for detected technology

   **Common Patterns by Technology** (from plan.md tech stack):
   - **Node.js/JavaScript/TypeScript**: `node_modules/`, `dist/`, `build/`, `*.log`, `.env*`
   - **Python**: `__pycache__/`, `*.pyc`, `.venv/`, `venv/`, `dist/`, `*.egg-info/`
   - **Java**: `target/`, `*.class`, `*.jar`, `.gradle/`, `build/`
   - **C#/.NET**: `bin/`, `obj/`, `*.user`, `*.suo`, `packages/`
   - **Go**: `*.exe`, `*.test`, `vendor/`, `*.out`
   - **Ruby**: `.bundle/`, `log/`, `tmp/`, `*.gem`, `vendor/bundle/`
   - **PHP**: `vendor/`, `*.log`, `*.cache`, `*.env`
   - **Rust**: `target/`, `debug/`, `release/`, `*.rs.bk`, `*.rlib`, `*.prof*`, `.idea/`, `*.log`, `.env*`
   - **Kotlin**: `build/`, `out/`, `.gradle/`, `.idea/`, `*.class`, `*.jar`, `*.iml`, `*.log`, `.env*`
   - **C++**: `build/`, `bin/`, `obj/`, `out/`, `*.o`, `*.so`, `*.a`, `*.exe`, `*.dll`, `.idea/`, `*.log`, `.env*`
   - **C**: `build/`, `bin/`, `obj/`, `out/`, `*.o`, `*.a`, `*.so`, `*.exe`, `*.dll`, `autom4te.cache/`, `config.status`, `config.log`, `.idea/`, `*.log`, `.env*`
   - **Swift**: `.build/`, `DerivedData/`, `*.swiftpm/`, `Packages/`
   - **R**: `.Rproj.user/`, `.Rhistory`, `.RData`, `.Ruserdata`, `*.Rproj`, `packrat/`, `renv/`
   - **Universal**: `.DS_Store`, `Thumbs.db`, `*.tmp`, `*.swp`, `.vscode/`, `.idea/`

   **Tool-Specific Patterns**:
   - **Docker**: `node_modules/`, `.git/`, `Dockerfile*`, `.dockerignore`, `*.log*`, `.env*`, `coverage/`
   - **ESLint**: `node_modules/`, `dist/`, `build/`, `coverage/`, `*.min.js`
   - **Prettier**: `node_modules/`, `dist/`, `build/`, `coverage/`, `package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`
   - **Terraform**: `.terraform/`, `*.tfstate*`, `*.tfvars`, `.terraform.lock.hcl`
   - **Kubernetes/k8s**: `*.secret.yaml`, `secrets/`, `.kube/`, `kubeconfig*`, `*.key`, `*.crt`

5. Parse tasks.md structure and extract:
   - **Task phases**: Setup, Tests, Core, Integration, Polish
   - **Task dependencies**: Sequential vs parallel execution rules
   - **Task details**: ID, description, file paths, parallel markers [P]
   - **Execution flow**: Order and dependency requirements

6. Execute implementation following the task plan:
   - **Phase-by-phase execution**: Complete each phase before moving to the next
   - **Respect dependencies**: Run sequential tasks in order, parallel tasks [P] can run together
   - **Follow TDD approach**: Execute test tasks before their corresponding implementation tasks
   - **File-based coordination**: Tasks affecting the same files must run sequentially
   - **Validation checkpoints**: Verify each phase completion before proceeding

7. Implementation execution rules:
   - **Setup first**: Initialize project structure, dependencies, configuration
   - **Tests before code**: If you need to write tests for contracts, entities, and integration scenarios
   - **Core development**: Implement models, services, CLI commands, endpoints
   - **Integration work**: Database connections, middleware, logging, external services
   - **Polish and validation**: Unit tests, performance optimization, documentation

8. Progress tracking and error handling:
   - Report progress after each completed task
   - Halt execution if any non-parallel task fails
   - For parallel tasks [P], continue with successful tasks, report failed ones
   - Provide clear error messages with context for debugging
   - Suggest next steps if implementation cannot proceed
   - **IMPORTANT** For completed tasks, make sure to mark the task off as [X] in the tasks file.

9. Completion validation:
   - Verify all required tasks are completed
   - Check that implemented features match the original specification
   - Validate that tests pass and coverage meets requirements
   - Confirm the implementation follows the technical plan

Note: This command assumes a complete task breakdown exists in tasks.md. If tasks are incomplete or missing, suggest running `/speckit-tasks` first to regenerate the task list.

## Mandatory Post-Execution Hooks

**You MUST complete this section before reporting completion to the user.**

Check if `.specify/extensions.yml` exists in the project root.
- If it does not exist, or no hooks are registered under `hooks.after_implement`, skip to the Completion Report.
- If it exists, read it and look for entries under the `hooks.after_implement` key.
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

Report final status with summary of completed work.

## Done When

- [ ] All tasks in tasks.md completed and marked `[X]`
- [ ] Implementation validated against specification, plan, and test coverage
- [ ] Extension hooks dispatched or skipped according to the rules in Mandatory Post-Execution Hooks above
- [ ] Completion reported to user with summary of completed work

---

## Feature Artifacts

speckit 명령이 만든 산출물이다. 블록은 `<!-- ARTIFACT:<기능>:<문서> BEGIN/END -->` 표식으로 구분한다.

<!-- ARTIFACT:001-report-4m-classification:implementation-notes.md BEGIN -->
# Implementation Notes: 조사 보고서 4M 원인 분류


## T001 기준선 (2026-10-22, 원격 57572a4 기준 · 변경 전)

| 대상 | 명령 | 결과 |
|------|------|------|
| backend | `./gradlew test` | 122 tests · 2 skipped · **0 failed** (BUILD SUCCESSFUL) |
| frontend | `npm ci && npm test && npm run build` | 5 files · 33 passed, build 성공 (구조 변경으로 node_modules가 없어 `npm ci`로 lockfile 그대로 설치) |
| ai | `uv run pytest -q` | 41 passed · 2 skipped |


## 백엔드 구현 메모 (US1 · US2)

- 목록 밖 기존 코드를 피한 방법 — 기존 호출부를 고치지 않으려고 이전 모양을 남겼다
  - `Report.confirm(userId, at)`(2인자)는 그대로 두고 분류를 받는 3인자로 위임 → 기존 `ReportTest` 무변경
  - `ApprovalDtos.ApprovalDecision`에 이전 4인자 생성자 추가 → `WorkService`(업무 채택 요청 발송 승인) 무변경
- 구현 중 발견해 동의받은 수정: **M14** `SeedRunner` 203행(시드 보고서 확정에 `MACHINE · slurry_flow`)
- 카테고리 표시 이름은 `ReportCauseCategory.label()`(프론트 `causeCategoryLabels`와 같음) — 이력 · Markdown 공용
- 1차 전체 실행: 134 tests · 2 skipped · **1 failed** — `SchemaTest.jsonColumnsAndRenamedColumn`이 jsonb 컬럼 수를 26개로 고정해 두었는데 새 jsonb 2개(`report.cause_equipment_parameters` · `approval.decision_input`)로 28개가 됨 → 사용자 확인 대기


## 마무리 검증 (2026-10-22)

| 항목 | 결과 |
|------|------|
| backend `./gradlew test --rerun-tasks` | **134 tests · 2 skipped · 0 failed** (기준선 122 + 새 12) |
| frontend `npm test && npm run build` | **7 files · 43 passed**, build 성공 (기준선 33 + 새 10) |
| ai `uv run pytest -q` (회귀만, 코드 변경 없음) | 41 passed · 2 skipped — 기준선과 같음 |
| 스모크 `backend/scripts/smoke-test.sh` (새로 시드한 서버) | **107 passed · 0 failed** — M16 반영 전 103 · 3(162행 분류 누락이 원인 · 이어진 2건) |
| `ops/tools/openapi_diff.py` | 이 기능의 차이 5행(센서 조회 · 확정 · 승인 결정 요청) → 계약 반영 후 **0행**. 남은 행은 변경 전에도 있던 것(work-items 6개 · comparisons · proposals/approvals의 MAINTENANCE_REQUEST enum) — 이번 범위 밖 |
| `ops/tools/java_pkgdeps.py` | 순환 `[domain.action, domain.checkrequest, domain.work]` — **변경 전 HEAD에도 같은 순환**(이번 기능이 만든 것 아님). 새 의존: approval → report 없음(승인 결정의 classification을 Map으로 둔 이유) |
| API 확인(설비정비팀 계정) | CMP 센서 5개 조회, 시드 보고서 `MACHINE · slurry_flow`(M14), 스모크 보고서 `METHOD` |

- 검증 서버는 별도 임시 Postgres 컨테이너(`fab-verify-pg`, 포트 55433)와 포트 8096으로 띄웠다 — 실행 중인 `fab-deploy-*` 컨테이너는 건드리지 않았다. 검증 뒤 정리
- 계약 요청 스키마 `CauseClassificationInput.category`는 서버(문자열 + 서버 검사)와 맞추려고 enum 대신 description에 값 목록. 응답 `ClassificationView.category`는 enum
- **T039(브라우저 수동 시나리오) 미실시** — 같은 시나리오를 API 테스트 · 스모크 · API 호출로 확인. 화면 클릭 확인은 사용자 확인 필요
- **T040(README 「검증 현황」)** — 기존 문서 수정이라 사용자 확인 대기

<!-- ARTIFACT:001-report-4m-classification:implementation-notes.md END -->

<!-- ARTIFACT:002-report-title-search:implementation-notes.md BEGIN -->
# Implementation Notes: 조사 보고서 제목 검색 · 서버 목록 조회


## T001 기준선 (2026-10-22, 001 반영 후 · 변경 전)

| 대상 | 결과 |
|------|------|
| backend `./gradlew test` | 134 tests · 2 skipped · 0 failed |
| frontend `npm test && npm run build` | 43 passed, build 성공 |


## T025 측정 (2026-10-22, 임시 컨테이너 fab-explain-pg · pgvector/pg16 · 고정 시드)

`ops/tools/report_search_explain.sh postgresql://fab:fab@127.0.0.1:55434/fab backend` 출력:

▶ 측정 데이터 생성: 사건 20,000 · 보고서 100,000 (setseed 0.42)

  report 100001 건 · incident 20009 건

### 인덱스 없음 (기존 incident_id 인덱스만)
| 쿼리 | 주요 계획 노드 | 결과 행 | 실행 시간(ms, 3회 중 최소) |
|------|----------------|---------|---------------------------|
| ① 검색어 없는 첫 페이지 | Index Scan using idx39sdjnmwf6bojr2shp475b89u on report, Parallel Seq Scan on incident | 21 | 3.059 |
| ② 사건 번호 검색(EV-1123) | Index Scan using incident_pkey on incident, Seq Scan on report | 21 | 40.108 |
| ③ 현상 검색(관리한계) + Machine + 기간 | Index Scan using idx39sdjnmwf6bojr2shp475b89u on report, Parallel Seq Scan on incident | 21 | 4.168 |
| ③-1 흔한 단어 검색만(관리한계, 보고서 20%) | Index Scan using idx39sdjnmwf6bojr2shp475b89u on report, Parallel Seq Scan on incident | 21 | 3.894 |
| ④ 결과 없는 검색 | Index Scan using incident_pkey on incident, Seq Scan on report | 0 | 43.834 |
| ⑤ 깊은 페이지(offset 20000) | Seq Scan on incident, Seq Scan on report | 21 | 35.203 |

### 인덱스 있음 (pg_trgm GIN 제목 + incident(process_code, detected_at desc, incident_id))
| 쿼리 | 주요 계획 노드 | 결과 행 | 실행 시간(ms, 3회 중 최소) |
|------|----------------|---------|---------------------------|
| ① 검색어 없는 첫 페이지 | Index Scan using idx_incident_process_detected on incident, Index Scan using idx39sdjnmwf6bojr2shp475b89u on report | 21 | 0.290 |
| ② 사건 번호 검색(EV-1123) | Bitmap Heap Scan on report, Bitmap Index Scan on idx_report_title_trgm, Index Scan using incident_pkey on incident | 21 | 1.284 |
| ③ 현상 검색(관리한계) + Machine + 기간 | Index Scan using idx_incident_process_detected on incident, Index Scan using idx39sdjnmwf6bojr2shp475b89u on report | 21 | 2.733 |
| ③-1 흔한 단어 검색만(관리한계, 보고서 20%) | Index Scan using idx_incident_process_detected on incident, Index Scan using idx39sdjnmwf6bojr2shp475b89u on report | 21 | 0.948 |
| ④ 결과 없는 검색 | Bitmap Heap Scan on report, Bitmap Index Scan on idx_report_title_trgm, Index Scan using incident_pkey on incident | 0 | 0.051 |
| ⑤ 깊은 페이지(offset 20000) | Seq Scan on incident, Seq Scan on report | 21 | 37.187 |

인덱스 크기: idx_report_title_trgm 5136 kB · idx_incident_process_detected 984 kB

### 판단

- **채택 `idx_report_title_trgm`(pg_trgm GIN, 5.1 MB)** — 선택적인 검색(②)이 40 → 1.3ms(31배), 결과 없는 검색(④)이 44 → 0.05ms. 전체 탐색(Seq Scan on report)이 Bitmap Index Scan으로 바뀜
- **채택 `idx_incident_process_detected`(1.0 MB)** — 검색어 없는 첫 페이지(①)가 3.1 → 0.3ms. 이상 감지일 최신순으로 사건 인덱스를 읽고 20건에서 멈춘다(Nested Loop + Index Scan)
- **쿼리 수정(측정으로 발견)**: 처음엔 `r.process_code`만 걸어 ①이 사건 인덱스를 못 쓰고 17ms(Hash Join + 정렬)였다. 사건 쪽 `i.process_code`도 함께 걸도록 `ReportSearchQuery`를 바꿨다(보고서 · 사건 공정 불일치 0건 확인 — 결과 동일)
- **흔한 단어(③ · ③-1, 보고서 20%가 포함)**는 인덱스 없이도 최신순으로 읽다가 20건에서 멈춰 4ms 안팎 → 인덱스로 1–3ms. spec SC-002의 「10배」는 선택적인 검색에서 충족(②31배 · ④860배), 흔한 단어는 원래 빨라 10배 여지가 없음 — 모두 100ms 이내
- **2글자 검색**(예: 「두께」)은 trigram을 만들 수 없어 인덱스가 쓰이지 않는다(결과는 정확) — spec 가정과 같음
- **깊은 페이지(⑤ offset 20000)**는 인덱스와 무관하게 35ms 안팎(offset이 앞의 2만 행을 읽고 버림). 「더 보기」 사용 범위에서는 문제없음. 필요해지면 키셋 커서로 바꾼다
- 측정 데이터는 임시 컨테이너에만 있었고 측정 뒤 컨테이너를 지운다(spec FR-012)


## 마무리 검증 (2026-10-22)

| 항목 | 결과 |
|------|------|
| backend `./gradlew test --rerun-tasks` | **142 · 2 skipped · 0 failed** (기준선 134 + 새 8: ReportSearchApiTest 4 · ReportSearchConditionTest 4) |
| frontend `npm test && npm run build` | **8 files · 47 passed**, build 성공 (기준선 43 + 새 4) |
| ai `uv run pytest -q` (회귀만) | 41 passed · 2 skipped — 변경 없음 |
| 스모크(새로 시드한 서버) | **106 passed · 0 failed** — 이전 107과 1개 차이는 62행의 조건부 검사(시드 사건의 첫 분석이 이미 끝났으면 기다리지 않음, 타이밍)로 이번 변경과 무관 |
| `openapi_diff` | `GET /api/reports` 계약 반영 후 **새 차이 0건** — 남은 행은 001 마무리 때와 같은 목록(work-items · comparisons · MAINTENANCE_REQUEST enum) |
| `java_pkgdeps` | 새 순환 없음(기존 action · checkrequest · work 순환은 그대로) |
| API 확인(설비정비팀) | CMP 목록 최신순 · Slice 모양, `q=pm3`(소문자) → EV-1009 CMP-PM3 보고서, Machine + slurry_flow → 시드 보고서 |

- 계약 문서화: `Pageable`이 서버 문서에 객체 하나(`pageable`, 필수)로 나와 프론트에 혼동을 줄 수 있어, 새 컨트롤러에 `@ParameterObject`를 붙여 `page` · `size` · `sort`(선택) 쿼리 파라미터로 문서화했다(동작은 같음). 계약에는 서버 문서를 그대로 옮기고 설명(description)에 정렬 고정 · size 최대 100 · hasNext만을 적었다
- 화면 수동 확인(T031) · README(T032, 기존 문서 수정)는 사용자 확인 대기

<!-- ARTIFACT:002-report-title-search:implementation-notes.md END -->

<!-- ARTIFACT:003-report-confirm-summary:implementation-notes.md BEGIN -->
# Implementation Notes: 보고서 확정 · 사건 자동 종료 · AI 요약 연결


## T001 기준선 (2026-10-23, HEAD 6c3ce85 · 변경 전)

| 대상 | 결과 |
|------|------|
| backend `./gradlew test --rerun-tasks` | 225 tests · 2 skipped · **0 failed** |
| frontend `npm test && npm run build` | 8 files · 47 passed, build 성공 |
| ai `uv run pytest -q` | 133 passed · 3 skipped |
| 스모크(새로 시드한 서버) | 직전 측정(002 통합 main, 같은 코드) 102 passed · **5 failed** — 요청 수락 단계 4건 · LOT 개수(데이터 v2.1) 1건, main에 이미 있는 실패 |


## 결정 기록

- 이름: 기존 목록 DTO `ReportDtos.ReportSummary`와 겹치지 않게 요약 값 객체는 `ReportAiSummary` · `ReportAiSummaryStatus`, 서비스 · 컨트롤러는 `ReportAiSummaryService` · `ReportAiSummaryController`, 응답 record는 `ReportDtos.AiSummaryView`(JSON 필드 이름은 계약대로 `summary`)
- 확정본 jsonb 열은 기존 `snapshotRef` · `snapshotMarkdown`과 구별되게 `confirmedSnapshot`(`confirmed_snapshot`)
- `Incident`에는 이미 공개 `setStatus`가 있어 시드의 옛 상태 직접 설정에 그대로 쓴다 — `Incident`의 기존 메서드는 바꾸지 않고 `closeByConfirmedReport`만 추가

- 회신 대기 작업 차단 규칙 변경(사용자 결정 2026-10-23) — 기다리는 요청이 OPEN · ACCEPTED일 때만 차단, 확정 때 남은 대기 작업은 `JobService.finishWaitingOnIncidentClosed`로 닫음(알림 없음). research R3에 기록
- 추가한 저장소 메서드(목록 밖이지만 기존 메서드 무변경 · 추가만): `ApprovalRepository.findByIncidentIdAndStatus`, `ReportRepository.findFirstByIncidentIdAndStatus`
- 동시 확정: 사건 행을 `refresh(PESSIMISTIC_WRITE)`로 잠근 뒤 보고서를 다시 읽는다 — 10건 동시 요청에서 200 1건 · 409 9건(`REPORT_CONFIRMED`). 부분 유니크 인덱스 위반은 전역 처리기의 409 `DUPLICATE_OR_CONFLICT`로 둔다(잠금이 먼저 막으므로 따로 바꾸지 않음)
- 요청 철회 권한: 요청자 본인, 아니면 요청을 보낸 공정의 사건 운영 권한(`requireInvestigationScope`). 받는 사람(다른 공정)은 403. `allowedActions`(받는 사람 동작)는 바꾸지 않았다 — 화면은 요청자 · 상태로 철회 버튼을 판단
- 업무 미수행 닫기 권한: 담당자 본인, 아니면 사건 운영 권한자(`loadForWrite`)
- `ConfirmReportHandler`(P4)는 US1에서 먼저 바꿈 — 승인 ID · 기다리던 작업 · 재개 작업을 제외 대상으로 넘김

- 목록 밖 추가 수정(동의 2026-10-23): **P22** `internal/job/AgentJobService.jobContext` — SUMMARY 작업 메시지에 `input.summaryRef`만 싣는다(팀 계약 job-message 필수 필드)
- P13 범위 안 추가: `schema.sql`에서 알림 종류 체크 제약(`user_notification_type_check`)도 새 값(REPORT_SUMMARY_READY · FAILED)으로 다시 만든다
- P5 범위 안 추가: `IncidentController` 전이 API의 문서 문구(summary · description)를 새 규칙으로
- T037: `Incident`에 이미 공개 `setStatus`가 있어 기존 메서드는 바꾸지 않았다. 시드는 `restoreLegacyStatus`로 옛 상태 · 이력 문구를 직접 넣는다
- 요약 대조: 다른 보고서 · 다른 확정본의 결과면 그 작업을 `STALE_RESULT`로 닫고, 그 작업이 현재 요약 작업이었으면 요약도 실패(다시 요청 가능)로 둔다. 예전 작업의 결과는 공통 앞단(끝난 작업 무시) 또는 `accepts` 대조로 버린다
- 시뮬레이터 요약 실패 주입: 요약 작업 질문 = 보고서 제목이라, 제목에 `[fail]`이 있으면 기존 `shouldFail` 규칙으로 실패한다(테스트용)
- 외부 Agent 경로는 `ReportAiSummaryExternalAgentApiTest`(외부 모드 · 자기 스키마 backend_ext)로 작업 메시지 · 확정본 조회 · SUMMARY_READY 대조를 결정적으로 시험

## 마무리 검증 (2026-10-23)

| 항목 | 결과 |
|------|------|
| backend `./gradlew test --rerun-tasks` | **246 tests · 2 skipped · 0 failed** (기준선 225 + 새 21) |
| frontend `npm test && npm run build` | **9 files · 50 passed**, build(tsc 포함) 성공 (기준선 47 + 새 3) |
| ai `uv run pytest -q` (회귀, 코드 변경 없음) | 133 passed · 3 skipped — 기준선과 같음 |
| 스모크(새로 시드한 서버, 임시 DB) | **108 passed · 5 failed** — 실패 5건은 기준선과 같은 main 기존 실패(요청 수락 단계 4 · LOT 개수 1). 새 검사(확정 차단 → 철회 → 확정 = 종료 · 요약 READY(rule) · 관리자 보류/종료 409) 모두 통과 |
| `openapi_diff` | 이번 기능 차이(새 operation 3 · 작업 종류 SUMMARY · 보고서 summary · 업무 상태 · 철회 필드) 계약 반영 후 **새 차이 0건**. 남은 행은 변경 전에도 있던 종류(요청 상태 enum ACCEPTED/REJECTED 미반영 · 업무 응답 서버 미정의 · 알림 등 main 미반영 operation) |
| `java_pkgdeps` | 순환 묶음 변경 없음(기존 `[action, analysis, approval, checkrequest, job, notification, work]`). 새 의존: internal → domain.report(허용 방향) |

- **사고 기록**: 검증 서버를 기존 임시 컨테이너(`fab-verify-pg`)의 새 스키마로 띄우려 했으나 `application.yml`이 `hibernate.default_schema: backend`로 고정돼 있어 8090 개발 서버가 쓰던 `backend` 스키마를 `DDL_AUTO=create`로 다시 만들었다(데이터 삭제). `fab-deploy-*`는 영향 없음. 검증은 별도 임시 컨테이너(`fab-verify003-pg`, 검증 뒤 삭제)로 다시 했고, 8090 서버는 새 코드로 재시작 · 재시드했다
- T044: quickstart 시나리오 1–7은 API 테스트(ReportConfirmClose · Blockers · AiSummary · External · IncidentApiTest)와 스모크로 확인. **T051(화면 확인)은 사용자 확인 필요**

## 전달 메모

- **재현(화면)**: 확정 409 `CONFIRMATION_BLOCKED`의 `details.blockers[{kind,id,label}]`로 「확정 전 정리 목록」, `ReportView.summary`(status · lines · evidenceIds · generatedBy · error)와 `POST /api/reports/{id}/summary-jobs`(FAILED에서만), 요청 철회 `POST /api/check-requests/{id}/withdrawals`, 업무 `POST /api/work-items/{id}/unperformed-closures`, 관리자 전이는 보류 해제만. 이번에 Reports · IncidentDetail · Requests · WorkItems에 기본 화면을 넣었다 — 디자인 다듬기는 재현 님 판단
- **아름(리뷰)**: `JobKind.affectsInvestigationState` · `JobService`(SUMMARY 격리, `finishWaitingOnIncidentClosed`) · `AgentEventService`(SUMMARY_READY · SUMMARY JOB_FAILED) · `AgentWorker`(규칙 요약 · 실패 시 이력 없음) · `NotificationEvents.reportSummaryFinished` · `AgentJobService.jobContext`(SUMMARY input)
- **의진 · 계약 주인**: 공개 계약 변경 기록 초안(plan contracts/public-api.md 「변경 기록 초안」) — `ai/docs/contract-changes.md` 반영은 `ai/` 미수정 원칙에 따라 계약 주인에게 넘긴다. 내부 확정본 조회 `GET /internal/reports/{id}/snapshot` 필드는 합의안 그대로

<!-- ARTIFACT:003-report-confirm-summary:implementation-notes.md END -->

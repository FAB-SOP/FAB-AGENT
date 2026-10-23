---
name: "speckit-specify"
description: "Create or update the feature specification from a natural language feature description."
argument-hint: "Describe the feature you want to specify"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/specify.md"
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

**Check for extension hooks (before specification)**:
- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_specify` key
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

The text the user typed after `/speckit-specify` in the triggering message **is** the feature description. Assume you always have it available in this conversation even if `$ARGUMENTS` appears literally below. Do not ask the user to repeat it unless they provided an empty command.

Given that feature description, do this:

1. **Generate a concise short name** (2-4 words) for the feature:
   - Analyze the feature description and extract the most meaningful keywords
   - Create a 2-4 word short name that captures the essence of the feature
   - Use action-noun format when possible (e.g., "add-user-auth", "fix-payment-bug")
   - Preserve technical terms and acronyms (OAuth2, API, JWT, etc.)
   - Keep it concise but descriptive enough to understand the feature at a glance
   - Examples:
     - "I want to add user authentication" → "user-auth"
     - "Implement OAuth2 integration for the API" → "oauth2-api-integration"
     - "Create a dashboard for analytics" → "analytics-dashboard"
     - "Fix payment processing timeout bug" → "fix-payment-timeout"

2. **Branch creation** (optional, via hook):

   If a `before_specify` hook ran successfully in the Pre-Execution Checks above, it will have created/switched to a git branch and output JSON containing `BRANCH_NAME` and `FEATURE_NUM`. Note these values for reference, but the branch name does **not** dictate the spec directory name.

   If the user explicitly provided `GIT_BRANCH_NAME`, pass it through to the hook so the branch script uses the exact value as the branch name (bypassing all prefix/suffix generation).

3. **Create the spec block** (프로젝트 규칙 — spec은 이 SKILL.md 끝 「Feature Artifacts」 섹션의 블록이다):

   Run `.specify/scripts/bash/create-new-feature.sh --json --short-name "<short-name>" "<feature description>"`
   from repo root (add `--timestamp` when `.specify/init-options.json` has `feature_numbering: "timestamp"`;
   add `--number N` only if the user asked for a number). The script:
   - picks the next feature name `<prefix>-<short-name>` (e.g., `003-user-auth`) by scanning existing
     `<!-- ARTIFACT:<기능>:spec.md BEGIN -->` markers in `.claude/skills/speckit-specify/SKILL.md`
   - appends a block `<!-- ARTIFACT:<기능>:spec.md BEGIN -->` … `END -->` containing the resolved `spec-template`
   - persists the feature name to `.specify/feature.json` as `{"feature_directory": "<기능>"}`
   - prints JSON with `BRANCH_NAME` (= feature name) and `SPEC_FILE` (= block reference
     `<SKILL.md 절대 경로>#<기능>:spec.md`)

   Set `SPECIFY_FEATURE_DIRECTORY` to the feature name and `SPEC_FILE` to the block reference.
   If the user explicitly provided `SPECIFY_FEATURE_DIRECTORY`, use its last path segment as the feature name and
   pass it via `--short-name`/`--number` so the block gets that name.
   If `branch_numbering` was used in init-options.json (and `feature_numbering` was absent), emit a one-line
   warning: "⚠️ `branch_numbering` in init-options.json is deprecated. Rename to `feature_numbering`."

   **IMPORTANT**:
   - You must only create one feature per `/speckit-specify` invocation
   - The feature name and the git branch name are independent — they may be the same but that is the user's choice
   - The spec block is always created by this command, never by the hook

4. Load the resolved active `spec-template` file to understand required sections.

5. **IF EXISTS**: Load `.claude/skills/speckit-constitution/SKILL.md`의 「Project Constitution」 섹션 for project principles and governance constraints.

6. Follow this execution flow:
    1. Parse user description from arguments
       If empty: ERROR "No feature description provided"
    2. Extract key concepts from description
       Identify: actors, actions, data, constraints
    3. For unclear aspects:
       - Make informed guesses based on context and industry standards
       - Only mark with [NEEDS CLARIFICATION: specific question] if:
         - The choice significantly impacts feature scope or user experience
         - Multiple reasonable interpretations exist with different implications
         - No reasonable default exists
       - **LIMIT: Maximum 3 [NEEDS CLARIFICATION] markers total**
       - Prioritize clarifications by impact: scope > security/privacy > user experience > technical details
    4. Fill User Scenarios & Testing section
       If no clear user flow: ERROR "Cannot determine user scenarios"
    5. Generate Functional Requirements
       Each requirement must be testable
       Use reasonable defaults for unspecified details (document assumptions in Assumptions section)
    6. Define Success Criteria
       Create measurable, technology-agnostic outcomes
       Include both quantitative metrics (time, performance, volume) and qualitative measures (user satisfaction, task completion)
       Each criterion must be verifiable without implementation details
    7. Identify Key Entities (if data involved)
    8. Return: SUCCESS (spec ready for planning)

6. Write the specification into the SPEC_FILE block (between its BEGIN/END markers only) using the template structure, replacing placeholders with concrete details derived from the feature description (arguments) while preserving section order and headings.

7. **Specification Quality Validation**: After writing the initial spec, validate it against quality criteria:

   a. **Create Spec Quality Checklist**: Generate a checklist block `<!-- ARTIFACT:<기능>:checklists/requirements.md BEGIN/END -->` in this SKILL.md (`.claude/skills/speckit-specify/SKILL.md`, 「Feature Artifacts」 섹션) using the checklist template structure with these validation items:

      ```markdown
      # Specification Quality Checklist: [FEATURE NAME]

      **Purpose**: Validate specification completeness and quality before proceeding to planning
      **Created**: [DATE]
      **Feature**: [Link to spec.md]

      ## Content Quality

      - [ ] No implementation details (languages, frameworks, APIs)
      - [ ] Focused on user value and business needs
      - [ ] Written for non-technical stakeholders
      - [ ] All mandatory sections completed

      ## Requirement Completeness

      - [ ] No [NEEDS CLARIFICATION] markers remain
      - [ ] Requirements are testable and unambiguous
      - [ ] Success criteria are measurable
      - [ ] Success criteria are technology-agnostic (no implementation details)
      - [ ] All acceptance scenarios are defined
      - [ ] Edge cases are identified
      - [ ] Scope is clearly bounded
      - [ ] Dependencies and assumptions identified

      ## Feature Readiness

      - [ ] All functional requirements have clear acceptance criteria
      - [ ] User scenarios cover primary flows
      - [ ] Feature meets measurable outcomes defined in Success Criteria
      - [ ] No implementation details leak into specification

      ## Notes

      - Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
      ```

   b. **Run Validation Check**: Review the spec against each checklist item:
      - For each item, determine if it passes or fails
      - Document specific issues found (quote relevant spec sections)

   c. **Handle Validation Results**:

      - **If all items pass**: Mark checklist complete and proceed to the Mandatory Post-Execution Hooks section

      - **If items fail (excluding [NEEDS CLARIFICATION])**:
        1. List the failing items and specific issues
        2. Update the spec to address each issue
        3. Re-run validation until all items pass (max 3 iterations)
        4. If still failing after 3 iterations, document remaining issues in checklist notes and warn user

      - **If [NEEDS CLARIFICATION] markers remain**:
        1. Extract all [NEEDS CLARIFICATION: ...] markers from the spec
        2. **LIMIT CHECK**: If more than 3 markers exist, keep only the 3 most critical (by scope/security/UX impact) and make informed guesses for the rest
        3. For each clarification needed (max 3), present options to user in this format:

           ```markdown
           ## Question [N]: [Topic]

           **Context**: [Quote relevant spec section]

           **What we need to know**: [Specific question from NEEDS CLARIFICATION marker]

           **Suggested Answers**:

           | Option | Answer | Implications |
           |--------|--------|--------------|
           | A      | [First suggested answer] | [What this means for the feature] |
           | B      | [Second suggested answer] | [What this means for the feature] |
           | C      | [Third suggested answer] | [What this means for the feature] |
           | Custom | Provide your own answer | [Explain how to provide custom input] |

           **Your choice**: _[Wait for user response]_
           ```

        4. **CRITICAL - Table Formatting**: Ensure markdown tables are properly formatted:
           - Use consistent spacing with pipes aligned
           - Each cell should have spaces around content: `| Content |` not `|Content|`
           - Header separator must have at least 3 dashes: `|--------|`
           - Test that the table renders correctly in markdown preview
        5. Number questions sequentially (Q1, Q2, Q3 - max 3 total)
        6. Present all questions together before waiting for responses
        7. Wait for user to respond with their choices for all questions (e.g., "Q1: A, Q2: Custom - [details], Q3: B")
        8. Update the spec by replacing each [NEEDS CLARIFICATION] marker with the user's selected or provided answer
        9. Re-run validation after all clarifications are resolved

   d. **Update Checklist**: After each validation iteration, update the checklist file with current pass/fail status

## Mandatory Post-Execution Hooks

**You MUST complete this section before reporting completion to the user.**

Check if `.specify/extensions.yml` exists in the project root.
- If it does not exist, or no hooks are registered under `hooks.after_specify`, skip to the Completion Report.
- If it exists, read it and look for entries under the `hooks.after_specify` key.
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

Report completion to the user with:
- `SPECIFY_FEATURE_DIRECTORY` — the feature name (e.g., `003-user-auth`)
- `SPEC_FILE` — the spec block reference (`<SKILL.md>#<기능>:spec.md`)
- Checklist results summary
- Readiness for the next phase (`/speckit-clarify` or `/speckit-plan`)

**NOTE:** Branch creation is handled by the `before_specify` hook (git extension). Spec block creation (in this SKILL.md) is always handled by this core command.

## Quick Guidelines

- Focus on **WHAT** users need and **WHY**.
- Avoid HOW to implement (no tech stack, APIs, code structure).
- Written for business stakeholders, not developers.
- DO NOT create any checklists that are embedded in the spec. That will be a separate command.

### Section Requirements

- **Mandatory sections**: Must be completed for every feature
- **Optional sections**: Include only when relevant to the feature
- When a section doesn't apply, remove it entirely (don't leave as "N/A")

### For AI Generation

When creating this spec from a user prompt:

1. **Make informed guesses**: Use context, industry standards, and common patterns to fill gaps
2. **Document assumptions**: Record reasonable defaults in the Assumptions section
3. **Limit clarifications**: Maximum 3 [NEEDS CLARIFICATION] markers - use only for critical decisions that:
   - Significantly impact feature scope or user experience
   - Have multiple reasonable interpretations with different implications
   - Lack any reasonable default
4. **Prioritize clarifications**: scope > security/privacy > user experience > technical details
5. **Think like a tester**: Every vague requirement should fail the "testable and unambiguous" checklist item
6. **Common areas needing clarification** (only if no reasonable default exists):
   - Feature scope and boundaries (include/exclude specific use cases)
   - User types and permissions (if multiple conflicting interpretations possible)
   - Security/compliance requirements (when legally/financially significant)

**Examples of reasonable defaults** (don't ask about these):

- Data retention: Industry-standard practices for the domain
- Performance targets: Standard web/mobile app expectations unless specified
- Error handling: User-friendly messages with appropriate fallbacks
- Authentication method: Standard session-based or OAuth2 for web apps
- Integration patterns: Use project-appropriate patterns (REST/GraphQL for web services, function calls for libraries, CLI args for tools, etc.)

### Success Criteria Guidelines

Success criteria must be:

1. **Measurable**: Include specific metrics (time, percentage, count, rate)
2. **Technology-agnostic**: No mention of frameworks, languages, databases, or tools
3. **User-focused**: Describe outcomes from user/business perspective, not system internals
4. **Verifiable**: Can be tested/validated without knowing implementation details

**Good examples**:

- "Users can complete checkout in under 3 minutes"
- "System supports 10,000 concurrent users"
- "95% of searches return results in under 1 second"
- "Task completion rate improves by 40%"

**Bad examples** (implementation-focused):

- "API response time is under 200ms" (too technical, use "Users see results instantly")
- "Database can handle 1000 TPS" (implementation detail, use user-facing metric)
- "React components render efficiently" (framework-specific)
- "Redis cache hit rate above 80%" (technology-specific)

## Done When

- [ ] Specification written to `SPEC_FILE` and validated against quality checklist
- [ ] Extension hooks dispatched or skipped according to the rules in Mandatory Post-Execution Hooks above
- [ ] Completion reported to user with feature name, spec block reference, and checklist results

---

## Feature Artifacts

speckit 명령이 만든 산출물이다. 블록은 `<!-- ARTIFACT:<기능>:<문서> BEGIN/END -->` 표식으로 구분한다.

<!-- ARTIFACT:001-report-4m-classification:spec.md BEGIN -->
# Feature Specification: 조사 보고서 4M 원인 분류

**Feature**: `001-report-4m-classification`
**Created**: 2026-10-22
**Status**: Draft
**Input**: 사용자 설명: "이상조사와 보고서 작성 기능의 실행 순서를 파악하고, 보고서 초안의 「검토 후 확정」 단계에
4M(Material · Man · Method · Machine) 분류 드롭다운을 추가한다. Machine이면 공정(CMP · Etch · CVD)별 장비
파라미터를 고를 수 있고, 목록은 공정 대시보드 「센서 시계열」의 센서 목록을 참조한다. 장비 엔지니어(PM 진행)와
공정 관리자가 각자의 시각으로 보고서를 분류해 볼 수 있게 하는 것이 목적이다."

## 현재 실행 순서 (As-Is, 조사 결과)

이 기능은 아래 흐름의 마지막 단계(⑧ 확정)에 분류를 덧붙인다. ①–⑦의 동작은 바꾸지 않는다.

1. **이상 감지 · 사건 생성** — 공정 데이터에서 이상이 감지되면 사건(공정 · 설비 · 챔버 · 로트 · 웨이퍼 · 관련 센서)이 만들어진다.
2. **조사 질문** — 공정 담당자가 사건 화면에서 Agent에게 질문하면 조사 작업이 시작된다.
3. **Agent 조사** — Agent가 서버가 계산한 공정 근거(센서 시계열 · 계측 · 문서)를 조회해 관측 사실 · 원인 후보 · 한계를 정리한다.
4. **요청 제안 · 승인** — Agent는 다른 공정 담당자 · 설비정비팀에게 보낼 확인 요청을 제안만 하고, 사람이 승인한 뒤에만 실행된다.
5. **회신 · 조치 기록** — 받는 사람이 회신하고, 조치와 확인 결과가 사건 이력에 쌓인다.
6. **보고서 초안 생성 요청** — 사건 화면 「보고서」에서 「초안 생성 요청」을 누르면 보고서 작업이 대기열에 들어간다(사건 발생 시 자동 생성 없음, 동시에 하나만).
7. **초안 작성 · 편집** — 작업이 사건 기록을 모아 새 판본의 초안(개요 · 조사 내용 · 요청과 회신 · 조치와 검증 · 미해결 사항 다섯 섹션)을 만든다. 담당자는 「조사 보고서 검토」 화면에서 제목 · 섹션을 고치고 저장한다(수정 판본 번호로 충돌을 막는다).
8. **검토 후 확정** — 「보고서 내용을 검토했습니다」를 체크하고 「검토한 버전 확정」을 누르면 보고서가 확정된다. 확정 시점 내용이 스냅샷으로 보존되고 이후 내려받기는 스냅샷을 준다. 확정은 Agent 제안 → 승인 경로로도 실행될 수 있다.

## Clarifications

### Session 2026-10-22

- Q: 보고서 하나에 4M 분류를 몇 개까지 지정하나? → A: 주 분류 하나만 선택한다. 장비 파라미터만 여러 개 선택할 수 있다.
- Q: 타사 장비 엔지니어는 보고서를 어떻게 보나? → A: 새 역할 없이 MAINTENANCE(설비정비팀) 계정으로 기존 권한 안에서 본다. 외부 업체에는 사람이 기존 Markdown 내려받기로 확정본을 받아 직접 전달하며, 별도 공유 · PDF 기능은 만들지 않는다.
- Q: 분류는 언제, 누가 정하나? → A: 보고서를 확정하는 시점에 사람이 정한다. 직접 확정은 「검토 후 확정」에서, Agent가 제안한 보고서 확정은 승인자가 승인할 때 같은 드롭다운으로 정한다. 초안 단계에는 분류를 저장하지 않는다.
- Q: 주기별 보고서 조회를 범위에 넣나? → A: 넣는다. 보고서 목록에 「이상 발생 기간」(시작일~종료일) 필터를 추가하며, 기준은 사건(이상)이 감지된 날이다. 분류 필터와 함께 쓴다.
- Q: 4M 중 어디에도 확정할 수 없으면? → A: 다섯 번째 선택지 「보류 (원인 미확정)」을 고를 수 있다. 장비 파라미터는 고르지 않으며, 사건 상태 「보류」와는 별개다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 확정할 때 4M 원인 분류를 지정한다 (Priority: P1)

공정 담당자가 보고서 초안을 검토한 뒤 확정하기 전에, 이 사건의 이상 원인이 4M 중 어디에 해당하는지
(Material · Man · Method · Machine) 드롭다운에서 고른다. 조사 결과로 4M 중 하나로 확정할 수 없으면
「보류 (원인 미확정)」을 고른다. 분류는 확정된 보고서와 함께 보존된다.

**Why this priority**: 분류가 기록되지 않으면 이후 시각별 조회가 불가능하다. 이 기능의 최소 가치 단위다.

**Independent Test**: 초안 하나를 열어 분류를 Method로 고르고 확정한 뒤, 보고서를 다시 열었을 때 분류가 Method로
보이고 내려받은 보고서에도 분류가 적혀 있으면 통과.

**Acceptance Scenarios**:

1. **Given** 검토 가능한 초안, **When** 담당자가 검토 체크를 하고 원인 분류를 고르지 않은 채 확정하려 하면, **Then** 확정 버튼이 비활성이고 "원인 분류를 선택해 주세요" 안내가 보인다.
2. **Given** 검토 체크와 분류(Material · Man · Method 중 하나)를 마친 초안, **When** 확정하면, **Then** 보고서가 확정되고 보고서 화면 · 목록 · 내려받은 문서에 선택한 분류가 표시된다.
3. **Given** 확정된 보고서, **When** 누구든 다시 열면, **Then** 분류는 읽기 전용으로 보이고 바꿀 수 없다.
4. **Given** 원인을 4M 중 하나로 정할 수 없는 초안, **When** 담당자가 「보류 (원인 미확정)」을 고르고 확정하면, **Then** 장비 파라미터 선택 없이 확정되고 보고서 화면 · 목록 · 내려받은 문서에 「보류 (원인 미확정)」으로 표시된다.

---

### User Story 2 - Machine이면 공정별 장비 파라미터를 고른다 (Priority: P1)

원인이 설비 쪽이라고 판단되면 담당자는 Machine을 고르고, 이어서 나타나는 두 번째 드롭다운에서 관련 장비
파라미터를 고른다. 선택지는 그 사건 공정의 「센서 시계열」에서 볼 수 있는 센서 목록과 같다
(예: CMP는 헤드 압력 · 플래튼 회전속도 · 슬러리 유량 등, Etch는 소스 · 바이어스 RF · CF₄ 유량 · 척 온도 등,
CVD는 챔버 압력 · 히터 온도 · SiH₄ 공급 유량 · RF 전력 등).

**Why this priority**: 타사 장비 엔지니어가 PM 때 볼 보고서를 고르려면 "어느 장비 파라미터 문제인가"까지 알아야 한다.

**Independent Test**: CMP 사건 보고서에서 Machine을 고르면 CMP 센서만 선택지로 나오고, Etch · CVD 센서는 나오지 않으면 통과.

**Acceptance Scenarios**:

1. **Given** CVD 사건의 초안, **When** 분류를 Machine으로 고르면, **Then** CVD 공정 센서 목록이 사람이 읽는 이름(단위 포함)으로 나타난다.
2. **Given** 분류가 Machine이고 파라미터를 하나도 고르지 않은 상태, **When** 확정하려 하면, **Then** 확정할 수 없고 "장비 파라미터를 하나 이상 선택해 주세요" 안내가 보인다.
3. **Given** Machine과 파라미터를 고른 상태, **When** 분류를 Method로 바꾸면, **Then** 파라미터 드롭다운이 사라지고 고른 파라미터는 확정 내용에 들어가지 않는다.
4. **Given** 사건에 관련 센서가 지정되어 있음, **When** 파라미터 목록을 열면, **Then** 그 센서들이 목록 위쪽에 「사건 관련」으로 표시된다(선택은 담당자가 한다).

**Acceptance Scenarios (Agent 제안 경로)**:

5. **Given** Agent가 제안한 「보고서 확정」 승인 대기 건, **When** 승인자가 승인 화면을 열면, **Then** 확정 경로와 같은 원인 분류 드롭다운(4M · 보류, Machine이면 파라미터 선택 포함)이 보이고, 분류를 고르기 전에는 승인할 수 없다.
6. **Given** 승인자가 분류를 Machine · 헤드 압력으로 고르고 승인, **When** 확정이 실행되면, **Then** 확정된 보고서의 분류는 승인자가 고른 Machine · 헤드 압력이며, 승인 기록에도 같은 분류와 승인자가 남는다.
7. **Given** 같은 승인 대기 건, **When** 승인자가 거절하면, **Then** 분류 입력 없이 거절되고 보고서는 초안으로 남는다.

---

### User Story 3 - 분류 · 이상 발생 기간으로 보고서를 골라 본다 (Priority: P2)

장비 엔지니어는 Machine · 특정 파라미터로, 공정 관리자는 Material · Man · Method로 보고서 목록을 걸러서
자신의 시각에 맞는 보고서만 본다. 「이상 발생 기간」을 함께 지정해 PM 주기 같은 기간 단위로 모아 본다.

**Why this priority**: 분류를 남기는 이유가 "시각별 조회"이므로 목록 필터가 있어야 가치가 완성된다. 다만 P1만으로도 분류 기록은 쌓인다.

**Independent Test**: 분류가 서로 다른 확정 보고서 3건이 있을 때 목록에서 Machine을 고르면 Machine 보고서만, 파라미터까지 고르면 그 파라미터가 포함된 보고서만 남으면 통과.

**Acceptance Scenarios**:

1. **Given** 여러 분류의 확정 보고서, **When** 목록에서 분류 필터를 Machine으로 고르면, **Then** Machine 보고서만 보인다.
2. **Given** 분류 필터 Machine, **When** 장비 파라미터 필터를 고르면, **Then** 그 파라미터를 포함한 보고서만 보인다.
3. **Given** 분류가 없는 이전 확정 보고서, **When** 필터를 「분류 없음」으로 고르면, **Then** 그 보고서들이 보이며, 「전체」에서는 모든 보고서가 기존처럼 보인다.
4. **Given** 10월 1일 · 10월 15일 · 11월 2일에 감지된 사건의 Machine 보고서, **When** 분류 Machine과 이상 발생 기간 10월 1일~10월 31일을 함께 지정하면, **Then** 10월 1일 · 10월 15일 사건의 보고서만 보인다(보고서 확정일과 무관).
5. **Given** 이상 발생 기간을 비워 둠, **When** 목록을 보면, **Then** 기간 제한 없이 기존처럼 모든 기간의 보고서가 보인다.

---

### Edge Cases

- 기능 도입 전에 확정된 보고서는 분류가 없다 — 「분류 없음」으로 표시하고 오류 없이 열린다. 확정본은 고칠 수 없으므로 소급 분류하지 않는다.
- 센서 목록을 불러오지 못하면 Machine 파라미터를 고를 수 없으므로, 안내를 보이고 Machine 확정만 막는다(Material · Man · Method 확정은 가능).
- 담당자가 분류를 고른 뒤 다른 사람이 초안을 수정해 판본이 바뀌면 기존처럼 확정이 거부되고, 새로 불러온 화면에서 분류를 다시 고른다.
- Agent 제안 → 승인 경로로 확정할 때는 Agent가 분류를 정하지 않는다 — 승인자가 승인 시점에 고른 분류가 승인 기록에 저장되고, 확정 실행은 그 저장된 분류만 쓴다(헌법 원칙 I · II).
- 승인 뒤 실행 전에 초안이 수정되어 판본이 바뀌면 기존처럼 실행이 거부된다.
- 장비 파라미터는 그 사건 공정의 센서여야 한다 — 다른 공정 센서나 목록에 없는 값이 들어오면 서버가 거부한다.
- 같은 확정 요청이 두 번 들어와도 한 번만 확정된다(기존 멱등성 유지).
- ~~「보류 (원인 미확정)」으로 확정한 뒤 새 판본으로 재분류~~ — **삭제(003 · 헌법 2.0.0)**: 확정은 사건당 한 번이고 확정이 사건을 닫는다. 늦게 밝혀진 원인은 사건 기록에 후속 확인으로 남긴다.
- 「보류 (원인 미확정)」은 사건 상태 「보류」(MANAGER의 상태 전이)와 별개다 — 분류로 보류를 골라도 사건 상태는 바뀌지 않고, 사건이 보류 상태여도 분류 선택지는 같다.
- 이상 발생 기간의 시작일이 종료일보다 늦으면 안내를 보이고 필터를 적용하지 않는다. 시작일 · 종료일 중 하나만 지정하면 그쪽 경계만 적용한다. 날짜는 화면 표시와 같은 한국 시간 기준이며 종료일 당일을 포함한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 「검토 후 확정」 영역에는 기존 검토 체크와 함께 원인 분류 드롭다운이 있어야 한다. 선택지는 4M(Material · Man · Method · Machine)과 「보류 (원인 미확정)」 다섯 가지이며, 보고서 판본당 **하나만** 고를 수 있어야 한다(복수 선택 없음). 보조 원인은 본문 섹션에 적는다.
- **FR-001a**: 「보류 (원인 미확정)」은 조사 결과로 원인을 4M 중 하나로 확정할 수 없을 때 고르는 값이며, 장비 파라미터를 갖지 않는다. 사건 상태(보류 · 종료)를 바꾸지 않는다.
- **FR-002**: 분류가 Machine이면 장비 파라미터 선택 드롭다운이 나타나야 하며, 하나 이상 고를 수 있어야 한다.
- **FR-003**: 장비 파라미터 선택지는 해당 사건 공정의 센서 목록이며, 공정 대시보드 「센서 시계열」이 보여 주는 센서 목록과 같은 출처 · 같은 표시 이름을 써야 한다.
- **FR-004**: 사건에 관련 센서가 지정되어 있으면 파라미터 목록에서 구분해 먼저 보여야 한다. 자동 선택은 하지 않는다.
- **FR-005**: 검토 체크와 분류(Machine이면 파라미터 1개 이상까지)가 모두 갖춰져야 확정할 수 있어야 한다. 화면과 서버 모두 검사한다.
- **FR-006**: 서버는 분류 값이 다섯 가지(4M · 보류) 중 하나인지, 장비 파라미터가 그 사건 공정의 센서인지 검증하고, 아니면 확정을 거부하며 이유를 알려야 한다.
- **FR-007**: 확정된 보고서는 분류 · 장비 파라미터 · 분류한 사람 · 시각을 함께 보존해야 하며, 확정 후에는 바꿀 수 없어야 한다.
- **FR-008**: 보고서 화면 · 보고서 목록 · 내려받은 보고서(확정 스냅샷)에 분류와 장비 파라미터가 표시되어야 한다.
- **FR-009**: 보고서 목록은 분류(전체 · Material · Man · Method · Machine · 보류 (원인 미확정) · 분류 없음)와, Machine일 때 장비 파라미터로 거를 수 있어야 한다.
- **FR-009a**: 보고서 목록은 「이상 발생 기간」(시작일~종료일, 종료일 포함)으로 거를 수 있어야 하며, 기준은 보고서가 속한 사건의 이상 감지일이다. 분류 · 파라미터 필터와 동시에 적용되고, 비워 두면 기간 제한이 없다. 목록 각 행에 이상 발생일이 보여야 한다.
- **FR-010**: Agent가 제안한 보고서 확정을 **승인할 때** 승인자는 원인 분류(4M 또는 보류, Machine이면 장비 파라미터 1개 이상)를 함께 골라야 하며, 고르지 않으면 승인할 수 없다. 거절에는 분류가 필요 없다.
- **FR-010a**: 승인자가 고른 분류는 승인 기록(승인자 · 시각)과 함께 저장되고, 확정 실행은 저장된 분류만 사용한다. Agent는 분류를 정하거나 제안하지 않으며, Agent가 제안한 내용(보고서 · 판본)의 기존 검증은 그대로 유지한다.
- **FR-011**: 분류 기능 도입 전 확정 보고서와 기존 화면 · 내려받기 · 초안 생성 · 편집 동작은 그대로 동작해야 한다(헌법 원칙 X).
- **FR-012**: 사건 이력의 보고서 확정 기록에 분류가 함께 남아야 한다.
- **FR-013**: 분류 선택 · 확정 권한은 기존 보고서 확정 권한과 같다(사건 운영 권한이 있는 사용자만). 보고서 열람 범위는 바꾸지 않으며, 타사 장비 엔지니어는 MAINTENANCE(설비정비팀) 계정의 기존 권한으로 본다. 새 역할 · 계정을 만들지 않는다.
- **FR-014**: 외부 업체 전달은 기존 Markdown 내려받기로 사람이 직접 한다. 외부 공유 링크 · 발송 · PDF 변환 기능은 만들지 않는다.

### Key Entities

- **원인 분류(4M Classification)**: 보고서 판본 하나에 붙는 분류. 범주 하나(Material · Man · Method · Machine · 보류 (원인 미확정) 중 택1), 장비 파라미터 목록(Machine일 때만), 분류한 사람, 분류 시각. 확정과 함께 고정된다.
- **장비 파라미터(Equipment Parameter)**: 공정별 센서 목록의 한 항목. 센서 ID, 표시 이름, 단위, 소속 공정. 공정 대시보드 센서 시계열과 같은 목록을 쓴다.
- **보고서(Report)**: 기존 엔티티. 판본 · 상태(초안 · 확정) · 섹션 · 확정 스냅샷에 원인 분류가 더해진다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 기능 도입 후 새로 확정되는 보고서의 100%가 원인 분류(4M 또는 보류)를 가진다.
- **SC-002**: 담당자는 기존 확정 절차 대비 30초 이내의 추가 시간으로 분류 · 파라미터 선택을 마친다.
- **SC-003**: 장비 파라미터 선택지는 세 공정(CMP · Etch · CVD) 모두에서 공정 대시보드 센서 시계열의 센서 목록과 100% 일치한다(누락 · 타 공정 센서 0건).
- **SC-004**: 장비 엔지니어는 보고서 목록에서 두 번 이하의 선택으로 Machine · 특정 파라미터 보고서만 걸러 볼 수 있고, 기간까지 지정해도 1분 안에 원하는 PM 주기의 보고서를 찾는다.
- **SC-005**: 기능 도입 전 보고서 · 기존 확정 · 승인 흐름에서 회귀 오류 0건(기존 검증 · 스모크 전부 통과).

## Assumptions

- 설비정비팀(MAINTENANCE) 계정은 현재 세 공정(CVD · ETCH · CMP) 조회 범위를 가지므로, 새 권한 없이 보고서 목록 · 필터를 쓸 수 있다.
- 주기별 조회는 「이상 발생 기간」 필터로 지원한다. 정기 발송 · 주기 자동 묶음 · 빠른 기간 버튼은 범위 밖이다.
- 4M 라벨은 Material · Man · Method · Machine 영문 표기와 한글 설명(자재 · 사람 · 방법 · 설비)을 함께 보여 주고, 다섯 번째 선택지는 「보류 (원인 미확정)」으로 표시한다.
- 센서 목록은 현재 데이터 팩의 공정별 센서 정의를 그대로 쓴다(새 센서를 만들지 않는다).

## Dependencies

- 공정별 센서 정의(공정 대시보드 「센서 시계열」이 쓰는 목록)
- 기존 보고서 확정 · 승인(CONFIRM_REPORT) · 내려받기 · 사건 이력 기능
- 공개 API 계약 변경은 계약 변경 기록과 계약 주인 확인이 필요하다(헌법 원칙 IV).
<!-- ARTIFACT:001-report-4m-classification:spec.md END -->

<!-- ARTIFACT:001-report-4m-classification:checklists/requirements.md BEGIN -->
# Specification Quality Checklist: 조사 보고서 4M 원인 분류

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-22
**Feature**: `001-report-4m-classification:spec.md` (이 SKILL.md의 spec 블록)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 1차 검증에서 FR-010의 "해시" 표현을 구현 세부로 보고 "승인 대상 내용"으로 고쳤다. 2차 검증 전 항목 통과.
- 「현재 실행 순서 (As-Is)」 섹션은 사용자가 요청한 실행 순서 파악 결과로, 템플릿 외 추가 섹션이다.
- 판단으로 정한 기본값은 Assumptions에 적었다. 바꾸려면 `/speckit-clarify`에서 다룬다.
<!-- ARTIFACT:001-report-4m-classification:checklists/requirements.md END -->

<!-- ARTIFACT:002-report-title-search:spec.md BEGIN -->
# Feature Specification: 조사 보고서 제목 검색 · 서버 목록 조회

**Feature**: `002-report-title-search`
**Created**: 2026-10-22
**Status**: Draft
**Input**: 사용자 설명: "조사 보고서 목록의 필터(원인 분류 · 이상 발생 기간 시작 · 종료) 옆에 검색 입력칸을 추가한다.
검색어는 서버에 보내 저장된 보고서 제목에서 부분 일치로 찾는다(검색 대상은 제목만). 성능을 높이기 위해 조회 계획을
측정하고 이를 줄일 수 있는 색인을 만든다. 측정은 임시 저장소에 대량의 가짜 보고서를 넣어 색인 전후를 비교한다
(시드 · 운영 데이터는 건드리지 않음). 기존 분류 · 기간 필터와 함께 동작해야 한다."

## Clarifications

### Session 2026-10-22

- Q: 검색과 필터를 함께 쓸 때 순서는? → A: 필터는 제목 검색 결과에 대해서만 적용한다(검색어가 비어 있으면 전체에 적용).
- Q: 필터는 어디서 적용하나? → A: 서버에서 한 번에 조회한다. 보고서 목록은 공정 · 검색어 · 원인 분류 · 장비 파라미터 · 이상 발생 기간을 한 번에 받아 조건에 맞는 보고서를 저장소에서 찾아 준다. 사건마다 보고서를 따로 불러오는 지금 방식(사건 최근 100건 한계 포함)을 대신한다.
- Q: 목록은 어떻게 나눠 받나? → A: 페이지 단위(기본 20건)로 받고 「더 보기」로 다음 페이지를 이어 붙인다. 전체 건수는 세지 않고 다음 페이지가 있는지만 알린다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 제목 일부로 보고서를 찾는다 (Priority: P1)

공정 담당자 · 설비정비팀은 조사 보고서 목록에서 기억나는 제목 일부(사건 번호 「EV-1009」, 설비 「CMP-PM3」,
현상 「두께」 등)를 검색칸에 입력해, 제목에 그 글자가 들어 있는 보고서만 본다.

**Why this priority**: 보고서가 쌓이면 분류 · 기간만으로는 원하는 한 건을 찾기 어렵다. 검색이 이 기능의 핵심 가치다.

**Independent Test**: 제목이 서로 다른 보고서들이 있을 때 「PM3」를 입력하면 제목에 「PM3」가 들어간 보고서만 남고,
검색칸을 비우면 다시 전체가 보이면 통과.

**Acceptance Scenarios**:

1. **Given** 여러 보고서가 있는 목록, **When** 검색칸에 「EV-1009」를 입력하면, **Then** 제목에 「EV-1009」가 들어 있는 보고서만 보인다.
2. **Given** 검색 결과가 보이는 상태, **When** 검색칸을 비우면, **Then** 검색 전과 같은 전체 목록이 보인다.
3. **Given** 영문이 섞인 제목, **When** 대소문자를 다르게 입력(「cmp-pm3」)하면, **Then** 「CMP-PM3」 보고서도 찾는다.
4. **Given** 일치하는 제목이 없음, **When** 검색하면, **Then** 「검색 결과가 없습니다」 안내가 보이고 오류가 나지 않는다.
5. **Given** 담당 공정이 CMP인 사용자, **When** 검색하면, **Then** 지금 고른 공정 중 볼 권한이 있는 공정의 보고서만 결과에 나온다.
6. **Given** 조건에 맞는 보고서가 20건보다 많음, **When** 목록을 보면, **Then** 처음 20건이 보이고 「더 보기」를 누르면 다음 20건이 이어 붙는다. 마지막 페이지에서는 「더 보기」가 사라진다.
7. **Given** 사건이 100건보다 많은 공정, **When** 목록을 보면, **Then** 오래된 사건의 보고서도 「더 보기」로 끝까지 볼 수 있다(지금의 100건 한계 해소).

---

### User Story 2 - 검색과 분류 · 기간 필터를 함께 쓴다 (Priority: P1)

검색어와 기존 필터(원인 분류 · 장비 파라미터 · 이상 발생 기간)를 동시에 걸어 범위를 좁힌다.

**Why this priority**: 사용자가 함께 쓰겠다고 명시했고, 따로 동작하면 결과가 엇갈려 신뢰를 잃는다.

**Independent Test**: 「CMP」 검색 + 분류 Machine + 10월 기간을 함께 지정하면 세 조건을 모두 만족하는 보고서만 남으면 통과.

**Acceptance Scenarios**:

1. **Given** 검색어 「CMP」와 분류 Machine, **When** 함께 적용하면, **Then** 제목에 「CMP」가 있으면서 분류가 Machine인 보고서만 보인다.
2. **Given** 검색어와 이상 발생 기간, **When** 함께 적용하면, **Then** 두 조건을 모두 만족하는 보고서만 보인다.
3. **Given** 필터를 바꾼 뒤, **When** 검색어를 그대로 두면, **Then** 검색어가 유지된 채 새 필터가 적용된다.

---

### User Story 3 - 대량 데이터에서도 검색 · 목록이 빠르다 (Priority: P2)

보고서가 대량으로 쌓여도 목록 · 검색 응답이 느려지지 않도록, 조회 계획을 측정해 개선 전후를 기록하고 개선 조치를 적용한다.

**Why this priority**: 지금 데이터는 적어 체감 차이가 없지만, 운영 중 보고서가 누적되면 검색이 가장 먼저 느려진다.

**Independent Test**: 임시 저장소에 보고서 10만 건을 넣고 같은 검색을 개선 전 · 후로 측정해, 개선 후 실행 계획이 전체 탐색을
피하고 시간이 줄었음을 기록으로 보이면 통과.

**Acceptance Scenarios**:

1. **Given** 보고서 10만 건이 든 임시 저장소, **When** 개선 전에 대표 검색어로 조회 계획을 측정하면, **Then** 전체 탐색 방식과 소요 시간이 기록된다.
2. **Given** 같은 저장소에 개선 조치 적용, **When** 같은 검색어로 다시 측정하면, **Then** 전체 탐색을 피하는 계획으로 바뀌고 소요 시간이 줄었음이 기록된다.
3. **Given** 개선 조치, **When** 서버를 여러 번 다시 시작하면, **Then** 조치가 중복 생성되거나 오류를 내지 않는다.

---

### Edge Cases

- 앞뒤 공백만 입력하면 검색하지 않은 것과 같다(전체 목록).
- 「%」 · 「_」 같은 기호는 그 글자 그대로 찾는다(와일드카드로 해석하지 않는다).
- 검색어는 최대 100자 — 넘으면 안내하고 서버도 거부한다.
- 한두 글자 검색도 결과는 정확해야 한다(속도 목표는 세 글자 이상 검색에 적용).
- 입력하는 동안 매 글자마다 요청하지 않는다 — 입력이 잠시 멈추거나 Enter를 누르면 검색한다.
- 검색 중 서버 오류가 나면 안내를 보이고 이전 결과를 지우지 않는다.
- 같은 사건의 여러 판본은 각 판본 제목으로 따로 검색된다(기존 목록과 같은 단위).
- 「더 보기」를 누르기 전에 검색어나 필터를 바꾸면 처음 페이지부터 다시 받는다.
- 다음 페이지를 받는 사이 새 보고서가 생겨도 이미 보인 항목이 중복되거나 순서가 뒤섞이지 않도록 정렬 순서를 고정한다(이상 감지일 최신순, 같으면 보고서 식별자 순).
- 권한 밖 공정을 요청하면 서버가 거부한다(기존 공정 범위 규칙).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 조사 보고서 목록의 필터 영역(원인 분류 · 이상 발생 기간 시작 · 종료 옆)에 검색 입력칸이 있어야 한다.
- **FR-002**: 검색은 서버에 저장된 보고서 **제목**에서 검색어를 부분 일치로 찾아야 하며, 영문 대소문자를 구분하지 않아야 한다. 본문은 검색하지 않는다.
- **FR-003**: 보고서 목록은 서버가 공정 · 검색어 · 원인 분류 · 장비 파라미터 · 이상 발생 기간을 **한 번에** 받아 저장소에서 조회한 결과여야 한다. 화면이 사건마다 보고서를 따로 불러오지 않는다.
- **FR-004**: 검색 결과는 지금 고른 공정 · 사용자의 조회 권한 범위 안의 보고서만 포함해야 한다.
- **FR-005**: 검색어는 앞뒤 공백을 제거하고, 비어 있으면 검색하지 않으며, 최대 100자다. 기호(%, _)는 글자 그대로 일치시킨다.
- **FR-006**: 검색은 입력이 잠시 멈추거나 Enter를 누를 때 실행한다.
- **FR-007**: 검색어가 있으면 원인 분류 · 장비 파라미터 · 이상 발생 기간 조건은 **제목 검색 결과에 대해서만** 적용되며, 최종 결과는 모든 조건을 만족해야 한다. 검색어가 비어 있으면 조건은 공정의 전체 보고서에 적용된다. 조건 의미(분류 없음 · Machine 파라미터 · 기간의 한국 시간 · 종료일 포함 · 한쪽만 지정)는 001에서 정한 화면 필터와 같다.
- **FR-007a**: 목록은 페이지 단위(기본 20건, 최대 100건)로 받고 「더 보기」로 다음 페이지를 이어 붙인다. 응답은 다음 페이지가 있는지만 알리고 전체 건수는 세지 않는다. 정렬은 이상 감지일 최신순 · 보고서 식별자 순으로 고정한다.
- **FR-007b**: 사건 수와 관계없이 조건에 맞는 모든 보고서를 페이지를 넘겨 끝까지 볼 수 있어야 한다.
- **FR-008**: 결과 행은 기존 목록과 같은 정보(제목 · 판본 · 작성일 · 이상 발생일 · 분류 · 상태)를 보여야 한다.
- **FR-009**: 결과가 없으면 「검색 결과가 없습니다」 안내를 보여야 한다.
- **FR-010**: 대량 데이터에서 목록 · 검색 조회 계획을 개선 전 · 후로 측정해 기록하고, 측정 근거로 조회를 빠르게 하는 저장소 개선 조치(색인)를 적용해야 한다.
- **FR-011**: 개선 조치는 서버를 다시 시작해도 중복 생성 · 오류 없이 유지되어야 하며, 기존 데이터 · 기능에 영향이 없어야 한다(헌법 원칙 X).
- **FR-012**: 측정용 대량 데이터는 임시 저장소에만 넣고, 시드 · 운영 데이터와 저장소 코드에 남기지 않는다.

### Key Entities

- **보고서 목록 조건**: 공정, 검색어(제목 부분 일치), 원인 분류(분류 없음 포함), 장비 파라미터, 이상 발생 기간, 페이지(번호 · 크기).
- **목록 페이지**: 보고서 행 목록과 다음 페이지 여부.
- **보고서(Report)**: 기존 엔티티. 제목 · 판본 · 상태 · 원인 분류 · 소속 사건(공정 · 이상 감지일).
- **측정 기록**: 데이터 건수, 대표 검색어, 개선 전 · 후의 실행 방식과 소요 시간.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 제목에 검색어가 들어 있는 보고서는 100% 결과에 나오고, 들어 있지 않은 보고서는 0건 나온다(권한 범위 안).
- **SC-002**: 보고서 10만 건에서 세 글자 이상 검색의 저장소 조회가 개선 후 개선 전보다 10배 이상 빠르고, 100ms 안에 끝난다.
- **SC-003**: 사용자는 검색어 입력을 멈춘 뒤 1초 안에 결과를 본다(일반 데이터 규모).
- **SC-004**: 검색 · 필터 동시 적용 결과가 각 조건을 따로 적용한 결과의 교집합과 100% 같다.
- **SC-006**: 목록 화면을 열 때 서버 요청이 사건 수와 무관하게 1번이다(지금은 사건 수 + 1번). 10만 건에서도 첫 페이지 조회가 100ms 안에 끝난다.
- **SC-005**: 기존 목록 · 필터 · 확정 흐름에서 회귀 오류 0건(기존 검증 · 스모크 전부 통과).

## Assumptions

- 검색 범위는 지금 목록과 같다 — 상단에서 고른 공정의 보고서(모든 판본, 초안 · 확정 모두).
- 대소문자 무시는 영문에만 의미가 있다. 한글은 글자 그대로 부분 일치한다.
- 기존 화면 필터 로직은 서버 조건과 의미를 맞추는 기준으로 남겨 두고, 목록 데이터는 서버 결과를 쓴다.
- 페이지 번호(1 · 2 · 3) 이동과 전체 건수 표시는 범위 밖이다(「더 보기」만).
- 측정 규모는 보고서 10만 건을 기준으로 한다. 측정 결과는 계획 · 구현 메모에 남긴다.
- 「입력이 잠시 멈춤」은 0.3초 안팎으로 본다.

## Dependencies

- 001 보고서 4M 원인 분류의 목록 필터(분류 · 장비 파라미터 · 이상 발생 기간)
- 공개 API 계약에 검색 조회가 추가되므로 계약 문서 갱신 · 기록이 필요하다(헌법 원칙 IV)
- 저장소 개선 조치는 기존 스키마 보조 스크립트에 추가된다 — 기존 파일 수정이라 계획 단계에서 동의가 필요하다(헌법 원칙 X)
<!-- ARTIFACT:002-report-title-search:spec.md END -->

<!-- ARTIFACT:002-report-title-search:checklists/requirements.md BEGIN -->
# Specification Quality Checklist: 조사 보고서 제목 검색

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-22
**Feature**: `002-report-title-search:spec.md` (이 SKILL.md의 spec 블록)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 사용자 요청 자체가 「LIKE」 · 「EXPLAIN ANALYZE」 · 「인덱스」라는 기술 수단을 지정했다. spec에는 「부분 일치」 · 「조회 계획 측정」 ·
  「색인(저장소 개선 조치)」으로 적고, 구체 수단(pg_trgm GIN 등)은 plan에서 정한다.
- 검색 대상(제목만) · 진행 방식(speckit) · 측정 방법(임시 저장소 10만 건)은 사용자가 사전 질문에서 정했다(2026-10-22).
<!-- ARTIFACT:002-report-title-search:checklists/requirements.md END -->

<!-- ARTIFACT:003-report-confirm-summary:spec.md BEGIN -->
# Feature Specification: 보고서 확정 · 사건 자동 종료 · AI 요약 연결

**Feature**: `003-report-confirm-summary`
**Created**: 2026-10-23
**Status**: Draft
**Input**: 사용자 설명: "조사 보고서 확정을 사건 종료와 AI 요약에 연결한다. 보고서 확정이 사건을 닫는 유일한 길이 되고,
확정본은 AI가 1~2줄로 요약한다. 자동 초안 생성은 이번 범위가 아니다." (팀 결정안 · Agent 담당과의 합의 2026-10-23 반영)

## 배경

지금은 관리자가 보고서 없이도 사건을 보류 · 종료할 수 있고, 보고서를 확정해도 사건은 열린 채로 남는다. 확정 뒤에도
새 초안을 만들 수 있어 「사건당 확정본 하나」가 보장되지 않는다. 이 기능은 **보고서 확정을 사건 종료의 유일한 길**로 만들고,
확정 직후 확정본을 AI가 짧게 요약해 보고서 화면에 보여 준다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 보고서를 확정하면 사건이 닫힌다 (Priority: P1)

공정 담당자가 최신 초안을 검토하고 원인 분류를 골라 확정하면, 그 사건은 자동으로 종료된다. 종료된 사건에는 더 이상
조사 · 요청 · 업무 · 보고서 변경이 들어가지 않는다.

**Why this priority**: 「모든 종료 사건에는 확정본이 있다」는 이 기능의 핵심 약속이다.

**Independent Test**: 정리할 것이 남지 않은 사건의 최신 초안을 확정하면 사건 상태가 종료로 바뀌고, 그 뒤 질문 · 초안 생성이 거부되면 통과.

**Acceptance Scenarios**:

1. **Given** 정리할 것이 없는 사건의 최신 초안, **When** 검토 체크 · 분류 후 확정하면, **Then** 보고서는 확정되고 사건은 종료되며 사건 이력에 「보고서 확정 · 사건 종료」가 남는다.
2. **Given** 초안 판본 v1 · v2가 있는 사건, **When** v1을 확정하려 하면, **Then** 「최신 판본만 확정할 수 있습니다」로 거부된다.
3. **Given** 두 사람이 같은 사건의 최신 초안을 거의 동시에 확정, **When** 두 요청이 처리되면, **Then** 하나만 성공하고 다른 하나는 「이미 확정된 사건입니다」와 확정본 정보를 받는다.
4. **Given** 확정되어 종료된 사건, **When** 질문 · 추가 분석 · 승인 · 조치 · 업무 · 확인 요청 · 회신 · 초안 생성 · 편집 · 확정을 시도하면, **Then** 모두 「종료된 사건」으로 거부된다.
5. **Given** 종료된 사건, **When** 사건 · 보고서를 조회하거나 확정본을 내려받으면, **Then** 지금처럼 동작한다.
6. **Given** 확정 처리 중 어느 한 단계가 실패, **When** 처리가 끝나면, **Then** 보고서는 초안, 사건은 열린 상태 그대로이고 요약도 접수되지 않는다.

---

### User Story 2 - 확정 전에 남은 일을 정리한다 (Priority: P1)

확정을 누르면, 결론을 바꾸거나 종료 뒤 처리할 길이 없는 일이 남아 있을 때 확정이 막히고 **무엇이 남았는지 목록**이 보인다.
담당자는 그 목록을 보고 승인 · 거절, 요청 철회, 업무 완료 · 미수행 닫기로 정리한 뒤 확정한다.

**Why this priority**: 정리 없이 닫으면 승인 대기 · 미회신 요청 · 미수행 업무가 영원히 남는다(종료 뒤에는 처리할 길이 없다).

**Independent Test**: 미회신 확인 요청이 있는 사건에서 확정하면 막히고 그 요청이 목록에 보이며, 사유를 적고 철회하면 확정되면 통과.

**Acceptance Scenarios**:

1. **Given** 조사 분석이 진행 중 · 대기 중(승인 대기 · 회신 대기 포함), **When** 확정하면, **Then** 막히고 「진행 중인 조사 분석」이 목록에 보인다.
2. **Given** Agent 제안이 승인 대기, **When** 확정하면, **Then** 막히고 그 제안이 목록에 보인다. 승인 또는 거절하면 확정할 수 있다.
3. **Given** 상급자 승인 대기 업무, **When** 확정하면, **Then** 막히고 관리자 처리가 필요하다고 보인다.
4. **Given** 회신이 오지 않은 확인 요청(대기 · 수락됨), **When** 요청자가 사유를 적고 철회하면, **Then** 요청은 철회 상태가 되고 받는 사람에게 철회가 보이며, 그 요청은 더 이상 확정을 막지 않는다.
5. **Given** 채택했지만 수행하지 않은 업무, **When** 담당자가 사유를 적고 「미수행으로 닫기」를 하면, **Then** 업무는 미수행 종결 상태가 되고 확정을 막지 않는다.
6. **Given** 채택하지 않은 제안 후보만 남음, **When** 확정하면, **Then** 막히지 않는다(후보는 이력으로 남는다).
7. **Given** 진행이 막혀 원인을 정할 수 없는 사건, **When** 요청 철회 · 미수행 닫기 뒤 「보류 (원인 미확정)」으로 확정하면, **Then** 사건이 종료된다.

---

### User Story 3 - 확정본을 AI가 요약해 보여 준다 (Priority: P2)

확정하면 확정본 요약이 자동으로 접수되고, 보고서 화면에 요약 상태(대기 · 진행 · 완료 · 실패)가 보인다. 완료되면 1~2줄
요약과 근거가 보이고, 실패하면 다시 요청할 수 있다. 완료 · 실패는 확정한 사람에게 알린다.

**Why this priority**: 확정 · 종료(P1)만으로도 핵심 약속은 지켜진다. 요약은 확정본을 빨리 파악하게 돕는 부가 가치다.

**Independent Test**: 확정 직후 보고서 화면에 요약 「대기」가 보이고, 잠시 뒤 「완료」와 요약 줄이 보이며 확정자에게 알림이 오면 통과(API 키 없이도).

**Acceptance Scenarios**:

1. **Given** 막 확정된 보고서, **When** 보고서 화면을 열면, **Then** 요약 상태가 「대기」 또는 「진행」으로 보인다.
2. **Given** 요약이 끝남, **When** 화면을 보면, **Then** 1~2줄 요약(줄당 120자 이내) · 근거 · 생성 방식(모델 · 규칙)이 보이고, 확정자에게 완료 알림이 온다.
3. **Given** 요약이 최종 실패, **When** 화면을 보면, **Then** 「실패」와 짧은 안내가 보이고 확정자에게 실패 알림이 온다. 조사 이력에는 남지 않는다.
4. **Given** 요약 실패 상태, **When** 확정 권한이 있는 사람이 다시 요청하면, **Then** 요약이 다시 「대기」로 접수된다. 대기 · 진행 · 완료 상태에서는 다시 요청할 수 없다.
5. **Given** 다시 요청해 새 요약이 진행 중, **When** 예전 요약 작업의 결과가 늦게 도착하면, **Then** 무시되고 현재 요약을 바꾸지 않는다.
6. **Given** API 키 없는 실행(시뮬레이터 모드), **When** 확정하면, **Then** 규칙 기반 요약으로 같은 흐름이 끝까지 동작한다.

---

### User Story 4 - 관리자는 보고서 없이 사건을 닫지 않는다 (Priority: P2)

관리자의 수동 보류 · 종료는 없어진다. 이미 보류된 사건은 그대로 보이고, 관리자는 그 보류를 해제(조사 재개)만 할 수 있다.

**Why this priority**: P1의 「종료 = 확정」을 우회하는 길을 막는다. 기존 데이터 보존이 함께 필요하다.

**Independent Test**: 관리자가 열린 사건을 종료 · 보류하려 하면 거부되고, 기존 보류 사건은 해제할 수 있으면 통과.

**Acceptance Scenarios**:

1. **Given** 열린 사건, **When** 관리자가 종료 또는 보류로 바꾸려 하면, **Then** 「보고서 확정으로만 종료할 수 있습니다」로 거부된다.
2. **Given** 기능 도입 전에 보류된 사건, **When** 관리자가 해제하면, **Then** 조사 중으로 돌아간다.
3. **Given** 기능 도입 전 보고서 없이 종료된 사건, **When** 조회하면, **Then** 이력 그대로 보이고 바뀌지 않는다.

---

### Edge Cases

- 확정 뒤 원인이 새로 밝혀져도 다시 확정하지 않는다 — 사건 기록에 후속 확인으로 남긴다. 001 스펙의 「보류로 확정 후 새 판본으로 재분류」 예외는 없앤다.
- 확정 요청이 같은 요청으로 두 번 오면(재전송) 같은 결과를 돌려준다(기존 멱등성).
- 확정을 누르는 사이 다른 사람이 초안을 수정해 판본 · 리비전이 바뀌면 기존처럼 확정이 거부된다.
- 확정 조건 검사와 확정 사이에 새 요청 · 분석이 끼어들어 조건이 깨진 채 확정되는 일이 없어야 한다.
- 요약 진행 · 실패는 확정 · 종료에 영향이 없다 — 요약이 실패해도 사건은 종료 상태다.
- 요약은 이미 종료된 사건에 대해 접수 · 실행된다(종료된 사건에서 허용되는 유일한 작업).
- 요약 결과는 그 보고서 · 그 확정본 · 현재 요약 작업의 결과일 때만 저장한다. 다른 확정본 · 예전 작업의 결과는 무시한다.
- 완료된 요약은 다시 요청하거나 늦은 결과로 덮어쓰지 않는다.
- 분류가 없는 옛 확정본은 보류로 자동 치환하지 않는다(001과 같음). 이런 옛 확정본은 요약 대상이 아니다(이 기능 이후 확정분만 요약).
- 철회 · 미수행 닫기 사유는 비울 수 없다. 이미 회신된 요청 · 완료된 업무는 철회 · 미수행 닫기 대상이 아니다.
- 시스템은 요청 · 업무를 자동으로 취소하거나 완료하지 않는다.

## Requirements *(mandatory)*

### Functional Requirements

**확정 = 종료**

- **FR-001**: 보고서를 확정하면 같은 처리 단위에서 사건이 종료되어야 한다. 확정 · 종료 · 요약 접수 중 하나라도 실패하면 모두 취소되어야 한다.
- **FR-002**: 확정은 사건당 한 번이어야 한다. 가장 최신 판본만 확정할 수 있고, 동시에 여러 확정 요청이 와도 하나만 성공해야 한다. 실패한 요청에는 이미 확정된 보고서를 알려 준다.
- **FR-003**: 확정 조건 검사와 확정은 다른 변경이 끼어들 수 없게 함께 처리되어야 한다.
- **FR-004**: 종료된 사건에는 질문 · 추가 분석 · 승인 제안 · 결정 · 조치 · 업무 · 확인 요청 · 결정 · 회신 · 보고서 초안 생성 · 편집 · 확정이 모두 거부되어야 한다. 조회 · 내려받기 · 요약 재요청만 허용한다.

**확정 조건**

- **FR-005**: 다음이 남아 있으면 확정을 거부하고, 남은 항목의 종류와 식별자를 목록으로 알려야 한다 — 진행 · 대기 중인 조사 분석, 승인 대기 중인 Agent 제안, 상급자 승인 대기 업무, 회신이 오지 않은 확인 요청(대기 · 수락됨), 채택했지만 수행하지 않은 업무.
- **FR-006**: 채택하지 않은 제안 후보와 요약 상태는 확정을 막지 않는다.
- **FR-007**: 요청자는 회신이 오지 않은 확인 요청을 사유와 함께 철회할 수 있어야 한다. 철회는 기록되고 받는 사람에게 보여야 한다.
- **FR-008**: 담당자는 채택했지만 수행하지 않은 업무를 사유와 함께 「미수행으로 닫기」할 수 있어야 한다. 닫은 업무는 이력에 사유와 함께 남는다.
- **FR-009**: 시스템은 요청 · 업무를 자동으로 취소 · 완료하지 않는다. 확정을 위한 정리는 모두 사람이 한다.

**관리자 상태 변경**

- **FR-010**: 관리자의 수동 보류 · 종료는 없어져야 한다. 기존 보류 사건의 해제(조사 재개)만 관리자에게 남긴다.
- **FR-011**: 기능 도입 전의 보류 사건 · 보고서 없이 종료된 사건은 이력을 바꾸지 않고 그대로 보존한다.

**확정본**

- **FR-012**: 확정 시점의 제목 · 본문 다섯 칸 · 원인 분류 · 장비 파라미터 · 근거 · 판본 · 리비전 · 확정자 · 확정 시각을 바꿀 수 없는 확정본으로 보존해야 한다. 기존 내려받기용 확정 문서도 그대로 유지한다.
- **FR-013**: Agent가 요약에 쓸 수 있도록 확정본을 조회할 수 있어야 한다. 본문은 칸 이름별 글(개요 · 조사 내용 · 요청과 회신 · 조치와 검증 · 미해결)이고, 사건 표시 ID · 판본 · 분류 · 근거를 포함한다. 확정되지 않은 보고서는 조회되지 않는다.
- **FR-014**: 확정 후 재확정 · 확정본 수정 경로는 없다(001 재분류 예외 삭제).

**AI 요약**

- **FR-015**: 확정과 함께 그 확정본에 대한 요약이 「대기」로 접수되어야 한다(FR-001의 같은 처리 단위).
- **FR-016**: 요약 상태는 대기 · 진행 · 완료 · 실패이며, 보고서 화면에 상태와 (완료 시) 요약 1~2줄(줄당 120자 이내) · 근거 · 생성 방식(모델 · 규칙)이 보여야 한다.
- **FR-017**: 요약 결과는 그 보고서 · 그 확정본 · 현재 요약 작업의 결과일 때만 저장해야 한다. 늦게 도착한 예전 결과 · 다른 확정본의 결과는 무시하고, 그 예전 작업은 끝난 것으로 정리한다.
- **FR-018**: 요약이 최종 실패하면 「실패」로 표시하고 사용자에게는 짧은 안내만 보여야 한다. 요약 실패는 조사 이력에 남기지 않는다.
- **FR-019**: 확정 권한이 있는 사람은 「실패」 상태에서만 요약을 다시 요청할 수 있다. 대기 · 진행 · 완료 상태에서는 거부하고, 동시에 여러 번 요청해도 하나만 접수된다. 완료된 요약은 덮어쓰지 않는다.
- **FR-020**: 요약이 완료 · 실패하면 보고서를 확정한 사람에게 알리고, 보고서를 보고 있는 화면은 즉시 갱신되어야 한다.
- **FR-021**: API 키 없이(시뮬레이터 모드)도 규칙 기반 요약으로 확정 → 요약 완료까지 동작해야 한다(헌법 원칙 VII).

**기존 동작**

- **FR-022**: 확정 화면의 분류 선택 · 승인 경로의 분류(001), 보고서 목록 · 검색(002), 초안 생성 · 편집은 종료 전에는 지금처럼 동작해야 한다(헌법 원칙 X).

### Key Entities

- **확정본(Confirmed Snapshot)**: 확정 시점에 고정된 보고서 내용 — 제목 · 칸 이름별 본문 · 분류 · 장비 파라미터 · 근거 · 판본 · 리비전 · 확정자 · 확정 시각 · 확정본 식별자 · 사건 표시 ID. 바뀌지 않는다.
- **보고서 요약(Report Summary)**: 확정본 하나에 대한 요약 — 상태(대기 · 진행 · 완료 · 실패), 요약 줄(1~2), 근거, 생성 방식, 현재 요약 작업, 갱신 시각, 실패 안내.
- **확정 차단 목록(Confirmation Blockers)**: 확정을 막는 남은 일 — 종류(조사 분석 · 승인 대기 · 상급자 승인 대기 · 미회신 요청 · 미수행 업무)와 식별자.
- **확인 요청(Check Request)**: 기존 엔티티에 「철회」 상태와 철회 사유 · 철회자 · 시각이 더해진다.
- **업무(Work Item)**: 기존 엔티티에 「미수행 종결」 상태와 사유 · 처리자 · 시각이 더해진다.
- **사건(Incident)**: 종료는 보고서 확정으로만 일어난다. 기존 보류는 해제만 가능하다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 기능 도입 후 종료되는 사건의 100%가 확정본 하나를 가진다(확정본 없는 종료 0건, 확정본 둘 이상인 사건 0건).
- **SC-002**: 동시 확정 시험(같은 사건에 동시 요청 10회)에서 성공은 정확히 1건이다.
- **SC-003**: 확정이 막힐 때 남은 항목이 100% 목록으로 보이고, 담당자는 목록만 보고 정리 · 확정을 마칠 수 있다.
- **SC-004**: 확정 처리 중 실패를 일으킨 시험에서 보고서 · 사건 · 요약 상태가 100% 확정 전으로 되돌아간다.
- **SC-005**: 일반 상황에서 확정 뒤 1분 안에 요약이 완료되어 보고서 화면에 보인다(API 키 없는 실행 포함).
- **SC-006**: 늦게 도착한 예전 요약 결과가 현재 요약을 바꾼 경우 0건.
- **SC-007**: 종료된 사건에 대한 변경 요청(조회 · 내려받기 · 요약 재요청 제외)이 100% 거부된다.
- **SC-008**: 001 · 002 기능과 기존 흐름에서 회귀 오류 0건(기존 검증 · 스모크 기준 — 단, 현재 main에 이미 있는 스모크 실패 5건은 별도).

## Assumptions

- 요약 작업은 지금 작업 전달 방식(서버 작업 목록을 Agent가 가져감)을 쓴다. Kafka 전환은 범위 밖이다.
- 요약 결과 형식(요약 줄 · 근거 · 생성 방식 · 대조용 보고서 · 확정본 식별자)과 요약 작업 입력(보고서 · 판본 · 리비전 · 확정본 식별자)은 Agent 담당과 합의한 형식을 따른다.
- 요약 재요청은 새 요약 작업으로 접수된다(재시도 횟수는 새로 시작).
- 요약 알림 수신자는 보고서를 확정한 사람 한 명이다.
- 확정 권한 · 철회 권한(요청자) · 미수행 닫기 권한(업무 담당자 · 사건 운영 권한자)은 기존 공정 범위 권한 규칙을 따른다.
- 이 기능 이전에 확정된 보고서(요약 없음)는 「요약 없음」으로 표시하고 소급 요약하지 않는다.
- 관리자 수동 종료 제거는 헌법 원칙 VI 개정으로 반영했다(헌법 2.0.0, 2026-10-23 — 종료는 보고서 확정으로만).

## Dependencies

- 001 보고서 4M 원인 분류(확정 · 분류 · 확정 문서), 002 보고서 목록 · 검색
- Agent SUMMARY(PR #8)와 요약 결과 · 확정본 조회 계약(Agent 담당 계약 파일 갱신)
- 공통 작업 · 이벤트 수신 · 시뮬레이터 · 알림(공통 담당 파일 — 이 기능에서 SUMMARY 부분을 함께 고치고 공통 담당이 리뷰)
- 헌법 원칙 VI 개정(2.0.0, 완료)
- 공개 API 계약 갱신(확정 차단 목록 · 요약 상태 · 요약 재요청 · 요청 철회 · 미수행 닫기)

## Out of Scope

- 자동 초안 생성(조사 정리 신호 · 사람 수정 칸 보존) — 다음 기능
- 작업 전달의 Kafka 전환 — 공통 작업 개선
- 원인 통계 탭 — 별도 담당
<!-- ARTIFACT:003-report-confirm-summary:spec.md END -->

<!-- ARTIFACT:003-report-confirm-summary:checklists/requirements.md BEGIN -->
# Specification Quality Checklist: 보고서 확정 · 사건 자동 종료 · AI 요약 연결

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-23
**Feature**: `003-report-confirm-summary:spec.md` (이 SKILL.md의 spec 블록)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 결정 사항은 팀 결정안 · Agent 담당 합의(2026-10-23)에서 왔다: 확정 조건 · A안(재확정 없음) · 요약 상태 · 재요청 · 알림 수신자.
- 잠금 · 고유 제약 · 트랜잭션 · 내부 조회 경로 · 작업 종류 같은 구현 수단은 spec에서 빼고 plan에서 정한다(「같은 처리 단위」 · 「끼어들 수 없게」로 표현).
- 001 스펙의 재분류 예외 삭제(FR-014)와 헌법 원칙 VI 개정은 이 기능의 선행 작업이다.
<!-- ARTIFACT:003-report-confirm-summary:checklists/requirements.md END -->

---
name: "speckit-constitution"
description: "Create or update the project constitution from interactive or provided principle inputs, ensuring all dependent templates stay in sync."
argument-hint: "Principles or values for the project constitution"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/constitution.md"
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

**Check for extension hooks (before constitution update)**:
- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_constitution` key
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

You are updating the project constitution stored in the 「Project Constitution」 section at the end of this file (`.claude/skills/speckit-constitution/SKILL.md`, between `<!-- CONSTITUTION:BEGIN -->` and `<!-- CONSTITUTION:END -->`). Edit only that section; never change the skill instructions above it while updating the constitution. The section starts from a TEMPLATE containing placeholder tokens in square brackets (e.g. `[PROJECT_NAME]`, `[PRINCIPLE_1_NAME]`). Your job is to (a) collect/derive concrete values, (b) fill the template precisely, and (c) propagate any amendments across dependent artifacts.

**Note**: If the 「Project Constitution」 section does not exist yet, it should have been initialized from `.specify/templates/constitution-template.md` during project setup. If it's missing, copy the template first.

Follow this execution flow:

1. Load the existing constitution from the 「Project Constitution」 section of this file.
   - Identify every placeholder token of the form `[ALL_CAPS_IDENTIFIER]`.
   **IMPORTANT**: The user might require less or more principles than the ones used in the template. If a number is specified, respect that - follow the general template. You will update the doc accordingly.

2. Collect/derive values for placeholders:
   - If user input (conversation) supplies a value, use it.
   - Otherwise infer from existing repo context (README, docs, prior constitution versions if embedded).
   - For governance dates: `RATIFICATION_DATE` is the original adoption date (if unknown ask or mark TODO), `LAST_AMENDED_DATE` is today if changes are made, otherwise keep previous.
   - `CONSTITUTION_VERSION` must increment according to semantic versioning rules:
     - MAJOR: Backward incompatible governance/principle removals or redefinitions.
     - MINOR: New principle/section added or materially expanded guidance.
     - PATCH: Clarifications, wording, typo fixes, non-semantic refinements.
   - If version bump type ambiguous, propose reasoning before finalizing.

3. Draft the updated constitution content:
   - Replace every placeholder with concrete text (no bracketed tokens left except intentionally retained template slots that the project has chosen not to define yet—explicitly justify any left).
   - Preserve heading hierarchy and comments can be removed once replaced unless they still add clarifying guidance.
   - Ensure each Principle section: succinct name line, paragraph (or bullet list) capturing non‑negotiable rules, explicit rationale if not obvious.
   - Ensure Governance section lists amendment procedure, versioning policy, and compliance review expectations.

4. Consistency propagation checklist (convert prior checklist into active validations):
   - Read `.specify/templates/plan-template.md` and ensure any "Constitution Check" or rules align with updated principles.
   - Read `.specify/templates/spec-template.md` for scope/requirements alignment—update if constitution adds/removes mandatory sections or constraints.
   - Read `.specify/templates/tasks-template.md` and ensure task categorization reflects new or removed principle-driven task types (e.g., observability, versioning, testing discipline).
   - Read each installed Spec Kit command file for your agent (including this one) — named `speckit.*` or `speckit-*` (dot or hyphen depending on the agent), or laid out as `speckit-<name>/SKILL.md` for skills-based integrations, e.g. in `.github/agents/`, `.github/skills/`, `.claude/skills/`, or your agent's equivalent commands directory — to verify no outdated references (CLAUDE-only or other agent-specific names) remain when generic guidance is required.
   - Read any runtime guidance docs (e.g., `README.md`, `docs/quickstart.md`, or agent-specific guidance files if present). Update references to principles changed.

5. Produce a Sync Impact Report (prepend as an HTML comment at the top of the 「Project Constitution」 section, right after `<!-- CONSTITUTION:BEGIN -->`):
   - Version change: old → new
   - List of modified principles (old title → new title if renamed)
   - Added sections
   - Removed sections
   - Templates requiring updates (✅ updated / ⚠ pending) with file paths
   - Follow-up TODOs if any placeholders intentionally deferred.

6. Validation before final output:
   - No remaining unexplained bracket tokens.
   - Version line matches report.
   - Dates ISO format YYYY-MM-DD.
   - Principles are declarative, testable, and free of vague language ("should" → replace with MUST/SHOULD rationale where appropriate).

7. Write the completed constitution back into the 「Project Constitution」 section (replace only the content between the markers).

8. Output a final summary to the user with:
   - New version and bump rationale.
   - Any files flagged for manual follow-up.
   - Suggested commit message (e.g., `docs: amend constitution to vX.Y.Z (principle additions + governance update)`).

Formatting & Style Requirements:

- Use Markdown headings exactly as in the template (do not demote/promote levels).
- Wrap long rationale lines to keep readability (<100 chars ideally) but do not hard enforce with awkward breaks.
- Keep a single blank line between sections.
- Avoid trailing whitespace.

If the user supplies partial updates (e.g., only one principle revision), still perform validation and version decision steps.

If critical info missing (e.g., ratification date truly unknown), insert `TODO(<FIELD_NAME>): explanation` and include in the Sync Impact Report under deferred items.

Do not create a new template; always operate on the existing 「Project Constitution」 section of this file.

## Post-Execution Checks

**Check for extension hooks (after constitution update)**:
Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.after_constitution` key
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

    **Optional Hook**: {extension}
    Command: `/{command}`
    Description: {description}

    Prompt: {prompt}
    To execute: `/{command}`
    ```
  - **Mandatory hook** (`optional: false`):
    ```
    ## Extension Hooks

    **Automatic Hook**: {extension}
    Executing: `/{command}`
    EXECUTE_COMMAND: {command}
    ```
    After emitting the block above you MUST actually invoke the hook and wait for it to finish before continuing. Run it the same way you would run the command yourself in this agent/session (the invocation may differ from the literal `{command}` id shown above, e.g. a skills-mode agent runs it as `/skill:speckit-...` or `$speckit-...`). Emitting the block alone does not run the hook.
- If no hooks are registered or `.specify/extensions.yml` does not exist, skip silently

---

## Project Constitution

<!-- CONSTITUTION:BEGIN -->
<!--
Sync Impact Report
- Version change: 2.0.0 → 3.0.0 (MAJOR — 원칙 VI에서 MANAGER의 「기존 보류 해제(조사 재개)」 권한 삭제. 팀 통합 정리 문서
  「관리자 수동 보류 · 보류해제 · 종료 제거」 반영, 2026-10-23. 남아 있던 관리자 권한을 없애는 비호환 변경)
- Modified principles (3.0.0):
  - VI. 공정 범위 권한 — 수동 보류 · 보류 해제 · 종료 모두 없음, 기존 보류 사건도 보고서 확정으로만 종료
- Templates requiring updates (3.0.0):
  - ✅ .specify/templates/*.md · .claude/skills/speckit-*/SKILL.md — 원칙 VI 문구를 직접 인용하지 않음
  - ✅ README.md 101행 · backend/README.md(관리자 「조사 재개」 설명 삭제) — 003 개정 구현과 함께 갱신(2026-10-23)
  - ✅ 003 spec(US4 · FR-010) · plan(P5 · P15) · tasks(Phase 9) · 코드 반영 완료(2026-10-23)

--- 이전 개정(2.0.0) ---
- Version change: 1.2.1 → 2.0.0 (MAJOR — 원칙 VI의 상태 전이 규칙 재정의: 「보류 · 종료는 MANAGER만」 →
  「종료는 보고서 확정으로만, MANAGER는 기존 보류 해제만」. 기존 관리자 수동 보류 · 종료 권한을 없애는 비호환 변경)
- Modified principles:
  - VI. 공정 범위 권한 — 종료 = 보고서 확정(사건당 한 번, 같은 처리 단위), 수동 보류 · 종료 제거, 기존 보류 해제만 MANAGER,
    종료 사건 변경 금지(조회 · 내려받기 · 요약 재요청 제외), 옛 이력 보존, 근거 보강
- Modified sections:
  - 기술 · 데이터 제약 — 데이터 팩 dataVersion `causal-pack-v2.1-scenarios`(원격 main의 데이터 갱신 반영, 사실 정정)
- Added / Removed sections: 없음
- Templates requiring updates:
  - ✅ .specify/templates/plan-template.md · spec-template.md · tasks-template.md — 원칙을 헌법에서 읽는 구조라 수정 불필요
  - ✅ .claude/skills/speckit-*/SKILL.md — 원칙 VI 문구를 직접 인용하지 않음
  - ✅ README.md 101행 · backend/README.md 20 · 126 · 137행(관리자 전이 설명) — 003 구현과 함께 갱신(2026-10-23)
- Follow-up TODOs:
  - ✅ 001 스펙의 「보류로 확정 후 새 판본으로 재분류」 예외 삭제(2026-10-23, 003 구현)
- History: 1.0.0 → 1.1.0(원칙 VIII) → 1.2.0(원칙 IX · X) → 1.2.1(경로) → 2.0.0(원칙 VI 종료 규칙, 2026-10-23)
  → 3.0.0(원칙 VI 보류 해제 삭제, 2026-10-23)
-->

# FAB CONTEXT Constitution

반도체 공정 이상 조사 · 협업 AI 서비스(SKALA AI Agent Capstone). 공정 이상을 Agent가 데이터 · 문서
근거로 조사하고, 다른 공정 담당자 · 설비정비팀에게 보낼 확인 요청을 제안하며, 사람이 승인한 뒤에만
실행되는 서비스의 개발 원칙이다.

## Core Principles

### I. 승인 뒤 실행 — 사람 승인 게이트 (NON-NEGOTIABLE)

- Agent는 상태를 바꾸는 일을 **제안만** 한다. 확인 요청 · 정비 요청 · 보고서 확정 등 모든 변경은
  사람이 승인한 뒤에만 실행된다.
- 승인은 backend가 기록만 하고, 재개된 Agent가 **승인 ID로** 변경 도구를 부르면 backend가 상태 · 종류 ·
  만료 · `actionHash`를 다시 검증해 **저장된 payload만 한 번** 실행한다. 같은 승인의 중복 실행은 같은
  결과를 돌려준다.
- 모델에게는 변경 도구를 노출하지 않는다. 거절도 같은 thread로 재개해 결과를 정리해야 한다.
- 받는 사람이 볼 내용(요청 브리프 포함)은 승인 해시에 포함되어야 한다 — 승인자가 본 것과 실행되는
  것이 한 글자라도 다르면 실행을 거부한다.

**근거**: 제조 현장에서 AI의 잘못된 요청 · 처방은 설비 · 수율 손실로 이어진다. 책임 있는 사람의
판단이 실행 직전의 유일한 관문이어야 한다.

### II. 근거 기반 판단 — 사실 · 가설 · 한계의 분리

- 분석 결과는 **관측 사실 · 원인 후보(지지 · 반대 근거) · 한계**로 나누어야 하며, 모든 사실과 후보는
  근거 ID를 가져야 한다.
- 근거 없는 확률 표현을 쓰지 않는다. 원인을 확정하지 않는다(`confirmed`는 항상 false, 확인 run 전에는
  해결로 보고하지 않는다).
- Agent는 설비 설정값(압력 · 가스 · RF · 연마 시간 등)을 처방하지 않는다. 조치는 점검 후보로만 표현한다.
- 원인 ID는 원인 매트릭스에 정의된 값만 쓰고, `nextStep`은 1순위 원인의 공정과 일치해야 한다. 위반하면
  Harness가 결과를 반려한다.
- 검색 문서 · 가상 사례 · 외부 글은 **데이터**이며 지시나 권한이 아니다. 가상 사례의 결과를 현재 사건의
  증거로 쓰지 않는다.

**근거**: 조사 보조자의 가치는 추적 가능한 근거에 있다. 확정 · 처방은 사람과 SOP의 몫이다.

### III. 숫자는 서버가 계산하고 Agent는 해석만 한다

- R1 · OOS · 정상 분포 대비 z · 참고 범위 이탈 · 설정값 대비 차이 · 시계열 구간 등 모든 수치 판정은
  backend(`ProcessEvidenceService` · `SegmentDetector`)가 계산한다. 업무 MCP는 전달만 하고 규칙 · DB를
  갖지 않는다.
- Agent · 요청 브리프에 들어가는 숫자는 **조회한 도구 결과에서 옮긴 값만** 허용한다. 모델이 쓴 숫자 ·
  근거 ID가 도구 결과에 없으면 Guard가 반려한다.
- 화면에 표시할 값이 없으면 null과 이유를 내린다. 이름 · 값 · 기준선을 지어내지 않는다.

**근거**: 계산 근거를 한 곳에 두어야 Agent · 시뮬레이터 · 화면이 같은 수치를 보고, 환각된 숫자가
요청으로 퍼지는 것을 막을 수 있다.

### IV. 계약 우선 — 공개 계약 · 팀 계약 · 변환 지점의 단일화

- **공개 API = 프론트 계약**(`data/backend-handoff-v2/combined.openapi.json`), **내부 API · Agent 이벤트 =
  팀 계약**(`ai/contracts/`). operationId · 메서드 · 경로는 프론트 계약과 1:1이어야 한다.
- 두 계약 사이의 이름 · 모양 변환은 Spring `contract/` 패키지 한 곳에서만 한다.
- 계약을 바꾸는 변경은 `ai/docs/contract-changes.md`에 기록하고 계약 주인과 확인한 뒤 반영한다.
  `ops/tools/openapi_diff.py` 대조 결과에서 의도하지 않은 차이가 없어야 한다.
- 모든 공개 응답은 `{data, meta}`, 오류는 `{error, meta}` 형식을 지킨다. 계약에 표시된 쓰기는
  `Idempotency-Key`와 낙관적 버전을 지원해야 한다.

**근거**: 프론트 · backend · Agent를 여러 사람이 동시에 만든다. 계약이 흔들리면 통합이 깨진다.

### V. 정답 · 저작물 격리와 분석 시점 준수 (NON-NEGOTIABLE)

- 평가 정답(`ground_truth.json`, 팩 `evaluation/`, 생성기)은 Agent 프롬프트 · RAG 색인 · 컨테이너 ·
  조립 데이터 팩 어디에도 넣지 않는다. 평가 스크립트만 실행 중에 읽는다.
- 원문 PDF · 원문 발췌 조각 · 강의자료 · API 키(`.env`)는 커밋하지 않는다.
- 분석 시점(`analysisAsOf`) 이후 공개된 계측 · FOLLOWUP run은 내부 API가 차단(403)해야 한다. 과거 사례는
  `availableAt ≤ asOf`만 검색된다.
- 운전 데이터는 합성임을 응답(`synthetic`)과 화면에 드러낸다.

**근거**: 평가의 공정성, 저작권, 미래 정보 누설 방지는 데모 신뢰성의 전제다.

### VI. 공정 범위 권한

- 조회는 `processScopes`, 사건 운영(질문 · 제안 · 승인 · 조치 · 보고서)은 `investigationProcessScopes`로
  서버가 검사한다.
- **사건 종료는 보고서 확정으로만 일어난다.** 확정은 사건당 한 번이며, 확정과 종료는 같은 처리 단위로 함께
  저장되거나 함께 취소된다. 관리자를 포함해 누구도 사건을 수동으로 보류 · 보류 해제 · 종료하지 않는다.
  기존에 보류된 사건도 남은 일을 정리하고 보고서를 확정해야 종료된다.
- 종료된 사건에는 조회 · 내려받기 · 확정본 요약 재요청 외의 변경을 허용하지 않는다(서버가 거부한다).
- 기존 보류 사건과 보고서 없이 종료된 옛 사건의 이력은 소급해 바꾸지 않는다.
- 다른 공정 담당자 · 설비정비팀은 사건 전체가 아니라 **자신에게 배정된 요청과 그 브리프**로만 협업한다.
- 제안의 받는 사람은 backend가 검증한다 — 앞 공정 확인은 그 공정의 PROCESS_OWNER에게만, 정비 요청은
  설비정비팀에게만, 질문한 본인에게는 보낼 수 없다.
- 프론트의 권한 표시는 편의일 뿐이며 모든 쓰기는 서버에서 다시 검사한다.

**근거**: 공정별 책임 경계가 실제 FAB 조직의 협업 방식이며, 과도한 공개는 보안 · 책임 문제를 만든다.
종료를 보고서 확정 하나로 묶어야 「모든 종료 사건에는 사람이 검토한 확정본이 하나 있다」를 보장하고,
닫힌 사건의 내용이 뒤늦게 바뀌는 것을 막을 수 있다.

### VII. 결정적 검증 · 복구 · 관측 가능성

- API 키 없이도 전체 흐름이 돌아야 한다 — 규칙 모델(`FAB_LLM=rule`) · 해시 임베딩(`EMBEDDER=hash`) ·
  시뮬레이터(`AGENT_MODE=simulator`)로 배선을 결정적으로 검증한다. 판단 품질은 실제 모델 평가로 따로 잰다.
- 설정이 잘못되면 조용히 대체하지 않고 원인을 밝히며 시작에 실패한다(예: 키 없는 openai 임베더).
- Agent는 상한(모델 판단 8회 · 같은 도구 3회 · 활성 120초 · LLM 30초 · MCP 10초)을 지키고, 일시 오류는
  재시도 → 대체 모델 → `RETRY_SCHEDULED` → `JOB_FAILED` 순으로 복구한다. 작업 확보는 attempt 단위로
  한 번만 처리된다.
- Agent 상태는 사건 단위 체크포인트로 영속되어 Worker 재시작 후에도 같은 대기 지점에서 재개되어야 한다.
- 하나의 trace ID로 node · tool · 지연 · 재시도 · 토큰을 기록한다. 로그 · 오류 메시지에 비밀값을 남기지 않는다.

**근거**: LLM은 비결정적이다. 결정적 경로와 복구 · 추적이 있어야 시연 · 운영에서 실패를 재현하고 설명할 수 있다.

### VIII. 동작을 보존하는 리팩터링 · 읽기 쉬운 코드

- 리팩터링은 **현재 기능을 바꾸지 않는다.** 공개 · 내부 API의 경로 · 요청 · 응답 모양, Agent 이벤트, DB 스키마,
  화면 동작이 리팩터링 전후로 같아야 한다. 기존 테스트를 고쳐서 통과시키지 않는다 — 테스트 기대값이 바뀌면
  그것은 리팩터링이 아니라 기능 변경이다.
- 리팩터링과 기능 변경은 같은 커밋에 섞지 않는다.
- **이름은 의미를 드러내야 한다.** 무엇을 담는지 알 수 없는 약어 · 한 글자 이름(`a`, `b`, `t`, `tmp`, `res2` 등)을
  쓰지 않는다. 짧은 루프 인덱스와 언어 관용(`i`, `e` 예외, `_`)은 허용한다.
- **바꾸면 안 되는 이름**: 계약에 고정된 이름(API JSON 필드 · operationId · 이벤트 타입 · MCP 도구 이름 ·
  환경 변수)과 영속된 이름(LangGraph 노드 이름 — 체크포인트에 저장된다, DB 컬럼)은 리팩터링 대상이 아니다.
- **함수는 한 가지 일만 한다.** 한 함수가 검증 · 조회 · 계산 · 저장 · 응답 변환 중 둘 이상을 하면 단계별 함수로
  나눈다. 본문이 약 40줄을 넘는 함수는 분리 대상으로 검토하고, 남겨 둘 때는 이유를 리뷰에 적는다.
- 클래스 · 모듈도 하나의 책임을 가진다. 분리 후에도 원칙 IV의 패키지 의존 방향과 순환 금지를 지킨다.
- 주변 코드의 관례(주석 밀도 · 명명 · 관용구)를 따르고, 리팩터링 중에 새 의존성을 들이지 않는다.

**근거**: 여러 사람이 짧은 기간에 만든 코드라 이름과 긴 함수가 이해 비용을 키운다. 동작을 고정한 채 구조만
바꿔야 승인 · 권한 · 계약 같은 핵심 규칙을 깨지 않고 유지보수성을 높일 수 있다.

### IX. 객체지향 설계 · 현업형 프로젝트 구조

- 새 코드는 **객체지향 원칙(SOLID)** 을 따른다.
  - 단일 책임: 클래스 하나는 변경 이유 하나만 가진다(원칙 VIII의 함수 · 클래스 책임 규칙과 같은 기준).
  - 개방 · 폐쇄: 새 동작은 기존 클래스를 고치기보다 인터페이스 구현 · 전략 · 핸들러 등록으로 **확장**한다
    (예: 새 승인 실행 동작은 `ApprovedActionHandler` 구현을 추가한다).
  - 의존 역전: 상위 정책은 구체 클래스가 아니라 인터페이스 · 추상에 의존하고, 의존성은 생성자 주입으로 받는다.
  - 캡슐화: 상태는 그 상태를 가진 객체의 메서드로만 바꾼다. 다른 클래스가 필드를 직접 조작하지 않는다.
  - 상속보다 조합을 우선하고, 공통 로직이 두 곳 이상에서 반복될 때만 추출한다(성급한 추상화 금지).
- **이름은 역할을 말한다.** 클래스는 명사(`ReportComposer`), 메서드는 동사구(`composeReport`), 불리언은
  `is` · `has` · `can` 접두로 짓는다. 언어별 표준 표기(Java `camelCase` · `PascalCase`, Python `snake_case`,
  React 컴포넌트 `PascalCase`)를 따른다.
- **폴더 구조는 기존 컴포넌트의 관례를 따른다.** 새 폴더 · 패키지는 현업에서 통용되는 기능 단위 구조로 만든다.
  - Spring: `com.fabcontext.api.domain.<기능>` 아래에 `*Controller` · `*Service` · `*Repository` · 엔티티 ·
    `*Dtos`를 함께 둔다(package-by-feature). 공통은 `common`, 설정은 `config`, 계약 변환은 `contract`.
  - Agent: `ai/src/fab/<영역>`(`agent` · `business` · `rag` · `common`) 아래에 모듈을 두고, 테스트는
    `ai/tests`에 대응되는 이름으로 둔다.
  - Web: `frontend/src/pages`(화면) · `components`(재사용 UI) · `api`(서버 호출) · `domain`(타입 · 도메인 로직),
    테스트는 `frontend/tests`.
- 기존 폴더 · 패키지를 옮기거나 이름을 바꾸는 재구성은 원칙 X의 사전 확인 대상이다.

**근거**: 여러 사람이 동시에 만드는 코드는 구조가 예측 가능해야 찾고 고칠 수 있다. 확장으로 기능을 더하면
이미 검증된 승인 · 권한 · 계약 코드를 건드리지 않고 기능을 늘릴 수 있다.

### X. 기존 동작 보존 · 기존 코드 수정 전 확인

- 코드를 **추가**할 때는 기존에 동작하던 기능이 깨지지 않아야 한다. 추가 전후로 품질 게이트의 테스트 ·
  스모크가 모두 통과해야 하며, 기존 API · 이벤트 · DB 스키마 · 화면 동작은 그대로 유지된다.
- 새 기능은 가능한 한 **새 파일 · 새 클래스 · 새 메서드로 추가**하고, 기존 코드는 연결 지점(등록 · 호출 한 줄 등)
  만 최소로 바꾼다.
- **기존 코드를 수정해야 할 때는 먼저 확인을 받는다.** 수정 전에 다음을 설명하고 담당자(요청자)의 동의를
  얻은 뒤에만 고친다.
  - 무엇을 고치는지(파일 · 함수 · 범위)
  - 왜 고쳐야 하는지(추가만으로는 안 되는 이유)
  - 영향받는 동작 · 호출자와 깨지지 않음을 보일 방법(테스트)
- 새 코드는 기존 코드의 **동작 원리와 컨벤션**을 먼저 읽고 맞춘다 — 오류 처리 방식(`{error, meta}`), 트랜잭션 ·
  멱등성 처리, 로깅 · trace, 테스트 작성 방식, 주석 밀도를 주변 코드와 같게 한다.
- 이 원칙은 원칙 I–VIII을 완화하지 않는다. 동의를 받았더라도 승인 · 권한 · 계약 · 정답 격리 규칙은 그대로 지킨다.

**근거**: 동작 중인 서비스의 회귀는 새 기능보다 비용이 크다. 수정 이유를 미리 공유하면 의도하지 않은 변경을
리뷰 전에 걸러낼 수 있고, 기존 관례를 따르면 코드베이스가 한 사람이 쓴 것처럼 일관된다.

## 기술 · 데이터 제약

- **구성**: React 프론트(`frontend`) → Spring backend 하나(`backend`, Java 21 · Spring Boot) ←
  업무 MCP · 지식 MCP · Agent Worker(`ai`, Python 3.12 · LangGraph · FastMCP). backend는 하나뿐이다.
- **DB**: 실행 · 테스트 모두 PostgreSQL(pgvector). 스키마는 `backend` · `agent`(체크포인트) · `knowledge`(벡터)로
  나눈다. H2 등 대체 DB를 쓰지 않는다. 스키마를 바꾸면 반드시 재시드한다.
- **데이터 팩**: `data/fab-causal-pack-v2`(dataVersion `causal-pack-v2.1-scenarios`)가 유일한 운전 데이터 원본이며
  `data/build_pack_v2.py`로 결정적으로 재생성한다. 경로(`FAB_PACK_DIR` · `FAB_RAG_DIR`)에는 코드 기본값을 두지 않는다.
- **패키지 의존**: Spring `domain`은 `internal` · `simulator` · `seed`를 모른다. 패키지 순환은 허용하지 않는다
  (`ops/tools/java_pkgdeps.py`가 「순환: 없음」).
- **배포**: 로컬 compose가 기준이며 웹만 외부 포트를 연다. 외부 공개 시 HTTPS · 운영 인증정보를 별도로 설정한다.

## 개발 워크플로 · 품질 게이트

- 작업 하나는 다음 검증을 통과한 뒤 커밋한다: `backend` `./gradlew test`, `ai` `uv run pytest -q`,
  `frontend` `npm test && npm run build`, compose 스모크(질문 → 제안 → 승인 → 실행 → 회신 → 보고서) 실패 0건.
- 공개 API를 바꾸면 `ops/tools/openapi_diff.py`, Spring 패키지를 바꾸면 `ops/tools/java_pkgdeps.py`를 돌린다.
- 승인 · 권한 · 계약 · 정답 격리에 닿는 변경은 해당 원칙을 지키는지 테스트로 보여야 한다
  (예: 승인 전 변경 0건, 같은 승인 중복 실행 1건, 다른 공정 사용자 403).
- 리팩터링 커밋은 위 검증에 더해 `ops/tools/openapi_diff.py` 결과가 리팩터링 전과 같고, 테스트 파일의 기대값을
  바꾸지 않았음을 보여야 한다(원칙 VIII).
- 기존 코드를 수정하는 작업은 수정 이유 · 범위 · 영향을 먼저 설명하고 동의를 받은 뒤 진행한다(원칙 X).
- 커밋 메시지는 「무엇을 · 왜」를 한 줄로 쓴다. `.env` · 원문 · 정답 파일은 커밋하지 않는다.
- 검증 결과와 날짜는 README의 「검증 현황」에 갱신한다.

## Governance

- 이 헌법은 저장소의 다른 관행보다 우선한다. README · 컴포넌트 README · `contract-changes.md`는 이 헌법과
  충돌해서는 안 되며, 충돌이 발견되면 문서를 고치거나 헌법 개정을 제안한다.
- **개정 절차**: 개정안은 변경 이유 · 영향받는 컴포넌트 · 이행 방법을 적어 팀 리뷰를 거친다. 원칙 I · V
  (NON-NEGOTIABLE)의 완화는 팀 전원 합의가 필요하다.
- **버전 정책**: MAJOR — 원칙 삭제 · 의미 재정의, MINOR — 원칙 · 섹션 추가 또는 지침의 실질적 확장,
  PATCH — 문구 · 오탈자 · 비의미적 정리.
- **준수 검토**: 모든 PR 리뷰는 원칙 준수를 확인한다. `/speckit-plan`의 Constitution Check는 원칙 I–X를
  게이트로 사용하고, 위반은 Complexity Tracking에 정당화를 남겨야 한다.
- 실행 지침은 `README.md`(전체) · `ai/README.md` · `backend/README.md`를 따른다.

**Version**: 3.0.0 | **Ratified**: 2026-10-21 | **Last Amended**: 2026-10-23
<!-- CONSTITUTION:END -->

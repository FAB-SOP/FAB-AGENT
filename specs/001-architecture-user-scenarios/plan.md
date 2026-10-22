# Implementation Plan: FAB CONTEXT 아키텍처 정리 및 핵심 사용자 시나리오

**Branch**: `feat/architecture` (feature dir `001-architecture-user-scenarios`) | **Date**: 2026-10-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-architecture-user-scenarios/spec.md`

## Summary

현재 저장소(프론트 · Spring backend · 업무/지식 MCP · LangGraph Agent · 데이터 팩)를 읽고 **기술 아키텍처 구조도**와
**서비스 아키텍처 구조도**를 Mermaid 문서로 남기고, 핵심 사용자 시나리오를 수용 기준과 함께 검증한다.
런타임 코드는 바꾸지 않는다.

**이번 계획의 검증 범위는 핵심 시나리오 2개로 한정한다(2026-10-21 사용자 요청, research R7).**

| 범위 | 시나리오 | 왜 핵심인가 |
|---|---|---|
| ✅ 포함 | **US1 이상 사건 자동 접수와 기본 분석** (P1, MVP) | 모든 흐름의 출발점. 「근거 기반 첫 판단」이 없으면 질문 · 제안 · 협업이 성립하지 않는다 |
| ✅ 포함 | **US2 추가 질문 · 제안 · 승인** (P1) | 서비스 고유 가치 「AI는 제안, 사람이 승인한 것만 한 번 실행」(헌법 I). 승인 뒤 요청 1건 생성까지 포함 |
| ⏸ 보류 | US3 요청 확인 · 회신 (P1) | US2가 만든 요청에 이어지는 후속 단계 — US2 검증 뒤 다음 반복에서 |
| ⏸ 보류 | US4 조치 · 보고서 (P2), US5 · US6 (P3) | 핵심 흐름 밖 · 우선순위 낮음 |

보류한 시나리오는 spec.md에 그대로 두고(요구사항은 유효), 이번 계획 · 검증 대상에서만 뺀다.
구조도(architecture.md)는 전체 시스템을 그린 그대로 유지한다.

## Technical Context

**Language/Version**: Markdown + Mermaid (mermaid-cli 11.4.2로 렌더링 검증). 대상 시스템: Java 21 · Python 3.12 · TypeScript 5.9

**Primary Dependencies**: 새 의존성 없음. 검증 도구: `@mermaid-js/mermaid-cli`(npx 일회 실행), `tools/java_pkgdeps.py`, `tools/openapi_diff.py`

**Storage**: N/A (문서). 대상 시스템은 PostgreSQL 16 + pgvector(`backend` · `agent` · `knowledge` 스키마) + 데이터 팩 파일

**Testing**: Mermaid 렌더링 오류 0건 + 기존 `./gradlew test` · `uv run pytest -q` · `npm test` · compose 스모크(새 테스트 코드 없음)

**Target Platform**: GitHub · IntelliJ · VS Code의 Markdown 미리보기, 로컬 배포(`127.0.0.1:8088`)에서의 시나리오 시연

**Project Type**: 웹 서비스 monorepo의 설계 문서(documentation feature)

**Performance Goals**: N/A — 실제 모델 성능 기준은 범위 밖(Clarification Q2)

**Constraints**: 코드 이름 · 수치가 코드와 일치해야 한다. 비밀값 · 정답 · 저작물 원문을 문서에 넣지 않는다(헌법 V)

**Scale/Scope**: 구조도 8개(렌더링 확인 완료), **검증 시나리오 2개 · 수용 기준 12개**(US1 5 + US2 7)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 게이트 | Phase 0 전 | Phase 1 후 |
|---|---|---|---|
| I. 승인 뒤 실행 | 검증 범위에 「제안 → 사람 승인 → 승인 ID로 1회 실행」이 들어 있는가 | ✅ US2 포함 | ✅ 추적표 US2-2 · 4 · 6 |
| II. 근거 기반 판단 | 결과에 근거 ID가 있고 확률 · 확정 · 처방이 없는지 검증하는가 | ✅ US1-3 · US2-7 | ✅ |
| III. 서버 계산 | 문서의 수치가 서버 계산 · 테스트 값과 같은가 | ⚠ z 값 README 간 불일치 | ✅ 서버 계산 −2.74(≈ −2.7) 확인 (R3) |
| IV. 계약 우선 | 공개 · 내부 API 구분과 변환 지점을 바르게 그렸는가 | ✅ 1.1 · 1.3 | ✅ 런타임 계약 변경 없음 |
| V. 정답 · 저작물 격리 | 문서에 정답 · 원문 · 키가 없는가 | ✅ | ✅ 개발용 고정 토큰 이름만(운영값 아님 명시) |
| VI. 공정 범위 권한 | 받는 사람 검증이 범위에 있는가 | ✅ US2-5 | ✅ 수신자 화면 권한(US3-2)은 보류 범위 — 자동 테스트로만 남는다 |
| VII. 결정적 검증 | 키 없이 검증 가능한가, 기존 품질 게이트를 통과하는가 | ✅ rule · hash | ✅ quickstart 1–4 |

위반 없음. 범위 축소로 원칙 VI의 화면 검증(수신자가 원 사건을 못 보는지)은 이번에 사람이 확인하지 않지만,
`InvestigationPermissionsApiTest`가 자동 검증을 계속 맡으므로 게이트는 통과로 본다.

## Project Structure

### Documentation (this feature)

```text
specs/001-architecture-user-scenarios/
├── spec.md                         # 시나리오 6개 · 요구사항 · 성공 기준 (변경 없음)
├── architecture.md                 # 기술 · 서비스 아키텍처 구조도 (정본, 전체 시스템)
├── plan.md                         # 이 파일 — 검증 범위 US1 · US2
├── research.md                     # Phase 0 — R7 범위 결정 추가
├── data-model.md                   # Phase 1 — US1 · US2가 쓰는 엔티티 표시
├── quickstart.md                   # Phase 1 — US1 · US2 시연 절차로 축소
├── contracts/
│   ├── architecture-doc.md         # architecture.md 필수 섹션 · 표기 규칙
│   └── scenario-traceability.md    # US1 · US2 = 이번 범위, US3–US6 = 보류 표시
├── checklists/requirements.md
├── tasks.md                        # US1 · US2 작업 21개 (/speckit-tasks)
└── verification.md                 # 검증 기록 (/speckit-implement)
```

### Source Code (repository root)

문서가 설명하는 대상(수정하지 않음):

```text
fab-context-web/          # React 프론트
fab-context-api/          # Spring backend — US1: events · incident · analysis · job / US2: investigation · approval · checkrequest · internal/job · internal/action
fab-agent/                # LangGraph Agent · Harness(guard · brief) · 업무/지식 MCP
compose.web.yaml · scripts/deploy.sh · fab-agent/compose.yaml   # 배포 구성
README.md                 # 아키텍처 문서 링크 한 줄 추가 대상 (R6)
```

**Structure Decision**: 정본 문서는 feature 디렉터리에 두고, 루트 README에는 링크만 더한다. 런타임 코드 변경은 없다.

## Phase 결과

- **Phase 0** — [research.md](./research.md): NEEDS CLARIFICATION 없음. 결정 7건(R1–R6 + **R7 검증 범위**)
- **Phase 1** — [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)
  - 사건 상태에 `WAITING_APPROVAL`은 없다(작업 상태) → data-model · architecture.md 2.2 정정(이전 회차)
  - Mermaid 8개 블록 렌더링 성공(mermaid-cli 11.4.2, 시스템 Chrome)
  - 시연 사건 배정: 스모크가 EV-1009를 소비하므로 US1 = EV-1009(스모크 전 화면 확인 또는 스모크 결과), US2 승인 = EV-1006, US2 거절 = EV-1007

## 후속 (tasks에서 다룰 것)

- ✅ `/speckit-tasks` 재실행 — US1 · US2만으로 작업 목록 재생성(기존 tasks.md 대체)
- ✅ 루트 `README.md`에 architecture.md 링크 추가 (R6)
- ✅ `fab-context-api/README.md` EV-1009 z 「≈ −2.8」 → 「≈ −2.7」 정정 (R3 — 서버 계산 −2.74)
- 다음 반복: US3(회신) → US4(보고서) 순으로 범위 확장
- ✅ 검증 실행 완료 — US1 5/5 · US2 7/7, [verification.md](./verification.md)

## Complexity Tracking

해당 없음.

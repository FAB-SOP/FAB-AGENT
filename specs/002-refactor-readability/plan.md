# Implementation Plan: 동작을 보존하는 코드 가독성 리팩터링

**Branch**: `feat/architecture` (feature dir `002-refactor-readability`) | **Date**: 2026-10-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-refactor-readability/spec.md` · **기준 커밋**: `ee171d2`

## Summary

기준 커밋 `ee171d2`의 실행 코드 전체를 **동작을 바꾸지 않고** 읽기 쉽게 정리한다 — 압축된 화면 코드 22개 파일 펼치기 ·
큰 화면 분리, 40줄 넘는 함수 22개(Python 13 · Java 9) 분리, Python 한두 글자 이름 432곳 정리. 먼저 일곱 가지 점검으로
동작 기준선을 잡고(테스트 3종 · 계약 대조 · 업무 흐름 스모크 · 재시작 스모크 · 화면 DOM 스냅샷), 구성요소별 작은 단계마다
빠른 점검, 구성요소를 마칠 때 전체 게이트를 돌려 기준선과 같을 때만 진행한다. 새 의존성 · 새 기능 · 새 테스트는 없다.

## Technical Context

**Language/Version**: TypeScript 5.9 · React 19(화면), Python 3.12(조사 보조자 · MCP · 스크립트), Java 21 · Spring Boot 4.1(업무 시스템)

**Primary Dependencies**: 기존 그대로 — 추가 없음(FR-006). 점검용으로만 로컬 npx 캐시의 `puppeteer-core` + 시스템 Chrome(저장소 의존성 아님)

**Storage**: 변경 없음 — PostgreSQL 스키마 · 데이터 팩 그대로

**Testing**: 기존 `./gradlew test`(109+2) · `uv run pytest -q`(39+2) · `npm test`(33) · compose 스모크(16 check) · 재시작 스모크 + DOM 스냅샷 비교(R3) + 계약 대조

**Target Platform**: 로컬 compose 배포(`./scripts/deploy.sh`) — 결정적 모드(rule · hash)

**Project Type**: 웹 서비스 monorepo(화면 · 업무 시스템 · Agent/MCP)

**Performance Goals**: 변경 없음(리팩터링이 응답 시간 · 폴링 주기를 바꾸지 않는다)

**Constraints**: 테스트 기대값 변경 0 · 보존 표면([contracts/preserved-surface.md](./contracts/preserved-surface.md)) 변경 0 ·
새 의존성 0 · 화면은 손으로 펼침(포매터 금지) · Java 분리는 같은 클래스 private 메서드로만

**Scale/Scope**: 화면 24파일 613줄(22파일 대상), Python 51+11파일 약 4,600줄(긴 함수 13 · 짧은 이름 40파일), Java 153파일 10,887줄(긴 메서드 9)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 게이트 | Phase 0 전 | Phase 1 후 |
|---|---|---|---|
| I. 승인 뒤 실행 | 승인 · 재개 경로의 동작 불변 | ✅ 스모크 · `test_no_change_before_approval_*` 기준선 | ✅ 게이트 4 · 5, `ApprovalService.decide` 검사 순서 유지(R7) |
| II. 근거 기반 판단 | Guard 규칙 · 결과 모양 불변 | ✅ | ✅ `guard.validate_result` 분리 시 검사 항목 · 순서 유지 |
| III. 서버 계산 | 계산 결과 불변 | ✅ | ✅ `runEvidence` · `SegmentDetector.detect` 분리 시 수치 테스트(`SegmentDetectorTest` 등) 기준선 |
| IV. 계약 우선 | 공개 · 내부 계약 불변 | ✅ 계약 대조 기준선 | ✅ preserved-surface · 게이트 3 |
| V. 정답 · 저작물 격리 | 정답 · 원문을 읽거나 옮기지 않음 | ✅ | ✅ 점검 도구는 저장소 내 공개 데이터만 사용 |
| VI. 공정 범위 권한 | 권한 검사 불변 | ✅ | ✅ `InvestigationPermissionsApiTest` 기준선 |
| VII. 결정적 검증 | 키 없이 검증 | ✅ rule · hash | ✅ quickstart 전 과정 키 불필요 |
| VIII. 동작 보존 리팩터링 | 기능 불변 · 기대값 불변 · 보존 이름 · 40줄 · 단일 책임 · 새 의존성 금지 | ✅ 명세 FR 전체 | ✅ gates · preserved-surface · R5–R7 규칙 |

위반 없음. 점검용 DOM 스냅샷 도구는 저장소 의존성을 늘리지 않으므로 원칙 VIII · FR-006에 해당하지 않는다(research R3).

## Project Structure

### Documentation (this feature)

```text
specs/002-refactor-readability/
├── spec.md · plan.md · research.md · data-model.md · quickstart.md
├── contracts/
│   ├── preserved-surface.md   # 바뀌면 안 되는 이름 · 모양 목록
│   └── gates.md               # 단계별 빠른 점검 · 전체 게이트 7항목
├── tools/                     # 점검 전용(제품 코드 아님) — tasks에서 작성
│   ├── metrics.py             # 가독성 지표(R4)
│   └── dom-snapshot.mjs       # 화면 DOM · 텍스트 스냅샷(R3)
├── records/                   # baseline · steps · metrics-before/after · exceptions · deferred · dom/ — 진행 중 생성
├── checklists/requirements.md
└── tasks.md                   # /speckit-tasks
```

### Source Code (repository root) — 리팩터링 대상

```text
fab-context-web/src/
├── App.tsx · main.tsx · auth.tsx
├── pages/        Workflow.tsx(2,711자 · 36줄 초과) · Login.tsx · Dashboard.tsx
├── components/   WorkItems · InvestigationContext · InvestigationConversation · SensorPanel · IncidentNotifications ·
│                 DemoPersonaSelect · ui · AnalysisRequestStatus · ActionWorkspace
├── api/          types · client · sensor-observation · incident-notifications · monitoring
└── domain/       actionSources · conversation
fab-agent/src/fab/
├── business/server.py(build_server 143) · rag/server.py(build_server 74) · rag/index.py(load_corpora)
├── agent/models/rule.py(ainvoke 113) · agent/brief.py(build) · agent/harness/guard.py(validate_result)
├── agent/graph/nodes/  required_evidence(69) · tools.save_proposal · finalize
└── devtools/  demo.evaluate · spring.drive
fab-context-api/src/main/java/com/fabcontext/api/
├── domain/  dashboard.DashboardService.get(66) · approval.ApprovalService.decide(65) · checkrequest.CheckRequestService.reply(64)
│            processdata.ProcessEvidenceService.runEvidence(56) · processdata.SegmentDetector.detect(52)
├── internal/  query.InternalProcessQuery.trace(58) · AgentEventService.handle(50)
├── contract/AgentResultMapper.toResult(47) · seed/SeedRunner.seed(57)
fab-agent/evaluation/ · data/build_pack_v2.py · tools/   — 스크립트(US5)
```

**Structure Decision**: 파일 이동 · 패키지 변경 없이 같은 파일(화면은 같은 폴더의 새 하위 컴포넌트 파일 허용) 안에서 정리한다.
화면의 큰 페이지(`Workflow.tsx` 등)는 같은 `pages/` · `components/` 폴더에 화면 단위 파일로 나눈다.

## Phase 결과

- **Phase 0** — [research.md](./research.md): NEEDS CLARIFICATION 없음. 결정 8건(기준선 구성 · 단계 단위 · DOM 스냅샷 ·
  지표 측정 · 화면 펼치기 규칙 · Python 규칙 · Java 규칙 · 순서)
- **Phase 1** — [data-model.md](./data-model.md)(진행 기록 엔티티), [contracts/](./contracts/)(보존 표면 · 게이트),
  [quickstart.md](./quickstart.md)(기준선 · 단계 점검 · 최종 확인)

## 위험과 대응

| 위험 | 대응 |
|---|---|
| JSX 공백 · 문구 · class 오타로 화면이 달라짐 | R5 규칙 + DOM 스냅샷 diff 0(게이트 6) |
| 컴포넌트 분리로 리마운트 · 입력 상태 초기화 | 계층 · key · 훅 순서 유지(R5) + 입력 중 상태 수동 확인 |
| 도구 등록 순서 · 설명 변경으로 모델 입력이 달라짐 | 등록 순서 유지(R6) + 도구 목록 출력 diff |
| 검사 순서 변경으로 다른 오류 코드 | R7 + API 테스트 |
| 트랜잭션 범위 변경 | 진입 메서드 유지 · private 분리만(R7) |
| 진행 중 팀 커밋과 충돌 | 구성요소 단위 진행, 새 커밋이 들어오면 그 구성요소 기준선만 재측정 |
| `fab-eval` 정답표 없음 | 스모크 판단 check · Agent 테스트로 대체(R1) — 한계로 기록 |

## Complexity Tracking

해당 없음.

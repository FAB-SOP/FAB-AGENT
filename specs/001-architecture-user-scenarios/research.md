# Research: FAB CONTEXT 아키텍처 정리 및 핵심 사용자 시나리오

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Date**: 2026-10-21

이 기능은 문서를 만드는 일이라 새로 고를 기술이 없다. 아래는 표기 방식 · 사실 출처 · 검증 방법에 대한 결정이다.

## R1. 구조도 표기 방식

- **Decision**: Mermaid(`flowchart` · `stateDiagram-v2` · `sequenceDiagram`)를 Markdown 안에 둔다.
- **Rationale**: GitHub · IntelliJ(`.idea` 사용 중) · VS Code가 바로 그려 주고, 텍스트라 코드와 같은 PR에서 diff로 리뷰할 수 있다.
  이미지 파일은 코드가 바뀌어도 함께 고치기 어렵다.
- **Alternatives considered**: PlantUML(별도 렌더러 필요), draw.io · FigJam(바이너리 · 외부 도구라 diff 리뷰 불가 — 발표용
  사본이 필요하면 Mermaid에서 내보낸다), C4-PlantUML(표기는 좋지만 GitHub 기본 렌더링 없음).

## R2. As-Is / To-Be 구분 표기 (Clarification Q1 반영)

- **Decision**: 예정 항목은 Mermaid `classDef planned stroke-dasharray: 5 5` 점선 노드 + 점선 간선(`-.->`) + 라벨 앞
  「예정 ·」 접두어. 상세는 표(architecture.md 1.6)에 현재 상태 · 예정 · 결정 대기 사유로 적는다.
- **Rationale**: 색만으로 구분하면 흑백 인쇄 · 다크 모드에서 사라진다. 선 모양과 글자 접두어를 같이 쓰면 어느 렌더러에서도 읽힌다.
  점선 간선(`-.->`)은 이미 「선택적 외부 연동(OpenAI)」에도 쓰이므로, 예정 여부는 노드의 「예정 ·」 접두어로 최종 판별한다.
- **Alternatives considered**: As-Is · To-Be 그림 분리(옵션 C — 사용자가 B를 선택), 색상만으로 구분(접근성 문제).

## R3. 사실의 출처와 우선순위

- **Decision**: 문서의 사실은 다음 순서로 확인한다 — ① 코드 · 설정(`compose.yaml`, `application.yml`, `graph/build.py`,
  패키지 구조) ② 테스트 단언값 ③ 컴포넌트 README ④ 루트 README. 서로 다르면 위쪽을 따른다.
- **Rationale**: README는 손으로 갱신되어 어긋날 수 있다. 실제로 EV-1009 Etch 제거량의 z가 `fab-agent/README.md`는 −2.7,
  `fab-context-api/README.md`는 −2.8이다. **실행 중인 서버가 계산한 값은 `amountZSamePm = −2.74`**(2026-10-21,
  `GET /api/incidents/{id}/comparisons?scope=UPSTREAM&processCode=ETCH`)이므로 「≈ −2.7」이 맞다.
  - 정정 이력: 처음에는 `test_agent_harness.py`의 `-2.76`을 근거로 −2.8이 맞다고 판단했으나, 그 값은 Guard 테스트가 만든
    **가짜 도구 결과 문자열**이었다. 테스트 원천은 서버 계산값이어야 한다는 교훈 — 출처 우선순위 ①(실행 결과 · 코드)을 테스트 예시보다 앞에 둔다
  - 후속 정정 대상은 `fab-context-api/README.md`(−2.8 → −2.7)이다
- **Alternatives considered**: README만 근거로 작성(빠르지만 어긋난 값을 그대로 옮김).

## R4. 확인한 주요 값

| 항목 | 값 | 출처 |
|---|---|---|
| 승인 만료 | 24시간(`approval-ttl: PT24H`) | `fab-context-api/src/main/resources/application.yml` |
| Agent 루프 상한 | 판단 8 · 같은 도구 3 · 활성 120초 · LLM 30초 · MCP 10초 | `fab-agent/src/fab/agent/harness/limits.py`(README 표) |
| 그래프 노드 | start · required_evidence · agent · request_brief · tools · approval_wait · reply_wait · finalize | `fab-agent/src/fab/agent/graph/build.py` |
| 공개 API | 41 operation | `backend-handoff-v2/combined.openapi.json`, `tools/openapi_diff.py` 결과 |
| 업무 MCP 도구 | 읽기 12 · 변경 3 | `fab-agent/src/fab/business/server.py` |
| 지식 | 원문 100 · 점검 문서 15 · 사례 7 (색인 122조각) | `fab-agent/README.md`, `fab.rag` |
| 배포 노출 | web `127.0.0.1:8088`만 | `compose.web.yaml` |
| EV-1009 Etch | 4896.3 Å, z = −2.74 | 실행 중 서버 `comparisons` 응답(2026-10-21) |

## R5. 문서 검증 방법

- **Decision**: (1) Mermaid 문법은 `npx -y @mermaid-js/mermaid-cli`(mmdc)로 블록마다 렌더링해 오류 0건을 확인한다 — 로컬에
  node · npx가 있고 mmdc는 설치되어 있지 않아 npx로 일회 실행한다. (2) 시나리오의 수용 기준은 기존 자동 테스트 · 스모크에
  대응시켜 추적표로 남긴다(contracts/scenario-traceability.md). (3) 시나리오는 compose 시연 환경에서 사람이 한 번 따라가 본다.
- **Rationale**: 문서 기능의 「테스트」는 렌더링 성공과 사실 일치다. 새 테스트 코드는 만들지 않는다(헌법 VII의 게이트는 기존 테스트로 충족).
- **Alternatives considered**: 문서 lint CI 추가(이번 범위에서는 과함 — 후속 제안), 스크린샷 비교(유지 비용 큼).

## R6. 문서 위치와 연결

- **Decision**: 정본은 `specs/001-architecture-user-scenarios/architecture.md`에 두고, 루트 `README.md` 「저장소 구성」 위
  아키텍처 그림 아래에 링크 한 줄을 더한다.
- **Rationale**: README의 ASCII 그림은 한 줄 요약으로 유지하고 상세는 한 곳에만 둔다(중복 갱신 방지).
- **Alternatives considered**: `docs/architecture.md` 신설(spec-kit 산출물과 이중 관리), README에 전부 넣기(README가 이미 15KB).

## R7. 검증 범위 — 핵심 시나리오 2개 (2026-10-21 사용자 요청)

- **Decision**: US1(이상 사건 자동 접수와 기본 분석) · US2(추가 질문 · 제안 · 승인)만 이번에 계획 · 검증한다. US3 · US4는
  다음 반복, US5 · US6은 보류. spec.md의 시나리오와 요구사항은 지우지 않는다.
- **Rationale**: 사용자가 US1–US4 가운데 가장 핵심 기능 2개만 요청했다. US1은 모든 흐름의 전제(MVP)이고, US2는 헌법 원칙 I
  「승인 뒤 실행」을 직접 보여 주는 서비스 고유 기능이다. 두 시나리오는 서로 다른 사건(EV-1009 / EV-1006 · EV-1007)으로
  독립 검증할 수 있다. US2가 「승인 → 요청 1건 생성」까지 덮으므로 협업 루프의 핵심 안전장치는 범위 안에 있다.
- **Alternatives considered**:
  - US2 + US3(협업 루프 전체) — 회신 흐름까지 보이지만 출발점(US1)이 빠지면 결과 구조 · 근거 ID 검증이 비고, US3은 US2에 의존해 독립성이 낮다
  - US1 + US4(조사 → 보고서) — 결과물은 보이지만 서비스 차별점인 승인 게이트가 빠진다
- **영향**: 원칙 VI의 화면 검증(수신자의 원 사건 접근 차단)은 사람이 확인하지 않고 자동 테스트(`InvestigationPermissionsApiTest`)에만
  맡긴다. SC-006(브리프만으로 이해) · SC-007(보고서까지 한 세션)은 이번에 측정하지 않는다.

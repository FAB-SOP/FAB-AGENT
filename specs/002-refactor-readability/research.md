# Research: 동작을 보존하는 코드 가독성 리팩터링

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Date**: 2026-10-21 · **기준 커밋**: `ee171d2`

새 기술을 고르는 작업이 아니다. 아래는 「동작을 바꾸지 않았다」를 어떻게 증명하고, 어떤 규칙으로 정리할지에 대한 결정이다.

## R1. 동작 기준선의 구성

- **Decision**: 기준 커밋에서 다음 일곱 가지를 기록한다 — ① backend 테스트(통과 109 · 건너뜀 2) ② Agent 테스트(통과 39 ·
  건너뜀 2) ③ 화면 테스트(33) + 화면 빌드 성공 ④ 공개 API 계약 대조 결과 표(41 operation, 차이 2종) ⑤ compose 스모크
  16 check ⑥ 재시작 스모크 ⑦ 화면 DOM 스냅샷(R3). 수치는 2026-10-21 `001` 검증 기록을 출발점으로, 기준 커밋에서 다시 잰다.
- **Rationale**: 헌법 VIII · 품질 게이트가 요구하는 항목에 화면의 「보이는 것」을 더했다. 결정적 판단 평가(`fab-eval`)는
  평가자 전용 정답표가 저장소에 없어 실행할 수 없다 — 대신 스모크의 「1순위 원인 = ETCH」 check와 Agent 테스트가 판단
  배선을 덮는다.
- **Alternatives considered**: 실제 모델로 비교(비결정적이라 기준선으로 쓸 수 없음), 코드 커버리지 측정 추가(새 의존성 — FR-006 위반).

## R2. 단계 단위와 되돌림

- **Decision**: 한 단계 = 한 구성요소의 파일 한 묶음(대개 파일 1–3개). 단계마다 해당 구성요소의 빠른 점검(테스트 · 빌드)을
  돌리고, 구성요소를 마칠 때 전체 게이트(R1 일곱 가지)를 돌린다. 단계가 실패하면 그 단계 변경만 되돌린다.
- **Rationale**: 작게 나눠야 「어느 변경이 동작을 바꿨는지」를 바로 찾는다. 전체 게이트(스모크 · 재시작)는 수 분이 걸려
  매 단계에 돌리기엔 비싸다.
- **Alternatives considered**: 파일 하나마다 전체 게이트(너무 느림), 구성요소 전체를 한 번에(실패 시 원인 추적 불가).

## R3. 화면의 「보이는 것」 비교 — DOM 스냅샷

- **Decision**: 리팩터링 전후로 같은 계정 · 같은 시드 데이터에서 라우트 전부(`/login` · `/dashboard` · `/incidents` ·
  `/incidents/:id` · `/work-items` · `/approvals` · `/check-requests` · `/reports` · `/reports/:id` · `/knowledge`, 계정별)를 열어 `document.body.innerText`와 정규화한 DOM(태그 · class · 속성, 시각 · ID 등 변하는
  값 제거)을 저장하고 diff가 0인지 본다. 도구는 이미 로컬 npx 캐시에 있는 `puppeteer-core` + 시스템 Chrome을 쓰는 점검
  스크립트이며, 저장소의 화면 프로젝트 의존성에는 넣지 않는다(`specs/002-refactor-readability/tools/`).
- **Rationale**: 화면은 모의 API 모드가 없고(`API_MODE='real'`) 자동 화면 테스트가 5개 파일뿐이다. 손으로 펼칠 때 가장
  흔한 사고인 **JSX 공백 변화**(R5)와 class 이름 오타는 텍스트 · DOM 비교로만 확실히 잡힌다.
- **Alternatives considered**: 스크린샷 픽셀 비교(폰트 렌더링 차이로 오탐), 사람 눈으로만 확인(공백 하나는 놓친다),
  화면 프로젝트에 Playwright 추가(FR-006 위반).

## R4. 가독성 지표 측정

- **Decision**: 측정 스크립트(`specs/002-refactor-readability/tools/metrics.py`) 하나로 ① 화면 파일별 가장 긴 줄 · 160자
  넘는 줄 수 ② Python 함수 길이(ast) ③ Java 메서드 길이(중괄호 짝) ④ Python 한두 글자 할당 이름 수(허용: `i j k v e _`)를
  잰다. 기준 커밋 값: 화면 최대 2,711자(글자 수 기준) · 160자 초과 파일 22개, 40줄 초과 함수 22개(Python 13 · Java 9), 짧은 이름 432곳.
- **Rationale**: 명세 SC-006 · 007 · 008을 같은 방법으로 전후 비교해야 한다. 측정 방법이 바뀌면 숫자가 의미 없다.
- **Alternatives considered**: 정적 분석 도구(radon · checkstyle 등 — 새 의존성).

## R5. 화면을 손으로 펼치는 규칙 (Clarification Q2 반영)

- **Decision**:
  - 표기는 이미 펼쳐진 화면 파일(`AnalysisRequestStatus.tsx` · `questionThreads.ts` 등)을 따른다 — 1칸 들여쓰기, 홑따옴표,
    세미콜론, 연산자 주변 공백 없음. 한 줄 160자 이하, 한 줄에 JSX 요소 · 문장 하나.
  - **JSX 공백 보존**: 줄을 나누는 위치는 태그 사이(`>` 와 `<` 사이)로 하고, 텍스트와 인라인 요소가 붙어 있던 곳은 붙인
    채로 둔다. 원래 공백이 있던 텍스트 · 요소 경계에서 줄을 나눠야 하면 `{' '}`를 명시한다.
  - **문자열 그대로**: 화면 문구 · `className` · `aria-*` · `placeholder` · 라우트 경로 문자열은 한 글자도 바꾸지 않는다.
  - **컴포넌트 분리**: 펼치기와 분리는 다른 단계로 한다. 분리할 때 컴포넌트 계층 · `key` · 훅 호출 순서 · `lazy` 경계를
    유지하고, 렌더 함수 안에서 정의되던 컴포넌트는 같은 동작(매 렌더 새 정의 여부)을 바꾸지 않는 위치에만 둔다.
- **Rationale**: 손으로 펼칠 때 동작이 바뀌는 대표 경로가 JSX 공백 · 문자열 오타 · 리마운트다.
- **Alternatives considered**: 포매터 사용(Clarification Q2에서 B 선택으로 제외).

## R6. Python 함수 분리 · 이름 규칙

- **Decision**:
  - 분리는 같은 모듈 안의 `_`로 시작하는 보조 함수로 한다. 공개 함수 이름 · 시그니처 · 반환 모양은 그대로.
  - **바꾸지 않는 이름**: LangGraph 노드 이름(`graph/build.py`의 문자열 — 체크포인트에 저장), MCP 도구 함수 이름 ·
    docstring(모델이 읽는 도구 설명), 이벤트 `type` 값, 환경 변수 이름, trace에 남는 문자열.
  - `build_server`(업무 MCP 143줄 · 지식 MCP 74줄)는 데코레이터로 도구를 등록하는 구조라 「읽기 도구 등록」 · 「변경 도구
    등록」 보조 함수로 나누되, 등록 순서(도구 목록 순서)를 유지한다 — 도구 목록 순서가 모델 입력에 들어간다.
  - 짧은 이름은 담는 값으로 바꾼다(`r` → `response`/`run` 등 맥락별). 허용: `i j k v e _`와 컴프리헨션 안의 한 글자 변수
    중 범위가 한 줄인 것.
- **Rationale**: 도구 설명 · 순서와 노드 이름은 모델 행동 · 재개에 직접 영향을 준다.
- **Alternatives considered**: 도구 등록을 별도 모듈로 이동(import 순서 · 등록 시점이 바뀔 위험).

## R7. Java 메서드 분리 규칙

- **Decision**: 같은 클래스의 `private` 메서드로만 분리한다. `@Transactional`이 붙은 메서드(`ApprovalService.decide` ·
  `CheckRequestService.reply` · `DashboardService.get`)는 진입점으로 남겨 트랜잭션 범위를 그대로 두고, 예외 종류 · 메시지 ·
  검사 순서(어떤 오류가 먼저 나는지)를 바꾸지 않는다. 다른 bean으로 옮기지 않는다(프록시 경계가 바뀐다).
- **Rationale**: 검사 순서가 바뀌면 같은 요청에 다른 오류 코드(409 vs 422 등)가 나가 계약이 바뀐다.
- **Alternatives considered**: 서비스 클래스 분할(패키지 의존 · 트랜잭션 경계 변경 위험 — 이번 범위 밖).

## R8. 순서

- **Decision**: 기준선 → 화면(펼치기 → 분리) → 조사 보조자 · MCP(함수 분리 → 이름) → 업무 시스템 → 스크립트 → 최종 지표.
- **Rationale**: 명세 우선순위(US1 → US2 → US3/US4 → US5)를 따르고, 구성요소별로 끝내 다른 팀원 커밋과 충돌을 줄인다.
  진행 중 팀 기능 커밋이 들어오면 그 구성요소의 기준선만 다시 잡는다(spec Assumptions).

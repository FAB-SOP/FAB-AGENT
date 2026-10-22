<!--
Sync Impact Report
- Version change: 1.0.0 → 1.1.0 (MINOR — 원칙 1개 추가, 기존 원칙 의미 변경 없음)
- Modified principles: 없음 (I–VII 그대로)
- Added principles:
  - VIII. 동작을 보존하는 리팩터링 · 읽기 쉬운 코드
- Modified sections:
  - 개발 워크플로 · 품질 게이트 — 리팩터링 커밋의 추가 게이트(계약 대조 동일 · 테스트 기대값 불변)
  - Governance — Constitution Check 범위 I–VII → I–VIII
- Removed sections: 없음
- Templates requiring updates:
  - ✅ .specify/templates/plan-template.md — Constitution Check가 헌법에서 게이트를 읽는 구조라 수정 불필요
  - ✅ .specify/templates/spec-template.md — 필수 섹션 변경 없음
  - ✅ .specify/templates/tasks-template.md — 리팩터링 작업은 기존 Polish 단계 「Code cleanup and refactoring」에 들어감
  - ✅ .claude/skills/speckit-*/SKILL.md — 특정 에이전트 전용 참조 없음
  - ✅ README.md — 「협업 규칙」과 충돌 없음(변경 불필요)
- Follow-up TODOs: 없음
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

- **공개 API = 프론트 계약**(`backend-handoff-v2/combined.openapi.json`), **내부 API · Agent 이벤트 =
  팀 계약**(`fab-agent/contracts/`). operationId · 메서드 · 경로는 프론트 계약과 1:1이어야 한다.
- 두 계약 사이의 이름 · 모양 변환은 Spring `contract/` 패키지 한 곳에서만 한다.
- 계약을 바꾸는 변경은 `fab-agent/docs/contract-changes.md`에 기록하고 계약 주인과 확인한 뒤 반영한다.
  `tools/openapi_diff.py` 대조 결과에서 의도하지 않은 차이가 없어야 한다.
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
  서버가 검사한다. 상태 전이(보류 · 종료)는 MANAGER만 할 수 있다.
- 다른 공정 담당자 · 설비정비팀은 사건 전체가 아니라 **자신에게 배정된 요청과 그 브리프**로만 협업한다.
- 제안의 받는 사람은 backend가 검증한다 — 앞 공정 확인은 그 공정의 PROCESS_OWNER에게만, 정비 요청은
  설비정비팀에게만, 질문한 본인에게는 보낼 수 없다.
- 프론트의 권한 표시는 편의일 뿐이며 모든 쓰기는 서버에서 다시 검사한다.

**근거**: 공정별 책임 경계가 실제 FAB 조직의 협업 방식이며, 과도한 공개는 보안 · 책임 문제를 만든다.

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

## 기술 · 데이터 제약

- **구성**: React 프론트(`fab-context-web`) → Spring backend 하나(`fab-context-api`, Java 21 · Spring Boot) ←
  업무 MCP · 지식 MCP · Agent Worker(`fab-agent`, Python 3.12 · LangGraph · FastMCP). backend는 하나뿐이다.
- **DB**: 실행 · 테스트 모두 PostgreSQL(pgvector). 스키마는 `backend` · `agent`(체크포인트) · `knowledge`(벡터)로
  나눈다. H2 등 대체 DB를 쓰지 않는다. 스키마를 바꾸면 반드시 재시드한다.
- **데이터 팩**: `data/fab-causal-pack-v2`(dataVersion `causal-pack-v2-scenarios`)가 유일한 운전 데이터 원본이며
  `data/build_pack_v2.py`로 결정적으로 재생성한다. 경로(`FAB_PACK_DIR` · `FAB_RAG_DIR`)에는 코드 기본값을 두지 않는다.
- **패키지 의존**: Spring `domain`은 `internal` · `simulator` · `seed`를 모른다. 패키지 순환은 허용하지 않는다
  (`tools/java_pkgdeps.py`가 「순환: 없음」).
- **배포**: 로컬 compose가 기준이며 웹만 외부 포트를 연다. 외부 공개 시 HTTPS · 운영 인증정보를 별도로 설정한다.

## 개발 워크플로 · 품질 게이트

- 작업 하나는 다음 검증을 통과한 뒤 커밋한다: `fab-context-api` `./gradlew test`, `fab-agent` `uv run pytest -q`,
  `fab-context-web` `npm test && npm run build`, compose 스모크(질문 → 제안 → 승인 → 실행 → 회신 → 보고서) 실패 0건.
- 공개 API를 바꾸면 `tools/openapi_diff.py`, Spring 패키지를 바꾸면 `tools/java_pkgdeps.py`를 돌린다.
- 승인 · 권한 · 계약 · 정답 격리에 닿는 변경은 해당 원칙을 지키는지 테스트로 보여야 한다
  (예: 승인 전 변경 0건, 같은 승인 중복 실행 1건, 다른 공정 사용자 403).
- 리팩터링 커밋은 위 검증에 더해 `tools/openapi_diff.py` 결과가 리팩터링 전과 같고, 테스트 파일의 기대값을
  바꾸지 않았음을 보여야 한다(원칙 VIII).
- 커밋 메시지는 「무엇을 · 왜」를 한 줄로 쓴다. `.env` · 원문 · 정답 파일은 커밋하지 않는다.
- 검증 결과와 날짜는 README의 「검증 현황」에 갱신한다.

## Governance

- 이 헌법은 저장소의 다른 관행보다 우선한다. README · 컴포넌트 README · `contract-changes.md`는 이 헌법과
  충돌해서는 안 되며, 충돌이 발견되면 문서를 고치거나 헌법 개정을 제안한다.
- **개정 절차**: 개정안은 변경 이유 · 영향받는 컴포넌트 · 이행 방법을 적어 팀 리뷰를 거친다. 원칙 I · V
  (NON-NEGOTIABLE)의 완화는 팀 전원 합의가 필요하다.
- **버전 정책**: MAJOR — 원칙 삭제 · 의미 재정의, MINOR — 원칙 · 섹션 추가 또는 지침의 실질적 확장,
  PATCH — 문구 · 오탈자 · 비의미적 정리.
- **준수 검토**: 모든 PR 리뷰는 원칙 준수를 확인한다. `/speckit-plan`의 Constitution Check는 원칙 I–VIII을
  게이트로 사용하고, 위반은 Complexity Tracking에 정당화를 남겨야 한다.
- 실행 지침은 `README.md`(전체) · `fab-agent/README.md` · `fab-context-api/README.md`를 따른다.

**Version**: 1.1.0 | **Ratified**: 2026-10-21 | **Last Amended**: 2026-10-21

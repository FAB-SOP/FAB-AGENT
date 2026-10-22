# Contract: 바뀌면 안 되는 표면

**Feature**: [spec.md](./spec.md) FR-004 · **Date**: 2026-10-21 · **기준 커밋**: `ee171d2`

리팩터링 전후로 아래 항목은 **한 글자도** 같아야 한다. 리뷰어는 diff에서 이 목록에 닿는 변경이 있는지 먼저 본다.

## 업무 시스템 (Spring)

| 항목 | 확인 방법 |
|---|---|
| 공개 API 41 operation의 경로 · 메서드 · operationId · 요청/응답 스키마 | `tools/openapi_diff.py` 결과 표가 기준선과 같음 |
| 내부 API `/internal/**` 경로 · 응답 `{data:{data,evidenceRefs,dataVersion,warnings,synthetic}}` · 오류 `{error:{code,message,hint,retryable}}` | `InternalApiTest` · 업무 MCP 테스트 · 스모크 |
| 오류 코드 · 메시지 · **검사 순서**(같은 요청이 같은 오류를 받음) | API 테스트 전부 |
| SSE 이벤트 이름 12종 | `IncidentApiTest` · 화면 알림 테스트 |
| DB 테이블 · 컬럼 · 외래 키 이름 | `SchemaTest` |
| `@Transactional` 경계 | 진입 메서드 유지(R7), 리뷰 |
| 패키지 의존 방향 · 순환 없음 | `tools/java_pkgdeps.py` |

## 조사 보조자 · MCP (Python)

| 항목 | 확인 방법 |
|---|---|
| LangGraph 노드 이름 8개(`start` · `required_evidence` · `agent` · `request_brief` · `tools` · `approval_wait` · `reply_wait` · `finalize`) | `graph/build.py` diff 없음, 재시작 스모크 |
| 업무 MCP 도구 17개(읽기 14 · 변경 3) · 지식 MCP 도구 3개 + 리소스 템플릿 2개의 이름 · 인자 · docstring · **등록 순서** | `test_business_tool_catalog_follows_pack_contract` · `test_knowledge_search_tools_and_resources` + 도구 목록 출력 diff |
| Harness 도구 스키마(제안 2 · 제출) | `tools/definitions.py` diff 없음 |
| 이벤트 `type` 값 · job-message 모양 | `test_events_and_jobs_follow_team_contract` |
| 프롬프트 파일(`prompts/*.md`) | 대상 아님 — diff 없음 |
| 환경 변수 이름 · 기본값 | `common/env.py` · compose diff 없음 |
| trace 레코드 키 | `harness/trace.py`의 키 문자열 유지 |

## 화면 (React)

| 항목 | 확인 방법 |
|---|---|
| 화면 문구 · `className` · `aria-*` · `placeholder` · `title` · 라우트 경로 | DOM 스냅샷(R3) diff 0 |
| 텍스트 사이 공백 | `innerText` 스냅샷 diff 0 |
| 컴포넌트 계층 · `key` · 훅 순서 · `lazy` 경계 | 리뷰 + 입력 중 상태 유지 수동 확인 |
| API 호출 경로 · 헤더(CSRF · Idempotency-Key) · 폴링 주기(5초 알림) | 화면 테스트 + 스모크(웹 경유) |

## 스크립트

| 항목 | 확인 방법 |
|---|---|
| `data/build_pack_v2.py` 결과 | 재생성 후 `SHA256SUMS.txt` 동일 |
| 평가 · 점검 스크립트 출력 형식 | 같은 입력으로 실행해 출력 diff 0 |

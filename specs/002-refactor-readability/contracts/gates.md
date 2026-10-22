# Contract: 단계 점검과 전체 게이트

**Feature**: [spec.md](./spec.md) FR-001 · FR-002 · **Date**: 2026-10-21

## 빠른 점검 (단계마다, 해당 구성요소만)

| 구성요소 | 명령 | 통과 조건 |
|---|---|---|
| 화면 | `cd fab-context-web && npm test && npm run build` | 33 통과 · 빌드 성공 · 타입 오류 0 |
| 화면 (펼치기 · 분리 단계) | DOM 스냅샷 다시 찍기 → 기준선과 diff | diff 0 |
| 조사 보조자 · MCP | `cd fab-agent && uv run pytest -q` | 39 통과 · 2 건너뜀 |
| 업무 시스템 | `cd fab-context-api && ./gradlew test` | 109 통과 · 2 건너뜀 |
| 스크립트 | 해당 스크립트를 같은 입력으로 실행 | 출력 diff 0 |

## 전체 게이트 (구성요소를 마칠 때 · 최종)

1. 위 빠른 점검 세 구성요소 모두
2. `python3 tools/java_pkgdeps.py fab-context-api/src/main/java` → 「순환: 없음」
3. 계약 대조(`quickstart.md` 2) → 기준선 표와 같음
4. 처음 시드된 배포에서 compose 스모크 → 16 check ✔ · 실패 0
5. 재시작 스모크(`restart_smoke.py before` → `restart agent` → `after`) → 실패 0
6. DOM 스냅샷 전 경로 → 기준선과 diff 0
7. `git diff ee171d2 -- '*/test/*' '*/tests/*'` → 테스트 기대값 변경 0(이름만 바뀐 경우 리뷰에 근거)

하나라도 다르면 그 구성요소의 마지막 단계부터 되돌려 원인을 찾는다.

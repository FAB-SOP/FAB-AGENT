# 동작 기준선 (기준 커밋 ee171d2)

| 항목 | 명령 | 결과 | 메모 |
|---|---|---|---|
| 기준 커밋 | `git rev-parse --short HEAD` | `ee171d2` (브랜치 `refactor/readability`) | 2026-10-21 |
| backend 테스트 | `./gradlew test` | 122 중 120 통과 · 2 건너뜀 · 실패 0 | 건너뜀: `KnowledgeApiTest`(원문 조각 없음). 명세 작성 시 109였으나 그 사이 팀 커밋으로 증가 |
| Agent 테스트 | `uv run pytest -q` | 46 통과 · 2 건너뜀 | 건너뜀: 정답표(`test_agent_harness.py:31`) · 원문 조각(`test_mcp_servers.py:108`) |
| 화면 테스트 · 빌드 | `npm test && npm run build` | 5 파일 · 33 통과 · 빌드 성공 | |
| 배포 | `DEPLOY_PROJECT_NAME=fab-refactor ./scripts/deploy.sh up -d --build --wait` | 6개 서비스 healthy | 사용자 요청으로 기존 `fab-deploy` 볼륨은 건드리지 않고 별도 프로젝트 · 볼륨(`fab-refactor_pgdata`)에서 처음 시드 |
| DOM 스냅샷 | `dom-snapshot.mjs before` | 40화면 · 80파일, 재촬영 diff 0(재현성 ✅) | Agent 기본 분석이 끝날 때까지 기다린 뒤 촬영 |
| MCP 도구 표면 | `tools/mcp-tools.py` → `mcp-tools-before.json` | 업무 17(읽기 14 · 변경 3) · 지식 3 + 리소스 템플릿 2 | |
| 공개 API 계약 | `openapi_diff.py` → `openapi-before.txt`, 원문 `api-docs-before.json` | 프론트 51 · 서버 51 · 공통 51 | 리팩터링 후 `api-docs` JSON 전체가 같아야 함 |
| compose 스모크 | `smoke` → `smoke-before.txt` | ⚠ 1 ✔ · 1 ✘ — 「질문 → WAITING_APPROVAL」 기대, 실제 SUCCEEDED | **기준선에서 이미 실패**(제안이 생기지 않음). 보류 목록 D1. 리팩터링 후 같은 출력이어야 함 |
| 재시작 스모크 | `restart_smoke.py before` → `restart-before.txt` | ⚠ IndexError(승인 목록 빔) | 같은 원인 — D1 |
| 패키지 의존 | `java_pkgdeps.py` → `pkgdeps-before.txt` | ⚠ 순환 1개 `domain.action · domain.checkrequest · domain.work` | **기준선에서 이미 존재**. 보류 목록 D2. 리팩터링 후 같은 목록(새 순환 없음) |
| 데이터 팩 | `python3 data/build_pack_v2.py` | 검사 통과 · `git status data/` 변경 0 | |
| 가독성 지표 | `tools/metrics.py` → `metrics-before.txt` | 화면 최대 2,711자 · 160자 초과 파일 22, Python 40줄 초과 13, Java 9, 짧은 이름 432 | 글자 수 기준(명세의 2,964는 바이트로 잰 값 — 정정함) |

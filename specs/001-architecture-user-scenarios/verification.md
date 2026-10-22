# 검증 기록

**날짜**: 2026-10-21 · **커밋**: `c2af82a` (+ 작업 트리의 specs · .specify 변경) · **모드**: `FAB_LLM=rule` · `EMBEDDER=hash`
(`deploy/.env.example` 기본값) · **배포**: `./scripts/deploy.sh` (프로젝트 `fab-deploy`, 새 볼륨에서 첫 시드)
**범위**: US1 기본 분석 · US2 제안 · 승인 ([plan.md](./plan.md), research R7)

화면 확인은 브라우저 대신 **웹과 같은 공개 API 경로**(`http://127.0.0.1:8088/api/**`, 세션 쿠키 + CSRF)를 사람 계정으로 호출해
수행했다 — 화면이 부르는 API와 같은 응답을 본다. 화면 렌더링 자체(버튼 · 표시 문구)는 이번에 사람이 보지 않았다.
검증 스크립트는 저장소 밖(세션 scratchpad)에 두었고 런타임 코드는 바꾸지 않았다.

## 자동 검증

| 항목 | 명령 | 결과 | 메모 |
|---|---|---|---|
| 배포 기동 (T002) | `./scripts/deploy.sh up -d --build --wait` | ✅ 6개 서비스 healthy | Compose v5.2.0에서도 `!override` 동작. Docker Desktop을 켠 뒤 실행 |
| backend 테스트 (T003) | `./gradlew test` | ✅ 111개 중 109 통과 · 2 건너뜀 · 실패 0 | README 「검증 현황」의 105개보다 늘었다(README 갱신 필요 — 후속) |
| Agent 테스트 (T004) | `uv run pytest -q` | ✅ 39 통과 · 2 건너뜀 | 건너뜀 2 = 정답표 · 원문 조각이 없는 로컬(예상대로) |
| 프론트 테스트 (T005) | `npm ci && npm test` | ✅ 5 파일 · 33 테스트 통과 | `analysis-request-status` · `incident-notifications` 포함 |
| compose 스모크 (T006) | `./scripts/deploy.sh --profile tools run --rm --build --no-deps smoke` | ✅ 16개 check 모두 ✔, 실패 0건 | EV-1009 질문 → 제안 → 승인 → 실행 → 회신 → 보고서 |
| 패키지 순환 (T007) | `python3 tools/java_pkgdeps.py fab-context-api/src/main/java` | ✅ 「순환: 없음」, `domain` → internal · simulator · seed 의존 0 | architecture.md 1.3과 일치 |
| Agent 그래프 (T007) | `graph/build.py` 노드 · 간선 | ✅ architecture.md 1.4 상태도와 일치 | |
| 노출 포트 (T007) | `compose.web.yaml` | ✅ web `127.0.0.1:8088`만 | 1.5와 일치 |
| Kafka (T007) | `worker/sources/kafka.py`, Spring 주석 | ✅ Worker 쪽만, Spring은 「Kafka 전 임시 HTTP」 | 1.6과 일치 |
| 공개 API 계약 (T007) | `tools/openapi_diff.py` | ✅ 41 · 41 · 공통 41, 차이는 `MAINTENANCE_REQUEST`와 comparisons뿐 | 배포 구성의 Nginx는 `/api/**`만 넘겨 `:8088/v3/api-docs`가 안 열린다 → 컨테이너 안에서 받아 대조(quickstart 수정) |
| Mermaid (T008) | mermaid-cli 11.4.2 | ✅ 8/8 | |

## US1

| 기준 | 검증 방법 | 결과 | 메모 |
|---|---|---|---|
| 1 이벤트 → 사건 생성 + 기본 분석 대기열 | `EventApiTest.newEventCreatesIncidentAndQueuesBasicAnalysis` (T003) | ✅ | |
| 2 같은 이벤트 ID → 기존 사건 | `EventApiTest.duplicateEventReturnsExistingIncident` (T003) | ✅ | |
| 3 사실 · 후보 · 한계, 근거 ID, 확률 · 확정 없음 | `cmp.park` · EV-1009 결과 이력의 BASIC 결과 | ✅ | 사실 3 · 후보 1 · 한계 2, 근거 없는 사실 · 후보 0. 1순위 「앞 공정 ETCH-PM2의 제거량 변화 기여 — 부품 원인 미확인」(지지 `E-RUN-…-ETCH` · `E-CHG-ETCH-PM2` · `E-PB-DEMO-ETCH_WALL`, 반대 `E-RUN-…-CMP`). 「확률」은 한계 「…확률이 아닙니다」의 부정 문장에만. 경로 CVD-PM1(z 1.16) → ETCH-PM2(4896.3 Å, z −2.74) → CMP-PM3 |
| 4 실시간 갱신 · 끊김 복구 | EV-1006 사건 스트림 구독 + 기본 분석 재실행 + `restart web` | ✅ | 재실행 중 `snapshot` · `job.status`×3 · `analysis.result` 수신. web 재시작 시 스트림 끊김 감지 → 재구독에서 `snapshot`, `getJob` = SUCCEEDED. 규칙 모드가 빨라 재시작 시점에 작업이 이미 끝나 있었다(진행 중 복구는 아님 — 메모) |
| 5 분석 시점 이후 데이터 제외 | `InternalApiTest.analysisTimeAndBaselineRulesAreEnforced` (T003), `test_errors_are_readable_and_followup_is_blocked` (T004) | ✅ | |

## US2

| 기준 | 검증 방법 | 결과 | 메모 |
|---|---|---|---|
| 1 질문 → 근거에 맞는 제안 종류 · 대상 | EV-1006 질문 「헤드 압력 이상이 두께에 영향을 줬는가? …」 + 스모크(EV-1009) | ✅ | EV-1006 → `MAINTENANCE_REQUEST`, 받는 사람 `USR-MNT-01`(설비정비팀), 대상 CMP-PM3. EV-1009 → Etch 담당자 확인 요청(스모크). 규칙 모델 동작 우려(tasks T012)는 해소 |
| 2 승인 전 요청 0건 | EV-1006 보낸 요청 목록, 스모크 | ✅ | 승인 전 0건 |
| 3 내용 변경 · 만료 · 선결정 시 거부 | `ApprovalApiTest.decisionGuardsVersionHashStateAndScope` · `expiredApprovalCannotBeExecuted` (T003) + EV-1006 재승인 | ✅ | 이미 승인된 제안 재결정 → 409 `APPROVAL_NOT_PENDING` 「이미 처리되었거나 만료된 제안입니다. 최신 내용을 다시 조회하세요.」 |
| 4 승인 내용 그대로 한 번만 실행 | EV-1006 승인 → 재개 → 요청 상세(`mnt.jung`) | ✅ | 승인 응답 `executionRef` 없음(기록만) → RESUME 작업 WAITING_REPLY → 요청 1건(`MAINTENANCE`, CMP-PM3), 요청의 `brief` · `causeIds`가 승인 payload와 **완전히 같음**. 재승인 뒤에도 1건 |
| 5 받는 사람 검증 | `InternalApiTest.proposalRecipientIsCheckedByBackend` (T003) | ✅ | |
| 6 거절 → 재개 · 정리 | EV-1007 질문 → 제안 거절(사유 「현장에서 이미 점검 중」) | ✅ | RESUME 작업 SUCCEEDED, 요청 0건, 한계에 「요청이 거절되어 담당자 확인 없이 정리했습니다.」, 새 질문 202. 규칙 모드 스모크에는 거절 경로가 없다(`SMOKE_MODE=real`에서만) — 추적표 수정 |
| 7 브리프 원인 「확정 아님」 · 처방 없음 | EV-1006 브리프 + `briefCannotConfirmACause` · `test_action_hash_covers_brief` | ✅ | `suspectedCauses` = [CMP_PRESSURE, confirmed=false], 확인 항목 3개, 설정값 변경 표현 0건. 편차: CMP-PM3 제거량 4749.0 Å z −22.03(정상 밖), CVD · ETCH 정상 안 |

**결과: US1 5/5 · US2 7/7 통과.**

## 후속

- **z 값 정정 번복**: 처음 계획에서 EV-1009 Etch z를 −2.8로 「정정」했으나, 실행 중 서버 계산은 **−2.74(≈ −2.7)**였다. 근거로 쓴
  −2.76은 Guard 테스트의 가짜 도구 결과 문자열이었다. spec · research · plan · tasks를 −2.7로 되돌렸고, 고칠 곳은
  `fab-agent/README.md`가 아니라 `fab-context-api/README.md`(−2.8)다 → T017에서 정정
- README 「검증 현황」의 backend 테스트 수(105) · pytest 수(38)가 실제(111 중 109 통과 · 39 통과)와 다르다 — 팀이 README를 갱신할 때 반영 제안(이번 범위에서는 손대지 않음)
- 배포 구성(`:8088`)에서는 OpenAPI 문서가 열리지 않는다(Nginx가 `/api/**`만 중계) — quickstart의 대조 명령을 컨테이너 안에서 받는 방식으로 고쳤다
- US1-4의 「진행 중 끊김 복구」는 규칙 모드가 너무 빨라 재현하지 못했다. 실제 모델 모드나 `fab.agent.step-delay-ms`가 적용되는 시뮬레이터 모드에서 다시 볼 수 있다
- 화면 렌더링(브라우저)은 사람이 보지 않았다 — 발표 전 quickstart 4를 브라우저로 한 번 따라가 볼 것
- 다음 반복: US3(회신 — EV-1006 정비 요청 `chk_prek8mqfdsc1`이 회신 대기 중) → US4(보고서)

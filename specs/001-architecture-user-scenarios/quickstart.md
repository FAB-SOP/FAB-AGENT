# Quickstart: 구조도 · 시나리오 검증

**Feature**: [spec.md](./spec.md) · **Date**: 2026-10-21

문서가 ① 렌더링되고 ② 코드와 맞고 ③ 시나리오대로 실제 동작하는지 확인하는 절차다. 모든 명령은 저장소 루트 기준이다.

## 준비물

- Node · npx(Mermaid 렌더링), Docker Desktop(compose v2), uv · JDK 21(자동 테스트)
- API 키는 필요 없다(규칙 모델 · 해시 임베딩)

## 1. Mermaid 렌더링 (오류 0건)

mermaid-cli는 Chrome이 필요하다. 번들 Chrome이 없으면 설치된 Chrome 경로를 puppeteer 설정으로 넘긴다(macOS 예시).

```bash
OUT=/tmp/fab-mmd && mkdir -p $OUT
echo '{"executablePath":"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}' > $OUT/p.json
for f in architecture data-model; do
  npx -y @mermaid-js/mermaid-cli@11.4.2 -p $OUT/p.json -i specs/001-architecture-user-scenarios/$f.md -o $OUT/$f.md
done
```

기대: architecture 5개 · data-model 3개 블록이 모두 ✅(SVG 생성), 파싱 오류 0건. 예정 노드는 점선 테두리로 보인다.
(2026-10-21 확인: 8/8 렌더링 성공)

## 2. 코드와의 일치

| 확인 | 명령 | 기대 |
|---|---|---|
| Agent 그래프 노드 · 간선 | `sed -n '1,40p' fab-agent/src/fab/agent/graph/build.py` | architecture.md 1.4 상태도와 같다 |
| 패키지 순환 | `python3 tools/java_pkgdeps.py fab-context-api/src/main/java` | 「순환: 없음」, 1.3 의존 방향과 같다 |
| 공개 API 수 | 개발 compose · bootRun: `python3 tools/openapi_diff.py http://localhost:8090`<br>배포 구성(`:8088`)은 Nginx가 `/api/**`만 넘겨 `/v3/api-docs`가 열리지 않는다 → 아래 「배포 구성에서 계약 대조」 | 41 · 41 · 공통 41, 차이는 `MAINTENANCE_REQUEST` · comparisons뿐 |
| 배포 노출 포트 | `grep -n ports compose.web.yaml` | web만 `127.0.0.1:8088` |
| 예정 항목 | `grep -n "kafka" fab-agent/src/fab/agent/worker/sources/kafka.py \| head -3` | Worker 쪽만 존재(1.6) |

**배포 구성에서 계약 대조** — 컨테이너 안에서 문서를 받아 임시 정적 서버로 넘긴다(`openapi_diff.py`는 `<base>/v3/api-docs`를 읽는다).

```bash
T=$(mktemp -d) && mkdir -p $T/v3
./scripts/deploy.sh exec -T backend curl -fs http://localhost:8080/v3/api-docs > $T/v3/api-docs
(cd $T && python3 -m http.server 8197 >/dev/null 2>&1 & echo $! > $T/pid); sleep 1
python3 tools/openapi_diff.py http://127.0.0.1:8197; kill $(cat $T/pid)
```

검토자는 [contracts/architecture-doc.md](./contracts/architecture-doc.md)의 필수 섹션을 하나씩 대조한다.

## 3. 시나리오 자동 검증

```bash
(cd fab-context-api && ./gradlew test)              # US1–US6 backend 수용 기준
(cd fab-agent && uv run pytest -q)                  # Agent · Guard · 승인/재개
(cd fab-context-web && npm ci && npm test)          # 화면 상태 · 알림 · 센서
```

기대: 모두 통과. 어느 수용 기준이 어느 테스트에 묶이는지는 [contracts/scenario-traceability.md](./contracts/scenario-traceability.md).

## 4. 시나리오 시연 (사람이 따라가기)

```bash
./scripts/deploy.sh up -d --build --wait
./scripts/deploy.sh --profile tools run --rm --build --no-deps smoke   # 처음 시드된 환경에서 한 번 — 「실패 0건」
```

**이번 검증 범위는 US1 · US2다**(plan.md · research R7). http://127.0.0.1:8088 에서 `cmp.park`(비밀번호 `fab1234!`)로:

1. **US1 기본 분석** — 이상 조사 → EV-1009
   - 기대: 경로 CVD-PM1 → ETCH-PM2 → CMP-PM3, 결과가 관측 사실 · 원인 후보(지지 · 반대 근거) · 한계로 나뉘고 모든 항목에
     근거 ID가 있다. 확률 · 원인 확정 표현이 없다
   - 화면을 연 채 기본 분석을 다시 실행하면 새로 고침 없이 상태가 바뀐다. `./scripts/deploy.sh restart web` 뒤에도 작업 상태가 복구된다
2. **US2 승인 흐름** — EV-1006(헤드 압력)에 질문 「헤드 압력 이상이 두께에 영향을 줬는가?」
   - 기대: 설비정비팀(`mnt.jung`)에게 보낼 정비 요청 제안, 브리프에 편차 수치 · 「확정 아님」 원인 후보 · 확인 항목, 설정값 처방 없음
   - 확인 · 정비 요청 목록에 EV-1006 요청이 **0건**인지 본 뒤 승인 → 요청이 **정확히 1건**, 내용이 승인 화면의 브리프와 같다
3. **US2 거절 흐름** — EV-1007(슬러리 유량)에 질문 → 제안을 사유와 함께 거절
   - 기대: 요청 없이 조사가 재개되어 결과가 정리되고, 다시 질문할 수 있다

스모크(위 명령)는 EV-1009의 질문 → 승인 → 회신 → 보고서를 자동으로 따라가므로, US2의 EV-1009 경로(앞 공정 ETCH 확인 요청)는 스모크
결과로 본다. 수용 기준과 테스트의 대응은 [contracts/scenario-traceability.md](./contracts/scenario-traceability.md)의 US1 · US2 표.

보류한 US3–US6 시연은 다음 반복에서 이 절에 다시 더한다. 2026-10-21 실행 결과는 [verification.md](./verification.md)(US1 5/5 · US2 7/7).

## 5. 정리

```bash
./scripts/deploy.sh down      # DB 볼륨은 남는다 (지우려면 down -v)
```

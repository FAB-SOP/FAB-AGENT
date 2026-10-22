# Quickstart: 기준선 잡기 · 단계 점검 · 최종 확인

**Feature**: [spec.md](./spec.md) · **Date**: 2026-10-21 · 모든 명령은 저장소 루트 기준

## 준비물

- Docker Desktop, JDK 21, uv, Node · npm, macOS Chrome(DOM 스냅샷 · 그림용)
- API 키 불필요 — 규칙 모델 · 해시 임베딩(`deploy/.env.example` 기본값)

## 1. 기준선 (리팩터링 시작 전 한 번, 기준 커밋 `ee171d2`)

```bash
git switch --detach ee171d2            # 또는 리팩터링 브랜치를 ee171d2에서 만든다
python3 specs/002-refactor-readability/tools/metrics.py > specs/002-refactor-readability/records/metrics-before.txt
(cd fab-context-api && ./gradlew test)  # 109 통과 · 2 건너뜀
(cd fab-agent && uv run pytest -q)      # 39 통과 · 2 건너뜀
(cd fab-context-web && npm ci && npm test && npm run build)   # 33 통과
./scripts/deploy.sh down -v && ./scripts/deploy.sh up -d --build --wait     # 처음 시드 — DB를 지우므로 사용자 확인 후
node specs/002-refactor-readability/tools/dom-snapshot.mjs before          # 화면 DOM · 텍스트 저장(스모크 전에)
./scripts/deploy.sh --profile tools run --rm --build --no-deps smoke       # 16 check, 실패 0
```

계약 대조(배포 구성은 `/v3/api-docs`가 Nginx 밖이라 컨테이너에서 받는다):

```bash
T=$(mktemp -d) && mkdir -p $T/v3
./scripts/deploy.sh exec -T backend curl -fs http://localhost:8080/v3/api-docs > $T/v3/api-docs
(cd $T && python3 -m http.server 8197 >/dev/null 2>&1 & echo $! > $T/pid); sleep 1
python3 tools/openapi_diff.py http://127.0.0.1:8197 > specs/002-refactor-readability/records/openapi-before.txt; kill $(cat $T/pid)
```

결과는 `records/baseline.md`에 적는다([data-model.md](./data-model.md)). 코드 변경 없이 한 번 더 돌려 같은지 확인한다.

## 2. 단계마다 (빠른 점검)

[contracts/gates.md](./contracts/gates.md)의 「빠른 점검」 — 바꾼 구성요소의 테스트만. 화면 단계는 DOM 스냅샷도:

```bash
./scripts/deploy.sh up -d --build --wait web          # 바뀐 화면만 다시 빌드
node specs/002-refactor-readability/tools/dom-snapshot.mjs after
diff -r specs/002-refactor-readability/records/dom/before specs/002-refactor-readability/records/dom/after   # 출력 없음이어야 함
```

DOM 스냅샷은 시드 직후 상태에서 찍은 기준과 비교해야 하므로, 스모크 · 수동 조작으로 데이터를 바꾼 뒤에는
`down -v` → `up` 으로 다시 시드하고 찍는다.

## 3. 구성요소 마칠 때 · 최종 (전체 게이트)

[contracts/gates.md](./contracts/gates.md)의 일곱 항목. 마지막으로:

```bash
python3 specs/002-refactor-readability/tools/metrics.py > specs/002-refactor-readability/records/metrics-after.txt
diff specs/002-refactor-readability/records/metrics-before.txt specs/002-refactor-readability/records/metrics-after.txt
```

기대: 화면 최대 줄 ≤ 160 · 160자 초과 파일 0, 40줄 초과 함수 0(또는 `records/exceptions.md`에 이유), 짧은 이름 0.

## 4. 정리

```bash
./scripts/deploy.sh down      # -v 금지 — 다른 작업의 DB 보존
```

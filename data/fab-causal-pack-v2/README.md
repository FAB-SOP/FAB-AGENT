# fab-causal-pack-v2 — dataVersion `causal-pack-v2-scenarios`

**전부 시연용 합성 데이터다.** `data/build_pack_v2.py`가 두 원본을 합쳐 만든다(원본은 고치지 않는다). 다시 돌리면 같은 바이트가 나오고, 조립이 끝나면 v1 · v2 원본과 행 · 개수를 대조하는 검사를 함께 돌린다(`python3 data/build_pack_v2.py`).

| 출처 | 쓴 것 |
|---|---|
| `FAB-실제문서-선별RAG-센서데이터-통합/fab-causal-pack-v1` (v1) | BASELINE 900 · FOLLOWUP 27 run의 행 전부, FOLLOWUP 원시 시계열 180, `run-events` · `wafer-lineage`, `knowledge/` |
| `backend-handoff-v2/data` (v2, `fab-scenarios-v2`) | CURRENT 90 run · 계측 450 · 특징 600 · 원시 시계열 600, `sensor-envelopes.json`(새 파일) |

v2가 v1에서 바꾼 것은 **`RUN-W-CURRENT-006-3-CMP` 한 run**이다 — 제거량 4829.951877 → 4749.009449 Å, 출고 평균 10200.449787 → 10281.392216 Å,
5위치 계측 5행, `head_pressure` 특징(mean 2.890141 → 2.841574 등) · 원시 시계열(약 3 psi → 처리 100–210초 약 2.55 psi → 회복).
나머지 89 run, L007 · L009, 정상 LOT, 관리한계는 그대로다. **v1 센서와 v2 계측을 섞지 않는다** — 네 가지(run · 계측 · 특징 · 시계열)를 모두 v2로 바꿨다.

- `current-wafer-summary.json`은 합친 run에서 다시 계산했다(v1에는 L006 옛 값 10200.4가 있다). 다른 29행은 v1과 같다
- CSV 사본은 넣지 않았다 — 코드는 JSON만 읽고, CSV를 옮기면 L006 옛 값이 남는다
- `evaluation/`(정답) · 생성기 · 검증 스크립트는 넣지 않았다. Agent · RAG · 컨테이너 어디에도 붙이지 않는다
- `sensor-envelopes.json`의 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아니다(`calibratedForRealTool=false`)

# data — 데이터 묶음

모든 운전 데이터 · 사례는 **팀 시연용 합성 데이터**다. 실제 출판 원문은 `fab-rag-real-documents-v1/`의 원문 7개뿐이다.
묶음 파일은 바이트 그대로 둔다(`.gitattributes`의 `-text`) — 줄바꿈을 바꾸면 각 묶음의 `SHA256SUMS` 검증이 깨진다.

| 폴더 · 파일 | 무엇 | 누가 쓰나 |
|---|---|---|
| `fab-causal-pack-v2/` | **실행에 쓰는 조립본**(dataVersion `causal-pack-v2.1-scenarios`) — v1 전체 + v2 CURRENT + 추가 묶음 L011 · L012. 정답 · 생성기는 들어 있지 않다 | backend(`FAB_PACK_DIR`) · 지식 MCP(`knowledge/`) · compose 마운트 |
| `build_pack_v2.py` | v1 + `backend-handoff-v2/data` + `priority-addon-l011-l012-v1/data` → `fab-causal-pack-v2/` 조립 · 검사(같은 입력이면 같은 바이트). 추가 묶음은 기준 팩 해시가 같을 때만 붙이고, 키가 겹치면 멈춘다 | `python3 data/build_pack_v2.py` |
| `fab-causal-pack-v1/` | 원본 팩 — 센서 데이터 · 가상 과거 사례(`knowledge/synthetic-cases.jsonl`). 조립의 입력 | `build_pack_v2.py` |
| `fab-rag-real-documents-v1/` | 실제 원문 선별 RAG — 출처 목록 · 적재 정책(`rag/`). 원문 검색 입력은 `rag/source-chunks.jsonl` | backend · 지식 MCP(`FAB_RAG_DIR`) |
| `priority-addon-l011-l012-v1/` | 추가 묶음(append-only) — CURRENT-L011 · L012(6 wafer · 18 run). L010 정상 LOT을 본떠 3번 wafer CMP-PM3 run에 짧은 센서 편차를 넣었다(L011 `head_pressure` 하나, L012 `platen_speed` · `head_speed` 둘). `data/`만 팩에 들어가고, 편차 구간 · 사건 후보(`event-candidates/`) · 조사 우선순위 정책(`policy/`, `enabled=false`)은 읽는 코드가 아직 없어 여기에만 둔다. `reference/`는 팩 파일의 사본(조립 때 같은지 확인) | 조립 입력 |
| `backend-handoff-v2/` | 프론트 전달 묶음 — 공개 API 계약 `combined.openapi.json` · 응답 예시 `examples/` · CURRENT 데이터 `data/` | 조립 입력 · `ops/tools/openapi_diff.py` · backend `MonitoringApiTest` |

자세한 설명은 각 폴더의 README를 본다(`fab-rag-real-documents-v1/README.md` · `fab-causal-pack-v2/README.md`). `backend-handoff-v2/README.md`와 `fab-causal-pack-v1/README.md`는 아래 「저장소에 없는 것」에 속해 로컬에만 있다.

## 저장소에 없는 것 (로컬 · 팀 내부로 받아 같은 경로에 둔다)

| 경로 | 무엇 |
|---|---|
| `fab-causal-pack-v1/evaluation/` · `generate.py` · `validate.py` · `README.md` | 평가 정답 · 생성기(평가자 전용) |
| `fab-rag-real-documents-v1/originals/` · `selected/` | 원문 PDF(출판자 저작물) |
| `fab-rag-real-documents-v1/rag/source-chunks.jsonl` | 실제 원문 발췌 100조각 |
| `backend-handoff-v2/`의 위 표 밖 파일 | 전달 명세 · 시나리오 설명 · 검증 기록 |
| `priority-addon-l011-l012-v1/evaluation/` · `build_addon.py` · `validate_addon.py` · `finalize_bundle.py` · `README.md` | 기대 우선순위(정답) · 그것을 만들고 검증하는 스크립트 · 정답을 적은 설명서 |

센서 · 계측은 DB에 적재한다. **evaluation과 생성기 정답은 Agent · RAG · 컨테이너에 노출하지 않는다.**

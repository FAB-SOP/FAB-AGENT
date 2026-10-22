# 유지 예외

| 함수 · 줄 · 이름 | 위치 | 남긴 이유 |
|---|---|---|
| `build_server(kb=…)` 매개변수 이름 `kb` | `fab-agent/src/fab/rag/server.py` | 공개 함수의 키워드 인자 — 테스트 · 개발 도구가 `kb`로 넘긴다. 함수 안에서는 `knowledge`로 받아 쓴다 |
| 속성 이름 `rt` · `dv` · `c` (`_Turn.rt` · `_Turn.dv` · `User.c`) | `graph/nodes/tools.py` · `devtools/spring.py` | 여러 모듈이 속성으로 읽는다 — 이번에는 지역 이름만 정리했다(짧은 이름 지표도 지역 대입만 센다) |
| Python 130자 넘는 줄 7개 | `agent/tools/definitions.py` 등 | 기준선부터 있던 줄(도구 설명 · 긴 문장 문자열). 화면 코드 줄 길이 기준(SC-006)의 대상이 아니고, 이번에 새로 만든 긴 줄은 다시 감쌌다 |
| 바이트코드가 달라진 함수 6곳 | `_vec` · `build_server`(지식 MCP) · `OpenAIEmbedder._call` · `PgVectorStore.index` · `_segment_facts` · `scenario_eval.judge` | 지역 변수가 늘었거나(한 이름을 두 용도로 쓰던 것을 나눔) 줄바꿈으로 점프 배치만 달라짐 — 각각 출력 대조로 같음을 확인(`steps.md` 16행 · 21행) |

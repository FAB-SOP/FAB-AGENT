# 실제 원문 선별 RAG + 시연 데이터 통합 묶음

다운로드·선별일: **2026-10-20**.

이 폴더의 원문 7개는 실제 출판 문서입니다. 원문을 새로 작성한 것이 아닙니다. 별도 `fab-causal-pack-v1` 폴더의 운전 데이터·사례는 팀 시연용 합성 데이터입니다.

## 1. 무엇이 들어 있나

|폴더/파일|내용|Agent 적용|
|---|---|---|
|`originals/`|다운로드한 실제 원문 PDF 6개 + MKS HTML 1개. PDF 총 369쪽|원문 보관·출처 검증용|
|`selected/`|관련 원본 페이지를 추린 PDF 6개, 총 56쪽 + 기사 본문 HTML 1개 + 문서별 추출 TXT|사람 검토·원문 열람용. 중복 임베딩하지 않음|
|`rag/source-chunks.jsonl`|선별 문서에서 추출한 **실제 원문 텍스트 100개 조각**. 문서/원본 페이지/출처/한계 메타데이터 포함|실제 기술 문서 검색 인덱스에 적재|
|`selection-manifest.json`|원문 링크·해시·선별 이유·절 이름·원본과 선별본 페이지 매핑|출처·버전 관리|
|`rag/ingestion-policy.json`|인덱싱 대상·제외 대상·검색 조건|중복 적재와 정답 유출 방지|
|`validation-report.json`|다운로드·PDF 페이지 보존·추출·출처 연결 검사|검증 기록|
|통합 ZIP의 `fab-causal-pack-v1/`|이전에 만든 센서·계측·이력·가상 사례 7건·생성기·검증 코드|DB 적재 및 가상 사례 검색용|

RAG 텍스트의 `textOrigin=SOURCE_EXCERPT`, `synthetic=false`는 원문 또는 원문 OCR이라는 뜻입니다. 이 README와 선별 이유 등 한국어 설명은 작업 안내이지 원문이 아닙니다.

## 2. 관련 부분은 여기까지 선별했다

**쪽수는 PDF 뷰어 기준 1부터 세는 물리 페이지**입니다. 책에 인쇄된 쪽수와 다를 수 있습니다. 정확한 매핑은 manifest를 사용합니다.

|ID|실제 문서|선별한 원본 PDF 페이지/절|이유|
|---|---|---|---|
|DOC01|MIT/TI, Advanced Process Control in Dielectric CMP|3–7쪽: CMP Process Development, In-Situ Sensors, On-Line Metrology|입고 두께·형상·패드/슬러리 변동·센서와 직접 두께 계측의 차이|
|DOC02|Yang & Chen, Sensors 2010, Plasma Process Condition Sensing|5–11, 15–19쪽: 실험 장비·조건 변화·관측 결과와 결론 시작 부분|RF·가스·압력·ESC/He 냉각·가스 분배의 실제 구성과 실험|
|DOC03|MKS, Mass Flow Technology|2–7쪽: 유량 단위·MFC·오차/교정·압력 제어·CVD 적용|유량 실제값 해석과 교정·환경 영향, 압력과 유량 연결|
|DOC04|MKS, Chemical Vapor Deposition Physics|기사 본문: Introduction, Precursors/Diluents, Fluid Flow/Diffusion, Energy, Transport of Product Gases|공급·수송·열/플라스마·배기가 증착에 미치는 원리|
|DOC05|UCSB, ICP Etch 2 Operating Manual|1쪽 General Information / Basic Rules of Thumb|소스/바이어스 RF·척·He 냉각·압력과 가스의 실제 장비 예|
|DOC06|NIST SP 400-100, Thin Film Reference Materials Development|7–8, 11–12, 26–31, 35–36쪽|계측 모델·알고리즘·샘플 균일성·불확실성·측정 변동. 인쇄 쪽수 1–2, 5–6, 20–25, 29–30|
|DOC07|NIST/SEMATECH Handbook, Chapter 6|10–14, 46–48, 58–60, 137–139, 247–252쪽|공정 관리·OOC·관리도·I 관리도·정상성·웨이퍼 내/간 변동. 공정능력은 도입 부분만 포함|

UCSB 문서는 특정 장비 매뉴얼이며 우리 장비를 조작하는 SOP가 아닙니다. 논문 속 가스·숫자·효과 방향·임계값을 우리 산화막 레시피에 그대로 적용하지 않습니다. 각 chunk의 `applicabilityLimits`를 답변 생성 시 함께 전달합니다.

## 3. OCR·수식·그림 처리

- 원본 PDF는 받은 바이트 그대로 보관했습니다. 선별 PDF도 원래 페이지를 복사하며 과학 내용을 다시 그리지 않았습니다.
- NIST 계측 문서는 스캔 PDF의 기존 텍스트가 단어를 붙여 추출해, 선택한 산문 페이지 11쪽을 영어 OCR했습니다. 그림 중심인 원본 28쪽은 선별 PDF에는 보관하되 텍스트 RAG에서는 제외했습니다.
- OCR은 그리스 문자·수식·표·그래프 숫자에 오류가 있을 수 있습니다. 다른 PDF도 그림 형태 수식이 텍스트에 없을 수 있습니다. `figuresEquationsTablesRequireSourceCheck=true`를 붙였습니다.
- 수치 계산은 검증한 백엔드 함수가 담당합니다. Agent가 OCR 수식을 임의로 복원하거나 문서 예시 숫자를 현재 공정의 규격으로 사용하지 않습니다.
- MKS CVD 원본은 다운로드 HTML입니다. 선별 HTML은 기사 본문 텍스트를 오프라인에서 읽도록 추린 것으로, 원 사이트 그림·레이아웃 전체를 복제한 것은 아닙니다.

## 4. 백엔드와 AI 담당자가 적용할 것

|담당|해야 할 작업|입력|
|---|---|---|
|백엔드|run·계측·웨이퍼 경로·기준·이력·센서 특징을 DB에 적재|`fab-causal-pack-v1/data/*.json` 중 각 데이터 테이블|
|백엔드|원시 센서 파일을 시계열 테이블 또는 파일 저장소에 적재|`sensor-traces.jsonl.gz`; t0 + index × sourceIntervalMs로 시각 복원|
|백엔드|공정 경로·입고/출고·제거량·센서 구간·기준 조회 MCP 제공|현재 데이터와 동일한 계산·권한 규칙|
|AI/RAG|실제 문서 조각을 임베딩/키워드 인덱스에 적재|**이 폴더의 `rag/source-chunks.jsonl`만**|
|AI/RAG|가상 사례를 구분해 검색 인덱스에 적재|기존 팩의 `knowledge/synthetic-cases.jsonl`; 가상 표시 유지|
|AI/RAG|기존 팀 점검 문서는 필요한 경우 별도 시연용 인덱스로 사용|기존 `knowledge/rag-chunks.jsonl`의 `TEAM_SYNTHETIC`만. 기존 공개 요약 9개는 이번 원문과 중복되므로 제외|
|AI Agent|DB 도구 조회 → 계산 → 문서/사례 검색 → 근거·반증·부족한 증거 → 확인/조치·보고 초안|자료의 출처·적용 한계와 사용자 권한 유지|

조회 조건 예:

```
incident의 wafer ID 확인
→ 설비 MCP: 실제 CVD→Etch→CMP 경로·두께·제거량·센서 확인
→ 지식 MCP: 관련 process + retrievalTier로 원문 검색
→ 실제 원리와 현재 수치 증거를 구분해 원인 후보 작성
→ 담당자 확인 요청 초안 / 추가 질의 / 보고서 초안
```

`evaluation/*`, 생성기 코드와 잠재 원인 정답은 Agent·검색 인덱스에 노출하지 않습니다. 원시 센서 점 전체도 문서 임베딩 대상이 아닙니다. 실제 문서 100개 조각은 **아직 임베딩하지 않은 입력 코퍼스**이며, 실제 Agent/MCP 서비스도 이 묶음에서 실행하지 않았습니다.

## 5. 데이터 양을 정확히 읽기

|구분|수량|
|---|---:|
|정상 생성 분포의 기준 이력|300장 × 3공정 = 900개 run. 센서 특징 제공, 기준 원시 시계열 파일은 생략|
|현재 10개 로트|30장 × 3공정 = 90개 run, 원시 센서 포함|
|후속 확인 3개 로트|9장 × 3공정 = 27개 run, 원시 센서 포함|
|전체|339장·1,017개 공정 run|
|원시 시계열|780개, 센서 샘플 1,120,860개|
|OOC 이상 사건|현재 3건: CMP 압력 저하, CMP 슬러리 공급 저하, Etch 영향의 CMP 발견|
|추가 초기 변화 예|Etch 변화 1건, CVD 소폭 변화 1건. 둘 다 최종 OOC 아님|
|가상 과거 사례 문서|7건. 각각에 대응하는 별도 센서 원본은 만들지 않음|

112만 개 센서 샘플은 112만 개 독립 사건이 아닙니다. 대표 화면·MCP·워크플로우 시연용이며, 다양한 원인 구분 성능을 주장할 수 있는 학습/평가 데이터셋은 아닙니다. 원인 후보 15종 중 모든 원인의 센서 사건을 구현한 것도 아닙니다.

## 6. 출처와 이용 범위

원문 URL·다운로드 URL·SHA-256은 `selection-manifest.json`에 있습니다. 출판자·저자 표시는 원문에 유지했습니다. 공개 다운로드 가능 여부가 재배포/서비스 이용에 대한 일괄 허가를 뜻하지는 않으므로, 팀 내부 검토를 넘어 공개 서비스나 외부 배포에 사용할 때 각 원문의 이용 조건을 확인합니다. 이 묶음은 해당 기관의 서비스 승인·장비 조치 승인을 뜻하지 않습니다.

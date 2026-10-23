# 프론트 연결: 원인 통계 조회

## 기준과 담당 범위

- 통계 Backend만 추가한다. 기존 보고서 생성·편집·확정·일반 검색과 AI·프론트는 변경하지 않는다.
- 로그인한 **MANAGER**의 허용 공정만 조회한다. 공정 생략은 허용 공정 전체이며, 명시한 타 공정·설비는 403이다. UI 메뉴 숨김과 별개로 서버가 검사한다.
- **확정 보고서가 있는 사건만 사건당 1건** 집계한다. 초안·미작성 사건은 제외한다.
- 구버전 데이터에 같은 사건의 확정본이 여러 개면 version이 가장 큰 확정본 하나를 선택한다(동률은 reportId 내림차순). 이후 초안이 있어도 확정본은 포함한다. 원인 필터는 이 선택 이후 적용한다.
- 날짜는 **사건 detectedAt, 한국 시간** 기준이다. 9월 발생·10월 확정 사건은 9월에 들어간다. 뒤늦게 확정하면 과거 월 통계가 늘어날 수 있다.
- `createdAt`은 해당 보고서 판의 작성일, `confirmedAt`은 확정일이다. 집계·정렬에 사용하지 않는다.
- `HOLD`는 원인 미확정 분류. 과거 classification 없는 확정본은 HOLD로 치환하지 않고 미분류 건수로 별도 표시한다.

## 화면별 호출

| 행동 | 통계 API | 목록 API |
|---|---|---|
| 최초 진입 | year 생략: 한국 시간 올해 | 같은 year로 첫 페이지 |
| 연도 변경 | 선택 year, months 해제 | 월·원인 선택 해제, page=0 |
| 월 막대 복수 선택 | months 반복 전달 | 같은 months, page=0 |
| 파이 원인 복수 선택 | **재조회 불필요. 전체 분포 유지·선택 조각 강조** | categories 반복 전달, page=0 |
| 월·원인 초기화 | year 유지, months 생략 | months·categories 생략, page=0 |
| 보고서 클릭 | 없음 | reportId로 기존 보고서 상세 화면 이동 |

월 막대는 선택 연도 **12개월 전체**를 유지한다. 파이는 선택 월의 분포다. 목록은 선택 월 AND 선택 원인이다. 같은 필터 내부의 복수 값은 OR이며 다른 필터끼리는 AND다. 월·원인 미선택은 전체를 뜻한다.

기존 세션 쿠키 인증, 성공 응답은 `{data, meta}`. GET만 추가하므로 CSRF·Idempotency-Key는 필요 없다. 반복 폴링은 필요 없으며 필터 변경·페이지 이동 때 조회한다.

## GET /api/cause-statistics

```http
GET /api/cause-statistics?year=2026&months=9&months=10&processCodes=CMP&equipmentIds=CMP-PM3
```

| 파라미터 | 규칙 |
|---|---|
| year | 선택. 생략 시 서버 Clock의 한국 시간 올해. 1~9998 |
| months | 선택. 1~12 복수 값. 중복 제거·오름차순 정규화. 생략은 연간 |
| processCodes | 선택. CVD/ETCH/CMP 복수 값. 생략은 허용 공정 전체 |
| equipmentIds | 선택. 사건 발생 설비 복수 값, 최대 100개. 원인 설비를 의미하지 않음 |

원인 선택은 목록에만 적용하므로 이 API에 categories는 전달하지 않는다.

### data 예시

```json
{
  "year": 2026,
  "dateBasis": "DETECTED_AT",
  "timezone": "Asia/Seoul",
  "months": [9, 10],
  "processCodes": ["CMP"],
  "equipmentIds": ["CMP-PM3"],
  "availableYears": [2026, 2025],
  "annualTotalCount": 8,
  "monthly": [
    {"month": 1, "count": 1}, {"month": 2, "count": 0},
    {"month": 3, "count": 0}, {"month": 4, "count": 0},
    {"month": 5, "count": 0}, {"month": 6, "count": 0},
    {"month": 7, "count": 0}, {"month": 8, "count": 0},
    {"month": 9, "count": 3}, {"month": 10, "count": 4},
    {"month": 11, "count": 0}, {"month": 12, "count": 0}
  ],
  "totalCount": 7,
  "classifiedCount": 6,
  "unclassifiedCount": 1,
  "categoryCounts": [
    {"category": "MATERIAL", "count": 1},
    {"category": "MAN", "count": 0},
    {"category": "METHOD", "count": 1},
    {"category": "MACHINE", "count": 3},
    {"category": "HOLD", "count": 1}
  ]
}
```

- `annualTotalCount`/`monthly`: 年도·공정·설비 조건 기준. 월 선택과 무관하다.
- `totalCount`/`classifiedCount`/`unclassifiedCount`/`categoryCounts`: 위 조건 + 선택 월 기준.
- 파이 비율 = count / classifiedCount. 0이면 빈 상태 표시. 미분류는 파이 밖에 별도 안내한다.
- 5개 분류와 12개월은 0건도 반환한다. 카테고리는 배열 순서 대신 enum을 키로 사용한다.
- `availableYears`: 같은 권한·공정·설비 범위에서 확정 보고서가 있는 사건의 발생 연도 + 올해 + 현재 선택 연도. 내림차순, 월 선택과 무관하다.

## GET /api/cause-statistics/reports

```http
GET /api/cause-statistics/reports?year=2026&months=9&months=10&categories=MACHINE&categories=HOLD&page=0&size=20
```

공통 필터 외에 `categories=MATERIAL|MAN|METHOD|MACHINE|HOLD`를 반복 전달한다. 생략은 미분류 포함 전체, 선택 시 미분류는 제외한다.

- page: 0 이상. 기본 0.
- size: 1~100. 기본 20.
- 정렬: **detectedAt 내림차순 → incidentId 오름차순 고정**. sort 파라미터는 제공하지 않는다.
- 목록과 count는 같은 필터로 DB 조회한다. 전체 보고서 본문을 읽거나 목록으로 노출하지 않는다.

```json
{
  "year": 2026,
  "dateBasis": "DETECTED_AT",
  "timezone": "Asia/Seoul",
  "months": [9, 10],
  "processCodes": ["CVD", "ETCH", "CMP"],
  "equipmentIds": [],
  "categories": ["HOLD", "MACHINE"],
  "items": [
    {
      "reportId": "report_01",
      "incidentId": "inc_01",
      "incidentDisplayId": "EV-1092",
      "title": "EV-1092 두께 관리한계 이탈 조사 보고서",
      "processCode": "CMP",
      "equipmentId": "CMP-PM3",
      "version": 2,
      "detectedAt": "2026-09-30T15:30:00Z",
      "createdAt": "2026-10-02T01:00:00Z",
      "confirmedAt": "2026-10-03T02:00:00Z",
      "classification": {
        "category": "MACHINE",
        "equipmentParameters": ["head_pressure"],
        "classifiedBy": "USR-CMP-01",
        "classifiedAt": "2026-10-03T02:00:00Z"
      }
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`detectedAt` 표시를 한국 시간으로 변환하면 위 예시는 **10월 1일 발생**이다. 목록에는 발생일·보고서 작성일을 구분해 표시할 수 있다. 상세는 기존 `GET /api/reports/{reportId}`를 사용한다. classification은 과거 미분류 확정본에서 null이며, 세부 파라미터가 없으면 빈 배열이다. AI 요약을 세부 원인으로 합성하지 않는다.

## 오류

| HTTP | code |
|---|---|
| 400 | INVALID_YEAR, INVALID_MONTH, INVALID_PROCESS_CODE, INVALID_CAUSE_CATEGORY, INVALID_FILTER, INVALID_EQUIPMENT_ID, INVALID_PAGE_SIZE |
| 400 | INVALID_PARAMETER: 숫자 등 파라미터 형식 오류 |
| 401 | UNAUTHENTICATED |
| 403 | MANAGER_ROLE_REQUIRED, PROCESS_SCOPE_DENIED |

필터와 맞는 데이터가 없으면 200, 0건·빈 목록이다. 알 수 없는 설비는 400이다. 설비 이름 접두사로 권한을 추정하지 않고 DB의 사건·run 소속 공정으로 확인한다.

## 검증

- `./gradlew test --offline`: 총 233건, 통과 231건, 실패/오류 0건, 기존 Knowledge 테스트 2건 건너뜀.
- 신규 통계 API 8건: 발생일/KST 연도·월 경계, 확정본 사건 단위 중복 제거, 최신 초안 제외, 복수 필터, 발생일 정렬·페이지·기존 상세 연결, 관리자·공정·설비 권한, 빈 데이터·입력 검증, Swagger.
- Testcontainers 별도 PostgreSQL 사용. 실제 서비스 DB는 변경하지 않는다. 프론트 화면 연결은 이 작업 범위에 포함하지 않는다.
- 최종 Swagger JSON Content-Type 명시 후 신규 API 8건 재검증 통과. 별도 미리보기 DB·8091 서버에서 실제 관리자 로그인, 연간/복수 월 집계, 원인 복수 선택 목록, Swagger UI/JSON HTTP 확인 완료.

## Swagger

통계 브랜치 Backend의 `/swagger-ui/index.html`에서 **cause-statistics** 태그를 확인한다. `/v3/api-docs`에는 응답 DTO·배열 파라미터·오류 설명이 포함된다. Swagger UI의 Try it out은 같은 Backend 세션의 관리자 로그인이 필요하다.

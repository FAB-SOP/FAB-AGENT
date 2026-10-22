# 조사 우선순위 Backend 도구 — C 연결 명세

## 범위와 역할

- 작업 브랜치: `feat/investigation-priority`, 기준 커밋: `312420f`.
- Backend: 원시 시계열·공정 경로·계측 조회, 계산, 설정 기반 상중하 판정, 근거 반환.
- C: 업무 MCP 도구 등록 및 Agent 호출 연결. 반환 등급·규칙을 유지하고 원인 후보·다음 확인 사항을 설명.
- 이번 변경은 Backend 읽기 API와 테스트다. AI/MCP/Frontend/보고서 코드는 수정하지 않는다.
- 사건 등급 DB 저장·목록 노출·Agent 결과 이벤트 확장은 이번 도구 구현에 포함되지 않는다.

## C에게 전달할 내용

> 업무 MCP에 `get_investigation_priority`라는 읽기 도구를 등록해 주세요.
> 사건 조회에서 얻은 대상 runId, 작업 context의 analysisAsOf, 아래 정책 버전을 Backend에 전달합니다.
> Backend가 `assessment`로 등급·사유·규칙·근거를 반환합니다. Agent가 같은 규칙을 별도로 재판정하거나 등급을 임의 변경하지 않습니다.
> RAG의 PM 기준 문서는 판정 의미·조사 지식 설명에 사용합니다. 문서와 도구의 정책 버전이 다르면 숨기지 말고 불일치를 처리합니다.
> 기준 부족으로 정상 응답된 잠정 중은 실패가 아닙니다. HTTP 오류·통신 오류는 임의의 중/하로 대체하지 않습니다.

## 호출

```http
GET /internal/runs/RUN-W-CURRENT-009-3-CMP/priority-evidence?policyVersion=DEMO-1.2-DRAFT&asOf=2026-10-20T02%3A59%3A00Z
Authorization: Bearer <SERVICE_TOKEN>
```

| 입력 | 필수 | 설명 |
|---|---|---|
| `runId` | O, 경로 | 실제 대상 run. 사건의 runIds 또는 실제 wafer 경로에서 얻음 |
| `policyVersion` | O | 현재 지원값 `DEMO-1.2-DRAFT`. AI가 임의 임계값을 전달하지 않음 |
| `asOf` | 권장 | ISO 8601 시각. C는 작업 context의 `analysisAsOf`를 전달. 생략하면 서버 데이터 팩의 고정 분석 시각 |
| `includeDetails` | X | 기본 false. true이면 센서별 편차 구간·반복 run 상세 포함. 계산·등급 자체는 동일 |

- 현재 분석 시각 이후 조회, 아직 시작하지 않은 run, FOLLOWUP run은 거절한다.
- `asOf`가 run 진행 중이면 해당 시점까지 관측한 센서만 사용한다. 이후 계측값은 반환하지 않는다.
- 내부 서비스 토큰만 허용한다. 일반 사용자 세션만으로 호출할 수 없다.
- 조회는 DB·업무·조사 종료·Agent pending/thread를 변경하지 않는다.
- 임계값, 센서 목록, 판정 정답을 요청으로 받지 않는다. 공정별 등록 센서를 서버가 모두 평가한다.

## 응답과 MCP 포장

HTTP 응답의 최상위 `data` 안에 기존 업무 도구 공통 envelope가 있다.
기존 `BackendClient.call()`은 최상위 `data`를 이미 한 번 벗기므로 MCP에서는 아래 envelope를 그대로 반환하면 된다.

```json
{
  "data": {
    "assessment": {
      "status": "COMPLETED",
      "level": "HIGH",
      "provisional": false,
      "ruleIds": ["P-HIGH-01"],
      "reason": "공정 경로의 유효 핵심 계측값이 등록 관리한계 또는 제품 규격을 벗어났습니다.",
      "evidenceIds": ["E-PRMET-RUN-W-CURRENT-009-3-CMP"],
      "missingData": [],
      "policyVersion": "DEMO-1.2-DRAFT",
      "assessmentAsOf": "2026-10-20T02:59:00Z",
      "mode": "DRAFT_PREVIEW"
    }
  },
  "evidenceRefs": [],
  "dataVersion": "causal-pack-v2-scenarios",
  "warnings": [],
  "synthetic": true
}
```

위 JSON은 응답 구조를 설명하기 위한 축약 예시다. 실제 응답에는 아래 관측값과 근거 목록도 포함된다.

| `data` 항목 | 내용 |
|---|---|
| `schemaVersion` | `1.0` |
| `runId`, `waferId`, `processCode`, `equipmentId`, `asOf` | 판정 대상과 조회 기준 시각 |
| `policy` | 문서 ID·버전·DRAFT 상태·enabled·검출기 버전·`gradeAssignedByTool=true` |
| `assessment` | 완료한 판정. HIGH/MEDIUM/LOW, 잠정 여부, 적용 규칙, 근거, 부족한 정보 |
| `metrology` | 실제 CVD/ETCH/CMP 경로의 계측 공개 여부·5위치 평균·OOC/OOS·관리한계 ID |
| `sensorObservations` | 유효 편차가 있거나 근거가 부족한 센서. 최대 편차 시간·회복·반복 횟수·근거 ID |
| `normalSensorIds` | 현재 시계열을 완전히 평가했고 유효 편차가 없었던 센서 |
| `relatedSensorGroups` | 설정에 등록된 관련 그룹의 관측 중첩 시간과 평가 가능 상태 |
| `comparisonRunIds` | 비교 run. 현재 run 포함 최근 3건, 같은 조건, 미래/FOLLOWUP 제외 |
| `validDeviationSensorCount` | 유효 편차가 검출된 센서 종류 수. 전체 센서 수가 아님 |
| `missingData` | 부족한 관측. 누락을 정상으로 해석하지 않음 |

## 판정과 실패의 구분

| 상황 | Backend 응답 | C 처리 |
|---|---|---|
| 유효 계측 OOC/OOS | COMPLETED / HIGH | 무관한 결측만으로 하향하지 않음 |
| 필수 근거 부족·계측 대기 | COMPLETED / MEDIUM / provisional=true / P-MEDIUM-04 | 정상적인 잠정 판정. 실패·보류 등급으로 바꾸지 않음 |
| 지속·반복·관련 센서 동반 조건 충족 | COMPLETED / MEDIUM | 적용 규칙과 관측값을 설명 |
| 단일 짧은 편차 후 회복, 필요한 근거 정상 | COMPLETED / LOW | 하여도 해결 완료·고장 없음으로 단정하지 않음 |
| 통신·서버·모델 오류 | 오류, 정상 assessment 없음 | 판정 실패로 처리. 중/하를 만들어 넣지 않음 |
| 아직 실행 전 | 응답·판정 기록 없음 | 미평가. 등급이 아닌 처리 상태 |

### 오류

| HTTP | code | 조건 |
|---|---|---|
| 400 | INVALID_ARGUMENT | 지원하지 않는 policyVersion |
| 400 | MISSING_PARAMETER / INVALID_PARAMETER | 필수값 없음, 시각 등의 형식 오류 |
| 401 / 403 | 공통 인증·인가 오류 | 토큰 없음·잘못됨, 일반 세션만 사용 |
| 404 | NOT_FOUND | run 없음 |
| 403 | FORBIDDEN | 미래 시점·미래 run·FOLLOWUP |
| 5xx / 통신 예외 | 기존 공통 오류 | 재시도·실패 처리. 등급으로 해석하지 않음 |

## C 구현 체크

1. MCP는 읽기 전용으로 등록. 위 GET으로 인수를 전달하고 기존 BackendClient와 오류 변환을 사용한다.
2. 기본 분석에서 대상 run별로 호출. 사건에 여러 run이 있으면 하나의 결과를 사건 전체에 임의 적용하지 않는다.
3. `assessment`를 유지하고 원인 분석에 관측 근거를 사용한다. 센서 방향 LOW는 하방 편차이며 우선순위 LOW와 다르다.
4. 정상 assessment와 HTTP/모델 오류를 구분한다. 근거 부족에 따른 잠정 중은 실패가 아니다.
5. 저장용 Agent 이벤트 필드명·스키마는 별도로 Backend와 합의한다. 현재 `additionalProperties:false` 계약에 임의로 추가하지 않는다.
6. 기본 요약 응답을 사용한다. includeDetails=true는 길어질 수 있어 현재 6,000자 도구 제한에서 조용히 잘리지 않게 처리한다.

## 정책·데이터 경계

- PM ZIP의 `policy/priority-rules.json`을 그대로 Backend resources에 배치했다. `enabled:false`를 유지하며 명시적 GET 호출로 DRAFT_PREVIEW 계산을 제공한다.
- 운영 정책 활성화·이벤트 자동 생성은 하지 않는다. 자동 판정 연결은 별도 작업이다.
- 기존 2초 평활·5초 검출기를 변경하지 않고, 새 1초 평활·1초 유효 편차 계산을 분리했다.
- L011/L012 추가 팩을 운영 데이터·DB에 적재하지 않았다. 서비스 DB에 아직 없다면 해당 run 조회는 404다.
- 테스트에만 PM ZIP의 대상 센서 3개를 압축 보존했다. 운영/RAG에는 평가 정답을 제공하지 않는다.
- 현재 DB에는 계측 품질 INVALID 등의 전용 필드가 없다. 합성 광학 모델의 5위치·단위·공개 시각을 구조 검증하며 실장비의 품질 보증을 뜻하지 않는다.
- 지속·반복·중첩 계산과 등급 조건을 테스트하며, 기능 테스트와 AI 평가를 구분한다. 이 테스트만으로 AI 평가 20건을 충족했다고 하지 않는다.

## 검증 명령

```sh
./backend/gradlew -p backend test --offline
```

Testcontainers의 격리 Postgres를 사용한다. 현재 서비스 DB는 초기화하지 않는다.

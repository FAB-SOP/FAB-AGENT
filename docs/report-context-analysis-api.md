# 보고서 초안용 최신 원인 분석 — Backend 계약

기준 main `319c49d`, 브랜치 `feat/agent-handoff`. 내부 조회와 이벤트 수신 보완만 한다. AI/MCP·Frontend·보고서 모듈은 수정하지 않는다.

## 조회와 기존 응답 유지

`GET /internal/incidents/{incidentId}/report-context`에 `latestCauseAnalysis` 한 필드를 추가한다. 기존 내부 Agent 서비스 토큰 인증·사건 ID 해석·incident/checkRequests/actions/reports/workItems/followupNote는 유지한다.

이 내부 조회의 envelope는 **`{data:{data:{...}, evidenceRefs:[], ...}, meta:{...}}`**다. raw HTTP에서 새 값은 `response.data.data.latestCauseAnalysis`, 기존 MCP 결과에서는 `result.data.latestCauseAnalysis`다. 다른 내부 history API의 envelope와 혼동하지 않는다.

- 같은 사건의 저장된 결과 중 **resultKind=CAUSE_ANALYSIS인 최신 결과**를 선택한다. BASIC/DEEP/RESUME 작업 종류로 제한하지 않는다.
- 좁은 일반 질문 ANSWER가 나중에 생겨도 원인 분석을 대체하지 않는다. 결론 변경 질문도 CAUSE_ANALYSIS로 저장됐으면 선택한다.
- 결과가 없거나 ANSWER만 있으면 **키를 유지하며 값은 JSON null**이다. 404/빈 객체/가짜 '원인 없음' 분석을 만들지 않는다.
- 저장된 분석은 보고서 확정 원인이나 조치 완료 사실이 아니다. 별도 보고서 검토·확정 규칙은 유지한다.

## 필드 형태

기존 공개 분석 조회·conversation의 **AnalysisResultView와 같은 객체**를 재사용한다. 새로운 후보 객체 이름이나 AI용 enum을 만들지 않는다. `history`는 null이며 전체 분석 이력을 중복 포함하지 않는다.

| 필드 | 형태·빈 값 규칙 |
| --- | --- |
| resultId / incidentId / jobId / threadId | 저장된 분석·사건·작업 참조 |
| kind / resultKind | 작업 종류, 결과 종류. 이 필드의 resultKind는 CAUSE_ANALYSIS |
| createdAt / dataVersion | 분석 저장 시각(UTC ISO-8601)·데이터 버전 |
| answer | string 또는 null. 기존 결과에 직접 답이 없으면 null |
| summary | 기존 저장 요약. answer가 null/빈 값이면 이를 사용 |
| observedFacts | 기존 관측 객체 배열. 없으면 [] |
| candidateCauses | 기존 후보 객체 배열. 없으면 []. hypothesis/priority·rank, supportingEvidenceIds, counterEvidenceIds, nextChecks, missingData 등을 기존 조회와 동일하게 제공 |
| candidateCauses[*].candidateId / causeIds / stance | C가 새 계약으로 보내 저장된 경우 제공. 기존 결과에는 키 자체가 없을 수 있음. 없는 stance를 사람 확정/지지/보류로 추정하지 않음 |
| evidenceRefs | 기존 출처 객체 배열. 없으면 [] |
| limitations / nextActions | 기존 배열. 없으면 [] |
| analysis | 기존 판단 메타데이터 객체. 없으면 {}. nextStep/conclusion 등 없는 키는 억지로 채우지 않음 |
| previousResultId / revision | 이전 결과 참조(nullable)·결과 판 번호 |
| question | 원래 질문(nullable) |
| history | null |

다음 행동은 **latestCauseAnalysis.analysis.nextStep**, 결론 변경은 **latestCauseAnalysis.analysis.conclusion**에서 읽는다. 최상위 nextStep을 새로 만들지 않는다. 구버전 결과/아직 미전송한 필드는 null 또는 키 없음으로 처리한다.

설명용 발췌(실제 객체는 위 필드를 모두 포함):

```json
{
  "latestCauseAnalysis": {
    "resultId": "res_01",
    "incidentId": "inc_01",
    "jobId": "job_01",
    "threadId": "thread_01",
    "kind": "DEEP",
    "resultKind": "CAUSE_ANALYSIS",
    "answer": "압력 제어 관련 추가 확인이 필요합니다.",
    "summary": "압력 제어 관련 추가 확인이 필요합니다.",
    "observedFacts": [],
    "candidateCauses": [],
    "evidenceRefs": [],
    "limitations": ["원인 확정 아님"],
    "analysis": {"resultKind": "CAUSE_ANALYSIS", "nextStep": "CURRENT_CHECK"},
    "history": null,
    "previousResultId": null,
    "revision": 1
  }
}
```

분석 없음 또는 일반 질문 답만 있음:

```json
{"latestCauseAnalysis": null}
```

새 API 호출은 원인 분석 저장/Job 상태/승인/초안 생성/사건 종료를 일으키지 않는다. 기존 응답 필드/인증 오류는 유지한다. 없는 사건은 기존 NOT_FOUND다.

## 선웅님·의진님 연결

- 아름: 위 조회의 저장 분석 선택·null 처리·회귀 검증.
- 선웅님/의진님: 초안 생성 시 필요한 관측·후보·근거·한계를 읽고 사용. 후보는 미확정 가설로 취급하며 최종 원인 분류는 사람이 선택.
- null이면 요청·회신·작업 기록으로 작성하고 분석 미작성/근거 부족을 명시한다. null을 원인 없음이나 해결 완료로 해석하지 않는다.
- 기존 report-context 본문 크기 한도/필드 축약 정책은 이번에 바꾸지 않는다. 이 객체는 history의 짧은 요약과 다르므로 Agent가 모델에 넣을 때 필요한 필드만 선택하고 기존 도구 결과 예산을 확인한다.
- 보고서 자동 초안·AI 수정 요청·사람 수정 칸 보호·시뮬레이터 ReportComposer 선택 정책은 담당자 영역이며 이번에 수정하지 않는다.

## 함께 보완하는 재전송 비교

RETRY_SCHEDULED.payload.nextRetryAt만 비교에서 추가 제외한다. 같은 ID의 nextAttempt/errorCode 변경은 여전히 충돌이다. JOB_FAILED.message와 ANALYSIS_PROGRESS.payload/ID는 아직 합의 확인 중이므로 변경하지 않는다.

이미 저장된 v1 RETRY 해시는 원래 nextRetryAt이 없으면 새 방식으로 변환할 수 없다. 신규 RETRY 해시에 v2 표기를 붙이고, 기존 v1 RETRY 행은 식별 정보가 같으면 내용을 재검증할 수 없다는 WARN과 함께 중복 무시를 유지한다. 원래 행을 새 해시로 덮어쓰거나 작업을 재접수하지 않는다. 다른 종류 이벤트의 해시는 유지한다.

## 검증

- Backend 대상 테스트 38건 통과. 전체 `./gradlew test --offline` 결과는 355건 중 353건 통과, 기존 2건 건너뜀, 실패 0건이다.
- 현재 Agent와의 `uv run --offline pytest -q` 결과는 243건 통과, 4건 건너뜀, 1건 실패다. 실패는 `test_full_flow_records_request_only_after_approval`의 REPORT 경로다.
- 해당 테스트에서 report-context JSON은 9,145자였고, 기존 Agent의 6,000자 문자열 자르기 이후 JSON이 깨져 `KeyError: 'data'`가 발생했다. Backend 응답 계약은 유지하며 Agent 담당자가 보고서용 입력을 항목 단위로 줄여 정상 JSON을 보존해야 한다. 이 연결 수정 전에는 전체 보고서 흐름 검증이 통과한 것으로 보지 않는다.
- 서비스 DB·배포·외부 메시지 전송은 이번 범위가 아니다. AI/MCP·Frontend·보고서 모듈은 수정하지 않았다.

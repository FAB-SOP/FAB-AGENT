# 프론트 전달: 승인·다른 팀 확인 요청의 조사 맥락

Backend 브랜치: `feat/user-notifications`. 프론트·Agent/Python/MCP·AI 계약은 수정하지 않았다. 새 AI 호출 없이 기존 분석 결과와 계측 조회 로직으로 요청 맥락을 구성한다.

## 최종 표시 방향

- 시간순 전체 조사 이력은 이번에 추가하지 않는다.
- **요청 이유 → 실제 관측·기준·편차 → 관련 기록 → 필요한 확인 항목**을 전달한다.
- 전체 원인 후보 순위·AI 전체 요약은 보내지 않는다. **이 업무와 연결되고 대상 공정에 해당하는 후보만** 최대 3개, `confirmed:false`로 제공한다.
- 후보는 확정 원인이 아니라 확인 배경이다. 화면 제목은 “확인 배경(미확정)” 또는 “확인이 필요한 항목의 배경”을 권장한다.
- 같은 형식을 상급자 승인 요청(MANAGER_APPROVAL)과 다른 팀 확인 요청(TEAM_REQUEST)에 사용한다.

## API와 표시 위치

| 화면 | API | 위치 |
|---|---|---|
| 채택 응답 | POST /api/work-items/{taskId}/adoptions | data.brief |
| 상급자 승인함 | GET /api/work-items?box=approval | data.items[i].brief |
| 사건 업무 목록 | GET /api/incidents/{incidentId}/work-items | data.items[i].brief |
| 받은 확인 요청 상세 | GET /api/check-requests/{requestId} | 기존 data.brief |

받은 요청 목록/incomingRequests는 기존 CheckRequestSummary를 유지한다. 목록에 brief가 있다고 가정하지 말고 요청 상세를 조회한다. 상급자 업무 승인은 `/api/work-items/{taskId}/decisions`이며 Agent HITL 승인 API와 별개다.

## brief 형태

아래 수치는 형태 설명용 예시다. Backend는 예시 숫자를 코드에 넣지 않고 기존 계측 서비스의 실제 값을 사용한다.

```json
{
  "brief": {
    "reason": "계측 결과를 바탕으로 앞 공정 기록을 추가 확인해야 합니다.",
    "deviations": [
      {"runId":"RUN-W-CURRENT-009-3-ETCH","processCode":"ETCH","equipmentId":"ETCH-PM2",
       "metric":"removedA","value":4896.3,"unit":"Å","baselineMean":4999.024,"z":-2.74,
       "threshold":2.5,"thresholdKind":"SAME_PM_BASELINE","withinNormal":false}
    ],
    "suspectedCauses": [
      {"causeId":"cause_...","candidate":"챔버 세정 이후 상태 변화 가능성","confirmed":false}
    ],
    "relatedRecords": [
      {"summary":"조사 기준: 분석 결과 res_... / 관측 사실과 등록 근거 확인"},
      {"evidenceId":"ev:change:...","summary":"2026-10-18T12:00:00Z ETCH-PM2 CHAMBER_SERVICE: 등록된 정비 기록 요약"}
    ],
    "askFor": ["앞 공정 정비 기록 확인","시즈닝 후 모니터 결과 확인"]
  }
}
```

| 필드 | 표시 |
|---|---|
| reason | 요청 사유. 기존 업무 reason 사용 |
| deviations | 관측값·기준·편차 표 |
| suspectedCauses | 대상 공정의 관련 후보만, 반드시 **미확정** 표시. 없으면 영역 생략 |
| relatedRecords[].summary | 조사한 내용·관련 기록 |
| askFor[] | 확인할 항목 / 승인할 업무 내용 |

다른 팀 요청 상세의 기존 `RequestContext`는 위 다섯 필드를 이미 표시한다. 승인함에는 새 `item.brief`를 연결한다. 실제 변경 대상·설비·공정·제목은 기존 WorkItem/CheckRequest 필드도 함께 표시한다.

### 관측값 해석

- `metric=depositedA/removedA`: 증착·제거량(Å), 같은 PM 정상 기준 평균과 z. `thresholdKind=SAME_PM_BASELINE`.
- 현재 사건 공정의 출고 두께는 `metric=outputMeanA`, `thresholdKind=CONTROL_LIMIT`, lcl/ucl을 함께 제공한다. 이 경우 baselineMean은 관리 중심값이고 z는 null이다. **정상 평균·sigma 표와 혼동하지 않게 관리 중심값·관리한계로 표시한다.**
- baselineMean/z/withinNormal이 null이면 기준이 없거나 판단할 수 없는 값이다. 정상으로 간주하지 않는다. 기존 화면의 `withinNormal===true` 표시 규칙은 유지한다.
- 최대 6개 관측 행. 원본 분석에 참조된 사건 웨이퍼의 run 중 기존 분석 기준시각에 공개된 계측만 사용한다. 다른 사건/후속 시점의 측정값을 끌어오지 않는다.
- 문자열에서 수치를 파싱하거나 AI 문장을 수치로 추정하지 않는다. 기존 ProcessEvidenceService가 돌려준 값과 기준을 그대로 보존한다.

## 생성·관련성·보존

- 사람이 업무를 채택해 승인/팀 요청을 보내는 시점에 서버가 만든다. 클라이언트가 brief를 제출할 필요가 없다.
- sourceResultId의 원본 분석을 사용하며 최신 분석으로 임의 대체하지 않는다. 전달 근거를 선택한 업무는 그 범위의 관측만 사용한다. 선택하지 않았다면 원본 분석의 등록된 근거를 사용한다.
- 관련 원인 후보는 대상 공정 일치가 필수다. sourceCauseId가 있으면 그 후보만, 없으면 전달 근거와 지지 근거가 연결된 후보만 포함한다. 그 후보의 nextChecks가 있으면 업무 제목과 함께 askFor에 추가한다. 후보가 없으면 작업자가 정한 업무 요청 내용만 보낸다.
- 원본 관측 요약은 최대 6개, 대상 설비의 정비/변경 기록도 원본 분석의 연결 근거와 기존 공개시각 제한 안에서만 제공한다. 관련 문서는 연결된 근거의 식별/표시 정보만 사용하며 문서 원문을 통째로 보내지 않는다.
- 필요한 기록·값이 없으면 빈 배열이나 기록 부족 안내를 제공한다. 없는 조사 결과나 확인 절차를 새로 만들지 않는다.
- 승인 요청은 WorkItem에 snapshot으로 보존한다. 다른 팀 발송은 같은 내용을 CheckRequest.brief에 복사한다. 이후 분석이 바뀌어도 보낸 내용은 유지한다.
- PROPOSED·기존 업무·직접 확인 업무는 brief가 null일 수 있다. 과거 요청을 최신 분석으로 소급 구성하지 말고 기존 제목/사유를 표시한다.

## 권한·DB·로그

수신자는 전달된 brief를 확인하지만 원래 사건·분석 전체 API 권한을 추가로 얻지 않는다. 알림에는 brief를 넣지 않는다. 기존 Agent 직접 요청의 payload.brief 경로는 그대로 유지한다.

승인함의 맥락을 보존하기 위해 `work_item.brief` nullable JSONB 컬럼 하나가 추가된다. 다른 팀 요청은 기존 check_request.brief를 재사용한다. 기존 ddl-auto=update 기준 새 이미지 기동 시 컬럼이 추가되는 변경이며, 실제 서비스 DB 초기화·배포는 하지 않았다.

원문·brief 내용은 새 서버 로그에 출력하지 않는다. 기존 ID 기반 상태·알림 로그만 사용한다.


## 최종 검증 — 2026-10-22

- `./gradlew test --offline`: 총 217건 중 215건 통과, 실패/오류 0건, 기존 Knowledge 테스트 2건 건너뜀.
- 실측값/z를 기존 계측 서비스와 대조, 정비 기록 전달, 대상 공정 관련 후보만 미확정으로 제공, 확인 항목 연결, 승인함·다른 팀 요청의 snapshot 보존, 기존 수신자 권한과 JSONB/Swagger를 검증했다.
- `git diff --check` 통과. 실제 Python Agent·브라우저·배포 nginx 통합 및 서비스 재배포는 미수행.
- 프론트 연결·실제 서비스 배포는 별도 작업이다.

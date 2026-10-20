#!/usr/bin/env bash
# FAB CONTEXT API 전체 흐름 스모크 테스트 (curl + jq)
# 사용: ./scripts/smoke-test.sh [BASE_URL]   기본 http://localhost:8090
set -uo pipefail
BASE=${1:-http://localhost:8090}
TMP=$(mktemp -d)
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); printf '  \033[32m✔\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31m✘\033[0m %s\n' "$1"; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
key()  { echo "idem-$(date +%s%N)-$RANDOM"; }

# req <jar> <method> <path> [body] [extra curl args...] → BODY, CODE, HEADERS 설정
req() {
  local jar=$1 method=$2 path=$3 body=${4:-}; shift 4 2>/dev/null || shift $#
  local csrf; csrf=$(cat "$TMP/$jar.csrf" 2>/dev/null || true)
  local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" -c "$TMP/$jar.jar" -b "$TMP/$jar.jar"
    -H "X-CSRF-TOKEN: $csrf")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  CODE=$(curl "${args[@]}" "$@" "$BASE$path")
  BODY=$(cat "$TMP/body"); HEADERS=$(cat "$TMP/headers")
}
expect() { # expect <code> <label>
  if [[ "$CODE" == "$1" ]]; then ok "$2 ($CODE)"; else bad "$2 — expected $1, got $CODE: ${BODY:0:300}"; fi
}
j() { echo "$BODY" | jq -r "$1"; }
check() { # check <label> <actual> <expected>
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1 — expected '$3', got '$2'"; fi
}

login() { # login <jar> <username>
  req "$1" GET /api/auth/csrf ""; j .data.token > "$TMP/$1.csrf"
  req "$1" POST /api/auth/login "{\"username\":\"$2\",\"password\":\"fab1234!\"}"; expect 200 "login $2"
}
wait_job() { # wait_job <jar> <jobId> <status...>
  local jar=$1 id=$2; shift 2
  for _ in $(seq 1 30); do
    req "$jar" GET "/api/jobs/$id" ""
    local s; s=$(j .data.status)
    for want in "$@"; do [[ "$s" == "$want" ]] && { ok "job $id → $s"; return 0; }; done
    sleep 1
  done
  bad "job $id did not reach $* (last: $(j .data.status))"; return 1
}
incident_version() { req "$1" GET "/api/incidents/$2" ""; j .data.version; }

step "1. 인증 (P01)"
req anon GET /api/auth/session ""; expect 401 "session without login"
req anon POST /api/auth/login '{"username":"cmp.park","password":"fab1234!"}'; expect 403 "login without CSRF"
login etch cmp.park
req etch GET /api/auth/session ""; expect 200 "session"; check "role ENGINEER" "$(j .data.role)" "ENGINEER"
req anon GET /api/auth/csrf ""; j .data.token > "$TMP/anon.csrf"
req anon POST /api/auth/login '{"username":"cmp.park","password":"wrong"}'; expect 401 "wrong password"

step "2. 대시보드 · 사건 (P02, P03)"
req etch GET "/api/dashboard?period=7d" ""; expect 200 "dashboard"
req etch GET "/api/dashboard?period=1y" ""; expect 400 "dashboard invalid period"
req etch GET "/api/incidents?q=EV-1009" ""; expect 200 "listIncidents q=EV-1009"
INC=$(j '.data.items[0].incidentId'); echo "    EV-1009 = $INC"
# 시드에서 QUEUED로 둔 BASIC 분석을 시뮬레이터가 끝낼 때까지 기다린다
req etch GET "/api/incidents/$INC" ""; J0=$(j '.data.activeJob.jobId // empty'); [[ -n "$J0" ]] && wait_job etch "$J0" SUCCEEDED
req etch GET "/api/incidents?size=2" ""; CUR=$(j .data.nextCursor)
req etch GET "/api/incidents?size=2&cursor=$CUR" ""; expect 200 "listIncidents next cursor"
req etch GET "/api/incidents?processCode=CVD" ""; expect 403 "listIncidents out of scope"
req etch GET "/api/incidents/$INC" ""; expect 200 "getIncident"; [[ $(j '.data.timeline|length') -ge 2 ]] && ok "timeline present" || bad "timeline missing"

step "3. 공정 데이터 (P04~P06)"
req etch GET "/api/incidents/$INC/sensor-data?includeBaseline=true&sensorId=head_pressure" ""; expect 200 "getSensorData + reference band"
check "baseline null + 이유 (정상 기준 trace 없음 — 참고값은 모니터링 상세)" "$(j '.data.baseline')/$(j '.data.baselineUnavailableReason | test("monitoring")')" "null/true"
[[ $(j '.data.totalCount') -gt 0 ]] && ok "sensor samples in default window ($(j .data.totalCount))" || bad "no sensor samples in default window"
check "quality summary all GOOD_SYNTHETIC" "$(j '.data.qualitySummary.GOOD_SYNTHETIC')" "$(j .data.totalCount)"
req etch GET "/api/incidents/$INC/sensor-data?limit=10" ""; check "truncated with limit=10" "$(j .data.truncated)" "true"
req etch GET "/api/incidents/$INC/sensor-data?sensorId=head_pressure" ""; check "default limit 5000 → not truncated · recipeStep READY/PROCESS/END" "$(j .data.truncated)/$(j '[.data.samples[].recipeStep] | unique | join(",")')" "false/END,PROCESS,READY"
req etch GET "/api/incidents/$INC/sensor-data?from=bad" ""; expect 400 "sensor-data invalid from"
req etch GET "/api/incidents/$INC/metrology?stage=AFTER_CMP" ""; expect 200 "getMetrology AFTER_CMP ($(j .data.totalCount) items)"
req etch GET "/api/wafers/W-CURRENT-009-3/route" ""; expect 200 "getRoute W-CURRENT-009-3 ($(j '.data.runs|length') runs)"
req etch GET "/api/incidents/$INC/changes" ""; expect 200 "getChanges ($(j '.data.items|length') items, relations: $(j '[.data.items[].relation]|unique|join(",")'))"
req etch GET "/api/incidents/$INC/comparisons?scope=CURRENT" ""; expect 200 "getComparisons CURRENT"
check "CURRENT(CMP) above UCL, within spec" "$(j .data.evidence.r1.side)/$(j .data.evidence.oos)" "ABOVE_UCL/false"
req etch GET "/api/incidents/$INC/comparisons?scope=UPSTREAM" ""; check "UPSTREAM(ETCH) removal outside normal variation" "$(j .data.evidence.amountWithinNormalVariation)" "false"

step "4. AI 조사 → AI 제안 (P07)"
req etch GET "/api/incidents/$INC/analysis-results?latest=false" ""; expect 200 "getResults (BASIC, $(j '.data.candidateCauses|length') causes)"
V=$(incident_version etch "$INC")
req etch POST "/api/incidents/$INC/questions" "{\"question\":\"이전 공정(ETCH) 영향도 확인해줘\",\"expectedIncidentVersion\":$((V-1))}" -H "Idempotency-Key: $(key)"
expect 409 "askQuestion stale version"
K=$(key); Q="{\"question\":\"이전 공정(ETCH) 영향도 확인해줘\",\"scope\":\"AUTO\",\"expectedIncidentVersion\":$V}"
req etch POST "/api/incidents/$INC/questions" "$Q" -H "Idempotency-Key: $K"; expect 202 "askQuestion"
JOB=$(j .data.jobId)
req etch POST "/api/incidents/$INC/questions" "$Q" -H "Idempotency-Key: $K"; expect 202 "askQuestion replay same key"
[[ $(j .data.jobId) == "$JOB" ]] && grep -qi "Idempotent-Replayed: true" <<<"$HEADERS" && ok "replay returned same job" || bad "replay returned same job"
req etch POST "/api/incidents/$INC/questions" '{"question":"다른 질문","expectedIncidentVersion":1}' -H "Idempotency-Key: $K"; expect 422 "same key, different body"
req etch POST "/api/incidents/$INC/questions" "$Q"; expect 400 "missing Idempotency-Key"
wait_job etch "$JOB" WAITING_APPROVAL

step "5. 승인함 (P08)"
req etch GET "/api/approvals?status=PENDING&incidentId=$INC" ""; expect 200 "listApprovals PENDING"
APV=$(j '.data.items[0].approvalId')
req etch GET "/api/approvals/$APV" ""; expect 200 "getApproval ($(j .data.actionType), source $(j .data.source))"
check "공개 API actionType = 프론트 계약 이름" "$(j .data.actionType)" "CHECK_REQUEST"
AV=$(j .data.version); HASH=$(j .data.actionHash)
# 시뮬레이터 대역은 원인을 판정하지 않는다 — 정해진 모양의 제안(고정 예시 브리프) 하나. 판정 확인(EV-1009 → Etch 담당 · ETCH_WALL)은
# fab-agent의 pytest · fab-eval · compose_smoke.py가 한다
BRIEF=$(j .data.payload.brief.reason)
check "AI 제안에 요청 브리프 (시뮬레이터 예시 — 원인 후보 없음)" \
  "$(j '.data.payload.brief.reason | startswith("시뮬레이터 예시")')/$(j '.data.payload.brief.suspectedCauses | length')" "true/0"
req etch POST "/api/approvals/$APV/decisions" "{\"decision\":\"APPROVE\",\"expectedVersion\":$AV,\"actionHash\":\"sha256:tampered\"}" -H "Idempotency-Key: $(key)"
expect 409 "decide with wrong actionHash"
req etch POST "/api/approvals/$APV/decisions" "{\"decision\":\"APPROVE\",\"comment\":\"확인 요청 발송\",\"expectedVersion\":$AV,\"actionHash\":\"$HASH\"}" -H "Idempotency-Key: $(key)"
expect 200 "decideApproval APPROVE"
RESUME1=$(j .data.resumeJobId)
check "approval only recorded (no executionRef yet)" "$(j .data.executionRef)" "null"
req etch POST "/api/approvals/$APV/decisions" "{\"decision\":\"APPROVE\",\"expectedVersion\":$AV}" -H "Idempotency-Key: $(key)"; expect 409 "decide again"
wait_job etch "$RESUME1" WAITING_REPLY
# 재개된 Agent가 승인 ID로 실행한 뒤에야 확인 요청이 생긴다
req etch GET "/api/approvals/$APV" ""; CHK=$(j .data.executionRef.id)
echo "    executionRef=$(j .data.executionRef.type)/$CHK resumeJobId=$RESUME1"
req etch GET "/api/incidents/$INC" ""; check "incident WAITING_REPLY" "$(j .data.status)" "WAITING_REPLY"

step "6. 확인 요청함 · 회신 (P09)"
login photo etch.lee
req photo GET "/api/incidents/$INC" ""; expect 403 "ETCH engineer cannot read CMP incident"
req photo GET "/api/check-requests?box=received&status=OPEN" ""; expect 200 "listRequests received"
check "received request is $CHK" "$(j '.data.items[0].requestId')" "$CHK"
req photo GET "/api/check-requests/$CHK" ""; expect 200 "getRequest (with incident context)"; RV=$(j .data.version)
check "받는 사람이 보는 브리프 = 승인한 브리프" "$(j .data.brief.reason)/$(j '.data.brief.suspectedCauses | length')" "$BRIEF/0"
req etch POST "/api/check-requests/$CHK/replies" "{\"findings\":\"x\",\"confirmedByUser\":true,\"expectedVersion\":$RV}" -H "Idempotency-Key: $(key)"
expect 403 "non-owner cannot reply"
req photo POST "/api/check-requests/$CHK/replies" "{\"findings\":\"ETCH-PM2 정비 후 시즈닝 매수가 절차보다 적었습니다. 모니터 결과를 첨부합니다\",\"evidenceIds\":[\"ev:change:DEMO-CHANGE-E02\"],\"confirmedByUser\":true,\"expectedVersion\":$RV}" -H "Idempotency-Key: $(key)"
expect 201 "submitReply"; RESUME2=$(j .data.resumeJobId)
wait_job etch "$RESUME2" SUCCEEDED
req etch GET "/api/incidents/$INC/analysis-results" ""; check "latest result is RESUME" "$(j .data.kind)" "RESUME"
req etch GET "/api/incidents/$INC" ""; check "incident back to INVESTIGATING" "$(j .data.status)" "INVESTIGATING"
req etch GET "/api/check-requests?box=sent" ""; expect 200 "listRequests sent"

step "7. 조치 · 효과 (P10)"
V=$(incident_version etch "$INC")
req etch POST "/api/incidents/$INC/actions" "{\"actionType\":\"CHAMBER_SEASONING\",\"description\":\"ETCH-PM2 추가 시즈닝 실시\",\"performedAt\":\"2099-01-01T00:00:00Z\",\"confirmedByUser\":true,\"expectedIncidentVersion\":$V}" -H "Idempotency-Key: $(key)"
expect 422 "recordAction future performedAt"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
req etch POST "/api/incidents/$INC/actions" "{\"actionType\":\"CHAMBER_SEASONING\",\"description\":\"ETCH-PM2 추가 시즈닝 실시\",\"performedAt\":\"$NOW\",\"evidenceIds\":[],\"confirmedByUser\":true,\"expectedIncidentVersion\":$V}" -H "Idempotency-Key: $(key)"
expect 201 "recordAction"; ACT=$(j .data.actionId)
req etch POST "/api/actions/$ACT/verifications" '{"outcome":"IMPROVED","rationale":"시즈닝 후 확인 run의 Etch 제거량이 정상 분포로 복귀","confirmedByUser":true,"expectedVersion":1}' -H "Idempotency-Key: $(key)"
expect 201 "verifyAction IMPROVED"
req etch GET "/api/incidents/$INC/actions" ""; check "listActions shows IMPROVED" "$(j '.data.items[0].verificationStatus')" "IMPROVED"

step "8. 보고서 (P11)"
V=$(incident_version etch "$INC")
req etch POST "/api/incidents/$INC/report-jobs" "{\"expectedIncidentVersion\":$V}" -H "Idempotency-Key: $(key)"; expect 202 "generateReport"
wait_job etch "$(j .data.jobId)" SUCCEEDED
req etch GET "/api/incidents/$INC/reports" ""; expect 200 "listReports"; RPT=$(j '.data.items[0].reportId')
req etch GET "/api/reports/$RPT" ""; expect 200 "getReport ($(j '[.data.sections[].key]|join(",")'))"
check "sections = 배열 다섯 칸, 순서 고정" "$(j '[.data.sections[].key]|join(",")')" "overview,investigation,requestsAndReplies,actionsAndVerification,unresolved"
SECTIONS=$(j '.data.sections')
req etch PATCH "/api/reports/$RPT" "{\"sections\":$SECTIONS,\"expectedContentRevision\":99}"; expect 409 "editReport revision conflict"
EDITED=$(echo "$SECTIONS" | jq '.[0].body += "\n- 검토 메모: 시즈닝 후 개선 확인"')
BAD=$(echo "$SECTIONS" | jq '[.[1], .[0], .[2], .[3], .[4]]')
req etch PATCH "/api/reports/$RPT" "{\"sections\":$BAD,\"expectedContentRevision\":1}"; expect 422 "editReport rejects reordered sections"
req etch PATCH "/api/reports/$RPT" "{\"sections\":$EDITED,\"expectedContentRevision\":1}"; expect 200 "editReport"
req etch POST "/api/reports/$RPT/confirmations" '{"expectedContentRevision":2,"reviewConfirmed":false}' -H "Idempotency-Key: $(key)"; expect 422 "confirm without review"
req etch POST "/api/reports/$RPT/confirmations" '{"expectedContentRevision":2,"reviewConfirmed":true}' -H "Idempotency-Key: $(key)"; expect 200 "confirmReport"
req etch PATCH "/api/reports/$RPT" "{\"sections\":$EDITED,\"expectedContentRevision\":2}"; expect 409 "edit confirmed report"
req etch GET "/api/reports/$RPT/export?format=markdown" ""; expect 200 "exportReport"
grep -qi "content-disposition: attachment" <<<"$HEADERS" && head -c 1 <<<"$BODY" | grep -q "#" && ok "markdown attachment" || bad "markdown attachment"

step "8b. 모니터링 (정상 LOT · PM)"
req etch GET "/api/monitoring/lots?processCode=CMP" ""; expect 200 "listMonitoringLots"; check "CURRENT LOT 10개" "$(j '.data.items|length')" "10"
req etch GET "/api/monitoring/lots?processCode=CVD" ""; expect 403 "monitoring lots out of scope"
req etch GET "/api/monitoring/lots?processCode=PHOTO" ""; expect 422 "monitoring lots invalid processCode"
req etch GET "/api/monitoring/lots/CURRENT-L006/summary?processCode=CMP" ""; expect 200 "getMonitoringSummary L006"
check "L006 CMP-PM3 v2 출고 평균 · 사건 연결" "$(j '.data.runs[2].outputMeanA')/$(j '.data.runs[2].incidentId != null')/$(j '.data.runs[0].incidentId')" "10281.392216/true/null"
check "sensorEnvelopes · references 별도 (5 · 5)" "$(j '.data.sensorEnvelopes|length')/$(j '.data.references|length')/$(j .data.dataVersion)" "5/5/causal-pack-v2-scenarios"
req etch GET "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data?sensorId=head_pressure" ""; expect 200 "getRunSensorData"
check "L006 head_pressure 전체 3201점 · baseline null" "$(j .data.totalCount)/$(j .data.truncated)/$(j .data.baseline)" "3201/false/null"
req etch GET "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data?sensorId=head_pressure&limit=0" ""; expect 422 "monitoring sensor-data invalid limit"

step "9. 지식 검색 (P12)"
Q_ENC=$(jq -rn --arg q "헤드 압력" '$q|@uri')
req etch GET "/api/knowledge/documents?q=$Q_ENC" ""; expect 200 "searchDocuments ($(j '.data.items|length') hits)"
DOC=$(j '.data.items[0].docId')
req etch GET "/api/knowledge/documents/$DOC" ""; expect 200 "getDocument $DOC (versions $(j '.data.versions|join(",")'))"
req etch GET "/api/knowledge/documents/$DOC/content" ""; expect 200 "readDocument"
req etch GET "/api/knowledge/documents/DEMO-CVD_GAS" ""; expect 403 "CVD document out of scope"

step "10. 관리자 상태 전이 (P03)"
V=$(incident_version etch "$INC")
req etch POST "/api/incidents/$INC/transitions" "{\"targetStatus\":\"CLOSED\",\"reason\":\"x\",\"expectedVersion\":$V}"; expect 403 "engineer cannot transition"
login mgr manager.choi
req mgr POST "/api/incidents/$INC/transitions" "{\"targetStatus\":\"INVESTIGATING\",\"reason\":\"x\",\"expectedVersion\":$V}"; expect 409 "invalid transition"
req mgr POST "/api/incidents/$INC/transitions" "{\"targetStatus\":\"ON_HOLD\",\"reason\":\"추가 데이터 대기\",\"expectedVersion\":$V}"; expect 200 "ON_HOLD"
req mgr POST "/api/incidents/$INC/transitions" "{\"targetStatus\":\"INVESTIGATING\",\"reason\":\"재개\",\"expectedVersion\":$(j .data.version)}"; expect 200 "ON_HOLD → INVESTIGATING"
req mgr POST "/api/incidents/$INC/transitions" "{\"targetStatus\":\"CLOSED\",\"reason\":\"조치 효과 확인, 보고서 확정\",\"evidenceIds\":[\"$ACT\"],\"expectedVersion\":$(j .data.version)}"; expect 200 "CLOSED"
req etch POST "/api/incidents/$INC/questions" "{\"question\":\"q\",\"expectedIncidentVersion\":$(j .data.version)}" -H "Idempotency-Key: $(key)"; expect 409 "closed incident rejects new job"

step "11. 이벤트 접수 · 분석 실패 · 재실행"
EV="{\"eventId\":\"EVT-SMOKE-$RANDOM$RANDOM\",\"anomalyType\":\"THICKNESS_OOC_R1\",\"severity\":\"MEDIUM\",\"processCode\":\"CMP\",\"equipmentId\":\"CMP-PM3\",\"lotId\":\"CURRENT-L007\",\"waferIds\":[\"W-CURRENT-007-3\"],\"runIds\":[\"RUN-W-CURRENT-007-3-CMP\"],\"detectedAt\":\"$NOW\",\"metrics\":{\"simulateAgentFailure\":true}}"
req anon POST /api/events/anomalies "$EV"; expect 401 "ingest without service token"
req anon POST /api/events/anomalies "$EV" -H "X-Service-Token: dev-simulator-token"; expect 202 "ingestAnomaly new"
NEW=$(j .data.incidentId); NJ=$(j .data.jobId)
req anon POST /api/events/anomalies "$EV" -H "X-Service-Token: dev-simulator-token"; expect 200 "ingestAnomaly duplicate"
[[ $(j .data.duplicate) == true && $(j .data.incidentId) == "$NEW" ]] && ok "duplicate returns existing incident" || bad "duplicate returns existing incident"
wait_job etch "$NJ" FAILED
req etch GET "/api/incidents/$NEW/sensor-data" ""; expect 200 "raw data still readable after failure"
req etch GET "/api/incidents/$NEW/analysis-results" ""; expect 404 "no result yet"
V=$(incident_version etch "$NEW")
req etch POST "/api/incidents/$NEW/basic-analysis-jobs" "{\"expectedIncidentVersion\":$V,\"reason\":\"실패 후 재실행\"}" -H "Idempotency-Key: $(key)"
expect 202 "rerunBasicAnalysis (attempt $(curl -s -b "$TMP/etch.jar" "$BASE/api/jobs/$(j .data.jobId)" | jq .data.attempt))"
wait_job etch "$(j .data.jobId)" SUCCEEDED

step "12. SSE · 로그아웃"
SSE=$(curl -s -N --max-time 2 -b "$TMP/etch.jar" "$BASE/api/incidents/$NEW/stream" || true)
grep -q "event:snapshot" <<<"$SSE" && ok "incidentStream sends snapshot" || bad "incidentStream: ${SSE:0:200}"
req etch POST /api/auth/logout ""; expect 204 "logout"
req etch GET /api/auth/session ""; expect 401 "session after logout"

printf '\n\033[1m결과: %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
rm -rf "$TMP"
[[ $FAIL -eq 0 ]]

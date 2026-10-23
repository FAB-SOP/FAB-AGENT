#!/usr/bin/env bash
# 보고서 목록 · 제목 검색(GET /api/reports) 조회 계획 측정 — 인덱스 전 · 후 EXPLAIN (ANALYZE, BUFFERS)
# 사용: ops/tools/report_search_explain.sh <DB_URL> [SCHEMA]
#   예: ops/tools/report_search_explain.sh postgresql://fab:fab@127.0.0.1:55434/fab backend
# 반드시 버려도 되는 임시 DB에서 돌린다 — 사건 2만 · 보고서 10만 건(bench_ 접두사)을 넣고, 측정용 인덱스를 만들었다 지운다.
# 스키마는 백엔드를 그 DB로 한 번 띄워(DDL_AUTO=create) 만들어 둔다. 데이터는 고정 시드로 매번 같다.
set -euo pipefail
DB_URL=${1:?DB_URL이 필요합니다 (예: postgresql://fab:fab@127.0.0.1:55434/fab)}
SCHEMA=${2:-backend}
PSQL=(psql "$DB_URL" -v ON_ERROR_STOP=1 -X -q -At)
SQL_SELECT="select r.report_id, r.incident_id, i.display_id, r.process_code, r.version, r.status, r.title,
  r.content_revision, r.created_at, r.confirmed_at, i.detected_at, r.cause_category,
  array_to_string(array(select jsonb_array_elements_text(r.cause_equipment_parameters)), ','),
  r.classified_by, r.classified_at
from $SCHEMA.report r join $SCHEMA.incident i on i.incident_id = r.incident_id"
SQL_ORDER="order by i.detected_at desc, r.report_id limit 21"

# ReportSearchQuery가 만드는 것과 같은 모양의 대표 쿼리 — "이름|WHERE 절|OFFSET"
QUERIES=(
  "① 검색어 없는 첫 페이지|r.process_code = 'CMP' and i.process_code = 'CMP'|0"
  "② 사건 번호 검색(EV-1123)|r.process_code = 'CMP' and i.process_code = 'CMP' and r.title ilike '%EV-1123%' escape '\\'|0"
  "③ 현상 검색(관리한계) + Machine + 기간|r.process_code = 'CMP' and i.process_code = 'CMP' and r.title ilike '%관리한계%' escape '\\' and r.cause_category = 'MACHINE' and i.detected_at >= now() - interval '90 days'|0"
  "③-1 흔한 단어 검색만(관리한계, 보고서 20%)|r.process_code = 'CMP' and i.process_code = 'CMP' and r.title ilike '%관리한계%' escape '\\'|0"
  "④ 결과 없는 검색|r.process_code = 'CMP' and i.process_code = 'CMP' and r.title ilike '%없는제목zz%' escape '\\'|0"
  "⑤ 깊은 페이지(offset 20000)|r.process_code = 'CMP' and i.process_code = 'CMP'|20000"
)

seed() {
  echo "▶ 측정 데이터 생성: 사건 20,000 · 보고서 100,000 (setseed 0.42)"
  "${PSQL[@]}" <<SQL
delete from $SCHEMA.report where report_id like 'bench_%';
delete from $SCHEMA.incident where incident_id like 'bench_%';
select setseed(0.42);
insert into $SCHEMA.incident (incident_id, display_id, display_seq, process_code, equipment_id, title, status,
  severity, detected_at, created_at, updated_at, version, row_version)
select 'bench_inc_' || g, 'EV-' || (100000 + g), 100000 + g, p.code, p.code || '-PM' || (1 + g % 3),
  p.code || '-PM' || (1 + g % 3) || ' ' || (array['두께 관리한계 이탈', '균일도 저하', '파티클 증가', '식각률 변동', '압력 편차'])[1 + (g % 5)],
  'CLOSED', 'MAJOR', now() - (random() * 730) * interval '1 day', now(), now(), 1, 0
from generate_series(1, 20000) g
cross join lateral (select (array['CVD', 'ETCH', 'CMP'])[1 + g % 3] as code) p;
insert into $SCHEMA.report (report_id, incident_id, process_code, version, status, title, content_revision,
  source_revision, created_at, updated_at, row_version, cause_category, cause_equipment_parameters, classified_by, classified_at)
select 'bench_rpt_' || g, i.incident_id, i.process_code, 1 + (g / 20000), c.status,
  i.display_id || ' ' || i.title || ' 조사 보고서', 1, 1, i.detected_at + interval '1 day', i.detected_at + interval '1 day', 0,
  c.category,
  case when c.category = 'MACHINE' then jsonb_build_array((array['head_pressure', 'slurry_flow', 'chamber_pressure', 'gas_CF4'])[1 + g % 4]) else '[]'::jsonb end,
  case when c.category is null then null else 'bench' end,
  case when c.category is null then null else i.detected_at + interval '2 day' end
from generate_series(0, 99999) g
join $SCHEMA.incident i on i.incident_id = 'bench_inc_' || (1 + g % 20000)
cross join lateral (select (array['MATERIAL', 'MAN', 'METHOD', 'MACHINE', 'HOLD', null, null])[1 + (g * 7 + g / 20000) % 7] as category,
  case when g % 10 < 3 then 'DRAFT' else 'CONFIRMED' end as status) c;
analyze $SCHEMA.incident;
analyze $SCHEMA.report;
SQL
  echo "  report $("${PSQL[@]}" -c "select count(*) from $SCHEMA.report") 건 · incident $("${PSQL[@]}" -c "select count(*) from $SCHEMA.incident") 건"
}

drop_indexes() {
  "${PSQL[@]}" -c "drop index if exists $SCHEMA.idx_report_title_trgm; drop index if exists $SCHEMA.idx_incident_process_detected;"
}

create_indexes() {
  "${PSQL[@]}" <<SQL
create extension if not exists pg_trgm with schema public;
create index if not exists idx_report_title_trgm on $SCHEMA.report using gin (title public.gin_trgm_ops);
create index if not exists idx_incident_process_detected on $SCHEMA.incident (process_code, detected_at desc, incident_id);
analyze $SCHEMA.incident;
analyze $SCHEMA.report;
SQL
}

# 각 쿼리를 세 번 돌려 가장 빠른 실행 시간과 그때의 주요 계획 노드를 남긴다(첫 실행의 캐시 영향 제거)
measure() {
  local label=$1
  echo
  echo "### $label"
  echo "| 쿼리 | 주요 계획 노드 | 결과 행 | 실행 시간(ms, 3회 중 최소) |"
  echo "|------|----------------|---------|---------------------------|"
  local item name where offset best plan rows
  for item in "${QUERIES[@]}"; do
    IFS='|' read -r name where offset <<<"$item"
    best=""
    for _ in 1 2 3; do
      local out time
      out=$("${PSQL[@]}" -c "explain (analyze, buffers) $SQL_SELECT where $where $SQL_ORDER offset $offset")
      time=$(grep -oE 'Execution Time: [0-9.]+' <<<"$out" | grep -oE '[0-9.]+')
      if [[ -z "$best" ]] || awk "BEGIN{exit !($time < $best)}"; then
        best=$time
        plan=$( (grep -oE '(Parallel )?(Seq Scan|Index Scan|Index Only Scan|Bitmap Index Scan|Bitmap Heap Scan)( using [a-z0-9_]+)?( on [a-z_]+)?' <<<"$out" || true) | sort -u | paste -sd ',' - | sed 's/,/, /g')
      fi
    done
    rows=$("${PSQL[@]}" -c "select count(*) from ($SQL_SELECT where $where $SQL_ORDER offset $offset) q")
    echo "| $name | $plan | $rows | $best |"
  done
}

seed
drop_indexes
measure "인덱스 없음 (기존 incident_id 인덱스만)"
create_indexes
measure "인덱스 있음 (pg_trgm GIN 제목 + incident(process_code, detected_at desc, incident_id))"
echo
echo "인덱스 크기: $("${PSQL[@]}" -c "select string_agg(indexname || ' ' || pg_size_pretty(pg_relation_size('$SCHEMA.' || indexname)), ' · ') from pg_indexes where schemaname = '$SCHEMA' and indexname in ('idx_report_title_trgm', 'idx_incident_process_detected')")"

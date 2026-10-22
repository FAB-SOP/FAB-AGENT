package com.fabcontext.api.internal.query;

import java.time.Instant;
import java.util.*;
import com.fabcontext.api.domain.processdata.*;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.*;
import com.fabcontext.api.domain.processdata.PriorityEvidenceCalculator.Observation;
import com.fabcontext.api.internal.InternalError;
import com.fabcontext.api.internal.view.InternalViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 계산 근거와 설정 기반 조사 우선순위. 조회는 사건·보고서·Agent 체크포인트를 변경하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class InternalPriorityEvidenceQuery {
	private final RouteRunRepository routes;
	private final MetrologyRepository metrology;
	private final PackStore pack;
	private final PriorityEvidencePolicy policy;
	private final InternalViews views;
	private static final Set<String> SITES=Set.of("CENTER","NORTH","EAST","SOUTH","WEST");

	public Map<String,Object> evidence(String runId,Instant requestedAsOf,String policyVersion) {
		return evidence(runId, requestedAsOf, policyVersion, false);
	}

	public Map<String,Object> evidence(String runId,Instant requestedAsOf,String policyVersion,boolean includeDetails) {
		if(!policy.version().equals(policyVersion)) throw InternalError.invalid("지원하지 않는 policyVersion입니다.","지원 버전: "+policy.version());
		Instant asOf=requestedAsOf==null?pack.analysisAsOf():requestedAsOf;
		if(asOf.isAfter(pack.analysisAsOf())) throw new InternalError("FORBIDDEN","서버의 분석 기준 시각 이후 자료는 조회할 수 없습니다.","job context의 analysisAsOf를 전달하세요.");
		RouteRun run=routes.findByRunId(runId).orElseThrow(()->InternalError.notFound("run을 찾을 수 없습니다: "+runId,"get_wafer_lineage로 runId를 확인하세요."));
		if(run.getStartedAt()==null || run.getStartedAt().isAfter(asOf) || "FOLLOWUP".equals(run.getPhase()))
			throw new InternalError("FORBIDDEN","미래 또는 FOLLOWUP run은 현재 우선순위의 근거로 사용할 수 없습니다.","");
		List<String> missing=new ArrayList<>();
		List<Map<String,Object>> refs=new ArrayList<>();
		List<Map<String,Object>> measurements=new ArrayList<>();
		List<RouteRun> lineage=routes.findByWaferIdOrderBySequenceNoAsc(run.getWaferId());
		for(String process:List.of("CVD","ETCH","CMP")) {
			List<RouteRun> stages=lineage.stream().filter(r->process.equals(r.getProcessCode()) && !"FOLLOWUP".equals(r.getPhase())).toList();
			if(stages.size()!=1) {
				measurements.add(Map.of("processCode",process,"status",stages.isEmpty()?"MISSING":"AMBIGUOUS"));
				missing.add("METROLOGY_ROUTE_"+process); continue;
			}
			RouteRun stage=stages.getFirst();
			Map<String,Object> row=measurement(stage,asOf);
			measurements.add(row);
			if(!"AVAILABLE".equals(row.get("status"))) missing.add("METROLOGY_"+process+":"+row.get("status"));
			else refs.add(ref("E-PRMET-"+stage.getRunId(),"METROLOGY",stage.getRunId(),asOf));
		}
		List<RouteRun> comparable=comparable(run,asOf);
		boolean historyComparable=matchKeysPresent(run) && comparable.size()==policy.repeatCount() && comparable.stream().anyMatch(r->r.getRunId().equals(runId));
		if(!historyComparable) missing.add("COMPARABLE_HISTORY_INSUFFICIENT");
		List<String> sensorIds=pack.sensors().stream().filter(s->run.getProcessCode().equals(s.get("process")))
			.map(s->String.valueOf(s.get("sensorId"))).distinct().sorted().toList();
		if(sensorIds.isEmpty()) missing.add("SENSOR_CATALOG_MISSING");
		List<Map<String,Object>> sensors=new ArrayList<>();
		List<String> normal=new ArrayList<>();
		Map<String,Observation> observations=new LinkedHashMap<>();
		for(String id:sensorIds) {
			Map<String,Object> envelope=pack.envelope(run.getProcessCode(),id).orElse(null);
			Observation observed=PriorityEvidenceCalculator.observe(run,pack.trace(runId,id).orElse(null),envelope,asOf,policy);
			observations.put(id,observed);
			if(!"COMPLETE".equals(observed.status())) missing.add("SENSOR_"+id+":"+String.join(",",observed.missingData()));
			if(observed.segments().isEmpty() && "COMPLETE".equals(observed.status())) { normal.add(id); continue; }
			Map<String,Object> row=new LinkedHashMap<>();
			String evidenceId="E-PRSEG-"+runId+"-"+id;
			row.put("sensorId",id); row.put("evidenceId",evidenceId); row.put("status",observed.status()); row.put("missingData",observed.missingData());
			row.put("unit",envelope==null?null:envelope.get("unit"));
			row.put("bounds",envelope==null || !finite(envelope.get("lower")) || !finite(envelope.get("upper"))?null:Map.of("lower",envelope.get("lower"),"upper",envelope.get("upper")));
			row.put("segments",observed.segments().stream().limit(3).toList());
			row.put("segmentCount",observed.segments().size()); row.put("segmentsTruncated",observed.segments().size()>3);
			row.put("maxDurationSeconds",observed.segments().stream().mapToDouble(PriorityEvidenceCalculator.Segment::durationSeconds).max().orElse(0));
			row.put("allRecovered",observed.segments().isEmpty()?null:observed.segments().stream().allMatch(PriorityEvidenceCalculator.Segment::recovered));
			List<Map<String,Object>> history=new ArrayList<>();
			for(RouteRun candidate:comparable) {
				Observation previous=candidate.getRunId().equals(runId)?observed:PriorityEvidenceCalculator.observe(candidate,pack.trace(candidate.getRunId(),id).orElse(null),envelope,asOf,policy);
				history.add(Map.of("runId",candidate.getRunId(),"status",previous.status(),"hasDeviation",!previous.segments().isEmpty()));
			}
			long observedCount=history.stream().filter(h->"COMPLETE".equals(h.get("status"))).count();
			boolean complete=historyComparable && observedCount==policy.repeatCount();
			Map<String,Object> repetition=new LinkedHashMap<>();
			repetition.put("status",complete?"COMPLETE":"INSUFFICIENT");
			repetition.put("observedDeviationRunCount",history.stream().filter(h->Boolean.TRUE.equals(h.get("hasDeviation"))).count());
			repetition.put("runs",history); row.put("repetition",repetition);
			if(!complete) missing.add("REPETITION_"+id+"_INSUFFICIENT");
			sensors.add(row); refs.add(ref(evidenceId,"SENSOR",runId+":"+id,asOf));
		}
		List<Map<String,Object>> groups=new ArrayList<>();
		for(Map<String,Object> group:policy.groups()) {
			if(!run.getProcessCode().equals(group.get("process"))) continue;
			List<?> members=(List<?>)group.get("sensorIds");
			if(members.size()!=2) throw new IllegalStateException("현재 계산 설정은 센서 2종 그룹만 지원합니다.");
			Observation left=observations.get(String.valueOf(members.get(0))),right=observations.get(String.valueOf(members.get(1)));
			boolean complete=left!=null && right!=null && "COMPLETE".equals(left.status()) && "COMPLETE".equals(right.status());
			groups.add(Map.of("groupId",group.get("groupId"),"sensorIds",members,"status",complete?"COMPLETE":"INSUFFICIENT",
				"observedMaxOverlapSeconds",left==null || right==null?0:PriorityEvidenceCalculator.maximumOverlap(left.segments(),right.segments())));
		}
		Map<String,Object> data=new LinkedHashMap<>();
		data.put("schemaVersion","1.0"); data.put("runId",runId); data.put("waferId",run.getWaferId()); data.put("processCode",run.getProcessCode());
		data.put("equipmentId",run.getEquipmentId()); data.put("asOf",asOf.toString()); data.put("policy",policy.metadata());
		data.put("metrology",measurements); data.put("sensorObservations",sensors); data.put("normalSensorIds",normal);
		data.put("relatedSensorGroups",groups); data.put("comparisonRunIds",comparable.stream().map(RouteRun::getRunId).toList());
		data.put("validDeviationSensorCount",observations.values().stream().filter(o->!o.segments().isEmpty()).count());
		data.put("missingData",missing.stream().distinct().toList());
		data.put("assessment",InvestigationPriorityRules.assess(measurements,sensors,groups,missing.stream().distinct().toList(),asOf.toString(),policy));
		if(!includeDetails) {
			// MCP의 6,000자 한도를 위해 원시 구간·run별 반복 상세를 기본 응답에서 제외한다. 계산은 전부 수행한다.
			data.put("metrology",measurements.stream().map(m->select(m,"processCode","status","meanA","ooc","oos","limitId","evidenceId")).toList());
			data.put("sensorObservations",sensors.stream().map(s->{
				Map<String,Object> row=select(s,"sensorId","evidenceId","status","maxDurationSeconds","allRecovered","segmentCount");
				Map<?,?> repeat=(Map<?,?>)s.get("repetition");
				row.put("repetition",Map.of("status",repeat.get("status"),"observedDeviationRunCount",repeat.get("observedDeviationRunCount")));
				return row;
			}).toList());
		}
		return views.env(data,refs,List.of("상중하는 등록 설정으로 계산합니다. Agent는 반환 등급·규칙을 유지하고 원인 후보·다음 확인을 분석합니다.",
			"DRAFT 시연 정책 계산이며 등급 정책을 자동 활성화하지 않습니다. 센서 방향 LOW는 우선순위 하를 뜻하지 않습니다.",
			"계측 품질 플래그는 현 DB에 없습니다. SYNTHETIC_OPTICAL_FILM_MODEL의 5개 위치·단위·공개 시각을 검증한 합성 계측만 계산합니다."));
	}

	private Map<String,Object> measurement(RouteRun run,Instant asOf) {
		Map<String,Object> row=new LinkedHashMap<>();
		row.put("runId",run.getRunId()); row.put("processCode",run.getProcessCode());
		if(run.getStartedAt()==null || run.getStartedAt().isAfter(asOf) || run.getEndedAt()==null || run.getEndedAt().isAfter(asOf)
			|| run.getMetrologyAvailableAt()==null || run.getMetrologyAvailableAt().isAfter(asOf)) { row.put("status","NOT_AVAILABLE_AS_OF"); return row; }
		List<Metrology> sites=metrology.findByRunIdOrderBySiteIdAsc(run.getRunId());
		Set<String> siteIds=new HashSet<>(); sites.forEach(s->siteIds.add(s.getSiteId()));
		boolean valid=sites.size()==5 && siteIds.equals(SITES) && sites.stream().allMatch(s->
			run.getWaferId().equals(s.getWaferId()) && "film_thickness".equals(s.getFeatureId()) && "Å".equals(s.getUnit())
			&& "SYNTHETIC_OPTICAL_FILM_MODEL".equals(s.getToolId()) && Double.isFinite(s.getValue()) && s.getMeasuredAt()!=null
			&& !s.getMeasuredAt().isAfter(asOf) && s.getAvailableAt()!=null && !s.getAvailableAt().isAfter(asOf));
		if(!valid) { row.put("status","MEASUREMENT_INCOMPLETE_OR_INVALID"); return row; }
		Map<String,Object> limit=pack.limit(run.getProcessCode()).orElse(null);
		if(limit==null || !"WAFER_5_SITE_MEAN".equals(limit.get("metric")) || !"Å".equals(limit.get("unit")) || !"R1_ONLY".equals(limit.get("rule"))
			|| !finite(limit.get("lcl")) || !finite(limit.get("ucl")) || number(limit.get("lcl"))>=number(limit.get("ucl"))) { row.put("status","LIMIT_MISSING_OR_INVALID"); return row; }
		double mean=sites.stream().mapToDouble(Metrology::getValue).average().orElseThrow();
		boolean spec=finite(limit.get("lsl")) && finite(limit.get("usl")) && number(limit.get("lsl"))<number(limit.get("usl"));
		row.put("status","AVAILABLE"); row.put("metric","WAFER_5_SITE_MEAN"); row.put("meanA",mean); row.put("qualityBasis","SYNTHETIC_STRUCTURAL_CHECK");
		row.put("limitId",limit.get("limitId")); row.put("lcl",limit.get("lcl")); row.put("ucl",limit.get("ucl"));
		row.put("ooc",mean<number(limit.get("lcl")) || mean>number(limit.get("ucl")));
		row.put("lsl",limit.get("lsl")); row.put("usl",limit.get("usl")); row.put("specRegistered",spec);
		row.put("oos",spec?mean<number(limit.get("lsl")) || mean>number(limit.get("usl")):null);
		row.put("evidenceId","E-PRMET-"+run.getRunId()); return row;
	}
	private List<RouteRun> comparable(RouteRun run,Instant asOf) {
		if(!matchKeysPresent(run)) return List.of();
		Instant end=run.getEndedAt()==null || run.getEndedAt().isAfter(asOf)?asOf:run.getEndedAt();
		return routes.findByProcessCodeAndEquipmentId(run.getProcessCode(),run.getEquipmentId()).stream()
			.filter(r->!"FOLLOWUP".equals(r.getPhase()) && r.getEndedAt()!=null && !r.getEndedAt().isAfter(end))
			.filter(r->Objects.equals(run.getChamberId(),r.getChamberId()) && Objects.equals(run.getRecipeId(),r.getRecipeId())
				&& Objects.equals(run.getProductId(),r.getProductId()) && Objects.equals(run.getLayerId(),r.getLayerId()))
			.filter(r->!r.getEndedAt().equals(end) || r.getRunId().equals(run.getRunId()))
			.sorted(Comparator.comparing(RouteRun::getEndedAt).reversed().thenComparing(RouteRun::getRunId))
			.limit(policy.repeatCount()).toList();
	}
	private static boolean matchKeysPresent(RouteRun run) { return java.util.stream.Stream.of(run.getProcessCode(),run.getEquipmentId(),run.getRecipeId(),run.getProductId(),run.getLayerId()).allMatch(s->s!=null && !s.isBlank()); }
	private Map<String,Object> ref(String id,String type,String source,Instant asOf) { Map<String,Object> ref=new LinkedHashMap<>(views.ref(id,type,source)); ref.put("asOf",asOf.toString()); ref.put("detectorVersion",policy.detectorVersion()); return ref; }
	private static boolean finite(Object value) { return value instanceof Number n && Double.isFinite(n.doubleValue()); }
	private static Map<String,Object> select(Map<String,Object> source,String... fields) {
		Map<String,Object> out=new LinkedHashMap<>();
		for(String field:fields) if(source.containsKey(field)) out.put(field,source.get(field));
		return out;
	}
	private static double number(Object value) { return ((Number)value).doubleValue(); }
}

package com.fabcontext.api.domain.processdata;

import java.time.Instant;
import java.util.*;
import com.fabcontext.api.domain.processdata.PackStore.Trace;

/** 원시 표본으로 관측값만 계산한다. HIGH/MEDIUM/LOW 판정은 하지 않는다. 기존 검출기를 변경하지 않는다. */
public final class PriorityEvidenceCalculator {
	private static final double EPS=1e-8;
	private PriorityEvidenceCalculator() {}
	public record Segment(double fromSeconds,double toSeconds,double durationSeconds,String direction,
		boolean recovered,double confirmedAtSeconds) {}
	public record Observation(String status,List<String> missingData,List<Segment> segments) {}
	private static final class Active {
		final double from;
		double to;
		final String direction;
		Active(double t,String direction) { from=t; to=t; this.direction=direction; }
	}

	public static Observation observe(RouteRun run,Trace trace,Map<String,Object> envelope,Instant asOf,PriorityEvidencePolicy policy) {
		if(trace==null) return missing("TRACE_MISSING");
		if(envelope==null) return missing("ENVELOPE_MISSING");
		if(!Set.of("GOOD","GOOD_SYNTHETIC").contains(Objects.toString(trace.quality(),""))) return missing("ACQUISITION_QUALITY_INVALID");
		if(trace.intervalMs()<=0 || trace.intervalMs()>policy.number("sourceCadenceMaxSeconds")*1000) return missing("CADENCE_UNSUPPORTED");
		// [t-window,t]의 실제 폭을 채울 수 없는 표본 주기는 정상으로 간주하지 않는다.
		double windowMs=policy.number("windowSeconds")*1000;
		if(Math.abs(windowMs/trace.intervalMs()-Math.rint(windowMs/trace.intervalMs()))>EPS) return missing("CADENCE_UNSUPPORTED");
		if(!run.getStartedAt().equals(trace.t0())) return missing("TRACE_TIMEBASE_MISMATCH");
		if(!(envelope.get("lower") instanceof Number low) || !(envelope.get("upper") instanceof Number high)
			|| !Double.isFinite(low.doubleValue()) || !Double.isFinite(high.doubleValue()) || low.doubleValue()>=high.doubleValue()) return missing("ENVELOPE_INVALID");
		double lower=low.doubleValue(),upper=high.doubleValue();
		double step=trace.intervalMs()/1000.0;
		double start=run.getReadySeconds()+policy.number("steadyStartOffsetSeconds");
		double end=run.getReadySeconds()+run.getProcessSeconds();
		if(end<=start || end-start<policy.number("windowSeconds")) return missing("STEADY_INTERVAL_TOO_SHORT");
		double availableSeconds=(asOf.toEpochMilli()-trace.t0().toEpochMilli())/1000.0;
		Set<String> missing=new LinkedHashSet<>();
		if(availableSeconds<end-EPS) missing.add("RUN_IN_PROGRESS");
		if(trace.values().length*step<end-EPS) missing.add("TRACE_INCOMPLETE");
		Deque<double[]> window=new ArrayDeque<>();
		double sum=0;
		Active active=null;
		List<Segment> segments=new ArrayList<>();
		for(int i=0;i<trace.values().length;i++) {
			double time=i*step;
			if(time<start-EPS) continue;
			if(time>=end-EPS || time>availableSeconds+EPS) break;
			double value=trace.values()[i];
			if(!Double.isFinite(value)) {
				missing.add("SAMPLES_MISSING"); close(active,false,segments,policy); active=null; window.clear(); sum=0; continue;
			}
			window.addLast(new double[]{time,value}); sum+=value;
			while(window.getFirst()[0]<time-policy.number("windowSeconds")-EPS) sum-=window.removeFirst()[1];
			if(time-window.getFirst()[0]<policy.number("windowSeconds")-EPS) continue;
			double mean=sum/window.size();
			String direction=mean<lower?"LOW":mean>upper?"HIGH":null;
			if(active!=null && active.direction.equals(direction)) active.to=time;
			else {
				close(active,direction==null,segments,policy);
				active=direction==null?null:new Active(time,direction);
			}
		}
		close(active,false,segments,policy); // 데이터 끝·분석 시점 도달은 회복이 아니다.
		return new Observation(missing.isEmpty()?"COMPLETE":"INSUFFICIENT",List.copyOf(missing),List.copyOf(segments));
	}
	private static Observation missing(String reason) { return new Observation("INSUFFICIENT",List.of(reason),List.of()); }
	private static void close(Active active,boolean recovered,List<Segment> out,PriorityEvidencePolicy policy) {
		if(active!=null && active.to-active.from>=policy.number("minimumDeviationSeconds")-EPS) {
			out.add(new Segment(round(active.from),round(active.to),round(active.to-active.from),active.direction,recovered,
				round(active.from+policy.number("minimumDeviationSeconds"))));
		}
	}
	/** 같은 run의 등록된 센서 그룹만 호출자가 넘긴다. 구간 중첩은 원시 시간 기준으로 계산한다. */
	public static double maximumOverlap(List<Segment> left,List<Segment> right) {
		double maximum=0;
		for(var a:left) for(var b:right) maximum=Math.max(maximum,Math.min(a.toSeconds(),b.toSeconds())-Math.max(a.fromSeconds(),b.fromSeconds()));
		return round(maximum);
	}
	private static double round(double value) { return Math.round(value*1_000_000.0)/1_000_000.0; }
}

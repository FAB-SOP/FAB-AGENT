package com.fabcontext.api.domain.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.contract.AgentResultContract;
import com.fabcontext.api.contract.AgentResultMapper;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.work.WorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이벤트 처리 트랜잭션에서 결과·업무 후보·이전 결과 연결을 함께 저장한다. */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class AgentResultService {
    private final AnalysisResultRepository results;
    private final AgentResultMapper mapper;
    private final WorkService work;
    private final IncidentStreamHub stream;

    @Transactional
    public AnalysisResult store(Job job, Incident incident, Map<String, Object> payload) {
        if (!List.of(com.fabcontext.api.domain.job.JobKind.BASIC, com.fabcontext.api.domain.job.JobKind.DEEP, com.fabcontext.api.domain.job.JobKind.RESUME).contains(job.getKind()))
            throw AgentResultContract.invalid("kind", "조사 작업에서만 분석 결과를 저장할 수 있습니다.");
        if (results.findFirstByJobIdOrderByCreatedAtDesc(job.getJobId()).isPresent())
            throw ApiException.conflict("ANALYSIS_RESULT_ALREADY_STORED", "이 작업의 답변은 이미 보존되었습니다. 같은 이벤트 ID로 재전송하세요.", Map.of("jobId", job.getJobId()));
        if (com.fabcontext.api.common.Json.write(payload).length() > 64000)
            throw AgentResultContract.invalid("payload", "분석 결과는 직렬화 기준 64,000자 이내여야 합니다.");
        var result = AgentResultContract.object(payload.get("result"), "payload.result");
        var incoming = payload.containsKey("analysis") ? AgentResultContract.object(payload.get("analysis"), "payload.analysis") : Map.<String, Object>of();
        AgentResultContract.validate(result, incoming, job.getJobId(), incident.getIncidentId());
        Map<String, Object> analysis = new LinkedHashMap<>();
        for (String key : List.of("resultKind", "nextStep", "conclusion", "requestSent", "proposalLimit", "droppedWorkSuggestions"))
            if (incoming.containsKey(key)) analysis.put(key, incoming.get(key));
        AnalysisResult stored = mapper.toResult(job, incident, result, analysis);
        if (job.getParentJobId() != null) results.findFirstByJobIdOrderByCreatedAtDesc(job.getParentJobId()).ifPresent(stored::linkPrevious);
        stored = results.save(stored);
        work.importSuggestions(incident, stored, AgentResultContract.objects(payload.getOrDefault("workSuggestions", List.of()), "payload.workSuggestions"));
        stream.publish(incident.getIncidentId(), "analysis.result", Map.of("resultId", stored.getResultId(), "jobId", job.getJobId(),
                "kind", stored.getKind(), "resultKind", stored.getResultKind()));
        String resultId=stored.getResultId(), incidentId=incident.getIncidentId(), jobId=job.getJobId(), resultKind=stored.getResultKind();
        com.fabcontext.api.common.AfterCommit.run(() -> log.info("event=analysis.result.stored incidentId={} jobId={} resultId={} resultKind={}",incidentId,jobId,resultId,resultKind));
        return stored;
    }
}

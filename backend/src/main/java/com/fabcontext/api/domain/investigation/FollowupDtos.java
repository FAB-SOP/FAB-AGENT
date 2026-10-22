package com.fabcontext.api.domain.investigation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fabcontext.api.common.Meta;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class FollowupDtos {
	private FollowupDtos() {}
	@Schema(name="FollowupSourceRef")
	public record SourceRef(@Schema(allowableValues={"ACTION","REPLY","REQUEST_DECISION"}, example="REPLY") @NotBlank @Pattern(regexp="ACTION|REPLY|REQUEST_DECISION") String type,@NotBlank String id) {}
	@Schema(name="FollowupRequest")
	public record Request(@NotEmpty @Size(max=20) List<@NotNull @Valid SourceRef> sourceRefs,
		@Size(max=2000) String question,@NotNull Long expectedIncidentVersion) {}
	@Schema(name="FollowupAccepted")
	public record Accepted(String analysisRequestId,String jobId,String incidentId,String displayId,JobKind kind,JobStatus status,
		String executionMode,List<SourceRef> sourceRefs,String statusUrl,String jobStatusUrl,String streamUrl,boolean duplicate) {}
	@Schema(name="FollowupAcceptedEnvelope")
	public record AcceptedEnvelope(Accepted data, Meta meta) {}
	@Schema(name="FollowupInput")
	public record Input(SourceRef sourceRef,boolean includedInInput,Boolean citedInResult) {}
	@Schema(name="FollowupView")
	public record View(String analysisRequestId,String incidentId,String requestedBy,JobStatus status,String executionMode,
		String initialJobId,String currentJobId,String analysisResultId,Instant createdAt,Instant finishedAt,List<Input> inputs,Object error) {}
	@Schema(name="FollowupResult")
	public record Result(SourceRef sourceRef,String incidentId,String taskId,String requestId,String content,String authorUserId,
		Instant createdAt,List<String> evidenceIds,Map<String,Object> analysisUse) {}
}

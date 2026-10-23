package com.fabcontext.api.domain.report;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 누가 어떤 경로로 확정하는지. 승인 경로에서는 지금 실행 중인 그 승인과 그 승인을 기다리던 · 재개한 작업이
 * 남은 일 검사에 걸리지 않도록 제외한다(승인된 확정이 자기 자신에게 막히지 않게).
 */
public record ConfirmationContext(String actorId, String excludedApprovalId, Set<String> excludedJobIds) {

	public static ConfirmationContext direct(String actorId) {
		return new ConfirmationContext(actorId, null, Set.of());
	}

	public static ConfirmationContext fromApproval(String actorId, String approvalId, String... relatedJobIds) {
		Set<String> jobIds = Set.copyOf(Stream.of(relatedJobIds).filter(Objects::nonNull).toList());
		return new ConfirmationContext(actorId, approvalId, jobIds);
	}

	boolean excludesApproval(String approvalId) {
		return approvalId != null && approvalId.equals(excludedApprovalId);
	}

	boolean excludesJob(String jobId) {
		return excludedJobIds.contains(jobId);
	}
}

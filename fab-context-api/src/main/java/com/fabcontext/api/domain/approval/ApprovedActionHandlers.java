package com.fabcontext.api.domain.approval;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 승인 종류 → 실행기. 모든 종류에 실행기가 하나씩 있어야 뜬다(빠지거나 겹치면 시작 실패) */
@Component
public class ApprovedActionHandlers {

	private final Map<ApprovalActionType, ApprovedActionHandler> byType = new EnumMap<>(ApprovalActionType.class);

	public ApprovedActionHandlers(List<ApprovedActionHandler> handlers) {
		for (ApprovedActionHandler h : handlers) {
			ApprovedActionHandler before = byType.put(h.type(), h);
			if (before != null) {
				throw new IllegalStateException(h.type() + " 실행기가 둘입니다: " + before.getClass().getSimpleName() + ", "
						+ h.getClass().getSimpleName());
			}
		}
		for (ApprovalActionType t : ApprovalActionType.values()) {
			if (!byType.containsKey(t)) {
				throw new IllegalStateException(t + " 실행기가 없습니다.");
			}
		}
	}

	public ApprovedActionHandler of(ApprovalActionType type) {
		return byType.get(type);
	}
}

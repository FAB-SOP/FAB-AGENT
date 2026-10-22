package com.fabcontext.api.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 트랜잭션 커밋 이후에 실행 (SSE 발행, 비동기 작업 투입 등) */
public final class AfterCommit {

	private AfterCommit() {
	}

	public static void run(Runnable task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}
}

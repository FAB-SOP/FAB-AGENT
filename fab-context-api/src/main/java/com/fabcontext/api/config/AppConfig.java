package com.fabcontext.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AppConfig {

	@Bean
	public MutableClock clock() {
		return new MutableClock();
	}

	/** Agent 시뮬레이터와 SSE heartbeat가 공유하는 스케줄러 */
	@Bean
	public TaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(4);
		scheduler.setThreadNamePrefix("fab-agent-");
		scheduler.initialize();
		return scheduler;
	}
}

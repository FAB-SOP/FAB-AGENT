package com.fabcontext.api.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 시스템 시계에 offset을 더하는 Clock. 시드 데이터가 과거 시각의 이력을 서비스 로직 그대로 만들 때만
 * 잠시 offset을 바꾼다. 요청 처리 중에는 항상 offset 0이다.
 */
public class MutableClock extends Clock {

	private volatile Duration offset = Duration.ZERO;

	public void travelTo(Instant instant) {
		offset = Duration.between(Instant.now(), instant);
	}

	public void reset() {
		offset = Duration.ZERO;
	}

	@Override
	public Instant instant() {
		return Instant.now().plus(offset);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.offset(Clock.system(zone), offset);
	}
}

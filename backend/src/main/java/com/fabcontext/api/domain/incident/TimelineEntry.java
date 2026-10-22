package com.fabcontext.api.domain.incident;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TimelineEntry {

	private Instant at;

	@Column(length = 60)
	private String type;

	private String actorId;

	@Column(length = 1000)
	private String message;

	private String refType;

	private String refId;
}

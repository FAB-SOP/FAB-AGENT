package com.fabcontext.api.common;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

	/** principal + operation + Idempotency-Key */
	@Id
	@Column(length = 300)
	private String id;

	@Column(nullable = false, length = 80)
	private String requestHash;

	private int statusCode;

	@Lob
	private String responseBody;

	private String location;

	private Instant createdAt;

	public IdempotencyRecord(String id, String requestHash, int statusCode, String responseBody, String location,
			Instant createdAt) {
		this.id = id;
		this.requestHash = requestHash;
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.location = location;
		this.createdAt = createdAt;
	}
}

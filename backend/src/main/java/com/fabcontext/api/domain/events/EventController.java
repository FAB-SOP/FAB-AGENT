package com.fabcontext.api.domain.events;

import java.net.URI;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.job.JobDtos.Accepted;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "events")
@RestController
@RequiredArgsConstructor
public class EventController {

	private final EventService service;

	@Operation(operationId = "ingestAnomaly", summary = "시뮬레이터 이상 이벤트 수신",
			security = @SecurityRequirement(name = "serviceToken"))
	@PostMapping("/api/events/anomalies")
	public ResponseEntity<ApiResponse<Accepted>> ingestAnomaly(@Valid @RequestBody AnomalyEvent body) {
		EventService.IngestResult result = service.ingest(body);
		if (!result.created()) {
			return ResponseEntity.ok(ApiResponse.of(result.accepted()));
		}
		return ResponseEntity.accepted()
			.location(URI.create("/api/incidents/" + result.accepted().incidentId()))
			.body(ApiResponse.of(result.accepted()));
	}
}

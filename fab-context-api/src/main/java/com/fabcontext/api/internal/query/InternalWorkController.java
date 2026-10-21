package com.fabcontext.api.internal.query;
import java.util.List;
import java.util.Map;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.work.WorkService;
import com.fabcontext.api.internal.InternalApi;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@io.swagger.v3.oas.annotations.Hidden
@RestController
@RequiredArgsConstructor
public class InternalWorkController implements InternalApi {
	private final WorkService work;
	private final InternalIncidentQuery incidents;
	@GetMapping("/internal/work-policy")
	public ApiResponse<?> policy() {var p=work.policy();return ApiResponse.of(Map.of("data",Map.of("version",p.get("version"),"operations",p.get("operations"),"hardwareExecution",false),"evidenceRefs",List.of(),"warnings",List.of()));}
	@GetMapping("/internal/incidents/{id}/work-items")
	public ApiResponse<?> items(@PathVariable String id) {return ApiResponse.of(Map.of("data",Map.of("items",work.internalList(incidents.resolve(id).getIncidentId())),"evidenceRefs",List.of(),"warnings",List.of()));}
}

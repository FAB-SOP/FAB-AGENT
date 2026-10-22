package com.fabcontext.api.domain.knowledge;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.Document;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.DocumentSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "knowledge")
@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeController {

	private final KnowledgeService service;

	@Operation(operationId = "searchDocuments", summary = "기술자료 검색")
	@GetMapping
	public ApiResponse<DocumentSearch> searchDocuments(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String q, @RequestParam(required = false) String processCode,
			@RequestParam(required = false) String docType, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return ApiResponse.of(service.search(user, q, processCode, docType, cursor, size));
	}

	@Operation(operationId = "getDocument", summary = "문서 메타데이터·원문 참조")
	@GetMapping("/{docId}")
	public ApiResponse<Document> getDocument(@AuthenticationPrincipal AuthUser user, @PathVariable String docId,
			@RequestParam(required = false) String version) {
		return ApiResponse.of(service.get(user, docId, version));
	}

	@Operation(operationId = "readDocument", summary = "권한 확인 후 문서 내용 (text/plain)")
	@GetMapping("/{docId}/content")
	public ResponseEntity<String> readDocument(@AuthenticationPrincipal AuthUser user, @PathVariable String docId,
			@RequestParam(required = false) String version) {
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
			.body(service.read(user, docId, version));
	}
}

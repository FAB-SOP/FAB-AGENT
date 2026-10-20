package com.fabcontext.api.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorEnvelope> handleApi(ApiException e) {
		return ResponseEntity.status(e.getStatus()).body(ErrorEnvelope.of(e.getCode(), e.getMessage(), e.getDetails()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException e) {
		List<Map<String, Object>> fields = e.getBindingResult().getFieldErrors().stream()
			.map(fe -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("field", fe.getField());
				m.put("reason", fe.getDefaultMessage());
				return m;
			})
			.toList();
		return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 값이 올바르지 않습니다.",
			Map.of("fields", fields));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorEnvelope> handleMethodValidation(HandlerMethodValidationException e) {
		return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 값이 올바르지 않습니다.", null);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorEnvelope> handleMissingHeader(MissingRequestHeaderException e) {
		return error(HttpStatus.BAD_REQUEST, "MISSING_HEADER", e.getHeaderName() + " 헤더가 필요합니다.",
			Map.of("header", e.getHeaderName()));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorEnvelope> handleMissingParam(MissingServletRequestParameterException e) {
		return error(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", e.getParameterName() + " 파라미터가 필요합니다.", null);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorEnvelope> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", e.getName() + " 값이 올바르지 않습니다.",
			Map.of("parameter", e.getName()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorEnvelope> handleUnreadable(HttpMessageNotReadableException e) {
		return error(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "요청 본문을 해석할 수 없습니다.", null);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorEnvelope> handleMediaType(HttpMediaTypeNotSupportedException e) {
		return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MEDIA_TYPE", "application/json 본문이 필요합니다.", null);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorEnvelope> handleMethod(HttpRequestMethodNotSupportedException e) {
		return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage(), null);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorEnvelope> handleNoResource(NoResourceFoundException e) {
		return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 경로가 없습니다.", null);
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorEnvelope> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
		return error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "동시에 변경되었습니다. 최신 내용을 다시 조회하세요.", null);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorEnvelope> handleIntegrity(DataIntegrityViolationException e) {
		return error(HttpStatus.CONFLICT, "DUPLICATE_OR_CONFLICT", "동시에 같은 데이터가 처리되었습니다. 다시 조회하세요.", null);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorEnvelope> handleUnknown(Exception e) {
		log.error("Unhandled error", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다.", null);
	}

	private ResponseEntity<ErrorEnvelope> error(HttpStatus status, String code, String message, Map<String, Object> details) {
		return ResponseEntity.status(status).body(ErrorEnvelope.of(code, message, details));
	}
}

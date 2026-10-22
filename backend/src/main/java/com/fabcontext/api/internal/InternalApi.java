package com.fabcontext.api.internal;

/**
 * 내부 API(/internal/**) 컨트롤러 표시 — {@link InternalAdvice}가 이 컨트롤러들의 오류를 내부 API 모양으로 바꾼다.
 * 장애 주입(DemoController)은 표시하지 않는다(공통 오류 모양 그대로).
 */
public interface InternalApi {
}

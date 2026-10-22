package com.fabcontext.api.domain.report;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * 사람이 고른 원인 분류 — 보고서 확정 요청과 보고서 확정 승인 결정 요청이 함께 쓴다.
 * 값 검사(범주 · 장비 파라미터 · 공정)는 {@link CauseClassificationPolicy}가 한다.
 */
public record CauseClassificationInput(String category, @Size(max = 20) List<String> equipmentParameters) {
}

package com.fabcontext.api.domain.investigation;

import java.util.List;

import com.fabcontext.api.common.Meta;
import com.fabcontext.api.domain.job.JobDtos.Accepted;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public final class RequestDraftDtos {
    private RequestDraftDtos() {}

    @Schema(name = "RequestDraftJobInput")
    public record Request(
            @Schema(description = "요청서 작성의 기준이 되는 같은 사건의 분석 결과 ID", example = "res_01")
            @NotBlank @Size(max = 128) String sourceResultId,
            @Schema(description = "현재 공정 또는 이 사건 웨이퍼의 실제 앞 공정", example = "ETCH", allowableValues = {"CVD", "ETCH", "CMP"})
            @NotBlank @Pattern(regexp = "CVD|ETCH|CMP") String targetProcessCode,
            @Schema(description = "선택 사항. 기준 분석에 포함된 원인 ID. 원인 확정을 의미하지 않습니다.")
            @Size(max = 20) List<@NotBlank @Size(max = 128) String> causeIds,
            @Schema(description = "선택 사항. 요청서 작성에 덧붙일 지시", example = "정비 전후 시즈닝 기록을 확인하도록 작성해 주세요.")
            @Size(max = 1000) String instruction,
            @NotNull @Min(0) Long expectedIncidentVersion) {}

    @Schema(name = "RequestDraftAcceptedEnvelope")
    public record AcceptedEnvelope(Accepted data, Meta meta) {}
}

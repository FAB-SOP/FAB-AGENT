package com.fabcontext.api.domain.investigation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 선택한 결과의 원문을 접수 시점에 보존한다. 읽음·검토 완료 상태는 만들지 않는다. */
@Entity
@Table(name="analysis_request",indexes=@Index(columnList="incidentId"))
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class AnalysisRequest {
	@Id private String analysisRequestId;
	private String incidentId;
	private String requestedBy;
	private String initialJobId;
	private String executionMode;
	private Instant createdAt;
	@JdbcTypeCode(SqlTypes.JSON) private List<Map<String,Object>> inputs;
	AnalysisRequest(String id,String incident,String user,String job,String mode,Instant at,List<Map<String,Object>> inputs) {
		analysisRequestId=id; incidentId=incident; requestedBy=user; initialJobId=job; executionMode=mode; createdAt=at; this.inputs=inputs;
	}
}

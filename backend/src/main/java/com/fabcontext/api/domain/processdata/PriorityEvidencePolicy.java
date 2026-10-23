package com.fabcontext.api.domain.processdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.fabcontext.api.common.Json;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/** PM 제공 기준의 계산 설정. DRAFT 문서 버전과 시연용 실행 활성화는 구분한다. */
@Component
public class PriorityEvidencePolicy {
	private final Map<String,Object> config;
	public PriorityEvidencePolicy() {
		try(var input=new ClassPathResource("priority/priority-rules.json").getInputStream()) {
			config=Json.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),new TypeReference<>() {});
		} catch(IOException ex) { throw new IllegalStateException("우선순위 계산 설정을 읽을 수 없습니다.",ex); }
	}
	public String version() { return String.valueOf(config.get("version")); }
	public String detectorVersion() { return String.valueOf(config.get("detectorVersion")); }
	public boolean enabled() { return Boolean.TRUE.equals(config.get("enabled")); }
	public double number(String name) { return ((Number)config.get(name)).doubleValue(); }
	public int repeatCount() { return ((Number)config.get("repeatWindowRunCount")).intValue(); }
	@SuppressWarnings("unchecked")
	public List<Map<String,Object>> groups() { return (List<Map<String,Object>>)config.get("relatedSensorGroups"); }
	public Map<String,Object> metadata() {
		return Map.of("documentId",config.get("documentId"),"version",version(),"status",config.get("status"),
			"enabled",config.get("enabled"),"detectorVersion",detectorVersion(),"gradeAssignedByTool",true);
	}
	public String missingPriority() { return String.valueOf(config.get("dataMissingPriority")); }
}

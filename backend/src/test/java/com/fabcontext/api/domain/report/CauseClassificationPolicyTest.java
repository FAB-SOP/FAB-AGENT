package com.fabcontext.api.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.fabcontext.api.domain.processdata.PackStore;

class CauseClassificationPolicyTest {

	static final Instant T = Instant.parse("2026-10-22T00:00:00Z");

	static CauseClassificationPolicy policy() {
		PackStore pack = mock(PackStore.class);
		when(pack.sensors()).thenReturn(List.of(
			Map.of("sensorId", "head_pressure", "process", "CMP"),
			Map.of("sensorId", "platen_speed", "process", "CMP"),
			Map.of("sensorId", "slurry_flow", "process", "CMP"),
			Map.of("sensorId", "chamber_pressure", "process", "CVD")));
		return new CauseClassificationPolicy(pack);
	}

	@Test
	void everyCategoryIsAcceptedAndNonMachineDropsParameters() {
		for (ReportCauseCategory category : List.of(ReportCauseCategory.MATERIAL, ReportCauseCategory.MAN,
				ReportCauseCategory.METHOD, ReportCauseCategory.HOLD)) {
			var classification = policy().validate(
				new CauseClassificationInput(category.name(), List.of("head_pressure")), "CMP", "u1", T);
			assertThat(classification.getCauseCategory()).isEqualTo(category);
			assertThat(classification.getCauseEquipmentParameters()).isEmpty();
			assertThat(classification.getClassifiedBy()).isEqualTo("u1");
			assertThat(classification.getClassifiedAt()).isEqualTo(T);
		}
	}

	@Test
	void machineKeepsOnlyProcessSensorsInCatalogOrderWithoutDuplicates() {
		var classification = policy().validate(new CauseClassificationInput("MACHINE",
			List.of("slurry_flow", "head_pressure", "slurry_flow")), "CMP", "u1", T);
		assertThat(classification.getCauseCategory()).isEqualTo(ReportCauseCategory.MACHINE);
		assertThat(classification.getCauseEquipmentParameters()).containsExactly("head_pressure", "slurry_flow");
	}

	@Test
	void missingOrUnknownCategoryIsRejected() {
		assertThatThrownBy(() -> policy().validate(null, "CMP", "u1", T))
			.hasFieldOrPropertyWithValue("code", "CAUSE_CLASSIFICATION_REQUIRED");
		assertThatThrownBy(() -> policy().validate(new CauseClassificationInput(" ", null), "CMP", "u1", T))
			.hasFieldOrPropertyWithValue("code", "CAUSE_CLASSIFICATION_REQUIRED");
		assertThatThrownBy(() -> policy().validate(new CauseClassificationInput("MACHINERY", null), "CMP", "u1", T))
			.hasFieldOrPropertyWithValue("code", "INVALID_CAUSE_CATEGORY");
	}

	@Test
	void machineNeedsAtLeastOneSensorOfTheIncidentProcess() {
		assertThatThrownBy(() -> policy().validate(new CauseClassificationInput("MACHINE", List.of()), "CMP", "u1", T))
			.hasFieldOrPropertyWithValue("code", "EQUIPMENT_PARAMETER_REQUIRED");
		assertThatThrownBy(() -> policy().validate(new CauseClassificationInput("MACHINE", null), "CMP", "u1", T))
			.hasFieldOrPropertyWithValue("code", "EQUIPMENT_PARAMETER_REQUIRED");
		// 다른 공정(CVD) 센서는 CMP 사건의 장비 파라미터가 될 수 없다
		assertThatThrownBy(() -> policy().validate(
			new CauseClassificationInput("MACHINE", List.of("head_pressure", "chamber_pressure")), "CMP", "u1", T))
			.hasFieldOrPropertyWithValue("code", "INVALID_EQUIPMENT_PARAMETER");
	}

	@Test
	void equipmentParametersOfFollowsCatalogOrder() {
		assertThat(policy().equipmentParametersOf("CMP")).containsExactly("head_pressure", "platen_speed", "slurry_flow");
		assertThat(policy().equipmentParametersOf("ETCH")).isEmpty();
	}
}

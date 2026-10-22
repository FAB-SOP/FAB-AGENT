package com.fabcontext.api.common;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 엔티티 JSON 컬럼 변환·해시용 정적 매퍼 (Jackson 3) */
public final class Json {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final JsonMapper CANONICAL = JsonMapper.builder()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	private Json() {
	}

	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	public static <T> T read(String json, TypeReference<T> type) {
		return MAPPER.readValue(json, type);
	}

	public static JsonNode tree(Object value) {
		return MAPPER.valueToTree(value);
	}

	public static <T> T convert(Object value, Class<T> type) {
		return MAPPER.convertValue(value, type);
	}

	public static String canonical(Object value) {
		return CANONICAL.writeValueAsString(CANONICAL.valueToTree(value));
	}
}

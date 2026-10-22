package com.fabcontext.api.domain.processdata;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 원본 데이터 버전 — 데이터 팩의 pack-manifest.json `dataVersion`(예: causal-pack-v2.1-scenarios).
 * 결과 · 근거 · 화면 응답이 어느 팩 기준인지 밝힌다. 이벤트 접수는 사건을 만들 뿐 팩을 바꾸지 않으므로 버전도 그대로다.
 */
@Component
@RequiredArgsConstructor
public class DataVersion {

	private final PackStore pack;

	public String current() {
		return pack.dataVersion();
	}

	/** 이벤트 접수 때 부르던 자리 — 팩이 바뀌지 않으므로 같은 버전을 돌려준다 */
	public String bump() {
		return current();
	}
}

package com.fabcontext.api.domain.processdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.config.FabProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * 데이터 팩(fab-causal-pack-v1)과 실제 원문 선별 RAG(fab-rag-real-documents-v1) 읽기. 둘이 유일한 원본이다 — 복사본을 두지 않는다.
 * <ul>
 * <li>DB로 옮기는 것(seed): run · 5위치 계측 · run 특징 · 정비 이력 · 지식 문서</li>
 * <li>여기서 읽기 전용으로 쓰는 것: 동결 관리한계 · 특징 참고 범위 · 센서 카탈로그 · 원인 매트릭스 · 분석 시점</li>
 * <li>원시 센서 시계열: DB · 문서 색인에 넣지 않고 data/sensor-traces.jsonl.gz에서 필요한 run만 꺼낸다(처음 조회 때 적재)</li>
 * </ul>
 * evaluation/(평가자 전용 정답)은 읽지 않는다.
 */
@Component
public class PackStore {

	/** 원시 시계열 한 줄 = run 하나 · 센서 하나. values[i]의 시각은 t0 + i × intervalMs */
	public record Trace(String runId, String sensorId, String unit, Instant t0, long intervalMs,
			List<Map<String, Object>> setpointProfile, double[] values, String quality) {
	}

	private final Path dir;

	private final Path ragDir;

	private final List<Map<String, Object>> limits;

	private final List<Map<String, Object>> bands;

	private final List<Map<String, Object>> sensors;

	private final List<Map<String, Object>> causes;

	private final List<Map<String, Object>> sources;

	private final Instant analysisAsOf;

	private final String dataVersion;

	private final List<Map<String, Object>> envelopes;

	private volatile Map<String, Trace> traces;

	public PackStore(FabProperties properties) {
		this.dir = Path.of(properties.packDir()).toAbsolutePath().normalize();
		this.ragDir = Path.of(properties.ragDir()).toAbsolutePath().normalize();
		if (!Files.isDirectory(dir.resolve("data"))) {
			throw new IllegalStateException("데이터 팩을 찾지 못했습니다: " + dir + " (fab.pack-dir · FAB_PACK_DIR)");
		}
		this.limits = list("data/quality-limits.json");
		this.bands = list("data/feature-reference-bands.json");
		this.sensors = list("data/sensor-catalog.json");
		this.causes = list("knowledge/cause-matrix.json");
		this.sources = list("knowledge/source-registry.json");
		Map<String, Object> contract = map("knowledge/agent-contract.json");
		this.analysisAsOf = instant(String.valueOf(contract.get("analysisAsOf")));
		// 팩 버전 — v2 팩(data/build_pack_v2.py)은 pack-manifest.json에 적는다. 없으면(v1 원본 등) 폴더 이름
		this.dataVersion = Files.isRegularFile(dir.resolve("pack-manifest.json"))
				? String.valueOf(map("pack-manifest.json").get("dataVersion")) : dir.getFileName().toString();
		this.envelopes = Files.isRegularFile(dir.resolve("data/sensor-envelopes.json")) ? list("data/sensor-envelopes.json")
				: List.of();
	}

	public String dataVersion() {
		return dataVersion;
	}

	/** 센서 시계열 참조 범위(sensorEnvelopes, DEMO-SEGMENT-V2) — run 평균 참고 분포(bands)와 다르다 */
	public List<Map<String, Object>> envelopes() {
		return envelopes;
	}

	public java.util.Optional<Map<String, Object>> envelope(String process, String sensorId) {
		return envelopes.stream().filter(e -> process.equals(e.get("process")) && sensorId.equals(e.get("sensorId"))).findFirst();
	}

	public Path dir() {
		return dir;
	}

	/** 실제 원문 조각 100개 (fab-rag-real-documents-v1/rag/source-chunks.jsonl) — 없으면 빈 목록 */
	public List<Map<String, Object>> sourceChunks() {
		Path p = ragDir.resolve("rag/source-chunks.jsonl");
		if (!Files.exists(p)) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
				if (!line.isBlank()) {
					out.add(Json.read(line, new TypeReference<Map<String, Object>>() {
					}));
				}
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("실제 원문 조각을 읽지 못했습니다: " + p, e);
		}
		return out;
	}

	/** 팩이 정한 분석 시점 — 이 시각 이후 계측 · 후속 run은 현재 판단에 쓰지 않는다 */
	public Instant analysisAsOf() {
		return analysisAsOf;
	}

	public List<Map<String, Object>> limits() {
		return limits;
	}

	public Optional<Map<String, Object>> limit(String processCode) {
		return limits.stream().filter(l -> processCode.equals(l.get("process"))).findFirst();
	}

	public Optional<Map<String, Object>> band(String processCode, String sensorId) {
		return bands.stream().filter(b -> processCode.equals(b.get("process")) && sensorId.equals(b.get("sensorId")))
			.findFirst();
	}

	/** run 평균 참고 분포(feature-reference-bands) — 원시 점의 SPC 한계가 아니다 */
	public List<Map<String, Object>> bands() {
		return bands;
	}

	/** 원인 매트릭스 한 행 — causeId로 */
	public java.util.Optional<Map<String, Object>> cause(String causeId) {
		return causes.stream().filter(c -> causeId.equals(c.get("causeId"))).findFirst();
	}

	public List<Map<String, Object>> sensors() {
		return sensors;
	}

	public List<Map<String, Object>> causes() {
		return causes;
	}

	public List<Map<String, Object>> sources() {
		return sources;
	}

	public List<Map<String, Object>> list(String rel) {
		return Json.read(read(rel), new TypeReference<List<Map<String, Object>>>() {
		});
	}

	public Map<String, Object> map(String rel) {
		return Json.read(read(rel), new TypeReference<Map<String, Object>>() {
		});
	}

	/** jsonl 한 줄 = 객체 하나 */
	public List<Map<String, Object>> lines(String rel) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String line : read(rel).split("\n")) {
			if (!line.isBlank()) {
				out.add(Json.read(line, new TypeReference<Map<String, Object>>() {
				}));
			}
		}
		return out;
	}

	public Optional<Trace> trace(String runId, String sensorId) {
		return Optional.ofNullable(traces().get(runId + "|" + sensorId));
	}

	public List<Trace> traces(String runId) {
		return traces().values().stream().filter(t -> t.runId().equals(runId)).toList();
	}

	private Map<String, Trace> traces() {
		if (traces == null) {
			synchronized (this) {
				if (traces == null) {
					traces = loadTraces();
				}
			}
		}
		return traces;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Trace> loadTraces() {
		Map<String, Trace> out = new HashMap<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(Files.newInputStream(dir.resolve("data/sensor-traces.jsonl.gz"))), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				Map<String, Object> m = Json.read(line, new TypeReference<Map<String, Object>>() {
				});
				List<Number> vs = (List<Number>) m.get("values");
				double[] values = new double[vs.size()];
				for (int i = 0; i < values.length; i++) {
					values[i] = vs.get(i) == null ? Double.NaN : vs.get(i).doubleValue();
				}
				Trace t = new Trace((String) m.get("runId"), (String) m.get("sensorId"), (String) m.get("unit"),
					instant((String) m.get("t0")), ((Number) m.get("sourceIntervalMs")).longValue(),
					(List<Map<String, Object>>) m.get("setpointProfile"), values, (String) m.get("quality"));
				out.put(t.runId() + "|" + t.sensorId(), t);
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("원시 시계열을 읽지 못했습니다: " + e.getMessage(), e);
		}
		return out;
	}

	private String read(String rel) {
		try {
			return Files.readString(dir.resolve(rel), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("팩 파일을 읽지 못했습니다: " + rel, e);
		}
	}

	public static Instant instant(String iso) {
		return iso == null ? null : OffsetDateTime.parse(iso).toInstant();
	}

	public static Map<String, Object> copy(Map<String, Object> m) {
		return new LinkedHashMap<>(m);
	}
}

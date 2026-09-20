package org.thought.acp.opencode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTests {

	@Test
	void writesScalarsWithTheirTypes() {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("theme", "system");
		config.put("share", false);
		config.put("count", 2);

		assertThat(Json.write(config))
				.isEqualTo("{\n  \"theme\": \"system\",\n  \"share\": false,\n  \"count\": 2\n}\n");
	}

	@Test
	void numbersAndBooleansThatArrivedAsTextStillLandAsNumbersAndBooleans() {
		assertThat(Json.write(Map.of("share", "false"))).isEqualTo("{\n  \"share\": false\n}\n");
	}

	@Test
	void writesNestedObjectsAndArrays() {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("mcp", Map.of("enabled", true));
		config.put("tools", List.of("a", "b"));

		assertThat(Json.write(config))
				.isEqualTo("{\n  \"mcp\": {\n    \"enabled\": true\n  },\n  \"tools\": [\"a\", \"b\"]\n}\n");
	}

	@Test
	void escapesWhatWouldOtherwiseBreakTheDocument() {
		assertThat(Json.write(Map.of("k", "a\"b\nc"))).isEqualTo("{\n  \"k\": \"a\\\"b\\nc\"\n}\n");
	}
}

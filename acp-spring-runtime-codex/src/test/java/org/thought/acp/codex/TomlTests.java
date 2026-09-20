package org.thought.acp.codex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TomlTests {

	@Test
	void writesScalarsWithTheirTypes() {
		Map<String, Object> table = new LinkedHashMap<>();
		table.put("text", "high");
		table.put("number", 3);
		table.put("flag", true);

		assertThat(Toml.write(table)).isEqualTo("text = \"high\"\nnumber = 3\nflag = true\n");
	}

	@Test
	void numbersAndBooleansThatArrivedAsTextStillLandAsNumbersAndBooleans() {
		// YAML quoting, or an environment variable, makes everything a string on the way in.
		assertThat(Toml.write(Map.of("flag", "true"))).isEqualTo("flag = true\n");
		assertThat(Toml.write(Map.of("n", "42"))).isEqualTo("n = 42\n");
	}

	@Test
	void writesArrays() {
		assertThat(Toml.write(Map.of("tools", List.of("a", "b")))).isEqualTo("tools = [\"a\", \"b\"]\n");
	}

	@Test
	void writesScalarsBeforeNestedTables() {
		// In TOML a key written after a [table] header belongs to that table, so order is correctness,
		// not formatting.
		Map<String, Object> table = new LinkedHashMap<>();
		table.put("nested", Map.of("inner", "low"));
		table.put("top", "high");

		assertThat(Toml.write(table)).isEqualTo("top = \"high\"\n\n[nested]\ninner = \"low\"\n");
	}

	@Test
	void quotesKeysAndValuesThatWouldOtherwiseChangeTheMeaning() {
		assertThat(Toml.write(Map.of("a key", "with \"quotes\""))).isEqualTo("\"a key\" = \"with \\\"quotes\\\"\"\n");
		assertThat(Toml.write(Map.of("k", "line\nbreak"))).isEqualTo("k = \"line\\nbreak\"\n");
	}

	@Test
	void nestsTableHeadersByPath() {
		assertThat(Toml.write(Map.of("a", Map.of("b", Map.of("c", "d")))))
				.isEqualTo("\n[a]\n\n[a.b]\nc = \"d\"\n");
	}
}

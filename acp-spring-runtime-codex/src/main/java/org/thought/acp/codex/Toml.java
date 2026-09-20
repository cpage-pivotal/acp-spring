package org.thought.acp.codex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the subset of TOML that a {@code config.toml} needs: scalars, arrays of scalars, and
 * nested tables.
 *
 * <p>A dependency would be the obvious alternative, and it was weighed. What is emitted here comes
 * entirely from an application's own {@code spring.acp.runtimes.codex.config-toml} block — a handful
 * of keys, already constrained to what YAML or a property file can express — so a full TOML
 * implementation would be carrying a transitive dependency into every application for a hundred
 * lines of output. What it must not do is emit something that parses as different TOML than was
 * meant, which is what the quoting and the key check below are for.
 */
final class Toml {

	/** A bare key. Anything else is quoted, which TOML allows for both keys and values. */
	private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

	private Toml() {
	}

	static String write(Map<String, Object> table) {
		StringBuilder out = new StringBuilder();
		writeTable(out, table, List.of());
		return out.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeTable(StringBuilder out, Map<String, Object> table, List<String> path) {
		List<Map.Entry<String, Object>> nested = new ArrayList<>();

		// Scalars first: in TOML a key after a [table] header belongs to that table, so anything
		// written after a nested table would silently move into it.
		table.forEach((key, value) -> {
			if (value instanceof Map) {
				nested.add(Map.entry(key, value));
			}
			else if (value != null) {
				out.append(key(key)).append(" = ").append(value(value)).append('\n');
			}
		});

		nested.forEach(entry -> {
			List<String> child = new ArrayList<>(path);
			child.add(entry.getKey());
			out.append('\n').append('[').append(String.join(".", child.stream().map(Toml::key).toList()))
					.append(']').append('\n');
			writeTable(out, (Map<String, Object>) entry.getValue(), child);
		});
	}

	private static String key(String key) {
		return BARE_KEY.matcher(key).matches() ? key : quote(key);
	}

	private static String value(Object value) {
		if (value instanceof Boolean || value instanceof Number) {
			return String.valueOf(value);
		}
		if (value instanceof List<?> list) {
			return "[" + String.join(", ", list.stream().map(Toml::value).toList()) + "]";
		}
		String text = String.valueOf(value);
		// Booleans and numbers written as YAML strings should still land as TOML booleans and numbers.
		if (text.equals("true") || text.equals("false") || text.matches("-?\\d+") || text.matches("-?\\d*\\.\\d+")) {
			return text;
		}
		return quote(text);
	}

	private static String quote(String text) {
		StringBuilder out = new StringBuilder("\"");
		text.chars().forEach(c -> {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append((char) c);
			}
		});
		return out.append('"').toString();
	}
}

package org.springaicommunity.acp.opencode;

import java.util.List;
import java.util.Map;

/**
 * Writes the tier-3 {@code config} block as JSON.
 *
 * <p>
 * Small enough to be obvious and to avoid putting a Jackson version on an application's
 * classpath for the sake of one file written once at startup; the values come from a
 * bound configuration block, so they are already limited to the scalars, lists and maps
 * YAML can express.
 */
final class Json {

	private Json() {
	}

	static String write(Map<String, Object> value) {
		StringBuilder out = new StringBuilder();
		writeValue(out, value, 0);
		return out.append('\n').toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeValue(StringBuilder out, Object value, int depth) {
		switch (value) {
			case null -> out.append("null");
			case Map<?, ?> map -> {
				out.append("{\n");
				int remaining = map.size();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					indent(out, depth + 1);
					writeString(out, String.valueOf(entry.getKey()));
					out.append(": ");
					writeValue(out, entry.getValue(), depth + 1);
					out.append(--remaining > 0 ? ",\n" : "\n");
				}
				indent(out, depth);
				out.append('}');
			}
			case List<?> list -> {
				out.append('[');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) {
						out.append(", ");
					}
					writeValue(out, list.get(i), depth);
				}
				out.append(']');
			}
			case Boolean b -> out.append(b);
			case Number n -> out.append(n);
			default -> {
				String text = String.valueOf(value);
				// A number or boolean that came through YAML as text should still land as
				// one here.
				if (text.equals("true") || text.equals("false") || text.matches("-?\\d+(\\.\\d+)?")) {
					out.append(text);
				}
				else {
					writeString(out, text);
				}
			}
		}
	}

	private static void writeString(StringBuilder out, String text) {
		out.append('"');
		text.chars().forEach(c -> {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", c));
					}
					else {
						out.append((char) c);
					}
				}
			}
		});
		out.append('"');
	}

	private static void indent(StringBuilder out, int depth) {
		out.append("  ".repeat(depth));
	}

}

package org.springaicommunity.acp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tier-3 configuration: whatever {@code spring.acp.runtimes.<id>.*} said, handed to that one
 * adapter and ignored by every other.
 *
 * <p>This exists because the shape the values arrive in is not the adapter's business. A nested
 * block in {@code application.yaml} binds as nested maps; the same block written as
 * {@code spring.acp.runtimes.codex.config-toml.model_reasoning_effort=high} in a properties file or
 * an environment variable binds as one flat dotted key. Both mean the same thing, and an adapter
 * that read the raw map would have to handle both. So the map is normalized into a tree once, here,
 * and adapters ask for paths.
 *
 * <p>Lookups are relaxed the way Spring's own binding is — {@code configToml}, {@code config-toml}
 * and {@code config_toml} are the same path — but the keys handed back by {@link #section} keep
 * their original spelling, because they end up verbatim in a TOML or JSON file the agent reads.
 */
public final class RuntimeOptions {

	private static final RuntimeOptions EMPTY = new RuntimeOptions(Map.of());

	/** Nested, with original key spellings preserved. */
	private final Map<String, Object> tree;

	private RuntimeOptions(Map<String, Object> tree) {
		this.tree = tree;
	}

	public static RuntimeOptions empty() {
		return EMPTY;
	}

	public static RuntimeOptions of(Map<String, ?> values) {
		if (values == null || values.isEmpty()) {
			return EMPTY;
		}
		Map<String, Object> tree = new LinkedHashMap<>();
		values.forEach((key, value) -> insert(tree, key, value));
		return new RuntimeOptions(tree);
	}

	/** Splits a dotted key into nested maps, merging into anything already there. */
	@SuppressWarnings("unchecked")
	private static void insert(Map<String, Object> into, String key, Object value) {
		if (key == null || key.isBlank()) {
			return;
		}
		String[] segments = key.split("\\.");
		Map<String, Object> node = into;
		for (int i = 0; i < segments.length - 1; i++) {
			Object existing = node.get(segments[i]);
			if (existing instanceof Map<?, ?> map) {
				node = (Map<String, Object>) map;
			}
			else {
				Map<String, Object> child = new LinkedHashMap<>();
				node.put(segments[i], child);
				node = child;
			}
		}
		String leaf = segments[segments.length - 1];
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> child = new LinkedHashMap<>();
			node.put(leaf, child);
			map.forEach((k, v) -> insert(child, String.valueOf(k), v));
		}
		else {
			node.put(leaf, value);
		}
	}

	public boolean isEmpty() {
		return tree.isEmpty();
	}

	/** The value at {@code path}, or empty. A path segment matches case- and separator-insensitively. */
	public Optional<Object> find(String path) {
		Object node = tree;
		for (String segment : path.split("\\.")) {
			if (!(node instanceof Map<?, ?> map)) {
				return Optional.empty();
			}
			node = lookup(map, segment);
			if (node == null) {
				return Optional.empty();
			}
		}
		return Optional.of(node);
	}

	private static Object lookup(Map<?, ?> map, String segment) {
		Object direct = map.get(segment);
		if (direct != null) {
			return direct;
		}
		String wanted = normalize(segment);
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (normalize(String.valueOf(entry.getKey())).equals(wanted)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static String normalize(String key) {
		return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
	}

	/** The scalar at {@code path}, rendered as text. Empty when absent or when it is a map or list. */
	public Optional<String> text(String path) {
		return find(path).filter(v -> !(v instanceof Map) && !(v instanceof List)).map(String::valueOf)
				.filter(s -> !s.isBlank());
	}

	/**
	 * The list at {@code path}. A single comma-separated scalar counts as a list, because that is how
	 * a list reaches us through an environment variable.
	 */
	public List<String> textList(String path) {
		Optional<Object> value = find(path);
		if (value.isEmpty()) {
			return List.of();
		}
		if (value.get() instanceof List<?> list) {
			return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).map(String::strip)
					.filter(s -> !s.isEmpty()).toList();
		}
		if (value.get() instanceof Map<?, ?> map) {
			// An indexed list bound as a map: {0: a, 1: b}.
			List<String> ordered = new ArrayList<>();
			map.values().forEach(v -> ordered.add(String.valueOf(v)));
			return List.copyOf(ordered);
		}
		return List.of(String.valueOf(value.get()).split("\\s*,\\s*"));
	}

	/** The block at {@code path}, with its keys spelled as they were declared. */
	public Map<String, Object> section(String path) {
		return find(path).filter(Map.class::isInstance).map(v -> {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) v;
			return Map.copyOf(map);
		}).orElseGet(Map::of);
	}

	/** {@link #section} flattened to text, for blocks that can only hold scalars — an env block. */
	public Map<String, String> textSection(String path) {
		Map<String, String> flat = new LinkedHashMap<>();
		section(path).forEach((key, value) -> {
			if (!(value instanceof Map) && !(value instanceof List) && value != null) {
				flat.put(key, String.valueOf(value));
			}
		});
		return Map.copyOf(flat);
	}

	@Override
	public String toString() {
		return "RuntimeOptions" + tree.keySet();
	}
}

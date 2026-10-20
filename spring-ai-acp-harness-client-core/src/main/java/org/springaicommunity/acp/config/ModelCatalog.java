package org.springaicommunity.acp.config;

import java.util.List;
import java.util.Optional;

/**
 * What an endpoint of the application's own says it serves.
 *
 * <p>
 * The counterpart to the catalogue an agent advertises on {@code session/new}. That one
 * is authoritative for the vendor the agent was built against and worthless for anything
 * else: no amount of reading it will tell you which model a private gateway is in front
 * of. When {@code spring.acp.provider.base-url} is set, this is the list that decides.
 *
 * <p>
 * A catalogue that cannot answer returns empty, which is not the same as an empty list.
 * Plenty of OpenAI-compatible endpoints serve completions and nothing else, and refusing
 * to run against one because it declined to enumerate itself would be worse than the
 * problem being solved.
 */
@FunctionalInterface
public interface ModelCatalog {

	/** The model ids {@code provider}'s endpoint serves, or empty when it did not say. */
	Optional<List<String>> modelsOf(ProviderSpec provider);

	/** A catalogue that never answers: every model is taken on trust. */
	static ModelCatalog none() {
		return provider -> Optional.empty();
	}

	/** Asks the endpoint itself, once per endpoint. */
	static ModelCatalog endpoint() {
		return new EndpointModelCatalog();
	}

}

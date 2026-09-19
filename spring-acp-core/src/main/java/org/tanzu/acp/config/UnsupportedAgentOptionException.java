package org.tanzu.acp.config;

/**
 * Thrown when a portable option cannot be honored by the selected runtime and
 * {@link OnUnsupported#FAIL} is in force.
 *
 * <p>The analogue of the store-specific {@code UnsupportedOperationException} a Spring Data
 * repository throws for a query its backend cannot express.
 */
public class UnsupportedAgentOptionException extends RuntimeException {

	private final String option;

	private final String runtime;

	public UnsupportedAgentOptionException(String option, String value, String runtime, Throwable cause) {
		super("runtime '" + runtime + "' cannot honor " + option + "='" + value + "'", cause);
		this.option = option;
		this.runtime = runtime;
	}

	public String option() {
		return option;
	}

	public String runtime() {
		return runtime;
	}
}

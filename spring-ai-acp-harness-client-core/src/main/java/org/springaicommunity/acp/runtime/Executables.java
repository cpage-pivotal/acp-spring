package org.springaicommunity.acp.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Finds an agent's executable named by an environment variable, as a buildpack or an
 * operator exports one.
 */
public final class Executables {

	private Executables() {
	}

	/**
	 * The executable {@code variable} names, or empty when it is unset or blank.
	 *
	 * <p>
	 * Checked here rather than left to process start, so that the failure, when there is
	 * one, names the variable and the file rather than surfacing as a bare
	 * {@code IOException}.
	 * @throws IllegalStateException if the variable names something that is not an
	 * executable file
	 */
	public static Optional<String> fromEnvironment(String variable) {
		return fromEnvironment(variable, System::getenv);
	}

	static Optional<String> fromEnvironment(String variable, UnaryOperator<String> environment) {
		String configured = environment.apply(variable);
		if (configured == null || configured.isBlank()) {
			return Optional.empty();
		}
		Path path = Path.of(configured);
		if (!Files.isExecutable(path)) {
			throw new IllegalStateException(
					variable + " points at '" + configured + "', which is not an executable file");
		}
		return Optional.of(path.toString());
	}

}

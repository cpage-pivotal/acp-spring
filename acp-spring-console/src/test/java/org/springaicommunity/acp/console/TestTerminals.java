package org.springaicommunity.acp.console;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;

/** A terminal that reads a script and records what was written, standing in for a person. */
final class TestTerminals {

	private TestTerminals() {
	}

	static Scripted typing(String input) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			// Constructed directly: TerminalBuilder would open a pseudo-terminal over these streams, whose
			// output is pumped on another thread and so races the assertions.
			Terminal terminal = new DumbTerminal("test", Terminal.TYPE_DUMB,
					new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, StandardCharsets.UTF_8);
			return new Scripted(terminal, output);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	record Scripted(Terminal terminal, ByteArrayOutputStream output) {

		String written() {
			terminal.writer().flush();
			return output.toString(StandardCharsets.UTF_8);
		}
	}
}

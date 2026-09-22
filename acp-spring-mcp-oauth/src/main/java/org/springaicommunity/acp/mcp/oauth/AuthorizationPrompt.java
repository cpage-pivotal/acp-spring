package org.springaicommunity.acp.mcp.oauth;

import java.awt.Desktop;
import java.io.PrintStream;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gets the user of a terminal application to an authorization server's sign-in page.
 *
 * <p>A bean, so an application with its own idea of the console — a TUI, a web view — can say it
 * differently. The default prints the URL and opens it.
 */
@FunctionalInterface
public interface AuthorizationPrompt {

	/**
	 * @param serverName the MCP server being signed in to
	 * @param authorizationUri where the user must go; opening it is the whole job
	 */
	void open(String serverName, URI authorizationUri);

	/** Called once the user is back and the token is stored. */
	default void signedIn(String serverName) {
	}

	/**
	 * Prints the URL and tries to open a browser on it, and says which happened.
	 *
	 * <p>Printed even when a browser opens, because a terminal that says only "waiting" is a dead end
	 * when the browser does not appear. And {@code open}/{@code xdg-open} are tried after
	 * {@link Desktop}, because a JVM started from a terminal on macOS reports no desktop at all.
	 */
	static AuthorizationPrompt browser(PrintStream out) {
		Logger logger = LoggerFactory.getLogger(AuthorizationPrompt.class);
		return new AuthorizationPrompt() {
			@Override
			public void open(String serverName, URI uri) {
				out.println();
				out.println("Sign in to " + serverName + ":");
				out.println("  " + uri);
				out.println(browse(uri) || launch(uri) ? "  (opened in your browser — waiting…)"
						: "  (open that URL to continue — waiting…)");
			}

			@Override
			public void signedIn(String serverName) {
				out.println("  signed in to " + serverName);
			}

			private boolean browse(URI uri) {
				try {
					if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
						Desktop.getDesktop().browse(uri);
						return true;
					}
				}
				catch (Exception ex) {
					logger.debug("Could not open a browser through Desktop: {}", ex.getMessage());
				}
				return false;
			}

			private boolean launch(URI uri) {
				String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
				String opener = os.contains("mac") ? "open" : os.contains("win") ? null : "xdg-open";
				if (opener == null) {
					return false;
				}
				try {
					return new ProcessBuilder(opener, uri.toString()).start().waitFor(10, TimeUnit.SECONDS);
				}
				catch (java.io.IOException ex) {
					logger.debug("Could not run {}: {}", opener, ex.getMessage());
					return false;
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		};
	}
}

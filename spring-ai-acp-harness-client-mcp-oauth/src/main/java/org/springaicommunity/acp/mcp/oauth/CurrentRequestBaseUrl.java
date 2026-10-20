package org.springaicommunity.acp.mcp.oauth;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * This application's base URL, as the browser that made the current request sees it.
 *
 * <p>
 * Needed once per MCP server, when registering with its authorization server: the
 * redirect URI sent then has to be one the user's browser can reach, and the request that
 * triggered the registration is the best evidence of what that is. Behind a proxy that
 * means the application must honour {@code X-Forwarded-*}
 * ({@code server.forward-headers-strategy}); where it cannot know, set
 * {@code spring.acp.mcp.oauth.base-url}, which wins.
 */
final class CurrentRequestBaseUrl implements Supplier<Optional<String>> {

	private static final boolean SERVLET = ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest",
			CurrentRequestBaseUrl.class.getClassLoader());

	private final String configured;

	CurrentRequestBaseUrl(String configured) {
		this.configured = configured == null || configured.isBlank() ? null : trimSlash(configured);
	}

	@Override
	public Optional<String> get() {
		if (configured != null) {
			return Optional.of(configured);
		}
		if (!SERVLET || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
			return Optional.empty();
		}
		var request = attributes.getRequest();
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
				|| ("https".equals(request.getScheme()) && port == 443);
		return Optional.of(request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port)
				+ request.getContextPath());
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

}

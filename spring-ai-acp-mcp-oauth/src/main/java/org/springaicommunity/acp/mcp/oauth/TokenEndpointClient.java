package org.springaicommunity.acp.mcp.oauth;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * The client token requests are sent with: Spring Security's own, except that an OAuth
 * error is read from a 401 as well as a 400.
 *
 * <p>
 * Spring Security reads the error only from a 400 and reports anything else as
 * {@code invalid_token_response}. The authorization server behind the Tanzu MCP gateway
 * answers a refresh token it no longer honours with a 401 and
 * {@code {"error":"invalid_grant"}} — measured — as RFC 6749 also allows for
 * {@code invalid_client}. Misread, that refusal is never seen as one: Spring Security
 * removes a stored token only on {@code invalid_grant} or {@code invalid_token}, so the
 * dead token stays, the user is never sent to sign in again, and every session after
 * fails the same way.
 */
final class TokenEndpointClient {

	private TokenEndpointClient() {
	}

	/** For a token response client's {@code setRestClient}. */
	static RestClient restClient() {
		return RestClient.builder()
			.configureMessageConverters(converters -> converters.addCustomConverter(new FormHttpMessageConverter())
				.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
			.defaultStatusHandler(new UnauthorizedReadAsOAuth2Error())
			.build();
	}

	private static final class UnauthorizedReadAsOAuth2Error implements ResponseErrorHandler {

		private final OAuth2ErrorResponseErrorHandler delegate = new OAuth2ErrorResponseErrorHandler();

		private final OAuth2ErrorHttpMessageConverter errors = new OAuth2ErrorHttpMessageConverter();

		@Override
		public boolean hasError(ClientHttpResponse response) throws IOException {
			return delegate.hasError(response);
		}

		@Override
		public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
			if (response.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
				delegate.handleError(url, method, response);
				return;
			}
			OAuth2Error error;
			try {
				error = errors.read(OAuth2Error.class, response);
			}
			catch (HttpMessageNotReadableException ex) {
				// A 401 with no OAuth error in it: what Spring Security would have said.
				error = new OAuth2Error("invalid_token_response",
						"The token endpoint answered 401 without an OAuth 2.0 error", null);
			}
			throw new OAuth2AuthorizationException(error);
		}

	}

}

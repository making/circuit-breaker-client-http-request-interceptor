/*
 * Copyright (C) 2026 Toshiaki Maki <makingx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.spring.http.client.circuitbreaker;

import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpRequest;

/**
 * Returns the {@link CircuitBreaker} that should protect a given outgoing HTTP request.
 *
 * <p>
 * Most common strategies are available as static factory methods:
 * <ul>
 * <li>{@link #fixed(CircuitBreaker)} — use the same circuit breaker for every request.
 * <li>{@link #byHost(CircuitBreakerRegistry)} — one circuit breaker per request host.
 * <li>{@link #byAuthority(CircuitBreakerRegistry)} — one circuit breaker per
 * {@code host:port}.
 * </ul>
 * For arbitrary keys, implement this interface (it is a functional interface).
 */
@FunctionalInterface
public interface CircuitBreakerProvider {

	/**
	 * Returns the circuit breaker to use for the given request.
	 * @param request the outgoing HTTP request
	 * @return the circuit breaker
	 */
	CircuitBreaker get(HttpRequest request);

	/**
	 * Returns a provider that always returns the given circuit breaker.
	 * @param circuitBreaker the circuit breaker shared by every request
	 * @return the provider
	 */
	static CircuitBreakerProvider fixed(CircuitBreaker circuitBreaker) {
		Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
		return request -> circuitBreaker;
	}

	/**
	 * Returns a provider that resolves a circuit breaker per request host (lower-cased).
	 * Falls back to {@code "<unknown>"} when the host cannot be determined.
	 * @param registry the registry to look up circuit breakers in
	 * @return the provider
	 */
	static CircuitBreakerProvider byHost(CircuitBreakerRegistry registry) {
		Objects.requireNonNull(registry, "registry must not be null");
		return request -> {
			URI uri = request.getURI();
			String host = (uri != null) ? uri.getHost() : null;
			return registry.circuitBreaker((host != null) ? host.toLowerCase() : "<unknown>");
		};
	}

	/**
	 * Returns a provider that resolves a circuit breaker per request authority
	 * ({@code host:port}, lower-cased). Falls back to {@code "<unknown>"} when the
	 * authority cannot be determined.
	 * @param registry the registry to look up circuit breakers in
	 * @return the provider
	 */
	static CircuitBreakerProvider byAuthority(CircuitBreakerRegistry registry) {
		Objects.requireNonNull(registry, "registry must not be null");
		return request -> {
			URI uri = request.getURI();
			String authority = (uri != null) ? uri.getAuthority() : null;
			return registry.circuitBreaker((authority != null) ? authority.toLowerCase() : "<unknown>");
		};
	}

}

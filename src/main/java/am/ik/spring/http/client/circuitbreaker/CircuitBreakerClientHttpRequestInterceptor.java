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

import am.ik.spring.http.client.circuitbreaker.CircuitBreakerLifecycle.ResponseOrException;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A {@link ClientHttpRequestInterceptor} that wraps every HTTP call with a
 * {@link CircuitBreaker}.
 *
 * <p>
 * Pick the construction style that fits the use case:
 * <ul>
 * <li>{@link #CircuitBreakerClientHttpRequestInterceptor(CircuitBreaker)} — share one
 * circuit breaker across every request.
 * <li>{@link #CircuitBreakerClientHttpRequestInterceptor(CircuitBreakerRegistry)} — one
 * circuit breaker per request host.
 * <li>{@link #CircuitBreakerClientHttpRequestInterceptor(CircuitBreakerProvider)} — fully
 * custom mapping from request to circuit breaker.
 * <li>{@link #builder()} — fine-tune the failure predicate and lifecycle.
 * </ul>
 *
 * <p>
 * When the circuit breaker rejects the call, a {@link CallNotPermittedException} is
 * thrown.
 */
public class CircuitBreakerClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

	private final CircuitBreakerProvider circuitBreakerProvider;

	private final FailurePredicate failurePredicate;

	private final CircuitBreakerLifecycle lifecycle;

	private final Logger log = LoggerFactory.getLogger(CircuitBreakerClientHttpRequestInterceptor.class);

	/**
	 * Creates an interceptor that uses the same circuit breaker for every request.
	 * Failures and lifecycle use the recommended defaults.
	 * @param circuitBreaker the circuit breaker shared by every request
	 */
	public CircuitBreakerClientHttpRequestInterceptor(CircuitBreaker circuitBreaker) {
		this(CircuitBreakerProvider.fixed(circuitBreaker), FailurePredicate.defaults(), CircuitBreakerLifecycle.NOOP);
	}

	/**
	 * Creates an interceptor that uses one circuit breaker per request host. Failures and
	 * lifecycle use the recommended defaults.
	 * @param registry the registry to look up circuit breakers in
	 */
	public CircuitBreakerClientHttpRequestInterceptor(CircuitBreakerRegistry registry) {
		this(CircuitBreakerProvider.byHost(registry), FailurePredicate.defaults(), CircuitBreakerLifecycle.NOOP);
	}

	/**
	 * Creates an interceptor backed by the given provider. Failures and lifecycle use the
	 * recommended defaults.
	 * @param circuitBreakerProvider the provider that maps requests to circuit breakers
	 */
	public CircuitBreakerClientHttpRequestInterceptor(CircuitBreakerProvider circuitBreakerProvider) {
		this(circuitBreakerProvider, FailurePredicate.defaults(), CircuitBreakerLifecycle.NOOP);
	}

	private CircuitBreakerClientHttpRequestInterceptor(CircuitBreakerProvider circuitBreakerProvider,
			FailurePredicate failurePredicate, CircuitBreakerLifecycle lifecycle) {
		this.circuitBreakerProvider = Objects.requireNonNull(circuitBreakerProvider,
				"circuitBreakerProvider must not be null");
		this.failurePredicate = Objects.requireNonNull(failurePredicate, "failurePredicate must not be null");
		this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
	}

	/**
	 * Returns a new {@link Builder}.
	 * @return the builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		CircuitBreaker circuitBreaker = this.circuitBreakerProvider.get(request);
		if (!circuitBreaker.tryAcquirePermission()) {
			this.log.debug("type=rej circuitBreaker=\"{}\" state={} method={} url=\"{}\"", circuitBreaker.name(),
					circuitBreaker.state(), request.getMethod(), request.getURI());
			this.lifecycle.onCallNotPermitted(circuitBreaker, request);
			throw new CallNotPermittedException(circuitBreaker);
		}
		long start = System.nanoTime();
		ClientHttpResponse response;
		try {
			response = execution.execute(request, body);
		}
		catch (IOException ex) {
			recordOutcome(circuitBreaker, request, null, ex, System.nanoTime() - start);
			throw ex;
		}
		catch (RuntimeException ex) {
			recordOutcome(circuitBreaker, request, null, ex, System.nanoTime() - start);
			throw ex;
		}
		recordOutcome(circuitBreaker, request, response, null, System.nanoTime() - start);
		return response;
	}

	private void recordOutcome(CircuitBreaker circuitBreaker, HttpRequest request,
			@Nullable ClientHttpResponse response, @Nullable Exception error, long durationNanos) throws IOException {
		boolean failure = this.failurePredicate.isFailure(response, error);
		if (failure) {
			circuitBreaker.onError(durationNanos, error);
			ResponseOrException result = (error != null) ? ResponseOrException.ofException(error)
					: ResponseOrException.ofResponse(Objects.requireNonNull(response));
			this.lifecycle.onFailure(circuitBreaker, request, result);
		}
		else {
			circuitBreaker.onSuccess(durationNanos);
			if (response != null) {
				this.lifecycle.onSuccess(circuitBreaker, request, response);
			}
		}
	}

	/**
	 * Builder for {@link CircuitBreakerClientHttpRequestInterceptor}. A circuit breaker
	 * source is required; failure predicate and lifecycle default to
	 * {@link FailurePredicate#defaults()} and {@link CircuitBreakerLifecycle#NOOP}.
	 */
	public static final class Builder {

		private @Nullable CircuitBreakerProvider circuitBreakerProvider;

		private FailurePredicate failurePredicate = FailurePredicate.defaults();

		private CircuitBreakerLifecycle lifecycle = CircuitBreakerLifecycle.NOOP;

		private Builder() {
		}

		/**
		 * Use the same circuit breaker for every request.
		 * @param circuitBreaker the circuit breaker
		 * @return this builder
		 */
		public Builder circuitBreaker(CircuitBreaker circuitBreaker) {
			this.circuitBreakerProvider = CircuitBreakerProvider.fixed(circuitBreaker);
			return this;
		}

		/**
		 * Use one circuit breaker per request host (default lookup strategy).
		 * @param registry the registry
		 * @return this builder
		 */
		public Builder registry(CircuitBreakerRegistry registry) {
			this.circuitBreakerProvider = CircuitBreakerProvider.byHost(registry);
			return this;
		}

		/**
		 * Use the given provider to resolve the circuit breaker for each request.
		 * @param circuitBreakerProvider the provider
		 * @return this builder
		 */
		public Builder circuitBreakerProvider(CircuitBreakerProvider circuitBreakerProvider) {
			this.circuitBreakerProvider = circuitBreakerProvider;
			return this;
		}

		/**
		 * Sets the failure predicate. Defaults to {@link FailurePredicate#defaults()}.
		 * @param failurePredicate the predicate
		 * @return this builder
		 */
		public Builder failurePredicate(FailurePredicate failurePredicate) {
			this.failurePredicate = Objects.requireNonNull(failurePredicate, "failurePredicate must not be null");
			return this;
		}

		/**
		 * Sets the lifecycle hooks. Defaults to {@link CircuitBreakerLifecycle#NOOP}.
		 * @param lifecycle the lifecycle hooks
		 * @return this builder
		 */
		public Builder lifecycle(CircuitBreakerLifecycle lifecycle) {
			this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
			return this;
		}

		/**
		 * Builds the interceptor.
		 * @return the interceptor
		 * @throws IllegalStateException if no circuit breaker source has been configured
		 */
		public CircuitBreakerClientHttpRequestInterceptor build() {
			if (this.circuitBreakerProvider == null) {
				throw new IllegalStateException(
						"A circuit breaker source must be configured: call circuitBreaker(...), registry(...), or circuitBreakerProvider(...)");
			}
			return new CircuitBreakerClientHttpRequestInterceptor(this.circuitBreakerProvider, this.failurePredicate,
					this.lifecycle);
		}

	}

}

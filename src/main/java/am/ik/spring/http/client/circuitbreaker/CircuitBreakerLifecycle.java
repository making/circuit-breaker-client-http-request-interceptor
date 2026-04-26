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

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Hooks into the lifecycle of HTTP calls protected by a {@link CircuitBreaker}.
 */
public interface CircuitBreakerLifecycle {

	/**
	 * Called when a call has been recorded as successful.
	 * @param circuitBreaker the circuit breaker that recorded the call
	 * @param request the HTTP request
	 * @param response the HTTP response
	 */
	default void onSuccess(CircuitBreaker circuitBreaker, HttpRequest request, ClientHttpResponse response) {
	}

	/**
	 * Called when a call has been recorded as a failure (either an HTTP response that was
	 * deemed a failure, or an exception).
	 * @param circuitBreaker the circuit breaker that recorded the call
	 * @param request the HTTP request
	 * @param responseOrException the response or exception
	 */
	default void onFailure(CircuitBreaker circuitBreaker, HttpRequest request,
			ResponseOrException responseOrException) {
	}

	/**
	 * Called when a call is rejected because the circuit breaker is open or out of probe
	 * permits.
	 * @param circuitBreaker the circuit breaker that rejected the call
	 * @param request the HTTP request
	 */
	default void onCallNotPermitted(CircuitBreaker circuitBreaker, HttpRequest request) {
	}

	/**
	 * No-op lifecycle.
	 */
	CircuitBreakerLifecycle NOOP = new CircuitBreakerLifecycle() {
	};

	/**
	 * A holder for either an HTTP response or an exception.
	 */
	final class ResponseOrException {

		private final @Nullable ClientHttpResponse response;

		private final @Nullable Exception exception;

		private ResponseOrException(@Nullable ClientHttpResponse response, @Nullable Exception exception) {
			this.response = response;
			this.exception = exception;
		}

		public static ResponseOrException ofResponse(ClientHttpResponse response) {
			return new ResponseOrException(response, null);
		}

		public static ResponseOrException ofException(Exception exception) {
			return new ResponseOrException(null, exception);
		}

		public @Nullable ClientHttpResponse response() {
			return this.response;
		}

		public @Nullable Exception exception() {
			return this.exception;
		}

		public boolean hasResponse() {
			return this.response != null;
		}

		public boolean hasException() {
			return this.exception != null;
		}

		@Override
		public String toString() {
			if (this.response != null) {
				return this.response.toString();
			}
			if (this.exception != null) {
				return this.exception.toString();
			}
			return "null";
		}

	}

}

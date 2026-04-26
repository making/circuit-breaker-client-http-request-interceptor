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

import java.io.IOException;

/**
 * Thrown by {@link CircuitBreakerClientHttpRequestInterceptor} when a circuit breaker is
 * open and rejects the call. Extends {@link IOException} so it can propagate from
 * {@link org.springframework.http.client.ClientHttpRequestInterceptor#intercept}.
 */
public class CallNotPermittedException extends IOException {

	private static final long serialVersionUID = 1L;

	private final String circuitBreakerName;

	private final CircuitBreaker.State state;

	public CallNotPermittedException(CircuitBreaker circuitBreaker) {
		super(String.format("CircuitBreaker '%s' is %s and does not permit further calls", circuitBreaker.name(),
				circuitBreaker.state()));
		this.circuitBreakerName = circuitBreaker.name();
		this.state = circuitBreaker.state();
	}

	/**
	 * Returns the name of the circuit breaker that rejected the call.
	 * @return the circuit breaker name
	 */
	public String circuitBreakerName() {
		return this.circuitBreakerName;
	}

	/**
	 * Returns the state of the circuit breaker at the time of rejection.
	 * @return the circuit breaker state
	 */
	public CircuitBreaker.State state() {
		return this.state;
	}

}

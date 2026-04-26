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

/**
 * A circuit breaker that protects callers from repeatedly invoking a failing dependency.
 *
 * <p>
 * State transitions:
 * <ul>
 * <li>{@link State#CLOSED}: calls are permitted. When the failure (or slow call) rate in
 * the sliding window reaches its threshold, the circuit transitions to
 * {@link State#OPEN}.</li>
 * <li>{@link State#OPEN}: calls are rejected. After the configured wait duration has
 * elapsed, the next call attempt transitions the circuit to {@link State#HALF_OPEN}.</li>
 * <li>{@link State#HALF_OPEN}: a limited number of probe calls are permitted. When the
 * configured number of probe calls have completed, the failure rate is evaluated and the
 * circuit transitions back to {@link State#CLOSED} or {@link State#OPEN}.</li>
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface CircuitBreaker {

	/**
	 * Returns the name identifying this circuit breaker (e.g. a host name).
	 * @return the name of this circuit breaker
	 */
	String name();

	/**
	 * Returns the current state of this circuit breaker.
	 * @return the current state
	 */
	State state();

	/**
	 * Tries to acquire a permission to execute a call. Updates internal state if needed
	 * (e.g. transitioning from {@code OPEN} to {@code HALF_OPEN}).
	 * @return {@code true} if a permission was acquired, {@code false} otherwise
	 */
	boolean tryAcquirePermission();

	/**
	 * Releases a previously acquired permission without recording an outcome. Should only
	 * be used when a permission was acquired but the call was not actually performed.
	 */
	void releasePermission();

	/**
	 * Records a successful call.
	 * @param durationNanos the duration of the call in nanoseconds
	 */
	void onSuccess(long durationNanos);

	/**
	 * Records a failed call.
	 * @param durationNanos the duration of the call in nanoseconds
	 * @param error the failure cause, or {@code null} if the failure was inferred from a
	 * response rather than an exception
	 */
	void onError(long durationNanos, @Nullable Throwable error);

	/**
	 * Returns the metrics of this circuit breaker.
	 * @return the metrics snapshot
	 */
	Metrics metrics();

	/**
	 * Resets the circuit breaker, clearing all metrics and returning to the initial
	 * state.
	 */
	void reset();

	/**
	 * Forces the circuit breaker into the {@link State#OPEN} state.
	 */
	void transitionToOpen();

	/**
	 * Forces the circuit breaker into the {@link State#CLOSED} state and clears metrics.
	 */
	void transitionToClosed();

	/**
	 * Forces the circuit breaker into the {@link State#HALF_OPEN} state and clears
	 * metrics.
	 */
	void transitionToHalfOpen();

	/**
	 * The state of a circuit breaker.
	 */
	enum State {

		/**
		 * Calls are permitted; outcomes are recorded.
		 */
		CLOSED,

		/**
		 * Calls are rejected.
		 */
		OPEN,

		/**
		 * A limited number of probe calls are permitted.
		 */
		HALF_OPEN

	}

	/**
	 * A snapshot of metrics for a circuit breaker.
	 */
	interface Metrics {

		/**
		 * The number of calls recorded in the current sliding window.
		 * @return the number of buffered calls
		 */
		int numberOfBufferedCalls();

		/**
		 * The number of failed calls recorded in the current sliding window.
		 * @return the number of failed calls
		 */
		int numberOfFailedCalls();

		/**
		 * The number of slow calls recorded in the current sliding window.
		 * @return the number of slow calls
		 */
		int numberOfSlowCalls();

		/**
		 * The failure rate (as percentage 0-100) computed against the current sliding
		 * window. Returns {@code -1f} when the minimum number of calls has not yet been
		 * reached.
		 * @return the failure rate or {@code -1f}
		 */
		float failureRate();

		/**
		 * The slow call rate (as percentage 0-100) computed against the current sliding
		 * window. Returns {@code -1f} when the minimum number of calls has not yet been
		 * reached.
		 * @return the slow call rate or {@code -1f}
		 */
		float slowCallRate();

	}

}

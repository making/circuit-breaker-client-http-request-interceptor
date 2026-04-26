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

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a {@link DefaultCircuitBreaker}.
 *
 * <p>
 * Instances are created via {@link #builder()} or {@link #ofDefaults()}.
 */
public final class CircuitBreakerConfig {

	/**
	 * Default failure rate threshold (in percent).
	 */
	public static final float DEFAULT_FAILURE_RATE_THRESHOLD = 50f;

	/**
	 * Default slow call rate threshold (in percent). Slow call detection is only enabled
	 * when {@link Builder#slowCallDurationThreshold(Duration)} is set to a positive
	 * value.
	 */
	public static final float DEFAULT_SLOW_CALL_RATE_THRESHOLD = 100f;

	/**
	 * Default size of the sliding window.
	 */
	public static final int DEFAULT_SLIDING_WINDOW_SIZE = 100;

	/**
	 * Default minimum number of calls before the failure rate can be evaluated.
	 */
	public static final int DEFAULT_MINIMUM_NUMBER_OF_CALLS = 100;

	/**
	 * Default wait duration in the {@code OPEN} state.
	 */
	public static final Duration DEFAULT_WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(60);

	/**
	 * Default number of permitted probe calls in the {@code HALF_OPEN} state.
	 */
	public static final int DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE = 10;

	/**
	 * Default slow call duration threshold (zero means slow call detection is disabled).
	 */
	public static final Duration DEFAULT_SLOW_CALL_DURATION_THRESHOLD = Duration.ZERO;

	private final float failureRateThreshold;

	private final float slowCallRateThreshold;

	private final Duration slowCallDurationThreshold;

	private final int slidingWindowSize;

	private final int minimumNumberOfCalls;

	private final Duration waitDurationInOpenState;

	private final int permittedNumberOfCallsInHalfOpenState;

	private CircuitBreakerConfig(Builder builder) {
		this.failureRateThreshold = builder.failureRateThreshold;
		this.slowCallRateThreshold = builder.slowCallRateThreshold;
		this.slowCallDurationThreshold = builder.slowCallDurationThreshold;
		this.slidingWindowSize = builder.slidingWindowSize;
		this.minimumNumberOfCalls = Math.min(builder.minimumNumberOfCalls, builder.slidingWindowSize);
		this.waitDurationInOpenState = builder.waitDurationInOpenState;
		this.permittedNumberOfCallsInHalfOpenState = builder.permittedNumberOfCallsInHalfOpenState;
	}

	/**
	 * Returns a new {@link CircuitBreakerConfig} with default values.
	 * @return the default configuration
	 */
	public static CircuitBreakerConfig ofDefaults() {
		return builder().build();
	}

	/**
	 * Returns a new builder.
	 * @return the builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	public float failureRateThreshold() {
		return this.failureRateThreshold;
	}

	public float slowCallRateThreshold() {
		return this.slowCallRateThreshold;
	}

	public Duration slowCallDurationThreshold() {
		return this.slowCallDurationThreshold;
	}

	public int slidingWindowSize() {
		return this.slidingWindowSize;
	}

	public int minimumNumberOfCalls() {
		return this.minimumNumberOfCalls;
	}

	public Duration waitDurationInOpenState() {
		return this.waitDurationInOpenState;
	}

	public int permittedNumberOfCallsInHalfOpenState() {
		return this.permittedNumberOfCallsInHalfOpenState;
	}

	/**
	 * Builder for {@link CircuitBreakerConfig}.
	 */
	public static final class Builder {

		private float failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;

		private float slowCallRateThreshold = DEFAULT_SLOW_CALL_RATE_THRESHOLD;

		private Duration slowCallDurationThreshold = DEFAULT_SLOW_CALL_DURATION_THRESHOLD;

		private int slidingWindowSize = DEFAULT_SLIDING_WINDOW_SIZE;

		private int minimumNumberOfCalls = DEFAULT_MINIMUM_NUMBER_OF_CALLS;

		private Duration waitDurationInOpenState = DEFAULT_WAIT_DURATION_IN_OPEN_STATE;

		private int permittedNumberOfCallsInHalfOpenState = DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE;

		private Builder() {
		}

		/**
		 * Sets the failure rate threshold (in percent, 0-100). When the failure rate in
		 * the sliding window reaches this value, the circuit transitions to {@code OPEN}.
		 * @param failureRateThreshold the threshold (must be in {@code (0, 100]})
		 * @return this builder
		 */
		public Builder failureRateThreshold(float failureRateThreshold) {
			if (failureRateThreshold <= 0f || failureRateThreshold > 100f) {
				throw new IllegalArgumentException("failureRateThreshold must be in (0, 100]");
			}
			this.failureRateThreshold = failureRateThreshold;
			return this;
		}

		/**
		 * Sets the slow call rate threshold (in percent, 0-100).
		 * @param slowCallRateThreshold the threshold (must be in {@code (0, 100]})
		 * @return this builder
		 */
		public Builder slowCallRateThreshold(float slowCallRateThreshold) {
			if (slowCallRateThreshold <= 0f || slowCallRateThreshold > 100f) {
				throw new IllegalArgumentException("slowCallRateThreshold must be in (0, 100]");
			}
			this.slowCallRateThreshold = slowCallRateThreshold;
			return this;
		}

		/**
		 * Sets the slow call duration threshold. A call is considered slow when its
		 * duration is greater than or equal to this value. Set to {@link Duration#ZERO}
		 * to disable slow call detection.
		 * @param slowCallDurationThreshold the threshold
		 * @return this builder
		 */
		public Builder slowCallDurationThreshold(Duration slowCallDurationThreshold) {
			Objects.requireNonNull(slowCallDurationThreshold, "slowCallDurationThreshold must not be null");
			if (slowCallDurationThreshold.isNegative()) {
				throw new IllegalArgumentException("slowCallDurationThreshold must not be negative");
			}
			this.slowCallDurationThreshold = slowCallDurationThreshold;
			return this;
		}

		/**
		 * Sets the size of the count-based sliding window.
		 * @param slidingWindowSize the size (must be positive)
		 * @return this builder
		 */
		public Builder slidingWindowSize(int slidingWindowSize) {
			if (slidingWindowSize <= 0) {
				throw new IllegalArgumentException("slidingWindowSize must be greater than 0");
			}
			this.slidingWindowSize = slidingWindowSize;
			return this;
		}

		/**
		 * Sets the minimum number of calls required before the failure rate can be
		 * evaluated. Capped at {@code slidingWindowSize} when building.
		 * @param minimumNumberOfCalls the minimum (must be positive)
		 * @return this builder
		 */
		public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
			if (minimumNumberOfCalls <= 0) {
				throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
			}
			this.minimumNumberOfCalls = minimumNumberOfCalls;
			return this;
		}

		/**
		 * Sets the wait duration in the {@code OPEN} state.
		 * @param waitDurationInOpenState the duration (must be non-negative)
		 * @return this builder
		 */
		public Builder waitDurationInOpenState(Duration waitDurationInOpenState) {
			Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
			if (waitDurationInOpenState.isNegative()) {
				throw new IllegalArgumentException("waitDurationInOpenState must not be negative");
			}
			this.waitDurationInOpenState = waitDurationInOpenState;
			return this;
		}

		/**
		 * Sets the number of permitted probe calls in the {@code HALF_OPEN} state.
		 * @param permittedNumberOfCallsInHalfOpenState the number of permitted calls
		 * (must be positive)
		 * @return this builder
		 */
		public Builder permittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
			if (permittedNumberOfCallsInHalfOpenState <= 0) {
				throw new IllegalArgumentException("permittedNumberOfCallsInHalfOpenState must be greater than 0");
			}
			this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
			return this;
		}

		/**
		 * Builds the configuration.
		 * @return the configuration
		 */
		public CircuitBreakerConfig build() {
			return new CircuitBreakerConfig(this);
		}

	}

}

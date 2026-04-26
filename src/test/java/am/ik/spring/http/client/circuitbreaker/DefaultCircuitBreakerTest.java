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
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCircuitBreakerTest {

	private static final long ONE_MS_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

	private static final class MutableInstantSource implements InstantSource {

		private Instant now;

		MutableInstantSource(Instant now) {
			this.now = now;
		}

		void advance(Duration d) {
			this.now = this.now.plus(d);
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

	@Test
	void initialStateIsClosed() {
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", CircuitBreakerConfig.ofDefaults());
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cb.tryAcquirePermission()).isTrue();
	}

	@Test
	void doesNotOpenBeforeMinimumCalls() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(10)
			.minimumNumberOfCalls(10)
			.failureRateThreshold(50f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		// Record 9 failures: still CLOSED because minimum has not been reached
		for (int i = 0; i < 9; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException("boom"));
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void opensWhenFailureRateThresholdReached() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(10)
			.minimumNumberOfCalls(10)
			.failureRateThreshold(50f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		// 5 successes + 5 failures = 50% failure rate => OPEN
		for (int i = 0; i < 5; i++) {
			cb.tryAcquirePermission();
			cb.onSuccess(ONE_MS_NANOS);
		}
		for (int i = 0; i < 5; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException("boom"));
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(cb.tryAcquirePermission()).isFalse();
	}

	@Test
	void doesNotOpenBelowFailureRateThreshold() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(10)
			.minimumNumberOfCalls(10)
			.failureRateThreshold(60f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		// 5 successes + 5 failures = 50% failure rate => still CLOSED (threshold 60%)
		for (int i = 0; i < 5; i++) {
			cb.tryAcquirePermission();
			cb.onSuccess(ONE_MS_NANOS);
		}
		for (int i = 0; i < 5; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException("boom"));
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void transitionsToHalfOpenAfterWaitDuration() {
		MutableInstantSource clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofSeconds(5))
			.permittedNumberOfCallsInHalfOpenState(2)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config, clock);
		// Trip the breaker
		for (int i = 0; i < 4; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException());
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(cb.tryAcquirePermission()).isFalse();
		// After wait duration, next acquire should transition to HALF_OPEN
		clock.advance(Duration.ofSeconds(5));
		assertThat(cb.tryAcquirePermission()).isTrue();
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
	}

	@Test
	void halfOpenLimitsPermits() {
		MutableInstantSource clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofSeconds(5))
			.permittedNumberOfCallsInHalfOpenState(2)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config, clock);
		for (int i = 0; i < 4; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException());
		}
		clock.advance(Duration.ofSeconds(5));
		assertThat(cb.tryAcquirePermission()).isTrue();
		assertThat(cb.tryAcquirePermission()).isTrue();
		// Third request should be rejected because only 2 probe calls are permitted
		assertThat(cb.tryAcquirePermission()).isFalse();
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
	}

	@Test
	void halfOpenTransitionsToClosedOnSuccess() {
		MutableInstantSource clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofSeconds(5))
			.permittedNumberOfCallsInHalfOpenState(2)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config, clock);
		for (int i = 0; i < 4; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException());
		}
		clock.advance(Duration.ofSeconds(5));
		// 2 probe calls, both success
		cb.tryAcquirePermission();
		cb.onSuccess(ONE_MS_NANOS);
		cb.tryAcquirePermission();
		cb.onSuccess(ONE_MS_NANOS);
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void halfOpenReOpensOnFailures() {
		MutableInstantSource clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofSeconds(5))
			.permittedNumberOfCallsInHalfOpenState(2)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config, clock);
		for (int i = 0; i < 4; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException());
		}
		clock.advance(Duration.ofSeconds(5));
		cb.tryAcquirePermission();
		cb.onError(ONE_MS_NANOS, new RuntimeException());
		cb.tryAcquirePermission();
		cb.onError(ONE_MS_NANOS, new RuntimeException());
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void slowCallsTripBreaker() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(10)
			.minimumNumberOfCalls(10)
			.failureRateThreshold(100f) // disable failure rate trigger
			.slowCallDurationThreshold(Duration.ofMillis(100))
			.slowCallRateThreshold(50f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		// 5 fast successes
		for (int i = 0; i < 5; i++) {
			cb.tryAcquirePermission();
			cb.onSuccess(TimeUnit.MILLISECONDS.toNanos(10));
		}
		// 5 slow successes => slow call rate 50% => OPEN
		for (int i = 0; i < 5; i++) {
			cb.tryAcquirePermission();
			cb.onSuccess(TimeUnit.MILLISECONDS.toNanos(200));
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void resetReturnsToClosed() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		for (int i = 0; i < 4; i++) {
			cb.tryAcquirePermission();
			cb.onError(ONE_MS_NANOS, new RuntimeException());
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
		cb.reset();
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cb.metrics().numberOfBufferedCalls()).isZero();
	}

	@Test
	void slidingWindowEvictsOldestEntry() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(75f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		// Fill window with 4 successes
		for (int i = 0; i < 4; i++) {
			cb.tryAcquirePermission();
			cb.onSuccess(ONE_MS_NANOS);
		}
		// Add 2 failures: window now [F, F, S, S] => 50% failure rate, still CLOSED
		cb.tryAcquirePermission();
		cb.onError(ONE_MS_NANOS, new RuntimeException());
		cb.tryAcquirePermission();
		cb.onError(ONE_MS_NANOS, new RuntimeException());
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
		// Add 1 more failure: window now [F, F, F, S] => 75% => OPEN (threshold 75%)
		cb.tryAcquirePermission();
		cb.onError(ONE_MS_NANOS, new RuntimeException());
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void metricsReflectFailureRate() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(10)
			.minimumNumberOfCalls(2)
			.failureRateThreshold(100f)
			.build();
		DefaultCircuitBreaker cb = new DefaultCircuitBreaker("test", config);
		cb.tryAcquirePermission();
		cb.onSuccess(ONE_MS_NANOS);
		cb.tryAcquirePermission();
		cb.onError(ONE_MS_NANOS, new RuntimeException());
		CircuitBreaker.Metrics metrics = cb.metrics();
		assertThat(metrics.numberOfBufferedCalls()).isEqualTo(2);
		assertThat(metrics.numberOfFailedCalls()).isEqualTo(1);
		assertThat(metrics.failureRate()).isEqualTo(50f);
	}

}

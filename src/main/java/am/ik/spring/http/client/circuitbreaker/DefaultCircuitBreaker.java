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

import java.time.InstantSource;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default thread-safe {@link CircuitBreaker} based on a count-based sliding window.
 *
 * <p>
 * In the {@code CLOSED} state, outcomes are recorded into a fixed-size ring buffer. Once
 * the configured minimum number of calls has been reached, the failure rate and slow call
 * rate are evaluated on every call; the circuit transitions to {@code OPEN} when either
 * threshold is reached.
 *
 * <p>
 * In the {@code HALF_OPEN} state, a fixed number of probe calls is permitted; when all
 * probe calls have completed, the failure rate of the probe window is evaluated and the
 * circuit transitions to {@code CLOSED} or back to {@code OPEN}.
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

	private static final Logger log = LoggerFactory.getLogger(DefaultCircuitBreaker.class);

	private static final byte FAILED = 0x01;

	private static final byte SLOW = 0x02;

	private final String name;

	private final CircuitBreakerConfig config;

	private final InstantSource instantSource;

	private final ReentrantLock lock = new ReentrantLock();

	private final byte[] outcomes;

	private volatile State state = State.CLOSED;

	private volatile long openedAtMillis;

	private int writeIndex;

	private int filled;

	private int failedCalls;

	private int slowCalls;

	private int halfOpenPermitsLeft;

	private int halfOpenCallsCompleted;

	private int halfOpenFailedCalls;

	private int halfOpenSlowCalls;

	/**
	 * Creates a circuit breaker with the given name and configuration, using the system
	 * instant source.
	 * @param name the name (e.g. a host name)
	 * @param config the configuration
	 */
	public DefaultCircuitBreaker(String name, CircuitBreakerConfig config) {
		this(name, config, InstantSource.system());
	}

	/**
	 * Creates a circuit breaker with the given name, configuration, and instant source.
	 * Mostly intended for testing.
	 * @param name the name
	 * @param config the configuration
	 * @param instantSource the instant source used for measuring the wait duration in the
	 * {@code OPEN} state
	 */
	public DefaultCircuitBreaker(String name, CircuitBreakerConfig config, InstantSource instantSource) {
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.instantSource = Objects.requireNonNull(instantSource, "instantSource must not be null");
		this.outcomes = new byte[config.slidingWindowSize()];
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public State state() {
		return this.state;
	}

	@Override
	public boolean tryAcquirePermission() {
		// fast path: no lock needed in CLOSED state
		if (this.state == State.CLOSED) {
			return true;
		}
		this.lock.lock();
		try {
			State s = this.state;
			if (s == State.CLOSED) {
				return true;
			}
			if (s == State.OPEN) {
				if (this.instantSource.millis() - this.openedAtMillis >= this.config.waitDurationInOpenState()
					.toMillis()) {
					doTransitionToHalfOpen();
				}
				else {
					return false;
				}
			}
			// HALF_OPEN
			if (this.halfOpenPermitsLeft > 0) {
				this.halfOpenPermitsLeft--;
				return true;
			}
			return false;
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void releasePermission() {
		this.lock.lock();
		try {
			if (this.state == State.HALF_OPEN
					&& this.halfOpenPermitsLeft < this.config.permittedNumberOfCallsInHalfOpenState()) {
				this.halfOpenPermitsLeft++;
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void onSuccess(long durationNanos) {
		recordOutcome(false, isSlow(durationNanos));
	}

	@Override
	public void onError(long durationNanos, @Nullable Throwable error) {
		recordOutcome(true, isSlow(durationNanos));
	}

	@Override
	public Metrics metrics() {
		this.lock.lock();
		try {
			if (this.state == State.HALF_OPEN) {
				int total = this.halfOpenCallsCompleted;
				int min = Math.min(this.config.permittedNumberOfCallsInHalfOpenState(),
						this.config.minimumNumberOfCalls());
				float failureRate = (total >= min && total > 0) ? 100f * this.halfOpenFailedCalls / total : -1f;
				float slowRate = (total >= min && total > 0) ? 100f * this.halfOpenSlowCalls / total : -1f;
				return new MetricsSnapshot(total, this.halfOpenFailedCalls, this.halfOpenSlowCalls, failureRate,
						slowRate);
			}
			int total = this.filled;
			float failureRate = (total >= this.config.minimumNumberOfCalls() && total > 0)
					? 100f * this.failedCalls / total : -1f;
			float slowRate = (total >= this.config.minimumNumberOfCalls() && total > 0) ? 100f * this.slowCalls / total
					: -1f;
			return new MetricsSnapshot(total, this.failedCalls, this.slowCalls, failureRate, slowRate);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void reset() {
		this.lock.lock();
		try {
			this.state = State.CLOSED;
			clearSlidingWindow();
			clearHalfOpen();
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void transitionToOpen() {
		this.lock.lock();
		try {
			doTransitionToOpen();
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void transitionToClosed() {
		this.lock.lock();
		try {
			doTransitionToClosed();
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void transitionToHalfOpen() {
		this.lock.lock();
		try {
			doTransitionToHalfOpen();
		}
		finally {
			this.lock.unlock();
		}
	}

	private boolean isSlow(long durationNanos) {
		long thresholdNanos = this.config.slowCallDurationThreshold().toNanos();
		return thresholdNanos > 0 && durationNanos >= thresholdNanos;
	}

	private void recordOutcome(boolean failed, boolean slow) {
		this.lock.lock();
		try {
			State s = this.state;
			if (s == State.OPEN) {
				// late result of a call started before transition; ignore
				return;
			}
			if (s == State.CLOSED) {
				pushOutcome(failed, slow);
				evaluateClosed();
			}
			else {
				this.halfOpenCallsCompleted++;
				if (failed) {
					this.halfOpenFailedCalls++;
				}
				if (slow) {
					this.halfOpenSlowCalls++;
				}
				if (this.halfOpenCallsCompleted >= this.config.permittedNumberOfCallsInHalfOpenState()) {
					evaluateHalfOpen();
				}
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	private void pushOutcome(boolean failed, boolean slow) {
		byte oldVal = this.outcomes[this.writeIndex];
		if (this.filled == this.outcomes.length) {
			if ((oldVal & FAILED) != 0) {
				this.failedCalls--;
			}
			if ((oldVal & SLOW) != 0) {
				this.slowCalls--;
			}
		}
		else {
			this.filled++;
		}
		byte v = 0;
		if (failed) {
			v |= FAILED;
		}
		if (slow) {
			v |= SLOW;
		}
		this.outcomes[this.writeIndex] = v;
		this.writeIndex = (this.writeIndex + 1) % this.outcomes.length;
		if (failed) {
			this.failedCalls++;
		}
		if (slow) {
			this.slowCalls++;
		}
	}

	private void evaluateClosed() {
		if (this.filled < this.config.minimumNumberOfCalls()) {
			return;
		}
		float failureRate = 100f * this.failedCalls / this.filled;
		float slowRate = 100f * this.slowCalls / this.filled;
		if (failureRate >= this.config.failureRateThreshold() || (this.config.slowCallDurationThreshold().toNanos() > 0
				&& slowRate >= this.config.slowCallRateThreshold())) {
			doTransitionToOpen();
		}
	}

	private void evaluateHalfOpen() {
		int total = this.halfOpenCallsCompleted;
		float failureRate = 100f * this.halfOpenFailedCalls / total;
		float slowRate = 100f * this.halfOpenSlowCalls / total;
		if (failureRate >= this.config.failureRateThreshold() || (this.config.slowCallDurationThreshold().toNanos() > 0
				&& slowRate >= this.config.slowCallRateThreshold())) {
			doTransitionToOpen();
		}
		else {
			doTransitionToClosed();
		}
	}

	private void doTransitionToOpen() {
		State previous = this.state;
		this.state = State.OPEN;
		this.openedAtMillis = this.instantSource.millis();
		clearSlidingWindow();
		clearHalfOpen();
		if (previous != State.OPEN) {
			log.info("Circuit breaker '{}' transitioned from {} to OPEN", this.name, previous);
		}
	}

	private void doTransitionToClosed() {
		State previous = this.state;
		this.state = State.CLOSED;
		clearSlidingWindow();
		clearHalfOpen();
		if (previous != State.CLOSED) {
			log.info("Circuit breaker '{}' transitioned from {} to CLOSED", this.name, previous);
		}
	}

	private void doTransitionToHalfOpen() {
		State previous = this.state;
		this.state = State.HALF_OPEN;
		clearSlidingWindow();
		this.halfOpenPermitsLeft = this.config.permittedNumberOfCallsInHalfOpenState();
		this.halfOpenCallsCompleted = 0;
		this.halfOpenFailedCalls = 0;
		this.halfOpenSlowCalls = 0;
		if (previous != State.HALF_OPEN) {
			log.info("Circuit breaker '{}' transitioned from {} to HALF_OPEN", this.name, previous);
		}
	}

	private void clearSlidingWindow() {
		Arrays.fill(this.outcomes, (byte) 0);
		this.writeIndex = 0;
		this.filled = 0;
		this.failedCalls = 0;
		this.slowCalls = 0;
	}

	private void clearHalfOpen() {
		this.halfOpenPermitsLeft = 0;
		this.halfOpenCallsCompleted = 0;
		this.halfOpenFailedCalls = 0;
		this.halfOpenSlowCalls = 0;
	}

	private record MetricsSnapshot(int numberOfBufferedCalls, int numberOfFailedCalls, int numberOfSlowCalls,
			float failureRate, float slowCallRate) implements Metrics {
	}

}

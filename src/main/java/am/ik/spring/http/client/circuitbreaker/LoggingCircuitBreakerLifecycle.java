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

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A {@link CircuitBreakerLifecycle} implementation that simply emits SLF4J log records
 * for each lifecycle event.
 *
 * <p>
 * Default log levels are:
 * <ul>
 * <li>{@link Level#DEBUG} for {@code onSuccess}
 * <li>{@link Level#WARN} for {@code onFailure}
 * <li>{@link Level#WARN} for {@code onCallNotPermitted}
 * </ul>
 *
 * <p>
 * The defaults can be replaced via {@link #builder()}:
 *
 * <pre>{@code
 * CircuitBreakerLifecycle lifecycle = LoggingCircuitBreakerLifecycle.builder()
 *     .successLevel(Level.TRACE)
 *     .failureLevel(Level.ERROR)
 *     .callNotPermittedLevel(Level.ERROR)
 *     .includeCauseOnFailure(true)
 *     .build();
 * }</pre>
 *
 * <p>
 * By default, exceptions reported to {@code onFailure} are <em>not</em> attached to the
 * log record as a cause (only the exception class name appears in the {@code outcome}
 * field). Set {@link Builder#includeCauseOnFailure(boolean)} to {@code true} to attach
 * the exception as the log record's throwable so the stack trace is rendered.
 */
public class LoggingCircuitBreakerLifecycle implements CircuitBreakerLifecycle {

	private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(LoggingCircuitBreakerLifecycle.class);

	private final Logger logger;

	private final Level successLevel;

	private final Level failureLevel;

	private final Level callNotPermittedLevel;

	private final boolean includeCauseOnFailure;

	/**
	 * Creates a lifecycle with the default logger and default levels.
	 */
	public LoggingCircuitBreakerLifecycle() {
		this(DEFAULT_LOGGER, Level.DEBUG, Level.WARN, Level.WARN, false);
	}

	private LoggingCircuitBreakerLifecycle(Logger logger, Level successLevel, Level failureLevel,
			Level callNotPermittedLevel, boolean includeCauseOnFailure) {
		this.logger = Objects.requireNonNull(logger, "logger must not be null");
		this.successLevel = Objects.requireNonNull(successLevel, "successLevel must not be null");
		this.failureLevel = Objects.requireNonNull(failureLevel, "failureLevel must not be null");
		this.callNotPermittedLevel = Objects.requireNonNull(callNotPermittedLevel,
				"callNotPermittedLevel must not be null");
		this.includeCauseOnFailure = includeCauseOnFailure;
	}

	/**
	 * Returns a new {@link Builder}.
	 * @return the builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public void onSuccess(CircuitBreaker circuitBreaker, HttpRequest request, ClientHttpResponse response) {
		if (!this.logger.isEnabledForLevel(this.successLevel)) {
			return;
		}
		this.logger.atLevel(this.successLevel)
			.log("type=ok circuitBreaker=\"{}\" state={} method={} url=\"{}\" status={}", circuitBreaker.name(),
					circuitBreaker.state(), request.getMethod(), request.getURI(), statusOf(response));
	}

	@Override
	public void onFailure(CircuitBreaker circuitBreaker, HttpRequest request, ResponseOrException responseOrException) {
		if (!this.logger.isEnabledForLevel(this.failureLevel)) {
			return;
		}
		var builder = this.logger.atLevel(this.failureLevel);
		if (this.includeCauseOnFailure) {
			builder = builder.setCause(responseOrException.exception());
		}
		builder.log("type=fail circuitBreaker=\"{}\" state={} method={} url=\"{}\" outcome=\"{}\"",
				circuitBreaker.name(), circuitBreaker.state(), request.getMethod(), request.getURI(),
				describeOutcome(responseOrException));
	}

	@Override
	public void onCallNotPermitted(CircuitBreaker circuitBreaker, HttpRequest request) {
		if (!this.logger.isEnabledForLevel(this.callNotPermittedLevel)) {
			return;
		}
		this.logger.atLevel(this.callNotPermittedLevel)
			.log("type=rej circuitBreaker=\"{}\" state={} method={} url=\"{}\"", circuitBreaker.name(),
					circuitBreaker.state(), request.getMethod(), request.getURI());
	}

	private static String statusOf(ClientHttpResponse response) {
		try {
			return String.valueOf(response.getStatusCode().value());
		}
		catch (Exception ex) {
			return "?";
		}
	}

	private static String describeOutcome(ResponseOrException responseOrException) {
		ClientHttpResponse response = responseOrException.response();
		if (response != null) {
			return "status=" + statusOf(response);
		}
		Throwable error = responseOrException.exception();
		if (error != null) {
			return error.getClass().getName();
		}
		return "unknown";
	}

	/**
	 * Builder for {@link LoggingCircuitBreakerLifecycle}.
	 */
	public static final class Builder {

		private @Nullable Logger logger;

		private Level successLevel = Level.DEBUG;

		private Level failureLevel = Level.WARN;

		private Level callNotPermittedLevel = Level.WARN;

		private boolean includeCauseOnFailure = false;

		private Builder() {
		}

		/**
		 * Sets the logger to use. Defaults to a logger named after
		 * {@link LoggingCircuitBreakerLifecycle}.
		 * @param logger the logger
		 * @return this builder
		 */
		public Builder logger(Logger logger) {
			this.logger = Objects.requireNonNull(logger, "logger must not be null");
			return this;
		}

		/**
		 * Sets the logger by name. Defaults to a logger named after
		 * {@link LoggingCircuitBreakerLifecycle}.
		 * @param name the logger name
		 * @return this builder
		 */
		public Builder loggerName(String name) {
			Objects.requireNonNull(name, "name must not be null");
			this.logger = LoggerFactory.getLogger(name);
			return this;
		}

		/**
		 * Sets the log level used by {@code onSuccess}. Defaults to {@link Level#DEBUG}.
		 * @param level the level
		 * @return this builder
		 */
		public Builder successLevel(Level level) {
			this.successLevel = Objects.requireNonNull(level, "level must not be null");
			return this;
		}

		/**
		 * Sets the log level used by {@code onFailure}. Defaults to {@link Level#WARN}.
		 * @param level the level
		 * @return this builder
		 */
		public Builder failureLevel(Level level) {
			this.failureLevel = Objects.requireNonNull(level, "level must not be null");
			return this;
		}

		/**
		 * Sets the log level used by {@code onCallNotPermitted}. Defaults to
		 * {@link Level#WARN}.
		 * @param level the level
		 * @return this builder
		 */
		public Builder callNotPermittedLevel(Level level) {
			this.callNotPermittedLevel = Objects.requireNonNull(level, "level must not be null");
			return this;
		}

		/**
		 * Sets whether the exception passed to {@code onFailure} is attached to the log
		 * record as a cause (so the stack trace is rendered). Defaults to {@code false}.
		 * @param includeCauseOnFailure whether to attach the exception as the log
		 * record's throwable
		 * @return this builder
		 */
		public Builder includeCauseOnFailure(boolean includeCauseOnFailure) {
			this.includeCauseOnFailure = includeCauseOnFailure;
			return this;
		}

		/**
		 * Builds the lifecycle.
		 * @return the lifecycle
		 */
		public LoggingCircuitBreakerLifecycle build() {
			Logger effectiveLogger = (this.logger != null) ? this.logger : DEFAULT_LOGGER;
			return new LoggingCircuitBreakerLifecycle(effectiveLogger, this.successLevel, this.failureLevel,
					this.callNotPermittedLevel, this.includeCauseOnFailure);
		}

	}

}

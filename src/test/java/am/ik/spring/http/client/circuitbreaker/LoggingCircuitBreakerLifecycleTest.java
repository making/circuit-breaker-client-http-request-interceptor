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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingCircuitBreakerLifecycleTest {

	private final CircuitBreaker circuitBreaker = new DefaultCircuitBreaker("example.com",
			CircuitBreakerConfig.builder().build());

	private final HttpRequest request = stubRequest("GET", URI.create("http://example.com/items/42"));

	@Test
	void onSuccessLogsAtConfiguredLevel() {
		captureLogs(events -> {
			LoggingCircuitBreakerLifecycle lifecycle = LoggingCircuitBreakerLifecycle.builder()
				.successLevel(org.slf4j.event.Level.INFO)
				.build();

			lifecycle.onSuccess(this.circuitBreaker, this.request, stubResponse(200));

			assertThat(events).hasSize(1);
			ILoggingEvent event = events.get(0);
			assertThat(event.getLevel()).isEqualTo(Level.INFO);
			assertThat(event.getFormattedMessage()).contains("type=ok")
				.contains("circuitBreaker=\"example.com\"")
				.contains("state=CLOSED")
				.contains("method=GET")
				.contains("url=\"http://example.com/items/42\"")
				.contains("status=200");
		});
	}

	@Test
	void onFailureLogsResponseOutcome() {
		captureLogs(events -> {
			LoggingCircuitBreakerLifecycle lifecycle = new LoggingCircuitBreakerLifecycle();

			lifecycle.onFailure(this.circuitBreaker, this.request, ResponseOrException.ofResponse(stubResponse(503)));

			assertThat(events).hasSize(1);
			ILoggingEvent event = events.get(0);
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("type=fail").contains("outcome=\"status=503\"");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	@Test
	void onFailureLogsExceptionWithCause() {
		captureLogs(events -> {
			LoggingCircuitBreakerLifecycle lifecycle = new LoggingCircuitBreakerLifecycle();
			IOException cause = new IOException("boom");

			lifecycle.onFailure(this.circuitBreaker, this.request, ResponseOrException.ofException(cause));

			assertThat(events).hasSize(1);
			ILoggingEvent event = events.get(0);
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("type=fail").contains("outcome=\"java.io.IOException\"");
			assertThat(event.getThrowableProxy()).isNotNull();
			assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
		});
	}

	@Test
	void onCallNotPermittedLogsAtConfiguredLevel() {
		captureLogs(events -> {
			LoggingCircuitBreakerLifecycle lifecycle = LoggingCircuitBreakerLifecycle.builder()
				.callNotPermittedLevel(org.slf4j.event.Level.ERROR)
				.build();

			lifecycle.onCallNotPermitted(this.circuitBreaker, this.request);

			assertThat(events).hasSize(1);
			ILoggingEvent event = events.get(0);
			assertThat(event.getLevel()).isEqualTo(Level.ERROR);
			assertThat(event.getFormattedMessage()).contains("type=rej")
				.contains("circuitBreaker=\"example.com\"")
				.contains("method=GET")
				.contains("url=\"http://example.com/items/42\"");
		});
	}

	@Test
	void doesNotLogWhenLevelDisabled() {
		Logger logger = (Logger) LoggerFactory.getLogger(LoggingCircuitBreakerLifecycle.class);
		Level previous = logger.getLevel();
		logger.setLevel(Level.WARN);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			LoggingCircuitBreakerLifecycle lifecycle = new LoggingCircuitBreakerLifecycle();

			lifecycle.onSuccess(this.circuitBreaker, this.request, stubResponse(200));

			assertThat(appender.list).isEmpty();
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	@Test
	void customLoggerIsUsed() {
		String loggerName = "test.logger." + System.nanoTime();
		Logger custom = (Logger) LoggerFactory.getLogger(loggerName);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		custom.addAppender(appender);
		try {
			LoggingCircuitBreakerLifecycle lifecycle = LoggingCircuitBreakerLifecycle.builder()
				.loggerName(loggerName)
				.successLevel(org.slf4j.event.Level.INFO)
				.build();

			lifecycle.onSuccess(this.circuitBreaker, this.request, stubResponse(200));

			assertThat(appender.list).hasSize(1);
		}
		finally {
			custom.detachAppender(appender);
		}
	}

	private static void captureLogs(Consumer<List<ILoggingEvent>> assertion) {
		Logger logger = (Logger) LoggerFactory.getLogger(LoggingCircuitBreakerLifecycle.class);
		Level previous = logger.getLevel();
		logger.setLevel(Level.TRACE);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertion.accept(appender.list);
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	private static HttpRequest stubRequest(String method, URI uri) {
		return new HttpRequest() {
			private final Map<String, Object> attributes = new HashMap<>();

			@Override
			public HttpMethod getMethod() {
				return HttpMethod.valueOf(method);
			}

			@Override
			public URI getURI() {
				return uri;
			}

			@Override
			public Map<String, Object> getAttributes() {
				return this.attributes;
			}

			@Override
			public HttpHeaders getHeaders() {
				return new HttpHeaders();
			}
		};
	}

	private static ClientHttpResponse stubResponse(int status) {
		return new ClientHttpResponse() {
			@Override
			public HttpStatusCode getStatusCode() {
				return HttpStatusCode.valueOf(status);
			}

			@Override
			public String getStatusText() {
				return "";
			}

			@Override
			public void close() {
			}

			@Override
			public InputStream getBody() {
				return new ByteArrayInputStream(new byte[0]);
			}

			@Override
			public HttpHeaders getHeaders() {
				return new HttpHeaders();
			}
		};
	}

}

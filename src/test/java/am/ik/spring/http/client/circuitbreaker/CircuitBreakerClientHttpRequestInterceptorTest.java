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
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerClientHttpRequestInterceptorTest {

	private final MockServerRunner mockServerRunner = new MockServerRunner();

	@BeforeEach
	void init() throws Exception {
		this.mockServerRunner.run();
	}

	@AfterEach
	void destroy() {
		this.mockServerRunner.destroy();
	}

	/**
	 * Builds a {@link RestClient} that swallows 4xx/5xx instead of throwing, so tests can
	 * focus on the breaker's bookkeeping.
	 */
	private RestClient client(ClientHttpRequestInterceptor interceptor) {
		return RestClient.builder().defaultStatusHandler(status -> status.value() >= 400, (request, response) -> {
		}).requestInterceptor(interceptor).build();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.mockServerRunner.port() + path);
	}

	@Test
	void successfulCallsKeepCircuitClosed() {
		this.mockServerRunner.respondWith("/ok", 200, "ok");
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		RestClient rest = client(new CircuitBreakerClientHttpRequestInterceptor(registry));
		for (int i = 0; i < 4; i++) {
			rest.get().uri(uri("/ok")).retrieve().toBodilessEntity();
		}
		assertThat(registry.circuitBreaker("localhost").state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void failingResponsesTripTheCircuit() {
		this.mockServerRunner.respondWith("/boom", 503, "boom");
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofSeconds(60))
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		RestClient rest = client(new CircuitBreakerClientHttpRequestInterceptor(registry));
		URI boom = uri("/boom");
		for (int i = 0; i < 4; i++) {
			rest.get().uri(boom).retrieve().toBodilessEntity();
		}
		assertThat(registry.circuitBreaker("localhost").state()).isEqualTo(CircuitBreaker.State.OPEN);
		// Next call should be rejected with CallNotPermittedException (wrapped)
		assertThatThrownBy(() -> rest.get().uri(boom).retrieve().toBodilessEntity())
			.isInstanceOf(ResourceAccessException.class)
			.hasCauseInstanceOf(CallNotPermittedException.class);
	}

	@Test
	void perHostCircuitBreakers() {
		this.mockServerRunner.respondWith("/ok", 200, "ok");
		this.mockServerRunner.respondWith("/boom", 503, "boom");
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		// Custom provider: host + path so /ok and /boom get separate breakers
		CircuitBreakerProvider byHostAndPath = request -> registry
			.circuitBreaker(request.getURI().getHost() + request.getURI().getPath());
		RestClient rest = client(new CircuitBreakerClientHttpRequestInterceptor(byHostAndPath));
		for (int i = 0; i < 4; i++) {
			rest.get().uri(uri("/ok")).retrieve().toBodilessEntity();
		}
		for (int i = 0; i < 4; i++) {
			rest.get().uri(uri("/boom")).retrieve().toBodilessEntity();
		}
		assertThat(registry.circuitBreaker("localhost/ok").state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(registry.circuitBreaker("localhost/boom").state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void ioExceptionTripsTheCircuit() {
		// no server bound on port 1: connection refused
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		RestClient rest = client(new CircuitBreakerClientHttpRequestInterceptor(registry));
		URI never = URI.create("http://localhost:1/never");
		for (int i = 0; i < 4; i++) {
			try {
				rest.get().uri(never).retrieve().toBodilessEntity();
			}
			catch (ResourceAccessException expected) {
				// connect refused is expected
			}
		}
		assertThat(registry.circuitBreaker("localhost").state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void lifecycleHooksFire() {
		this.mockServerRunner.respondWith("/ok", 200, "ok");
		this.mockServerRunner.respondWith("/boom", 503, "boom");
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger failures = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<String> events = new ArrayList<>();
		CircuitBreakerLifecycle lifecycle = new CircuitBreakerLifecycle() {
			@Override
			public void onSuccess(CircuitBreaker cb, HttpRequest request, ClientHttpResponse response) {
				successes.incrementAndGet();
				events.add("success");
			}

			@Override
			public void onFailure(CircuitBreaker cb, HttpRequest request, ResponseOrException result) {
				failures.incrementAndGet();
				events.add("failure");
			}

			@Override
			public void onCallNotPermitted(CircuitBreaker cb, HttpRequest request) {
				rejected.incrementAndGet();
				events.add("rejected");
			}
		};
		RestClient rest = client(
				CircuitBreakerClientHttpRequestInterceptor.builder().registry(registry).lifecycle(lifecycle).build());

		URI ok = uri("/ok");
		URI boom = uri("/boom");

		rest.get().uri(ok).retrieve().toBodilessEntity();
		assertThat(successes.get()).isEqualTo(1);
		// 1 success + 3 failures fills the window of 4; breaker trips after the 3rd boom
		for (int i = 0; i < 3; i++) {
			rest.get().uri(boom).retrieve().toBodilessEntity();
		}
		assertThat(failures.get()).isEqualTo(3);
		// next call rejected
		try {
			rest.get().uri(boom).retrieve().toBodilessEntity();
		}
		catch (ResourceAccessException ignored) {
		}
		assertThat(rejected.get()).isEqualTo(1);
	}

	@Test
	void singleSharedCircuitBreaker() {
		this.mockServerRunner.respondWith("/boom", 503, "boom");
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		CircuitBreaker cb = new DefaultCircuitBreaker("shared", config);
		RestClient rest = client(new CircuitBreakerClientHttpRequestInterceptor(cb));
		URI boom = uri("/boom");
		for (int i = 0; i < 4; i++) {
			rest.get().uri(boom).retrieve().toBodilessEntity();
		}
		assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThatThrownBy(() -> rest.get().uri(boom).retrieve().toBodilessEntity())
			.isInstanceOf(ResourceAccessException.class)
			.hasCauseInstanceOf(CallNotPermittedException.class);
	}

	@Test
	void customFailurePredicateNarrowsToServerErrorsOnly() {
		// 404 should NOT count as a failure when using serverErrors()
		this.mockServerRunner.respondWith("/notfound", 404, "nope");
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		RestClient rest = client(CircuitBreakerClientHttpRequestInterceptor.builder()
			.registry(registry)
			.failurePredicate(FailurePredicate.serverErrors())
			.build());
		URI notfound = uri("/notfound");
		for (int i = 0; i < 4; i++) {
			rest.get().uri(notfound).retrieve().toBodilessEntity();
		}
		assertThat(registry.circuitBreaker("localhost").state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void builderRejectsMissingCircuitBreakerSource() {
		assertThatThrownBy(() -> CircuitBreakerClientHttpRequestInterceptor.builder().build())
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void halfOpenTransitionsBackToClosedOnSuccess() throws Exception {
		AtomicInteger counter = new AtomicInteger();
		this.mockServerRunner.addContext("/maybe", exchange -> {
			int n = counter.getAndIncrement();
			boolean fail = n < 4;
			byte[] payload = (fail ? "boom" : "ok").getBytes();
			exchange.sendResponseHeaders(fail ? 503 : 200, payload.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(payload);
			}
		});
		CircuitBreakerConfig config = CircuitBreakerConfig.builder()
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofMillis(100))
			.permittedNumberOfCallsInHalfOpenState(2)
			.build();
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
		RestClient rest = client(new CircuitBreakerClientHttpRequestInterceptor(registry));
		URI maybe = uri("/maybe");
		for (int i = 0; i < 4; i++) {
			rest.get().uri(maybe).retrieve().toBodilessEntity();
		}
		assertThat(registry.circuitBreaker("localhost").state()).isEqualTo(CircuitBreaker.State.OPEN);
		Thread.sleep(150);
		// 2 probe calls succeed => CLOSED
		rest.get().uri(maybe).retrieve().toBodilessEntity();
		rest.get().uri(maybe).retrieve().toBodilessEntity();
		assertThat(registry.circuitBreaker("localhost").state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

}

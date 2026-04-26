# circuit-breaker-client-http-request-interceptor

`CircuitBreakerClientHttpRequestInterceptor` for Spring `RestTemplate` and `RestClient`.

A self-contained, thread-safe Circuit Breaker implementation that plugs into Spring's HTTP
client interceptor chain. No runtime dependency on resilience4j or any other circuit
breaker library.

## When to use

Use this library when you want to protect outbound HTTP calls from cascading failures
(repeated calls to a degraded backend) without bringing in a full resilience framework.
The interceptor model lets you apply a circuit breaker uniformly to every call made
through a `RestTemplate` or `RestClient`, including per-host isolation.

## Requirements

- Java 17+
- Spring Framework 6+ (Spring Boot 3+)

## Installation

```xml
<dependency>
    <groupId>am.ik.spring</groupId>
    <artifactId>circuit-breaker-client-http-request-interceptor</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quick start

The most common case — one circuit breaker per request host — is a single-line setup:

```java
CircuitBreakerConfig config = CircuitBreakerConfig.builder()
    .slidingWindowSize(20)
    .minimumNumberOfCalls(10)
    .failureRateThreshold(50f)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .permittedNumberOfCallsInHalfOpenState(5)
    .build();

CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

RestClient restClient = RestClient.builder()
    .requestInterceptor(new CircuitBreakerClientHttpRequestInterceptor(registry))
    .build();
```

When the circuit is open, the interceptor throws `CallNotPermittedException` (an
`IOException`). With `RestTemplate` / `RestClient` it surfaces as a
`ResourceAccessException` whose cause is `CallNotPermittedException`.

```java
try {
    restClient.get().uri("https://example.com/api").retrieve().body(String.class);
}
catch (ResourceAccessException ex) {
    if (ex.getCause() instanceof CallNotPermittedException cnp) {
        // circuit is open — fall back to cached value, secondary endpoint, etc.
    }
    throw ex;
}
```

## How to choose a circuit breaker for each request

There are three constructors, depending on how you want requests mapped to circuit
breakers:

```java
// 1. One circuit breaker per request host (most common)
new CircuitBreakerClientHttpRequestInterceptor(registry);

// 2. The same circuit breaker for every request
new CircuitBreakerClientHttpRequestInterceptor(circuitBreaker);

// 3. Arbitrary mapping
CircuitBreakerProvider provider = request ->
    registry.circuitBreaker(request.getURI().getHost() + request.getURI().getPath());
new CircuitBreakerClientHttpRequestInterceptor(provider);
```

`CircuitBreakerProvider` ships with two ready-made strategies:

```java
CircuitBreakerProvider.byHost(registry);       // one breaker per host (default)
CircuitBreakerProvider.byAuthority(registry);  // one breaker per host:port
CircuitBreakerProvider.fixed(circuitBreaker);  // single shared breaker
```

## How to control what counts as a failure

A single `FailurePredicate` decides whether a call's outcome (a response *or* an
exception) is recorded as a failure. The default is sensible:

> 408 / 429 / 500 / 502 / 503 / 504 status codes, plus read timeouts, connect timeouts
> and unknown-host errors.

To change it, use the builder:

```java
new CircuitBreakerClientHttpRequestInterceptor(registry); // uses FailurePredicate.defaults()

CircuitBreakerClientHttpRequestInterceptor.builder()
    .registry(registry)
    .failurePredicate(FailurePredicate.serverErrors())   // 5xx only
    .build();
```

Predicates compose with `or` / `and`:

```java
FailurePredicate p = FailurePredicate.statusCodes(500, 502, 503)
    .or(FailurePredicate.timeouts())
    .or(FailurePredicate.connectErrors());
```

Available factories:

| Factory | Records as failure when... |
| --- | --- |
| `FailurePredicate.defaults()` | 408, 429, 5xx + network errors |
| `FailurePredicate.serverErrors()` | response is 5xx |
| `FailurePredicate.statusCodes(...)` | response status is one of the given codes |
| `FailurePredicate.response(predicate)` | the given `Predicate<ClientHttpResponse>` matches |
| `FailurePredicate.networkErrors()` | timeouts + connect + unknown-host errors |
| `FailurePredicate.timeouts()` | read timeout |
| `FailurePredicate.connectErrors()` | connection refused / connect timeout |
| `FailurePredicate.unknownHostErrors()` | DNS resolution failed |
| `FailurePredicate.anyError()` | any thrown `Throwable` |
| `FailurePredicate.error(predicate)` | the given `Predicate<Throwable>` matches |
| `FailurePredicate.never()` | never (useful as a baseline for `or`) |

## How to observe state

Implement `CircuitBreakerLifecycle` to receive callbacks:

```java
CircuitBreakerLifecycle lifecycle = new CircuitBreakerLifecycle() {
    @Override
    public void onCallNotPermitted(CircuitBreaker cb, HttpRequest request) {
        log.warn("circuit '{}' rejected {}", cb.name(), request.getURI());
    }

    @Override
    public void onFailure(CircuitBreaker cb, HttpRequest request, ResponseOrException result) {
        log.warn("call failed: {}", result);
    }
};

CircuitBreakerClientHttpRequestInterceptor.builder()
    .registry(registry)
    .lifecycle(lifecycle)
    .build();
```

## The Builder

For full control, use the builder. Exactly one of `circuitBreaker`, `registry`,
`circuitBreakerProvider` must be set; `failurePredicate` and `lifecycle` are optional and
default to `FailurePredicate.defaults()` / `CircuitBreakerLifecycle.NOOP`.

```java
CircuitBreakerClientHttpRequestInterceptor interceptor =
    CircuitBreakerClientHttpRequestInterceptor.builder()
        .registry(registry)                                         // or .circuitBreaker(...) / .circuitBreakerProvider(...)
        .failurePredicate(FailurePredicate.serverErrors().or(FailurePredicate.timeouts()))
        .lifecycle(myLifecycle)
        .build();
```

## Configuration reference

`CircuitBreakerConfig` (defaults shown):

| Property | Default | Description |
| --- | --- | --- |
| `failureRateThreshold` | `50.0` | Failure rate (%) at or above which the circuit opens. |
| `slowCallRateThreshold` | `100.0` | Slow call rate (%) at or above which the circuit opens. Effective only when `slowCallDurationThreshold > 0`. |
| `slowCallDurationThreshold` | `0` (disabled) | Duration at or above which a call is recorded as slow. |
| `slidingWindowSize` | `100` | Size of the count-based sliding window used in `CLOSED`. |
| `minimumNumberOfCalls` | `100` | Minimum number of calls before the failure rate is evaluated (capped at `slidingWindowSize`). |
| `waitDurationInOpenState` | `60s` | How long the circuit stays `OPEN` before allowing probe calls. |
| `permittedNumberOfCallsInHalfOpenState` | `10` | Maximum number of probe calls in `HALF_OPEN`. |

## State machine

```
              failure / slow call rate ≥ threshold
   ┌──────────────────────────────────────────────────┐
   │                                                  ▼
CLOSED ◀── probe rates below threshold ── HALF_OPEN ◀── waitDuration elapsed ── OPEN
                                              │                                  ▲
                                              └── probe rates ≥ threshold ───────┘
```

- `CLOSED`: every call passes through. Outcomes are recorded in the sliding window.
  Once at least `minimumNumberOfCalls` have been recorded, the circuit transitions to
  `OPEN` if the failure rate or slow call rate reaches its threshold.
- `OPEN`: every call is rejected with `CallNotPermittedException`. After
  `waitDurationInOpenState`, the next call attempt transitions the circuit to
  `HALF_OPEN`.
- `HALF_OPEN`: up to `permittedNumberOfCallsInHalfOpenState` probe calls are permitted.
  Evaluation happens only after **all** permitted probes have completed:
  - if the failure rate or slow call rate is at or above its threshold, the circuit
    returns to `OPEN`,
  - otherwise it transitions to `CLOSED`.

## License

Licensed under the Apache License, Version 2.0.

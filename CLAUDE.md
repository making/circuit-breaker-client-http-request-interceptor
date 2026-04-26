# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
```

## Design Requirements
- **Package**: `am.ik.spring.http.client.circuitbreaker` - Main package
- **Public API surface**:
  - `CircuitBreakerClientHttpRequestInterceptor` — Spring `ClientHttpRequestInterceptor` that
    wraps every HTTP call with a circuit breaker. Three constructors
    (`CircuitBreaker`, `CircuitBreakerRegistry`, `CircuitBreakerProvider`) for the
    common cases, plus a `Builder` for full control.
  - `CircuitBreaker` interface, `DefaultCircuitBreaker` implementation, and
    `CircuitBreakerConfig` builder.
  - `CircuitBreakerRegistry` for per-key (e.g. per-host) circuit breakers.
  - `CircuitBreakerProvider` (functional interface) maps each `HttpRequest` to a
    `CircuitBreaker`. Static factories: `fixed`, `byHost`, `byAuthority`.
  - `FailurePredicate` (functional interface) decides whether each call's outcome
    (response *or* throwable) is a failure. Composable via `or` / `and`. Static
    factories: `defaults`, `serverErrors`, `statusCodes`, `response`, `networkErrors`,
    `timeouts`, `connectErrors`, `unknownHostErrors`, `anyError`, `error`, `never`.
  - `CircuitBreakerLifecycle` hooks: `onSuccess` / `onFailure` / `onCallNotPermitted`.
  - `CallNotPermittedException` (extends `IOException`) thrown when the circuit is open.
- The implementation is self-contained — no runtime dependency on resilience4j.

## Implemented Features

- Count-based sliding window with thread-safe outcome tracking
- Failure rate threshold and slow call rate threshold
- `CLOSED` / `OPEN` / `HALF_OPEN` state machine with configurable wait duration and
  probe call count
- Per-host circuit breakers via `CircuitBreakerRegistry`
- Custom predicates for HTTP response failures and IOException failures
- Lifecycle hooks for observability

## Development Requirements

### Prerequisites

- Java 17+

### Code Standards

- No external dependencies except for testing libraries
- Use builder pattern if the number of arguments is more than two
- Write javadoc and comments in English
- Spring Java Format enforced via Maven plugin
- All code must pass formatting validation before commit
- Use Java 17 compatible features (avoid Java 21+ specific APIs)
- Use modern Java technics as much as possible like Java Records, Pattern Matching, Text Block etc ...
- Be sure to avoid circular references between classes and packages.

### Documentation

- Specify when this library should be used
- The explanations are written from the perspective of the API user, with plenty of code examples to make usage easy to understand.
- No need for excessive advertising
- There is no need to use emojis or flashy expressions, just write simply and honestly.

### Testing Strategy

- JUnit 5 with AssertJ
- All tests must pass before completing tasks
- Code examples in the README must be tested to ensure they work.

### After Task completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"’
```

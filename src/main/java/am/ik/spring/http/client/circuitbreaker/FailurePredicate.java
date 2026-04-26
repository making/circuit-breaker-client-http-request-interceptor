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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Decides whether the outcome of an HTTP call should be recorded as a failure by the
 * circuit breaker.
 *
 * <p>
 * Either the {@code response} or the {@code error} is non-null when the predicate is
 * invoked, never both. Static factory methods cover the common cases and can be combined
 * with {@link #or(FailurePredicate)} / {@link #and(FailurePredicate)}:
 *
 * <pre>{@code
 * FailurePredicate.serverErrors().or(FailurePredicate.timeouts())
 * }</pre>
 */
@FunctionalInterface
public interface FailurePredicate {

	/**
	 * Returns {@code true} if the call should be recorded as a failure.
	 * @param response the HTTP response, or {@code null} if the call ended with an
	 * exception
	 * @param error the exception thrown by the call, or {@code null} if the call returned
	 * a response
	 * @return {@code true} if the call should be recorded as a failure
	 * @throws IOException if reading the response fails
	 */
	boolean isFailure(@Nullable ClientHttpResponse response, @Nullable Throwable error) throws IOException;

	/**
	 * Returns a predicate that records a failure when this predicate or {@code other}
	 * does.
	 * @param other the other predicate
	 * @return a composed predicate
	 */
	default FailurePredicate or(FailurePredicate other) {
		Objects.requireNonNull(other, "other must not be null");
		return (response, error) -> this.isFailure(response, error) || other.isFailure(response, error);
	}

	/**
	 * Returns a predicate that records a failure only when both this predicate and
	 * {@code other} do.
	 * @param other the other predicate
	 * @return a composed predicate
	 */
	default FailurePredicate and(FailurePredicate other) {
		Objects.requireNonNull(other, "other must not be null");
		return (response, error) -> this.isFailure(response, error) && other.isFailure(response, error);
	}

	/**
	 * Returns a predicate that never records a failure.
	 * @return the predicate
	 */
	static FailurePredicate never() {
		return (response, error) -> false;
	}

	/**
	 * Returns the recommended default predicate. It records a failure when:
	 * <ul>
	 * <li>the response status is one of {@code 408, 429, 500, 502, 503, 504};
	 * <li>or the call ended with a network error (timeouts, connect errors, unknown
	 * host).
	 * </ul>
	 * @return the predicate
	 */
	static FailurePredicate defaults() {
		return statusCodes(408, 429, 500, 502, 503, 504).or(networkErrors());
	}

	/**
	 * Records a failure when the response status is in the 5xx range.
	 * @return the predicate
	 */
	static FailurePredicate serverErrors() {
		return (response, error) -> response != null && response.getStatusCode().is5xxServerError();
	}

	/**
	 * Records a failure when the response status is one of the given codes.
	 * @param codes the HTTP status codes
	 * @return the predicate
	 */
	static FailurePredicate statusCodes(Integer... codes) {
		return statusCodes(new HashSet<>(Arrays.asList(codes)));
	}

	/**
	 * Records a failure when the response status is one of the given codes.
	 * @param codes the HTTP status codes
	 * @return the predicate
	 */
	static FailurePredicate statusCodes(Set<Integer> codes) {
		Objects.requireNonNull(codes, "codes must not be null");
		Set<Integer> snapshot = new HashSet<>(codes);
		return (response, error) -> response != null && snapshot.contains(response.getStatusCode().value());
	}

	/**
	 * Records a failure when the response satisfies the given predicate.
	 * @param predicate the predicate against the response
	 * @return the predicate
	 */
	static FailurePredicate response(Predicate<ClientHttpResponse> predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		return (response, error) -> response != null && predicate.test(response);
	}

	/**
	 * Records a failure for any thrown {@link Throwable}.
	 * @return the predicate
	 */
	static FailurePredicate anyError() {
		return (response, error) -> error != null;
	}

	/**
	 * Records a failure when the thrown exception satisfies the given predicate.
	 * @param predicate the predicate against the exception
	 * @return the predicate
	 */
	static FailurePredicate error(Predicate<Throwable> predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		return (response, error) -> error != null && predicate.test(error);
	}

	/**
	 * Records a failure when the call failed with a network error: a read or connect
	 * timeout, a refused connection, or an unresolvable host.
	 * @return the predicate
	 */
	static FailurePredicate networkErrors() {
		return timeouts().or(connectErrors()).or(unknownHostErrors());
	}

	/**
	 * Records a failure for read timeouts ({@link SocketTimeoutException},
	 * {@link HttpTimeoutException}).
	 * @return the predicate
	 */
	static FailurePredicate timeouts() {
		return error(
				throwable -> throwable instanceof SocketTimeoutException || throwable instanceof HttpTimeoutException
						|| (throwable instanceof IOException && throwable.getCause() instanceof TimeoutException));
	}

	/**
	 * Records a failure for connection refusals ({@link ConnectException},
	 * {@link HttpConnectTimeoutException}).
	 * @return the predicate
	 */
	static FailurePredicate connectErrors() {
		return error(
				throwable -> throwable instanceof ConnectException || throwable instanceof HttpConnectTimeoutException);
	}

	/**
	 * Records a failure for unresolvable hosts ({@link UnknownHostException}).
	 * @return the predicate
	 */
	static FailurePredicate unknownHostErrors() {
		return error(throwable -> throwable instanceof UnknownHostException
				|| (throwable instanceof ConnectException && throwable.getCause() instanceof ConnectException
						&& throwable.getCause().getCause() instanceof UnresolvedAddressException));
	}

}

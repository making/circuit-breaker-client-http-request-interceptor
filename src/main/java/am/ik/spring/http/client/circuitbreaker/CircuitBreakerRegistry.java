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

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * In-memory registry of {@link CircuitBreaker} instances keyed by name. New circuit
 * breakers are created on demand using the configured factory.
 */
public class CircuitBreakerRegistry {

	private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

	private final Function<String, CircuitBreaker> factory;

	/**
	 * Creates a registry that creates circuit breakers via the given factory.
	 * @param factory the factory used to create a new circuit breaker for an unknown name
	 */
	public CircuitBreakerRegistry(Function<String, CircuitBreaker> factory) {
		this.factory = Objects.requireNonNull(factory, "factory must not be null");
	}

	/**
	 * Creates a registry that creates {@link DefaultCircuitBreaker} instances using the
	 * given configuration.
	 * @param config the configuration shared by all created circuit breakers
	 * @return the registry
	 */
	public static CircuitBreakerRegistry of(CircuitBreakerConfig config) {
		Objects.requireNonNull(config, "config must not be null");
		return new CircuitBreakerRegistry(name -> new DefaultCircuitBreaker(name, config));
	}

	/**
	 * Returns the circuit breaker for the given name, creating one on demand if
	 * necessary.
	 * @param name the name (must not be null)
	 * @return the circuit breaker
	 */
	public CircuitBreaker circuitBreaker(String name) {
		Objects.requireNonNull(name, "name must not be null");
		return this.circuitBreakers.computeIfAbsent(name, this.factory);
	}

	/**
	 * Returns an immutable view of all registered circuit breakers.
	 * @return the circuit breakers
	 */
	public Collection<CircuitBreaker> circuitBreakers() {
		return Collections.unmodifiableCollection(this.circuitBreakers.values());
	}

	/**
	 * Removes the circuit breaker registered under the given name, if any.
	 * @param name the name
	 * @return the removed circuit breaker, or {@code null} if none was registered
	 */
	public CircuitBreaker remove(String name) {
		return this.circuitBreakers.remove(name);
	}

}

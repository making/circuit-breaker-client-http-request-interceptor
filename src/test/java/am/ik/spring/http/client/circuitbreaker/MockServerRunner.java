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

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight HTTP server for testing the interceptor end-to-end. Binds to an ephemeral
 * port chosen by the OS; the actual port is exposed via {@link #port()} after
 * {@link #run()} returns.
 */
public class MockServerRunner {

	private @Nullable HttpServer httpServer;

	private final Logger log = LoggerFactory.getLogger(MockServerRunner.class);

	public int port() {
		HttpServer server = Objects.requireNonNull(this.httpServer, "server is not running");
		return server.getAddress().getPort();
	}

	public MockServerRunner addContext(String path, HttpHandler handler) {
		HttpServer server = Objects.requireNonNull(this.httpServer, "server is not running");
		try {
			server.createContext(path, handler);
		}
		catch (IllegalArgumentException ex) {
			server.removeContext(path);
			server.createContext(path, handler);
		}
		return this;
	}

	public MockServerRunner respondWith(String path, int status, String body) {
		return addContext(path, exchange -> {
			byte[] payload = body.getBytes();
			exchange.sendResponseHeaders(status, payload.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(payload);
			}
		});
	}

	public void run() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.setExecutor(Executors.newSingleThreadExecutor());
		server.start();
		this.httpServer = server;
		this.log.info("Start http server on {}", server.getAddress().getPort());
	}

	public void destroy() {
		HttpServer server = this.httpServer;
		if (server != null) {
			this.log.info("Stop http server on {}", server.getAddress().getPort());
			server.stop(0);
			this.httpServer = null;
		}
	}

}

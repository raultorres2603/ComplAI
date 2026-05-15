package cat.complai.utilities.http;

import cat.complai.dto.http.HttpDto;
import cat.complai.dto.http.OpenRouterStreamStartResult;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.exceptions.OpenRouterStreamingException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class HttpWrapperTest {

    private static List<String> collectStream(OpenRouterStreamStartResult result) {
        assertInstanceOf(OpenRouterStreamStartResult.Success.class, result);
        return Flux.from(((OpenRouterStreamStartResult.Success) result).stream()).collectList()
                .block(Duration.ofSeconds(5));
    }

    @Test
    public void postToOpenRouterAsync_shouldReturnParsedMessage() throws Exception {
        // Start a simple HTTP server that mimics OpenRouter's response
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String responseJson = "{\"choices\":[{\"message\":{\"content\":\"Hello from mock\"}}]}";
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] bytes = responseJson.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper httpWrapper = ctx.getBean(HttpWrapper.class);
            var future = httpWrapper.postToOpenRouterAsync("Test prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(200, dto.statusCode());
            assertNull(dto.error());
            assertNotNull(dto.message());
            assertTrue(dto.message().contains("Hello from mock"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postToOpenRouterAsync_shouldRetryOn5xxAndSucceed() throws Exception {
        // Server returns 500 on first call, 200 on retry - tests Micronaut's @Retryable behavior
        String errorBody = "{\"error\":\"server failure\"}";
        String successBody = "{\"choices\":[{\"message\":{\"content\":\"success\"}}]}";
        AtomicInteger callCount = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // First call returns 500 - triggers retry
                byte[] bytes = errorBody.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                // Subsequent calls return 200 - success after retry
                byte[] bytes = successBody.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            var future = wrapper.postToOpenRouterAsync("prompt");
            HttpDto dto = future.get(10, TimeUnit.SECONDS);

            // After retry, should succeed
            assertNotNull(dto);
            assertEquals(200, dto.statusCode());
            assertNotNull(dto.message());
            assertTrue(dto.message().contains("success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postToOpenRouterAsync_shouldHandleMalformedJsonGracefully() throws Exception {
        String body = "not a json";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            var future = wrapper.postToOpenRouterAsync("prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(200, dto.statusCode());
            // current implementation returns null message when parsing fails
            assertNull(dto.message());
            assertNull(dto.error());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postToOpenRouterAsync_shouldPrefixBearerWhenNeeded() throws Exception {
        final String[] receivedAuth = new String[1];
        String resp = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            Headers reqHeaders = exchange.getRequestHeaders();
            receivedAuth[0] = reqHeaders.getFirst("Authorization");
            byte[] bytes = resp.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                // Put a plain token so wrapper prefixes Bearer
                "OPENROUTER_API_KEY", "plain-token");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            var future = wrapper.postToOpenRouterAsync("prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(200, dto.statusCode());
            assertNull(dto.error());
            assertNotNull(dto.message());
            assertEquals("ok", dto.message());
            assertNotNull(receivedAuth[0]);
            assertTrue(receivedAuth[0].startsWith("Bearer "));
            assertTrue(receivedAuth[0].endsWith("plain-token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamFromOpenRouter_shouldSkipCommentLinesAndEmitDataLines() throws Exception {
        String sseBody = ": OPENROUTER PROCESSING\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // Use 0 for chunked transfer encoding so Micronaut's dataStream() receives
            // HttpContent chunks
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            List<String> lines = collectStream(wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test"))));

            assertNotNull(lines);
            assertEquals(2, lines.size());
            assertTrue(lines.contains("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"));
            assertTrue(lines.contains("data: [DONE]"));
            assertTrue(lines.stream().noneMatch(l -> l.startsWith(":")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamFromOpenRouter_shouldReconstructLinesAcrossChunkBoundaries() throws Exception {
        String part1 = ": OPENROUTER PROCESSING\n\ndata: {\"choices\"";
        String part2 = ":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // Use 0 for chunked transfer encoding (unknown length)
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write(part1.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(part2.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            List<String> lines = collectStream(wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test"))));

            assertNotNull(lines);
            assertEquals(2, lines.size());
            assertTrue(lines.contains("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"));
            assertTrue(lines.contains("data: [DONE]"));
            assertTrue(lines.stream().noneMatch(l -> l.startsWith(":")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamFromOpenRouter_shouldWorkWithNoCommentLines() throws Exception {
        String sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // Use 0 for chunked transfer encoding so Micronaut's dataStream() receives
            // HttpContent chunks
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            List<String> lines = collectStream(wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test"))));

            assertNotNull(lines);
            assertEquals(2, lines.size());
            assertTrue(lines.stream().allMatch(l -> l.startsWith("data:")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamFromOpenRouter_shouldReturnTypedUpstreamErrorOn402Startup() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] bytes = "payment required".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            OpenRouterStreamStartResult result = wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test")));

            assertInstanceOf(OpenRouterStreamStartResult.Error.class, result);
            OpenRouterStreamingException failure = ((OpenRouterStreamStartResult.Error) result).failure();
            assertEquals(OpenRouterErrorCode.UPSTREAM, failure.getErrorCode());
            assertEquals(402, failure.getUpstreamStatus());
        } finally {
            server.stop(0);
        }
    }

    // Test removed - old custom retry logic replaced with Micronaut's built-in @Retryable

    // ─────────────────────────────────────────────────────────────────────────
    // Circuit breaker integration tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that while the circuit breaker is OPEN, HttpWrapper returns a
     * fallback response immediately without making any HTTP call to OpenRouter.
     */
    @Test
    public void circuitBreaker_shouldNotCallOpenRouterWhenCircuitIsOpen() throws Exception {
        AtomicInteger openRouterCallCount = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            openRouterCallCount.incrementAndGet();
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        // Default CircuitBreaker config (failureThreshold=5, windowSize=10, cooldown=30)
        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);
            CircuitBreaker cb = wrapper.getCircuitBreaker();

            // First call: succeed normally
            var dto1 = wrapper.postToOpenRouterAsync("prompt").get(5, TimeUnit.SECONDS);
            assertEquals(200, dto1.statusCode());
            assertEquals(1, openRouterCallCount.get());

            // Trip the circuit: 5 failures in default window of 10 = 50%+ → OPEN
            for (int i = 0; i < 5; i++) {
                cb.recordFailure();
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());

            // Second call: should be rejected immediately — NO HTTP call made
            var dto2 = wrapper.postToOpenRouterAsync("prompt").get(5, TimeUnit.SECONDS);
            assertNull(dto2.statusCode());
            assertEquals(OpenRouterErrorCode.CIRCUIT_OPEN, dto2.errorCode());
            assertTrue(dto2.error().contains("no està disponible"));
            // No additional HTTP calls
            assertEquals(1, openRouterCallCount.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verifies that after cooldown expires, a probe call in HALF_OPEN succeeds
     * and the circuit transitions back to CLOSED.
     */
    @Test
    public void circuitBreaker_shouldRecordSuccessAndClose() throws Exception {
        String resp = "{\"choices\":[{\"message\":{\"content\":\"recovered\"}}]}";
        AtomicInteger callCount = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = resp.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        // Short cooldown (1 second) so test runs fast
        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "circuit-breaker.failure-threshold", "1",
                "circuit-breaker.window-size", "1",
                "circuit-breaker.cooldown-seconds", "1",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);
            CircuitBreaker cb = wrapper.getCircuitBreaker();

            // Trip the circuit
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());

            // Wait for cooldown
            Thread.sleep(1_200);

            // Probe call — should succeed and transition to CLOSED
            var dto = wrapper.postToOpenRouterAsync("prompt").get(5, TimeUnit.SECONDS);
            assertEquals(200, dto.statusCode());
            assertEquals(1, callCount.get());
            assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verifies that when the probe call in HALF_OPEN fails, the circuit
     * transitions back to OPEN.
     */
    @Test
    public void circuitBreaker_shouldRecordFailureAndReopen() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = "{\"error\":\"server error\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "circuit-breaker.failure-threshold", "1",
                "circuit-breaker.window-size", "1",
                "circuit-breaker.cooldown-seconds", "1",
                "OPENROUTER_MAX_RETRIES", "1", // 1 retry only to speed up test
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);
            CircuitBreaker cb = wrapper.getCircuitBreaker();

            // Trip the circuit
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());

            // Wait for cooldown
            Thread.sleep(1_200);

            // Probe call fails (500 response) — should transition to OPEN again
            var dto = wrapper.postToOpenRouterAsync("prompt").get(5, TimeUnit.SECONDS);
            assertNotNull(dto.statusCode());
            assertEquals(500, dto.statusCode());
            // After retries are exhausted, the call is recorded as failure → OPEN again
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());
            assertEquals(1, callCount.get()); // Only the probe was made
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verifies that streamFromOpenRouter returns a circuit-open error when the
     * circuit is OPEN, without making any HTTP call.
     */
    @Test
    public void streamFromOpenRouter_shouldReturnCircuitOpenErrorWhenCircuitIsOpen() throws Exception {
        AtomicInteger openRouterCallCount = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            openRouterCallCount.incrementAndGet();
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "circuit-breaker.failure-threshold", "1",
                "circuit-breaker.window-size", "1",
                "circuit-breaker.cooldown-seconds", "30",
                "micronaut.application.name", "complai-test");
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST)
                .build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);
            CircuitBreaker cb = wrapper.getCircuitBreaker();

            // Trip the circuit
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());

            // Streaming call should be rejected immediately
            OpenRouterStreamStartResult result = wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test")));

            assertInstanceOf(OpenRouterStreamStartResult.Error.class, result);
            OpenRouterStreamingException failure = ((OpenRouterStreamStartResult.Error) result).failure();
            assertEquals(OpenRouterErrorCode.CIRCUIT_OPEN, failure.getErrorCode());
            assertEquals(0, openRouterCallCount.get()); // No HTTP call made
        } finally {
            server.stop(0);
        }
    }
}

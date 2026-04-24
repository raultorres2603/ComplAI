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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
    public void postToOpenRouterAsync_shouldReturnErrorOnNon2xx() throws Exception {
        String body = "{\"error\":\"server failure\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
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
            assertEquals(500, dto.statusCode());
            assertNotNull(dto.error());
            assertTrue(dto.error().contains("OpenRouter non-2xx response"));
            assertEquals(body, dto.message());
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

    @Test
    public void postToOpenRouterAsync_retryUsesDelayedExecutorWithoutBlockingSleep() throws Exception {
        class ImmediateRetryWrapper extends HttpWrapper {
            private final List<HttpDto> responses = new ArrayList<>(List.of(
                    new HttpDto(null, 429, "POST", "OpenRouter non-2xx response: 429"),
                    new HttpDto("retried-ok", 200, "POST", null)));
            private final List<Long> recordedDelays = new ArrayList<>();

            @Override
            protected String resolveAuthHeader() {
                return "Bearer test-key";
            }

            @Override
            protected CompletableFuture<HttpDto> executeRequestAsync(Map<String, Object> payload, String authValue,
                    int attemptNumber) {
                return CompletableFuture.completedFuture(responses.remove(0));
            }

            @Override
            protected Executor retryDelayExecutor(long delayMs) {
                recordedDelays.add(delayMs);
                return Runnable::run;
            }
        }

        ImmediateRetryWrapper wrapper = new ImmediateRetryWrapper();
        HttpDto dto = wrapper.postToOpenRouterAsync(List.of(Map.of("role", "user", "content", "prompt")))
                .get(1, TimeUnit.SECONDS);

        assertEquals(200, dto.statusCode());
        assertEquals("retried-ok", dto.message());
        assertEquals(1, wrapper.recordedDelays.size());
        assertTrue(wrapper.recordedDelays.get(0) > 0);
    }
}

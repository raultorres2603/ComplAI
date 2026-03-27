package cat.complai.http;

import cat.complai.http.dto.HttpDto;
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

import static org.junit.jupiter.api.Assertions.*;

public class HttpWrapperTest {

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
                "micronaut.application.name", "complai-test"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
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
                "OPENROUTER_API_KEY", "test-key"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
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
                "OPENROUTER_API_KEY", "test-key"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
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
                "OPENROUTER_API_KEY", "plain-token"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
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
            // Use 0 for chunked transfer encoding so Micronaut's dataStream() receives HttpContent chunks
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            List<String> lines = Flux.from(wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test"))
            )).collectList().block(Duration.ofSeconds(5));

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
                "micronaut.application.name", "complai-test"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            List<String> lines = Flux.from(wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test"))
            )).collectList().block(Duration.ofSeconds(5));

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
            // Use 0 for chunked transfer encoding so Micronaut's dataStream() receives HttpContent chunks
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, Object> props = Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort(),
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            ctx.registerSingleton(new ObjectMapper());
            ctx.start();
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            List<String> lines = Flux.from(wrapper.streamFromOpenRouter(
                    List.of(Map.of("role", "user", "content", "test"))
            )).collectList().block(Duration.ofSeconds(5));

            assertNotNull(lines);
            assertEquals(2, lines.size());
            assertTrue(lines.stream().allMatch(l -> l.startsWith("data:")));
        } finally {
            server.stop(0);
        }
    }
}

package cat.complai.http;

import cat.complai.http.dto.HttpDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
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
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            // register singletons before starting context so DI finds them
            ctx.registerSingleton(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
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
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                "OPENROUTER_API_KEY", "test-key"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            ctx.registerSingleton(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
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
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                "OPENROUTER_API_KEY", "test-key"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            ctx.registerSingleton(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
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
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                // Put a plain token so wrapper prefixes Bearer
                "OPENROUTER_API_KEY", "plain-token"
        );
        try (ApplicationContext ctx = ApplicationContext.builder().properties(props).environments(Environment.TEST).build()) {
            ctx.registerSingleton(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
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
}

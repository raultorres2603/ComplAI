package cat.complai.http;

import cat.complai.http.dto.HttpDto;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class HttpWrapperTest {

    @Test
    public void postToOpenRouterAsync_shouldReturnParsedMessage() throws Exception {
        // Start a simple HTTP server that mimics OpenRouter's response
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String responseJson = "{\"choices\":[{\"message\":{\"content\":\"Hello from mock\"}}]}";
        server.createContext("/api/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = responseJson.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                "OPENROUTER_API_KEY", "test-key",
                "micronaut.application.name", "complai-test"
        ), Environment.TEST)) {
            HttpWrapper httpWrapper = ctx.getBean(HttpWrapper.class);
            var future = httpWrapper.postToOpenRouterAsync("Test prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(200, dto.getStatusCode());
            assertNull(dto.getError());
            assertNotNull(dto.getMessage());
            assertTrue(dto.getMessage().contains("Hello from mock"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postToOpenRouterAsync_shouldReturnErrorOnNon2xx() throws Exception {
        String body = "{\"error\":\"server failure\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                "OPENROUTER_API_KEY", "test-key"
        ), Environment.TEST)) {
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            var future = wrapper.postToOpenRouterAsync("prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(500, dto.getStatusCode());
            assertNotNull(dto.getError());
            assertTrue(dto.getError().contains("OpenRouter non-2xx response"));
            assertEquals(body, dto.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postToOpenRouterAsync_shouldHandleMalformedJsonGracefully() throws Exception {
        String body = "not a json";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                "OPENROUTER_API_KEY", "test-key"
        ), Environment.TEST)) {
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            var future = wrapper.postToOpenRouterAsync("prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(200, dto.getStatusCode());
            // current implementation returns null message when parsing fails
            assertNull(dto.getMessage());
            assertNull(dto.getError());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postToOpenRouterAsync_shouldPrefixBearerWhenNeeded() throws Exception {
        final String[] receivedAuth = new String[1];
        String resp = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Headers reqHeaders = exchange.getRequestHeaders();
                receivedAuth[0] = reqHeaders.getFirst("Authorization");
                byte[] bytes = resp.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "openrouter.url", "http://localhost:" + server.getAddress().getPort() + "/api/v1/chat/completions",
                // Put a plain token so wrapper prefixes Bearer
                "OPENROUTER_API_KEY", "plain-token"
        ), Environment.TEST)) {
            HttpWrapper wrapper = ctx.getBean(HttpWrapper.class);

            var future = wrapper.postToOpenRouterAsync("prompt");
            HttpDto dto = future.get(5, TimeUnit.SECONDS);

            assertNotNull(dto);
            assertEquals(200, dto.getStatusCode());
            assertNull(dto.getError());
            assertNotNull(dto.getMessage());
            assertEquals("ok", dto.getMessage());
            assertNotNull(receivedAuth[0]);
            assertTrue(receivedAuth[0].startsWith("Bearer "));
            assertTrue(receivedAuth[0].endsWith("plain-token"));
        } finally {
            server.stop(0);
        }
    }
}

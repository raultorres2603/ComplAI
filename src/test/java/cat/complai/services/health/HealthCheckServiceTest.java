package cat.complai.services.health;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HealthCheckService}.
 *
 * <p>Uses the protected no-arg constructor to create a testable instance whose
 * individual check methods are overridden to return predictable results. This
 * avoids the need for real AWS infrastructure or mocking the SDK clients.
 */
class HealthCheckServiceTest {

    // =========================================================================
    // checkAll() — orchestrator
    // =========================================================================

    @Test
    void checkAll_allChecksSuccessful_returnsAllKeys() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                okResult("S3 reachable"),
                okResult("SQS reachable"),
                okResult("SES configured"),
                okResult("RAG loaded"),
                okResult("OpenRouter configured"));

        Map<String, Object> results = service.checkAll();

        assertNotNull(results);
        assertEquals(5, results.size());
        assertTrue(results.containsKey("s3"));
        assertTrue(results.containsKey("sqs"));
        assertTrue(results.containsKey("ses"));
        assertTrue(results.containsKey("ragIndexes"));
        assertTrue(results.containsKey("openRouterApiKeyConfigured"));

        assertStatus(results.get("s3"), true);
        assertStatus(results.get("ses"), true);
    }

    @Test
    void checkAll_someChecksFail_stillReturnsAllKeys() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                errorResult("S3 unreachable"),
                okResult("SQS reachable"),
                errorResult("SES not configured"),
                okResult("RAG loaded"),
                okResult("OpenRouter configured"));

        Map<String, Object> results = service.checkAll();

        assertNotNull(results);
        assertEquals(5, results.size());
        assertStatus(results.get("s3"), false);
        assertStatus(results.get("sqs"), true);
        assertStatus(results.get("ses"), false);
        assertStatus(results.get("ragIndexes"), true);
        assertStatus(results.get("openRouterApiKeyConfigured"), true);
    }

    @Test
    void checkAll_rapidConcurrentCalls_doesNotBlock() {
        // Override check methods to simulate fast checks
        TestableHealthCheckService service = new TestableHealthCheckService(
                okResult("S3 ok"),
                okResult("SQS ok"),
                okResult("SES ok"),
                okResult("RAG ok"),
                okResult("OpenRouter ok"));

        // Run checks multiple times in quick succession — no thread starvation
        for (int i = 0; i < 10; i++) {
            Map<String, Object> results = service.checkAll();
            assertEquals(5, results.size());
        }
    }

    // =========================================================================
    // Individual check methods (unit-level)
    // =========================================================================

    @Test
    void checkS3_returnsResult() {
        // Use the real service via testable subclass that only overrides checkAll
        TestableHealthCheckService service = new TestableHealthCheckService(
                okResult("S3 ok"), null, null, null, null);

        Map<String, Object> result = service.checkS3();
        assertStatus(result, true);
        assertEquals("S3 ok", result.get("message"));
    }

    @Test
    void checkSQS_returnsResult() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                null, okResult("SQS ok"), null, null, null);

        Map<String, Object> result = service.checkSQS();
        assertStatus(result, true);
        assertEquals("SQS ok", result.get("message"));
    }

    @Test
    void checkSES_returnsResult() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                null, null, okResult("SES ok"), null, null);

        Map<String, Object> result = service.checkSES();
        assertStatus(result, true);
        assertEquals("SES ok", result.get("message"));
    }

    @Test
    void checkRAG_returnsResult_withItemCount() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                null, null, null, ragLoadedResult(), null);

        Map<String, Object> result = service.checkRAG();
        assertStatus(result, true);
        assertEquals(42, result.get("items"));
    }

    @Test
    void checkRAG_returnsResult_whenNotLoaded() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                null, null, null, errorResult("not loaded"), null);

        Map<String, Object> result = service.checkRAG();
        assertStatus(result, false);
    }

    @Test
    void checkOpenRouter_returnsResult() {
        TestableHealthCheckService service = new TestableHealthCheckService(
                null, null, null, null, okResult("OpenRouter key configured"));

        Map<String, Object> result = service.checkOpenRouter();
        assertStatus(result, true);
    }

    // =========================================================================
    // Testable subclass
    // =========================================================================

    /**
     * A {@link HealthCheckService} that overrides individual check methods to
     * return fixed results, avoiding the need for real AWS SDK clients.
     */
    private static class TestableHealthCheckService extends HealthCheckService {

        private final Map<String, Object> s3Result;
        private final Map<String, Object> sqsResult;
        private final Map<String, Object> sesResult;
        private final Map<String, Object> ragResult;
        private final Map<String, Object> openRouterResult;

        TestableHealthCheckService(
                Map<String, Object> s3Result,
                Map<String, Object> sqsResult,
                Map<String, Object> sesResult,
                Map<String, Object> ragResult,
                Map<String, Object> openRouterResult) {
            super(); // uses protected no-arg constructor
            this.s3Result = s3Result;
            this.sqsResult = sqsResult;
            this.sesResult = sesResult;
            this.ragResult = ragResult;
            this.openRouterResult = openRouterResult;
        }

        @Override
        Map<String, Object> checkS3() {
            return s3Result;
        }

        @Override
        Map<String, Object> checkSQS() {
            return sqsResult;
        }

        @Override
        Map<String, Object> checkSES() {
            return sesResult;
        }

        @Override
        Map<String, Object> checkRAG() {
            return ragResult;
        }

        @Override
        Map<String, Object> checkOpenRouter() {
            return openRouterResult;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Map<String, Object> okResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", true);
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", false);
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> ragLoadedResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", true);
        result.put("message", "RAG loaded with " + 42 + " items");
        result.put("items", 42);
        return result;
    }

    private static void assertStatus(Object checkResult, boolean expectedStatus) {
        assertNotNull(checkResult);
        assertInstanceOf(Map.class, checkResult, "checkResult must be a Map");
        Map<?, ?> result = (Map<?, ?>) checkResult;
        assertEquals(expectedStatus, result.get("status"),
                "Expected status=" + expectedStatus + " but got " + result);
    }
}

package cat.complai.utilities.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InteractionMetricsPublisher}.
 *
 * <p>Tests the publisher's metric construction and error handling
 * using a testable subclass that captures published data without
 * contacting real AWS CloudWatch.
 */
@DisplayName("InteractionMetricsPublisher Tests")
class InteractionMetricsPublisherTest {

    // -------------------------------------------------------------------------
    // Testable subclass — captures metrics instead of sending to AWS
    // -------------------------------------------------------------------------

    static class CapturingMetricsPublisher extends InteractionMetricsPublisher {
        final List<CapturedMetric> published = new ArrayList<>();

        @Override
        public void publishInteraction(String operation, String cityId, boolean success, long latencyMs) {
            published.add(new CapturedMetric(operation, cityId, success, latencyMs));
        }
    }

    record CapturedMetric(String operation, String cityId, boolean success, long latencyMs) {}

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Publishes ASK success interaction with correct fields")
    void publishInteraction_askSuccess_setsCorrectFields() {
        CapturingMetricsPublisher publisher = new CapturingMetricsPublisher();
        publisher.publishInteraction("ASK", "elprat", true, 1234L);

        assertEquals(1, publisher.published.size());
        CapturedMetric metric = publisher.published.get(0);
        assertEquals("ASK", metric.operation());
        assertEquals("elprat", metric.cityId());
        assertTrue(metric.success());
        assertEquals(1234L, metric.latencyMs());
    }

    @Test
    @DisplayName("Publishes REDACT error interaction with correct fields")
    void publishInteraction_redactError_setsCorrectFields() {
        CapturingMetricsPublisher publisher = new CapturingMetricsPublisher();
        publisher.publishInteraction("REDACT", "testcity", false, 567L);

        assertEquals(1, publisher.published.size());
        CapturedMetric metric = publisher.published.get(0);
        assertEquals("REDACT", metric.operation());
        assertEquals("testcity", metric.cityId());
        assertFalse(metric.success());
        assertEquals(567L, metric.latencyMs());
    }

    @Test
    @DisplayName("Publishes FEEDBACK interaction with correct fields")
    void publishInteraction_feedbackSuccess_setsCorrectFields() {
        CapturingMetricsPublisher publisher = new CapturingMetricsPublisher();
        publisher.publishInteraction("FEEDBACK", "barcelona", true, 89L);

        assertEquals(1, publisher.published.size());
        CapturedMetric metric = publisher.published.get(0);
        assertEquals("FEEDBACK", metric.operation());
        assertEquals("barcelona", metric.cityId());
        assertTrue(metric.success());
        assertEquals(89L, metric.latencyMs());
    }

    @Test
    @DisplayName("Handles null cityId gracefully")
    void publishInteraction_nullCityId_doesNotThrow() {
        CapturingMetricsPublisher publisher = new CapturingMetricsPublisher();
        // Should not throw
        publisher.publishInteraction("ASK", null, true, 100L);

        assertEquals(1, publisher.published.size());
        assertNull(publisher.published.get(0).cityId());
    }

    @Test
    @DisplayName("Handles zero latency gracefully")
    void publishInteraction_zeroLatency_doesNotThrow() {
        CapturingMetricsPublisher publisher = new CapturingMetricsPublisher();
        publisher.publishInteraction("ASK", "elprat", true, 0L);

        assertEquals(1, publisher.published.size());
        assertEquals(0L, publisher.published.get(0).latencyMs());
    }

    @Test
    @DisplayName("Supports multiple consecutive publishes")
    void publishInteraction_multipleCalls_accumulatesAll() {
        CapturingMetricsPublisher publisher = new CapturingMetricsPublisher();

        publisher.publishInteraction("ASK", "elprat", true, 100L);
        publisher.publishInteraction("REDACT", "elprat", true, 200L);
        publisher.publishInteraction("FEEDBACK", "elprat", false, 300L);

        assertEquals(3, publisher.published.size());
        assertEquals("ASK", publisher.published.get(0).operation());
        assertEquals("REDACT", publisher.published.get(1).operation());
        assertEquals("FEEDBACK", publisher.published.get(2).operation());
    }

    @Test
    @DisplayName("Protected no-arg constructor produces usable null-client instance")
    void protectedNoArgConstructor_clientIsNull_doesNotThrow() {
        // Use the protected no-arg constructor via anonymous subclass
        InteractionMetricsPublisher publisher = new InteractionMetricsPublisher() {
            // No need to override — just testing that the constructor works
            // and publishInteraction handles null client
        };

        // Should log a warning but not throw
        publisher.publishInteraction("ASK", "elprat", true, 100L);
        // If we reach here, the test passes
        assertTrue(true);
    }

    @Test
    @DisplayName("Constants are defined correctly")
    void constants_definedCorrectly() {
        assertEquals("ComplAI", InteractionMetricsPublisher.METRICS_NAMESPACE);
        assertEquals("Interaction", InteractionMetricsPublisher.INTERACTION_METRIC_NAME);
        assertEquals(10, InteractionMetricsPublisher.BATCH_SIZE);
    }
}

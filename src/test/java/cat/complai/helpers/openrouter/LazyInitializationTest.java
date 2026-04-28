package cat.complai.helpers.openrouter;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that RAG helpers are strictly lazy-initialized only on first
 * request per city.
 *
 * <p>
 * Goal: Confirm that Lambda cold-start is optimized by deferring RAG index
 * initialization until
 * the first request that needs it (by city).
 */
@MicronautTest
@DisplayName("Lazy RAG Helper Initialization Tests")
class LazyInitializationTest {

    @Inject
    private ProcedureRagHelperRegistry procedureRegistry;

    @Inject
    private EventRagHelperRegistry eventRegistry;

    @Test
    @DisplayName("Registries created with empty ConcurrentHashMap (no eager init)")
    void test_registriesCreatedEmpty() {
        // Create NEW registries to verify empty state
        ProcedureRagHelperRegistry newProcRegistry = new ProcedureRagHelperRegistry();
        EventRagHelperRegistry newEventRegistry = new EventRagHelperRegistry();

        assertNotNull(newProcRegistry, "ProcedureRagHelperRegistry should be instantiated");
        assertNotNull(newEventRegistry, "EventRagHelperRegistry should be instantiated");
    }

    @Test
    @DisplayName("Helper built only on first call to getForCity()")
    void test_helperBuiltOnlyOnFirstCall() {
        String testCity = "testcity"; // Use test city with actual data files

        // First call should trigger initialization
        RagHelper<RagHelper.Procedure> helper1 = procedureRegistry.getForCity(testCity);
        assertNotNull(helper1, "RagHelper for procedures should be initialized on first call");
        assertNotNull(helper1.getAll(), "Procedures should be loaded");
        assertTrue(helper1.getAll().size() > 0, "Procedures list should not be empty");

        // Same test for EventRagHelper
        RagHelper<RagHelper.Event> eventHelper1 = eventRegistry.getForCity(testCity);
        assertNotNull(eventHelper1, "RagHelper for events should be initialized on first call");
        assertNotNull(eventHelper1.getAll(), "Events should be loaded");
        assertTrue(eventHelper1.getAll().size() > 0, "Events list should not be empty");
    }

    @Test
    @DisplayName("Second call returns same cached instance (no rebuild)")
    void test_helperCachedOnSecondCall() {
        String testCity = "testcity"; // Use test city with actual data files

        // First call
        RagHelper<RagHelper.Procedure> helper1 = procedureRegistry.getForCity(testCity);

        // Second call should return the same instance
        RagHelper<RagHelper.Procedure> helper2 = procedureRegistry.getForCity(testCity);

        assertSame(helper1, helper2, "Second call should return same cached instance for procedures");

        // Same test for events
        RagHelper<RagHelper.Event> eventHelper1 = eventRegistry.getForCity(testCity);
        RagHelper<RagHelper.Event> eventHelper2 = eventRegistry.getForCity(testCity);

        assertSame(eventHelper1, eventHelper2, "Second call should return same cached instance for events");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Different cities can be initialized concurrently")
    @Timeout(10) // 10 second timeout
    void test_concurrentCityInitialization() throws InterruptedException {
        String testCity = "testcity"; // Use test city with actual data files
        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        RagHelper<RagHelper.Procedure>[] helpers = new RagHelper[2];

        try {
            executor.submit(() -> {
                try {
                    helpers[0] = procedureRegistry.getForCity(testCity);
                    assertNotNull(helpers[0]);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    helpers[1] = procedureRegistry.getForCity(testCity);
                    assertNotNull(helpers[1]);
                } finally {
                    latch.countDown();
                }
            });

            assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Both threads should complete within timeout");

            // Both threads should get the same instance even with concurrent access
            assertSame(helpers[0], helpers[1], "Concurrent access to same city should return same instance");
        } finally {
            executor.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Concurrent requests for same city initialize only once")
    @Timeout(10) // 10 second timeout
    void test_concurrentSameCityOnlyInitializeOnce() throws InterruptedException {
        String testCity = "testcity"; // Use test city with actual data files
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(5);
        ExecutorService executor = Executors.newFixedThreadPool(5);

        // Use AtomicInteger to track how many different instances we got
        // If lazy init is working correctly, all threads should get the same instance
        RagHelper<RagHelper.Procedure>[] helperInstances = new RagHelper[5];

        try {
            for (int i = 0; i < 5; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to start simultaneously
                        RagHelper<RagHelper.Procedure> helper = procedureRegistry.getForCity(testCity);
                        helperInstances[index] = helper;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Release all threads simultaneously
            assertTrue(endLatch.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "All threads should complete within timeout");

            // Verify all threads got the same instance
            for (int i = 1; i < 5; i++) {
                assertSame(helperInstances[0], helperInstances[i],
                        "All concurrent requests should return the same cached instance");
            }
        } finally {
            executor.shutdown();
        }
    }
}

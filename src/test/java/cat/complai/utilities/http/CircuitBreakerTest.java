package cat.complai.utilities.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CircuitBreaker} state machine transitions.
 *
 * <p>
 * These tests verify the three-state circuit breaker:
 * {@code CLOSED} → {@code OPEN} → {@code HALF_OPEN} and back,
 * plus the sliding-window failure-rate tracking.
 */
public class CircuitBreakerTest {

    private CircuitBreaker newBreaker(int failureThreshold, int windowSize, int cooldownSeconds) {
        return new CircuitBreaker(failureThreshold, windowSize, cooldownSeconds);
    }

    private CircuitBreaker newBreaker() {
        return new CircuitBreaker(5, 10, 30);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLOSED state — normal operation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void closed_allowsFirstCall() {
        CircuitBreaker cb = newBreaker();
        assertTrue(cb.isCallPermitted());
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void closed_successesDoNotAffectState() {
        CircuitBreaker cb = newBreaker();
        for (int i = 0; i < 20; i++) {
            assertTrue(cb.isCallPermitted());
            cb.recordSuccess();
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void closed_tripsToOpenWhenErrorRateExceedsThreshold() {
        // failureThreshold=3, windowSize=4 → need >50% failures in window
        CircuitBreaker cb = newBreaker(3, 4, 30);

        // 2 failures + 1 success in a window of 4 → 2/3 = 66% > 50% → OPEN
        assertEquals(CircuitBreaker.State.CLOSED, cb.recordFailure());
        assertEquals(CircuitBreaker.State.CLOSED, cb.recordFailure());
        assertEquals(CircuitBreaker.State.OPEN, cb.recordSuccess());
        // At this point: total=3, failures=2, 3>=3 && 2 > 3/2=1 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void closed_exactly50PercentErrorRateDoesNotTrip() {
        // failureThreshold=2, windowSize=4 → exactly 50% = 2 failures / 4 total
        // 2 > 4/2=2? No (equal, not strictly greater). Stays CLOSED.
        CircuitBreaker cb = newBreaker(2, 4, 30);
        assertEquals(CircuitBreaker.State.CLOSED, cb.recordSuccess()); // total=1, fail=0
        assertEquals(CircuitBreaker.State.CLOSED, cb.recordFailure()); // total=2, fail=1
        assertEquals(CircuitBreaker.State.CLOSED, cb.recordSuccess()); // total=3, fail=1
        assertEquals(CircuitBreaker.State.CLOSED, cb.recordFailure()); // total=4, fail=2, 4>=2 && 2 > 4/2=2? No, equal. Stays CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void closed_windowOverflowEvictsOldEntries() {
        // windowSize=3, failureThreshold=2
        CircuitBreaker cb = newBreaker(2, 3, 30);

        // Fill window with successes, then overflow
        cb.recordSuccess(); // idx=1, count=1, window=[T,?,?]
        cb.recordSuccess(); // idx=2, count=2, window=[T,T,?]
        cb.recordSuccess(); // idx=0, count=3, window=[T,T,T]
        cb.recordSuccess(); // idx=1, count=4, window=[T,T,T] (overwrote idx=0 with T)

        // Now add failures. Window has 3 entries (last 3): all successes.
        cb.recordFailure(); // idx=2, count=5, window=[T,F,T] (overwrote idx=1 with F)
        // total=min(5,3)=3, failures=1, 3>=2 && 1 > 3/2=1? No. Stays CLOSED.

        // Add another failure
        cb.recordFailure(); // idx=0, count=6, window=[F,F,T] (overwrote idx=2 with F)
        // total=min(6,3)=3, failures=2, 3>=2 && 2 > 3/2=1? Yes -> OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OPEN state — fail-fast
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void open_rejectsCallsImmediately() {
        CircuitBreaker cb = newBreaker(2, 4, 30);
        // Trip the circuit: 3 failures out of 4 = 75%
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure(); // total=4, failures=4, 4>=2 && 4 > 4/2=2 → OPEN

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.isCallPermitted());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OPEN → HALF_OPEN transition (after cooldown)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void open_transitionsToHalfOpenAfterCooldownExpires() throws Exception {
        // cooldown = 1 second
        CircuitBreaker cb = newBreaker(2, 4, 1);
        // Trip the circuit
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();

        // Within cooldown — should still be OPEN
        assertFalse(cb.isCallPermitted());

        // Wait for cooldown to expire
        Thread.sleep(1_100);

        // After cooldown — should transition to HALF_OPEN and permit call
        assertTrue(cb.isCallPermitted());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HALF_OPEN state — probe
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void halfOpen_successClosesTheCircuit() {
        CircuitBreaker cb = newBreaker(2, 4, 30);
        // Trip to OPEN
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Advance to HALF_OPEN (simulate cooldown expiry via isCallPermitted)
        // We need to use a short cooldown or manipulate. Let's use cooldown=0.
        CircuitBreaker cb2 = newBreaker(2, 4, 0);
        cb2.recordFailure();
        cb2.recordFailure();
        cb2.recordFailure();
        cb2.recordFailure();

        // With cooldown=0, isCallPermitted should immediately transition to HALF_OPEN
        assertTrue(cb2.isCallPermitted());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb2.getState());

        // Probe success → CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb2.recordSuccess());
    }

    @Test
    void halfOpen_failureReopensTheCircuit() {
        CircuitBreaker cb = newBreaker(1, 2, 0);
        // Trip to OPEN (1 failure in window of 2 → failureThreshold reached)
        cb.recordFailure(); // total=1, failures=1, 1>=1 && 1 > 1/2=0 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Advance to HALF_OPEN (cooldown=0 so immediate)
        assertTrue(cb.isCallPermitted());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Probe failure → back to OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void halfOpen_allowsOnlyOneProbeCall() {
        CircuitBreaker cb = newBreaker(1, 2, 0);
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // First call permitted (transitions to HALF_OPEN)
        assertTrue(cb.isCallPermitted());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Second call rejected
        assertFalse(cb.isCallPermitted());
        assertFalse(cb.isCallPermitted()); // still rejected
    }

    @Test
    void halfOpen_circuitResetsAfterClosing() {
        CircuitBreaker cb = newBreaker(1, 2, 0);
        cb.recordFailure(); // OPEN
        assertTrue(cb.isCallPermitted()); // OPEN→HALF_OPEN
        cb.recordSuccess();               // HALF_OPEN→CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Should be back to normal — allow more calls
        assertTrue(cb.isCallPermitted());
        cb.recordSuccess();
        assertTrue(cb.isCallPermitted());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void halfOpen_reopenAfterProbeFailure_resetsCountersAndCooldown() throws Exception {
        CircuitBreaker cb = newBreaker(1, 2, 1);
        cb.recordFailure(); // OPEN
        // Wait for cooldown to elapse
        Thread.sleep(1_100);
        assertTrue(cb.isCallPermitted()); // OPEN→HALF_OPEN (probe)
        cb.recordFailure();               // HALF_OPEN→OPEN (reopened with fresh cooldown)
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Should not allow calls immediately (cooldown just restarted)
        assertFalse(cb.isCallPermitted());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void resetForTest_restoresClosedStateAndClearsCounters() {
        CircuitBreaker cb = newBreaker(1, 2, 30);
        cb.recordFailure(); // OPEN
        cb.resetForTest();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isCallPermitted()); // Should allow call again
    }

    @Test
    void minimumWindowSizeOf2_worksCorrectly() {
        // windowSize is clamped to minimum 2
        CircuitBreaker cb = newBreaker(1, 2, 30);
        // recordSuccess stays CLOSED
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // 2 failures in 3 total = 66% > 50%, should trip
        cb.recordFailure(); // total=2, fail=1, 2>=1 && 1 > 2/2=1? No. CLOSED.
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        cb.recordFailure(); // total=3, fail=2, 3>=1 && 2 > 3/2=1? Yes → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void negativeConfigValues_areNormalizedToMinimums() {
        CircuitBreaker cb = newBreaker(-5, -10, -100);
        assertTrue(cb.isCallPermitted()); // Should work with normalized values

        // windowSize was clamped to 2, failureThreshold to 1
        // One failure should be enough to trip (1 > 2/2=1? No...)
        // Wait, with window=2, threshold=1: 1 failure in total=1
        // 1>=1 && 1 > 1/2=0 → OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }
}
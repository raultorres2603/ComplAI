package cat.complai.utilities.http;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Lightweight circuit breaker protecting OpenRouter calls from cascading
 * failures when the upstream provider is degraded or unresponsive.
 *
 * <p>
 * The circuit tracks success/failure in a sliding window of the last N
 * requests. When the error rate exceeds 50% (i.e., more than half of the
 * window entries are failures), the circuit trips to {@code OPEN}.
 * </p>
 *
 * <p>
 * State machine:
 * <ul>
 * <li>{@code CLOSED} — normal operation; requests are allowed through.
 * Failure/success counts are tracked in a circular buffer of the last
 * {@code windowSize} outcomes. If the error rate exceeds 50%, the breaker
 * trips to {@code OPEN}.</li>
 *
 * <li>{@code OPEN} — all calls are rejected immediately (fail-fast). After
 * the configured {@code cooldown} period the breaker moves to
 * {@code HALF_OPEN} to probe whether the provider has recovered.</li>
 *
 * <li>{@code HALF_OPEN} — exactly one probe request is allowed through.
 * If the probe succeeds the breaker returns to {@code CLOSED}; if it fails
 * it returns to {@code OPEN}.</li>
 * </ul>
 *
 * <p>
 * All state transitions and counter updates are atomic ({@link AtomicReference}
 * and {@link AtomicInteger}), making this class safe for concurrent use.
 * </p>
 *
 * <p>
 * This class is <em>not</em> a Micronaut-managed bean. It is constructed
 * directly by {@link HttpWrapper} which supplies configuration values from
 * its own {@code @Value}-injected properties.
 * </p>
 *
 * @see HttpWrapper
 */
public class CircuitBreaker {

    /** State of the circuit breaker. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final Logger logger = Logger.getLogger(CircuitBreaker.class.getName());

    private final int failureThreshold;
    private final int windowSize;
    private final int cooldownSeconds;

    // Sliding window — circular buffer: true = success, false = failure.
    private final boolean[] window;
    private final AtomicInteger windowIndex = new AtomicInteger(0);
    private final AtomicInteger windowCount = new AtomicInteger(0);

    // Current state — atomic read/write via compareAndSet
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    // Timestamp (epoch millis) when the circuit last transitioned to OPEN
    private volatile long openSinceEpochMillis;

    // Counter for probe calls in HALF_OPEN state
    private final AtomicInteger probeCount = new AtomicInteger(0);

    /**
     * Constructs a circuit breaker with explicit configuration.
     *
     * @param failureThreshold minimum failures in the sliding window to consider tripping (min 1)
     * @param windowSize       number of recent outcomes tracked in the sliding window (min 2)
     * @param cooldownSeconds  seconds to wait in OPEN before transitioning to HALF_OPEN (min 0)
     */
    public CircuitBreaker(int failureThreshold, int windowSize, int cooldownSeconds) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.windowSize = Math.max(2, windowSize);
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.window = new boolean[this.windowSize];
    }

    /**
     * Protected no-arg constructor for test subclasses.
     * Uses default values: windowSize=10, failureThreshold=5, cooldownSeconds=30.
     */
    protected CircuitBreaker() {
        this.failureThreshold = 5;
        this.windowSize = 10;
        this.cooldownSeconds = 30;
        this.window = new boolean[10];
    }

    /**
     * Returns whether a call is currently permitted by the circuit breaker.
     *
     * <p>
     * When the circuit is {@code OPEN}, this method checks whether the cooldown
     * period has elapsed. If so, it atomically transitions to {@code HALF_OPEN}
     * and permits the probe call. Otherwise it returns {@code false}.
     *
     * @return {@code true} if the call may proceed; {@code false} if the circuit is OPEN
     *         and the cooldown has not yet elapsed
     */
    public boolean isCallPermitted() {
        State current = state.get();

        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.HALF_OPEN) {
            // Allow exactly one probe call in HALF_OPEN
            return probeCount.incrementAndGet() == 1;
        }

        // current == OPEN — check cooldown
        long elapsed = Instant.now().toEpochMilli() - openSinceEpochMillis;
        long cooldownMillis = cooldownSeconds * 1000L;

        if (elapsed >= cooldownMillis) {
            // Cooldown elapsed — transition to HALF_OPEN and permit the probe
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                logger.info(() -> "Circuit breaker HALF_OPEN — upstream provider probed after " + elapsed + " ms cooldown");
                // The current call IS the probe — mark it consumed
                probeCount.set(1);
                return true;
            }
            // Lost the CAS — another thread already transitioned; re-check
            State s = state.get();
            if (s == State.HALF_OPEN) {
                return probeCount.incrementAndGet() == 1;
            }
            return false;
        }

        logger.fine(() -> "Circuit breaker OPEN — call rejected (cooldown " + (cooldownMillis - elapsed) + " ms remaining)");
        return false;
    }

    /**
     * Records a successful outcome for the current call.
     *
     * <p>
     * If the circuit is in {@code HALF_OPEN} (the probe succeeded), the circuit
     * immediately returns to {@code CLOSED} and the window is cleared.
     * If the circuit is {@code CLOSED}, the success is recorded in the window.
     *
     * @return the state after recording (for logging / testing)
     */
    public State recordSuccess() {
        State current = state.get();

        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                clearWindow();
                logger.info("Circuit breaker CLOSED — upstream provider recovered (HALF_OPEN probe succeeded)");
            }
        } else if (current == State.CLOSED) {
            addToWindow(true);

            // Even successes can fill the window to the threshold;
            // re-evaluate the error rate.
            evaluateTripCondition();
        }

        return state.get();
    }

    /**
     * Records a failed outcome for the current call.
     *
     * <p>
     * If the circuit is in {@code HALF_OPEN} (the probe failed), the circuit
     * returns to {@code OPEN} and the cooldown restarts. If the circuit is
     * {@code CLOSED}, the failure is recorded in the sliding window and the
     * error rate is evaluated; if it exceeds the threshold the circuit trips
     * to {@code OPEN}.
     *
     * @return the state after recording (for logging / testing)
     */
    public State recordFailure() {
        State current = state.get();

        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openSinceEpochMillis = Instant.now().toEpochMilli();
                probeCount.set(0);
                clearWindow();
                logger.warning("Circuit breaker OPEN — upstream still degraded (HALF_OPEN probe failed)");
            }
        } else if (current == State.CLOSED) {
            addToWindow(false);

            // Evaluate the error rate and possibly trip to OPEN
            evaluateTripCondition();
        }

        return state.get();
    }

    /**
     * Evaluates whether the error rate in the current sliding window exceeds 50%
     * and transitions the circuit to {@code OPEN} if so.
     * <p>
     * Only acts when the circuit is currently {@code CLOSED}. This is safe to
     * call from both {@link #recordSuccess()} and {@link #recordFailure()}.
     */
    private void evaluateTripCondition() {
        int failures = countFailures();
        int total = Math.min(windowCount.get(), windowSize);

        // Trip: error rate > 50% AND at least failureThreshold failures observed
        if (total >= failureThreshold && failures > total / 2) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                openSinceEpochMillis = Instant.now().toEpochMilli();
                clearWindow();
                logger.warning(() -> String.format(
                        "Circuit breaker OPEN — error rate %.0f%% (%d failures / %d requests)",
                        (double) failures / total * 100, failures, total));
            }
        }
    }

    /**
     * Returns the current state of the circuit breaker.
     *
     * @return current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Resets the circuit breaker to CLOSED with all counters cleared.
     * Intended for testing only.
     */
    void resetForTest() {
        state.set(State.CLOSED);
        clearWindow();
        probeCount.set(0);
    }

    // ─────────────────────────────────────────────────────────────────
    // Sliding window helpers
    // ─────────────────────────────────────────────────────────────────

    private void addToWindow(boolean success) {
        int idx = windowIndex.getAndUpdate(i -> (i + 1) % windowSize);
        synchronized (window) {
            window[idx] = success;
        }
        windowCount.incrementAndGet();
    }

    private int countFailures() {
        int total = Math.min(windowCount.get(), windowSize);
        int failures = 0;
        synchronized (window) {
            for (int i = 0; i < total; i++) {
                if (!window[i]) {
                    failures++;
                }
            }
        }
        return failures;
    }

    private void clearWindow() {
        synchronized (window) {
            for (int i = 0; i < windowSize; i++) {
                window[i] = true; // default to success
            }
        }
        windowIndex.set(0);
        windowCount.set(0);
    }
}
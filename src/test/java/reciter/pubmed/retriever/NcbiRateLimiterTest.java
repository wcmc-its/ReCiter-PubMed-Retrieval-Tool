package reciter.pubmed.retriever;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NcbiRateLimiterTest {

    /** N permits at R/s must take at least (N-1)/R seconds — the first is free, the rest are paced. */
    @Test
    void pacesRequestsToConfiguredRate() {
        NcbiRateLimiter limiter = new NcbiRateLimiter(100.0); // 10ms between permits
        int permits = 6;                                      // expect >= 5 * 10ms = 50ms

        long start = System.nanoTime();
        for (int i = 0; i < permits; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs >= 40, "expected >= ~50ms of pacing, got " + elapsedMs + "ms");
    }

    /** A non-positive / bad configured rate falls back to the default instead of dividing by zero. */
    @Test
    void invalidRateFallsBackToDefault() {
        NcbiRateLimiter limiter = new NcbiRateLimiter(0.0);
        limiter.acquire(); // must not throw (default 3/s → ~333ms interval, first permit is free)
    }
}

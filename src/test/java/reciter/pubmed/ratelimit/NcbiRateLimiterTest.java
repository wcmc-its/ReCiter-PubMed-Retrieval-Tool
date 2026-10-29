package reciter.pubmed.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link NcbiRateLimiter}. A fake clock and a fake sleeper (which
 * advances that clock instead of actually sleeping) make the limiter's timing fully testable with
 * no wall-clock waits.
 * <p>
 * Merge note: ported from dev's TestNG version to JUnit 5, because master's unchanged pom.xml runs
 * only JUnit Platform tests (TestNG classes would compile but silently never execute).
 */
public class NcbiRateLimiterTest {

    /** Fake monotonic clock, in nanoseconds. */
    private final long[] nowNanos = {0L};
    /** Records every requested sleep (ns); a sleep advances the fake clock by that amount. */
    private final List<Long> sleeps = new ArrayList<>();

    // Reset the fake clock and recorded sleeps before each test to keep them independent.
    @BeforeEach
    public void resetClock() {
        nowNanos[0] = 0L;
        sleeps.clear();
    }

    private NcbiRateLimiter limiter(double permitsPerSecond, boolean enabled) {
        return new NcbiRateLimiter(permitsPerSecond, enabled,
                () -> nowNanos[0],
                nanos -> { sleeps.add(nanos); nowNanos[0] += nanos; });
    }

    private static long millisToNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    public void firstPermitIsImmediateThenSpacedAtTheConfiguredRate() {
        NcbiRateLimiter limiter = limiter(2.0, true); // 2/s => 500ms spacing

        limiter.acquire(); // #1 immediate
        limiter.acquire(); // #2 waits one interval
        limiter.acquire(); // #3 waits another interval

        assertEquals(2, sleeps.size(), "Only the 2nd and 3rd acquires should wait; the first is immediate");
        assertEquals(millisToNanos(500), (long) sleeps.get(0), "2nd permit must wait one 500ms interval");
        assertEquals(millisToNanos(500), (long) sleeps.get(1), "3rd permit must wait one more 500ms interval");
    }

    @Test
    public void pauseForMakesTheNextAcquireWaitOutTheRetryAfter() {
        NcbiRateLimiter limiter = limiter(2.0, true);

        limiter.acquire();      // #1 immediate, advances next permit by 500ms
        limiter.pauseFor(3);    // Retry-After: 3s — must dominate the 500ms spacing
        limiter.acquire();      // #2 must wait ~3s

        assertEquals(1, sleeps.size(), "Only the post-pause acquire should sleep");
        assertEquals(TimeUnit.SECONDS.toNanos(3), (long) sleeps.get(0),
                "After pauseFor(3) the next permit must wait the full 3s Retry-After");
    }

    @Test
    public void longestPauseWins() {
        NcbiRateLimiter limiter = limiter(100.0, true); // tiny spacing so pauses dominate

        limiter.pauseFor(2);
        limiter.pauseFor(5);
        limiter.pauseFor(1); // shorter than the already-set 5s pause; must not shorten it
        limiter.acquire();

        assertEquals(TimeUnit.SECONDS.toNanos(5), (long) sleeps.get(0),
                "The longest outstanding pause must win");
    }

    @Test
    public void disabledLimiterNeverWaits() {
        NcbiRateLimiter limiter = limiter(2.0, false);

        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        limiter.pauseFor(10);
        limiter.acquire();

        assertTrue(sleeps.isEmpty(), "A disabled limiter must never sleep or pause");
    }

    @Test
    public void nonPositiveRateIsRejected() {
        try {
            limiter(0.0, true);
            fail("Expected IllegalArgumentException for a non-positive rate");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("permits-per-second"),
                    "Message should name the offending property");
        }
    }
}

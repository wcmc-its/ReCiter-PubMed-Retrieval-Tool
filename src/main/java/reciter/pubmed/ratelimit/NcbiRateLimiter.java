package reciter.pubmed.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-pod, in-process rate limiter for outbound NCBI E-utilities calls (issue #117, Phase 1).
 *
 * <p>NCBI enforces its request quota <em>per API key</em> — 10 req/s with a key, 3 req/s without —
 * shared across every process that uses that key. With this service horizontally scaled (the HPA
 * allows up to 4 pods) and a 50-connection HTTP pool per pod, nothing previously capped how fast
 * the service issued NCBI requests, so concurrent servlet requests could collectively exceed the
 * quota and earn HTTP 429s. This limiter smooths each pod to a configurable fraction of the key
 * quota ({@code pubmed.ratelimit.permits-per-second}, default 2.0 ≈ 10/s ÷ 4 pods ÷ headroom) so
 * the aggregate across the expected pod count stays under the per-key limit.
 *
 * <p>It is a single shared bean: {@link #acquire()} is called before <em>both</em> the ESearch POST
 * and the EFetch GET, so all in-pod NCBI traffic draws from one budget. When NCBI signals throttling
 * (HTTP 429 / {@code X-RateLimit-Remaining: 0} with a {@code Retry-After}), {@link #pauseFor(long)}
 * suspends <em>all</em> permit grants in this pod until the advertised interval passes — so a
 * throttle observed by one request immediately backs off every other request in the pod, instead of
 * each thread sleeping independently.
 *
 * <p>This is the per-pod (Option 1) phase of {@code docs/rate-limiter-design-117.md}. Cross-pod
 * coordination (Options 2/3) is deliberately out of scope: correctness here depends on the static
 * sizing {@code permits-per-second ≈ key-quota ÷ maxReplicas ÷ safety-factor}.
 */
@Slf4j
@Component
public class NcbiRateLimiter {

    private final boolean enabled;
    private final long intervalNanos;     // minimum spacing between successive permits
    private final LongSupplier nanoClock; // seam for tests; defaults to System::nanoTime
    private final Sleeper sleeper;        // seam for tests; defaults to TimeUnit.NANOSECONDS.sleep

    /** Earliest time (nanoClock domain) at which the next permit may be granted. Guarded by {@code this}. */
    private long nextPermitNanos = Long.MIN_VALUE;

    /** Indirection over the actual blocking wait so tests can run without real time passing. */
    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    @Autowired
    public NcbiRateLimiter(
            @Value("${pubmed.ratelimit.permits-per-second:2.0}") double permitsPerSecond,
            @Value("${pubmed.ratelimit.enabled:true}") boolean enabled) {
        this(permitsPerSecond, enabled, System::nanoTime, defaultSleeper());
    }

    /** Seam constructor: inject the clock and sleeper for deterministic unit tests. */
    NcbiRateLimiter(double permitsPerSecond, boolean enabled, LongSupplier nanoClock, Sleeper sleeper) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "pubmed.ratelimit.permits-per-second must be > 0, was " + permitsPerSecond);
        }
        this.enabled = enabled;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.intervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / permitsPerSecond);
        if (enabled) {
            log.info("NCBI rate limiter enabled: {} permit(s)/sec ({} ms spacing).",
                    permitsPerSecond, TimeUnit.NANOSECONDS.toMillis(intervalNanos));
        } else {
            log.info("NCBI rate limiter disabled (pubmed.ratelimit.enabled=false).");
        }
    }

    private static Sleeper defaultSleeper() {
        return nanos -> {
            if (nanos > 0) {
                TimeUnit.NANOSECONDS.sleep(nanos);
            }
        };
    }

    /**
     * Blocks until a permit is available — respecting both the steady-state rate and any active
     * {@link #pauseFor(long) Retry-After pause} — then returns. A no-op when disabled. The wait is
     * computed under the lock but performed outside it, so a sleeping caller never blocks others
     * from reserving their own (later) slots.
     */
    public void acquire() {
        if (!enabled) {
            return;
        }
        long waitNanos;
        synchronized (this) {
            long now = nanoClock.getAsLong();
            long grantAt = Math.max(now, nextPermitNanos);
            waitNanos = grantAt - now;
            nextPermitNanos = grantAt + intervalNanos;
        }
        if (waitNanos > 0) {
            try {
                sleeper.sleepNanos(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for an NCBI rate-limit permit.", e);
            }
        }
    }

    /**
     * Suspends all permit grants until at least {@code retryAfterSeconds} from now, honoring an NCBI
     * {@code Retry-After}. Safe to call from any thread; the longest pause wins. A no-op when
     * disabled or given a non-positive interval.
     */
    public synchronized void pauseFor(long retryAfterSeconds) {
        if (!enabled || retryAfterSeconds <= 0) {
            return;
        }
        long resumeAt = nanoClock.getAsLong() + TimeUnit.SECONDS.toNanos(retryAfterSeconds);
        if (resumeAt > nextPermitNanos) {
            nextPermitNanos = resumeAt;
            log.warn("NCBI throttling observed; pausing all outbound NCBI requests in this pod for {}s (Retry-After).",
                    retryAfterSeconds);
        }
    }
}

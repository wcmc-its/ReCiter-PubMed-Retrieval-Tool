package reciter.pubmed.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-pod, in-process rate limiter for every outbound NCBI E-utilities call (ESearch and EFetch).
 *
 * <p>NCBI enforces its request quota <em>per API key</em> — ~10 req/s with a key, 3 req/s without —
 * shared across every pod that uses that key. Previously nothing capped how fast the fleet hit NCBI,
 * so it earned HTTP 429s / HTML error pages that were swallowed downstream and silently dropped
 * articles (wcmc-its/ReCiter-PubMed-Retrieval-Tool#117, #166).
 *
 * <p><b>Merge note (dev → master, Java 17 / Spring Boot 3).</b> Both branches grew their own limiter:
 * <ul>
 *   <li>master: a static {@code INSTANCE} singleton sized by the {@code NCBI_RATE_LIMIT_PER_SEC} env
 *       var (default 3/s, sized for the HPA cap of 3 pods).</li>
 *   <li>dev: this Spring bean, which additionally supports {@link #pauseFor(long)} so that a
 *       {@code Retry-After} observed by ONE request backs off EVERY request in the pod, can be
 *       disabled for local dev, and has clock/sleeper seams for deterministic tests.</li>
 * </ul>
 * The dev design is kept because it is strictly more capable; master's env var and 3/s default are
 * preserved through {@code application.properties}
 * ({@code pubmed.ratelimit.permits-per-second=${NCBI_RATE_LIMIT_PER_SEC:3.0}}), so existing
 * deployments behave exactly as before.
 *
 * <p>Sizing rule (no cross-pod coordination): {@code permits-per-second × maxReplicas} must stay under
 * the per-key quota. With 3 pods × 3/s = 9/s &lt; 10/s.
 */
@Component
public class NcbiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NcbiRateLimiter.class);

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
            @Value("${pubmed.ratelimit.permits-per-second:3.0}") double permitsPerSecond,
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
     * {@link #pauseFor(long) Retry-After pause} — then returns. A no-op when disabled. The slot is
     * reserved under the lock but the wait happens outside it, so a sleeping caller never blocks
     * others from reserving their own (later) slots.
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

    /** @return {@code false} when {@code pubmed.ratelimit.enabled=false}; {@link #pauseFor(long)} is then a no-op. */
    public boolean isEnabled() {
        return enabled;
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
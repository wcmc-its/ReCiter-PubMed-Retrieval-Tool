package reciter.pubmed.retriever;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide pacer for outbound NCBI E-utilities requests (esearch / efetch).
 *
 * NCBI enforces a per-api-key request rate (~10 req/s with a key). Every reciter-pubmed pod
 * shares one PUBMED_API_KEY, and retrieval previously fanned efetch pages out on an unbounded
 * work-stealing pool, so the fleet blew past the limit and NCBI answered with HTTP 429 /
 * HTML error pages. Those failures were swallowed downstream, silently dropping articles.
 *
 * {@link #acquire()} paces every caller in this JVM to at most NCBI_RATE_LIMIT_PER_SEC
 * requests per second (default 3, so 3 pods stay under NCBI's 10/s). Deliberately a simple
 * steady-rate min-interval limiter — no bursting. Swap for a token bucket only if a real
 * need for bursts shows up.
 */
public final class NcbiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NcbiRateLimiter.class);

    private static final double DEFAULT_RATE_PER_SEC = 3.0;

    public static final NcbiRateLimiter INSTANCE = new NcbiRateLimiter(readRateFromEnv());

    private final long minIntervalNanos;
    private long nextPermitNanos;

    NcbiRateLimiter(double permitsPerSecond) {
        double rate = permitsPerSecond > 0 ? permitsPerSecond : DEFAULT_RATE_PER_SEC;
        this.minIntervalNanos = (long) (1_000_000_000L / rate);
        this.nextPermitNanos = System.nanoTime();
        log.info("NcbiRateLimiter pacing outbound NCBI requests to {} req/s", rate);
    }

    private static double readRateFromEnv() {
        String v = System.getenv("NCBI_RATE_LIMIT_PER_SEC");
        if (v == null || v.isBlank()) {
            return DEFAULT_RATE_PER_SEC;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid NCBI_RATE_LIMIT_PER_SEC=[{}]; using default {}", v, DEFAULT_RATE_PER_SEC);
            return DEFAULT_RATE_PER_SEC;
        }
    }

    /**
     * Reserve the next permit slot (fast, under lock) and then block until it is due (outside
     * the lock, so concurrent callers each sleep to their own distinct slot rather than
     * serializing on the monitor).
     */
    public void acquire() {
        long scheduledNanos;
        synchronized (this) {
            long now = System.nanoTime();
            scheduledNanos = Math.max(now, nextPermitNanos);
            nextPermitNanos = scheduledNanos + minIntervalNanos;
        }
        long waitNanos = scheduledNanos - System.nanoTime();
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

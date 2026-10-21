package reciter.pubmed;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reciter.pubmed.ratelimit.NcbiRateLimiter;

/**
 * The single choke point through which every NCBI E-utilities request (ESearch POST and EFetch GET)
 * is sent. Centralising it here means rate limiting, throttle handling and transient-failure retry
 * behave identically on every code path — previously the controller, the retrieval service, the
 * ESearch handler and the EFetch callable each had their own (divergent) copy of this logic.
 *
 * <p>Per attempt it:
 * <ol>
 *   <li>acquires a permit from the shared {@link NcbiRateLimiter} — before EVERY attempt, including
 *       retries, so a retry can never bypass the fleet-wide pacing;</li>
 *   <li>retries a transient {@link IOException} (covers {@link java.net.http.HttpTimeoutException}
 *       and the "Connection reset" NCBI produces when it closes an idle pooled connection);</li>
 *   <li>treats NCBI throttling — HTTP 429/503, or {@code X-RateLimit-Remaining: 0} with a
 *       {@code Retry-After} — by pausing the shared limiter for the advertised interval (so every
 *       in-pod request backs off, not just this thread) and replaying;</li>
 *   <li>retries other 5xx gateway errors with exponential backoff.</li>
 * </ol>
 * Every caller is an idempotent read (ESearch/EFetch), so blind replay is safe.
 *
 * <p><b>Merge note:</b> the IOException retry comes from master (#174, connection-reset fix); the
 * Retry-After → {@link NcbiRateLimiter#pauseFor(long)} handling and the 429/503 detection come from
 * dev (#117). Neither branch alone had both.
 */
public final class NcbiHttp {

    private static final Logger log = LoggerFactory.getLogger(NcbiHttp.class);

    /** Default attempts per request (1 initial + 3 retries). */
    public static final int DEFAULT_MAX_ATTEMPTS = 4;

    /**
     * Upper bound on waiting for NCBI's response headers, so a stalled NCBI can never wedge a
     * servlet thread indefinitely (the JDK client has no timeout by default).
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private static final long INITIAL_BACKOFF_MS = 1500L;
    private static final long MAX_BACKOFF_MS = 9000L;

    private NcbiHttp() {
    }

    /**
     * Sends {@code request} via {@code client}, retrying transient failures up to
     * {@code maxAttempts} attempts in total. IOException / 5xx backoff is
     * {@code 1.5s * 2^(attempt-1)} capped at 9s; throttled responses wait the advertised
     * {@code Retry-After} instead. Once attempts are exhausted the last failure is thrown as an
     * {@link IOException}, which the caller's {@code @Retryable} / exception handler understands.
     *
     * @param rateLimiter shared per-pod limiter; may be {@code null} in unit tests
     * @return a response with a status below 500 that is not a throttle (the caller still checks
     *         for non-2xx and reads/ closes the body)
     */
    public static HttpResponse<InputStream> sendWithRetry(HttpClient client, HttpRequest request, int maxAttempts,
            NcbiRateLimiter rateLimiter) throws IOException {
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (rateLimiter != null) {
                rateLimiter.acquire();
            }

            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                lastFailure = e;
                log.warn("NCBI request attempt {}/{} failed with {}: {}", attempt, maxAttempts,
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt < maxAttempts) {
                    sleepMillis(backoffMillis(attempt));
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted sending NCBI request", e);
            }

            int status = response.statusCode();
            OptionalLong retryAfter = response.headers().firstValueAsLong("Retry-After");
            long rateLimitRemaining = response.headers().firstValueAsLong("X-RateLimit-Remaining").orElse(-1L);
            boolean throttled = status == HTTP_TOO_MANY_REQUESTS || status == HTTP_SERVICE_UNAVAILABLE
                    || (rateLimitRemaining == 0 && retryAfter.isPresent());

            if (throttled) {
                closeQuietly(response);
                // NCBI sends Retry-After as delta-seconds; floor at 1s so a throttle can never
                // turn into a tight retry loop when the header is missing or malformed.
                long retryAfterSeconds = Math.max(1L, retryAfter.orElse(1L));
                lastFailure = new IOException("NCBI throttled the request (HTTP " + status
                        + ", X-RateLimit-Remaining=" + rateLimitRemaining + ", Retry-After=" + retryAfterSeconds + "s)");
                log.warn("NCBI request attempt {}/{} throttled: HTTP {}, Retry-After={}s", attempt, maxAttempts,
                        status, retryAfterSeconds);
                if (attempt < maxAttempts) {
                    waitOutThrottle(rateLimiter, retryAfterSeconds);
                }
                continue;
            }

            if (status >= 500) {
                closeQuietly(response);
                lastFailure = new IOException("NCBI returned HTTP " + status);
                log.warn("NCBI request attempt {}/{} failed with HTTP {}", attempt, maxAttempts, status);
                if (attempt < maxAttempts) {
                    sleepMillis(backoffMillis(attempt));
                }
                continue;
            }

            return response;
        }

        throw lastFailure;
    }

    /**
     * Pausing the shared limiter makes the NEXT {@code acquire()} — ours and every other thread's in
     * this pod — wait out the Retry-After. A missing or disabled limiter cannot do that, so fall back
     * to sleeping this thread directly.
     */
    private static void waitOutThrottle(NcbiRateLimiter rateLimiter, long retryAfterSeconds) throws IOException {
        if (rateLimiter != null && rateLimiter.isEnabled()) {
            rateLimiter.pauseFor(retryAfterSeconds);
        } else {
            sleepMillis(retryAfterSeconds * 1000L);
        }
    }

    private static long backoffMillis(int attempt) {
        return Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << (attempt - 1)));
    }

    private static void sleepMillis(long delayMs) throws IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during NCBI retry backoff", e);
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        // Closing the unread body releases the underlying connection.
        try {
            response.body().close();
        } catch (IOException ignored) {
            // Nothing useful to do; the connection will be discarded.
        }
    }
}

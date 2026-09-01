package reciter.pubmed;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reciter.pubmed.retriever.NcbiRateLimiter;

/**
 * Retries a {@link HttpClient#send} call against NCBI E-utilities when the pooled connection
 * resets underneath us ({@link IOException}, which covers {@link java.net.http.HttpTimeoutException}
 * too). NCBI closes idle pooled connections server-side; the next send on that connection fails
 * with "Connection reset" rather than transparently reconnecting. Every caller of this helper is
 * an idempotent GET-shaped eutils read, so a blind retry is safe.
 *
 * {@link NcbiRateLimiter#INSTANCE}.acquire() is called before EVERY attempt, including retries —
 * the limiter paces the whole fleet and must not be bypassed just because a request is being
 * retried.
 */
public final class NcbiHttp {

    private static final Logger log = LoggerFactory.getLogger(NcbiHttp.class);

    private static final long INITIAL_BACKOFF_MS = 1500L;
    private static final long MAX_BACKOFF_MS = 9000L;

    private NcbiHttp() {
    }

    /**
     * Sends {@code request} via {@code client}, retrying a transient {@link IOException} up to
     * {@code maxAttempts} attempts total. Backoff between attempts is
     * {@code 1.5s * 2^(attempt-1)}, capped at 9s. Rethrows the last {@link IOException} once
     * attempts are exhausted.
     */
    public static HttpResponse<InputStream> sendWithRetry(HttpClient client, HttpRequest request, int maxAttempts)
            throws IOException {
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            NcbiRateLimiter.INSTANCE.acquire();
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                lastFailure = e;
                log.warn("NCBI request attempt {}/{} failed with {}: {}", attempt, maxAttempts,
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted sending NCBI request to [" + request.uri() + "]", e);
            }
        }

        throw lastFailure;
    }

    private static void sleepBackoff(int attempt) throws IOException {
        long delayMs = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << (attempt - 1)));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during NCBI retry backoff", e);
        }
    }
}

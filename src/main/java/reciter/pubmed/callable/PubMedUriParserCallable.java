package reciter.pubmed.callable;

import lombok.AllArgsConstructor;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.ratelimit.NcbiRateLimiter;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

import javax.xml.parsers.SAXParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    /** Time to establish a TCP connection to NCBI for the EFetch fetch. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Read timeout once connected, so a stalled EFetch response can never wedge a worker thread. */
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    /** Only the NCBI E-utilities host may be fetched (SSRF guard on the SAX system-id). */
    private static final String EXPECTED_HOST = "www.ncbi.nlm.nih.gov";

    /** HTTP 429 (Too Many Requests) — no constant for it on {@link HttpURLConnection} in Java 11. */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final InputSource inputSource;
    /** Per-pod NCBI rate limiter (issue #117). May be {@code null} in pure unit tests. */
    private final NcbiRateLimiter rateLimiter;

    public List<PubMedArticle> parse(InputSource inputSource) throws SAXException, IOException {
        //inputSource = preprocessSpecialCharacters(inputSource);
        saxParser.parse(inputSource, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
        InputSource inputSource = preprocessSpecialCharacters(this.inputSource);
        return parse(inputSource);
    }

    private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        String xml;
        if (inputSource.getSystemId() != null) {
            URL url = new URL(inputSource.getSystemId());
            if (!EXPECTED_HOST.equalsIgnoreCase(url.getHost())) {
                throw new IOException("Refusing to fetch EFetch XML from unexpected host: " + url.getHost());
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            // Honor the per-pod NCBI budget before issuing the EFetch request (issue #117).
            if (rateLimiter != null) {
                rateLimiter.acquire();
            }
            int status = connection.getResponseCode();
            if (status == HTTP_TOO_MANY_REQUESTS || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                // EFetch was throttled. Read the server's Retry-After, pause the shared limiter so
                // every other in-pod request backs off too, and surface an IOException so the
                // outer @Retryable re-enters retrieve() (whose next acquire() waits out the pause).
                long retryAfterSeconds = parseRetryAfterSeconds(connection.getHeaderField("Retry-After"));
                if (rateLimiter != null) {
                    rateLimiter.pauseFor(retryAfterSeconds);
                }
                connection.disconnect();
                throw new IOException("EFetch throttled by NCBI (HTTP " + status
                        + "), Retry-After=" + retryAfterSeconds + "s");
            }
            try (InputStream inputStream = connection.getInputStream()) {
                xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            xml = new String(inputSource.getByteStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        xml = xml.replace("<sup>", "&lt;sup&gt;");
        xml = xml.replace("</sup>", "&lt;/sup&gt;");
        xml = xml.replace("<sub>", "&lt;sub&gt;");
        xml = xml.replace("</sub>", "&lt;/sub&gt;");
        xml = xml.replace("<i>", "&lt;i&gt;");
        xml = xml.replace("</i>", "&lt;/i&gt;");
        xml = xml.replace("<b>", "&lt;b&gt;");
        xml = xml.replace("</b>", "&lt;/b&gt;");
        return new InputSource(new StringReader(xml));
    }

    /**
     * Parses an NCBI {@code Retry-After} header (delta-seconds form). Returns a 1-second floor when
     * the header is absent or not an integer (NCBI uses delta-seconds, not the HTTP-date form), so a
     * throttled response always produces some back-off rather than a tight retry loop.
     */
    private static long parseRetryAfterSeconds(String headerValue) {
        if (headerValue != null) {
            try {
                long seconds = Long.parseLong(headerValue.trim());
                if (seconds > 0) {
                    return seconds;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the default floor below.
            }
        }
        return 1L;
    }
}

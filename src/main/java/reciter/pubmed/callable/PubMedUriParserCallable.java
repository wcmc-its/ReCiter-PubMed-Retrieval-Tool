package reciter.pubmed.callable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.xml.parsers.SAXParser;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.AllArgsConstructor;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;
import java.net.HttpURLConnection;

@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PubMedUriParserCallable.class);

    /** Time to establish a TCP connection to NCBI for the EFetch fetch. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Read timeout once connected, so a stalled EFetch response can never wedge a worker thread. */
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    /** Only the NCBI E-utilities host may be fetched (SSRF guard on the SAX system-id). */
    private static final String EXPECTED_HOST = "www.ncbi.nlm.nih.gov";

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final InputSource inputSource;
    
    private static final Map<String, String> TAG_REPLACEMENTS = Map.of(
            "<sup>",  "&lt;sup&gt;",
            "</sup>", "&lt;/sup&gt;",
            "<sub>",  "&lt;sub&gt;",
            "</sub>", "&lt;/sub&gt;",
            "<i>",    "&lt;i&gt;",
            "</i>",   "&lt;/i&gt;",
            "<b>",    "&lt;b&gt;",
            "</b>",   "&lt;/b&gt;"
    );
    
    private static final String TAG_PATTERN = String.join("|",
            TAG_REPLACEMENTS.keySet().stream()
                    .map(java.util.regex.Pattern::quote)
                    .toList()
    );


    public List<PubMedArticle> parse(InputSource inputSource) throws SAXException, IOException {
        saxParser.parse(inputSource, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
        InputSource inputSource = preprocessSpecialCharacters(this.inputSource);
        return parse(inputSource);
    }

   /* private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        String xml;
        if (inputSource.getSystemId() != null) {
            URL url = new URL(inputSource.getSystemId());
            if (!EXPECTED_HOST.equalsIgnoreCase(url.getHost())) {
                throw new IOException("Refusing to fetch EFetch XML from unexpected host: " + url.getHost());
            }
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
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
    }*/
    private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        String xml;
        if (inputSource.getSystemId() != null) {
            URL url = new URL(inputSource.getSystemId());
            if (!EXPECTED_HOST.equalsIgnoreCase(url.getHost())) {
                throw new IOException("Refusing to fetch EFetch XML from unexpected host: " + url.getHost());
            }
            xml = fetchWithRateLimitAwareness(url);
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
     * Opens the EFetch connection and, if NCBI responds with HTTP 429, reads the Retry-After
     * (or X-RateLimit-Remaining) headers via {@link HttpURLConnection#getResponseCode()} instead
     * of blindly calling getInputStream() (which throws before the caller can inspect headers).
     * Sleeps for the advertised interval and retries once inline; if the retry also 429s, throws
     * IOException so the outer @Retryable on retrieve() (maxAttempts=7, backoff) takes over.
     */
    private String fetchWithRateLimitAwareness(URL url) throws IOException {
        HttpURLConnection connection = openConnection(url);
        int responseCode = connection.getResponseCode();

        if (responseCode == 429) {
            long sleepSeconds = parseRetryAfter(connection);
            log.info("EFetch url=[{}] returned 429. Sleeping {}s before one inline retry.",
                    url, sleepSeconds);
            try {
                Thread.sleep(sleepSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to retry EFetch after 429", e);
            }
            connection.disconnect();

            // One inline retry with a fresh connection.
            connection = openConnection(url);
            responseCode = connection.getResponseCode();
            if (responseCode == 429) {
                long retryAfter = parseRetryAfter(connection);
                connection.disconnect();
                throw new IOException("Server returned HTTP response code: 429 for URL: " + url
                        + " (retried once after sleeping, still rate-limited; Retry-After=" + retryAfter + "s)");
            }
        }

        try (InputStream inputStream = connection.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        return connection;
    }

    private long parseRetryAfter(HttpURLConnection connection) {
        String retryAfterHeader = connection.getHeaderField("Retry-After");
        if (retryAfterHeader != null) {
            try {
                return Long.parseLong(retryAfterHeader.trim());
            } catch (NumberFormatException e) {
                log.warn("Non-numeric Retry-After header value [{}]; using default of 1s.", retryAfterHeader);
            }
        }
        return 1L; // default backoff when NCBI omits Retry-After, matching the ESearch-side default
    }
}

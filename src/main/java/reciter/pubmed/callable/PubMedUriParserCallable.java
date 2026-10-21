package reciter.pubmed.callable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.AllArgsConstructor;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.NcbiHttp;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.ratelimit.NcbiRateLimiter;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

/**
 * Fetches one EFetch XML document (or reads a local byte stream in tests) and parses it into
 * {@link PubMedArticle}s.
 *
 * <p><b>Merge note:</b> master fetched with a bare {@code URL.openStream()} (no timeouts, no throttle
 * handling, no retry on a reset connection). dev added timeouts, an SSRF host guard and 429/503
 * handling via {@code HttpURLConnection}. This version routes the fetch through
 * {@link NcbiHttp#sendWithRetry} on the shared {@link HttpClient}, which gives EFetch the same rate
 * limiting, Retry-After back-off and connection-reset retry as ESearch, and keeps dev's host guard —
 * pointed at master's current {@code eutils} host (dev's guard still allowed only the retired
 * {@code www.ncbi.nlm.nih.gov}, which would have refused every EFetch after the #166 host switch).
 */
@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    /** Escaped so the SAX parser keeps inline markup as literal title/abstract text. */
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

    /** Compiled once: a single pass over the XML instead of eight {@code String.replace} calls. */
    private static final Pattern TAG_PATTERN = Pattern.compile(String.join("|",
            TAG_REPLACEMENTS.keySet().stream().map(Pattern::quote).toList()));

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final InputSource inputSource;
    /** Per-pod NCBI rate limiter (issue #117). May be {@code null} in pure unit tests. */
    private final NcbiRateLimiter rateLimiter;
    /** Shared NCBI client. May be {@code null} when {@link #inputSource} is a local byte stream. */
    private final HttpClient httpClient;

    public List<PubMedArticle> parse(InputSource inputSource) throws SAXException, IOException {
        saxParser.parse(inputSource, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    @Override
    public List<PubMedArticle> call() throws Exception {
        return parse(preprocessSpecialCharacters(this.inputSource));
    }

    private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        String xml = (inputSource.getSystemId() != null)
                ? fetchEFetchXml(inputSource.getSystemId())
                : new String(inputSource.getByteStream().readAllBytes(), StandardCharsets.UTF_8);

        xml = TAG_PATTERN.matcher(xml).replaceAll(match -> TAG_REPLACEMENTS.get(match.group()));
        return new InputSource(new StringReader(xml));
    }

    private String fetchEFetchXml(String eFetchUrl) throws IOException {
        URI uri = URI.create(eFetchUrl);
        // SSRF guard: the SAX system-id must only ever point at NCBI E-utilities.
        if (!PubmedXmlQuery.EUTILS_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Refusing to fetch EFetch XML from unexpected host: " + uri.getHost());
        }
        if (httpClient == null) {
            throw new IOException("No HttpClient configured for EFetch");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(NcbiHttp.REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response =
                NcbiHttp.sendWithRetry(httpClient, request, NcbiHttp.DEFAULT_MAX_ATTEMPTS, rateLimiter);

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                // Surface as IOException so the outer @Retryable / GlobalExceptionHandler see it,
                // instead of feeding an HTML error page to the SAX parser.
                throw new IOException("EFetch returned HTTP " + response.statusCode());
            }
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

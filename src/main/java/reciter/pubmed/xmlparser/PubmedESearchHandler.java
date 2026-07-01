package reciter.pubmed.xmlparser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalLong;

import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reciter.model.pubmed.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;

/**
 * A SAX handler for parsing the ESearch query from PubMed.
 *
 * @author Jie
 */
public class PubmedESearchHandler extends DefaultHandler {

	private static final Logger log = LoggerFactory.getLogger(PubmedESearchHandler.class);
	
    private String webEnv;
    private int count;
    private boolean bWebEnv;
    private boolean bCount;
    private int numCountEncounteredSoFar = 0;
    
    // ── Thread-safe, expensive to construct — shared static constants ──
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Shared HttpClient — reuses connection pool across all calls ──
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    private StringBuilder chars = new StringBuilder();

    /**
     * Sends a query to the NCBI web site to retrieve the webEnv.
     *
     * @param eSearchUrl example query: http://www.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmax=1&usehistory=y&term=Kukafka%20R[au].
     * @return WebEnvHandler that contains the WebEnv data.
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     */
    public static PubmedESearchResult executeESearchQuery(String eSearchUrl) {
       
		PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(eSearchUrl);
		pubmedXmlQuery.setRetMax(1);

		// Build base URL — api_key in URL, form params in POST body
		// Note: buildESearchQuery() result intentionally not used — api_key path
		// overrides it
		String fullUrl = (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty())
				? PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey()
				: PubmedXmlQuery.ESEARCH_BASE_URL;
		log.info("ESearch Query=[{}]", fullUrl);

		// Build URL-encoded form body — StandardCharsets.UTF_8 avoids
		// UnsupportedEncodingException
		String formData = "db=" + URLEncoder.encode(pubmedXmlQuery.getDb(), StandardCharsets.UTF_8) + "&retmax="
				+ pubmedXmlQuery.getRetMax() + "&usehistory="
				+ URLEncoder.encode(pubmedXmlQuery.getUseHistory(), StandardCharsets.UTF_8) + "&term="
				+ URLEncoder.encode(java.net.URLDecoder.decode(pubmedXmlQuery.getTerm(), StandardCharsets.UTF_8),
						StandardCharsets.UTF_8)
				+ "&retmode=" + URLEncoder.encode(pubmedXmlQuery.getRetMode(), StandardCharsets.UTF_8) + "&retstart="
				+ pubmedXmlQuery.getRetStart();

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(fullUrl))
				.header("Content-Type", "application/x-www-form-urlencoded").header("Cache-Control", "no-cache")
				.POST(HttpRequest.BodyPublishers.ofString(formData)).build();

		try {
			return executeWithRateLimitHandling(request, eSearchUrl, fullUrl);
		} catch (IOException e) {
			log.error("Error executing eSearch query=[{}], fullUrl=[{}]", eSearchUrl, fullUrl, e);
		}

		return new PubmedESearchResult();
    }

    /**
     * Executes the HTTP request with rate-limit handling.
     *
     * Matches original condition exactly:
     *  - Outer guard: X-RateLimit-Remaining EXISTS and equals 0
     *    (orElse(-1) → absent header = -1, never triggers block)
     *  - Inner guard: Retry-After EXISTS and has a value
     *    (retryAfter.isPresent() matches original null + length + [0] != null checks)
     *  - Retry happens ONLY when both conditions are true
     */
    private static PubmedESearchResult executeWithRateLimitHandling(
            HttpRequest request, String eSearchUrl, String fullUrl) throws IOException {
        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            // orElse(-1): absent header → -1 → block never triggers (matches original null/length guard)
            int rateLimitRemaining = (int) response.headers()
                    .firstValueAsLong("X-RateLimit-Remaining")
                    .orElse(-1L);

            if (rateLimitRemaining == 0) {
                // Inner guard: only sleep+retry if Retry-After header is present
                // matches: headerRetryAfter != null && length > 0 && headerRetryAfter[0] != null
                OptionalLong retryAfter = response.headers().firstValueAsLong("Retry-After");
                if (retryAfter.isPresent()) {
                    long sleepSeconds = retryAfter.getAsLong();
                    log.info("Rate limit hit. eSearchUrl=[{}] Retry-After: {} seconds",
                            eSearchUrl, sleepSeconds);
                    try {
                        Thread.sleep(sleepSeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("InterruptedException during rate-limit pause", ie);
                    }
                    // Retry only after confirmed sleep — matches original retry placement
                    response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                }
            }

            // Parse esearchresult JSON from response body
            try (InputStream esearchStream = response.body()) {
                JsonNode json = OBJECT_MAPPER.readTree(esearchStream).get("esearchresult");
                if (json != null) {
                    return OBJECT_MAPPER.treeToValue(json, PubmedESearchResult.class);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted for eSearchUrl=[" + eSearchUrl + "]", e);
        }

        return new PubmedESearchResult();
    }
    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

        chars.setLength(0);

        if (qName.equalsIgnoreCase("WebEnv")) {
            bWebEnv = true;
        }
        if (qName.equalsIgnoreCase("Count") && numCountEncounteredSoFar == 0) {
            numCountEncounteredSoFar++;
            bCount = true;
        }
    }

	@Override
	public void characters(char ch[], int start, int length) throws SAXException {
		if (bWebEnv || bCount) {
			chars.append(ch, start, length);
		}
	}

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // WebEnv
        if (bWebEnv) {
            webEnv = chars.toString();
            bWebEnv = false;
        }

        // Count.
        if (bCount) {
            count = Integer.parseInt(chars.toString());
            bCount = false;
        }
    }

    public String getWebEnv() {
        return webEnv;
    }

    public int getCount() {
        return count;
    }
}

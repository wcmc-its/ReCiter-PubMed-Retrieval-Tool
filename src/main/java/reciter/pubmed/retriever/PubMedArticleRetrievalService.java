package reciter.pubmed.retriever;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reciter.model.pubmed.PubMedArticle;
import reciter.model.pubmed.PubmedESearchResult;
import reciter.pubmed.NcbiHttp;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

@Service
public class PubMedArticleRetrievalService {
	
	private static final Logger log = LoggerFactory.getLogger(PubMedArticleRetrievalService.class);

    private static final int RETRIEVAL_THRESHOLD = 2000;

    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private CloseableHttpClient pubMedHttpClient;

    //To avoid thread errors - FWK005 parse may not be called while parsing.
    //https://stackoverflow.com/questions/39658247/singleton-thread-safe-sax-parser-instance
    private final ThreadLocal<SAXParserFactory> factoryThreadLocal = new ThreadLocal<SAXParserFactory>() {
        public SAXParserFactory initialValue() {
            try {
                SAXParserFactory factory = SAXParserFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                return factory;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    };

   public SAXParser getSaxParser() throws ParserConfigurationException, SAXException {
	   return factoryThreadLocal.get().newSAXParser();
   }

   /** Bound on concurrent efetch worker threads per call. Configurable via NCBI_FETCH_POOL_SIZE (default 3). */
   private static int fetchPoolSize() {
	   String v = System.getenv("NCBI_FETCH_POOL_SIZE");
	   if (v != null && !v.isBlank()) {
		   try {
			   int n = Integer.parseInt(v.trim());
			   if (n > 0) {
				   return n;
			   }
		   } catch (NumberFormatException ignored) {
			   // fall through to default
		   }
	   }
	   return 3;
   }

    /**
     * Initializes and starts threads that handles the retrieval process. Partition the number of articles
     * into manageable pieces and ask each thread to handle one partition.
     */
    @Retryable(maxAttempts = 7, retryFor = { RuntimeException.class, IOException.class },
        backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
	public List<PubMedArticle> retrieve(String pubMedQuery) throws IOException {

		PubmedESearchResult eSearchResult = getNumberOfPubMedArticles(pubMedQuery);
		int numberOfPubmedArticles = eSearchResult.getCount();
		List<PubMedArticle> pubMedArticles = new ArrayList<>();

		if (numberOfPubmedArticles > RETRIEVAL_THRESHOLD) {
			throw new IOException("Number of PubMed articles [" + numberOfPubmedArticles + "] exceeded threshold ["
					+ RETRIEVAL_THRESHOLD + "]");
		}

		// Bounded pool (was an unbounded newWorkStealingPool that was also never shut down).
		// The actual NCBI request rate is capped by NcbiRateLimiter; this just bounds the
		// worker threads that feed it. Configurable via NCBI_FETCH_POOL_SIZE (default 3).
		ExecutorService executor = Executors.newFixedThreadPool(fetchPoolSize());

		PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery();
		pubmedXmlQuery.setTerm(pubMedQuery);

		log.info("retMax=[{}], pubMedQuery=[{}], numberOfPubmedArticles=[{}].", pubmedXmlQuery.getRetMax(), pubMedQuery,
				numberOfPubmedArticles);

		List<Callable<List<PubMedArticle>>> callables = new ArrayList<>();
		int currentRetStart = 0;

		// Partition articles into retMax-sized chunks
		while (numberOfPubmedArticles > 0) {
			pubmedXmlQuery.setRetStart(currentRetStart);

			if (eSearchResult.getWebenv() != null) {
				pubmedXmlQuery.setWebEnv(eSearchResult.getWebenv());
			}

			String eFetchUrl = pubmedXmlQuery.buildEFetchQuery();
			log.info("eFetchUrl=[{}].", eFetchUrl);

			try {
				callables.add(new PubMedUriParserCallable(new PubmedEFetchHandler(), getSaxParser(),
						new InputSource(eFetchUrl)));
			} catch (ParserConfigurationException | SAXException e) {
				log.error("Failed to create PubMedUriParserCallable for url=[{}]", eFetchUrl, e);
			}

			currentRetStart += pubmedXmlQuery.getRetMax();
			pubmedXmlQuery.setRetStart(currentRetStart);
			numberOfPubmedArticles -= pubmedXmlQuery.getRetMax();
		}

		try {
			executor.invokeAll(callables).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception e) {
					log.error("Failed to retrieve PubMed articles from future", e);
					throw new IllegalStateException(e);
				}
			}).forEach(pubMedArticles::addAll);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("Interrupted while invoking callables", e);
		} finally {
			executor.shutdown();
		}

		return pubMedArticles;
	}

    
    /**
     * Calls PubMed eSearch API to get the total article count for a query.
     *
     * Migration from Apache HttpClient to java.net.http.HttpClient (Java 11+):
     *  - No external Apache dependency
     *  - Shared HttpClient instance reuses connection pool
     *  - Safe header reading via Optional API — no ArrayIndexOutOfBoundsException
     *  - StandardCharsets.UTF_8 replaces "UTF-8" string literal (no checked exception)
     *  - Rate-limit handling matches original: only retry when BOTH
     *    X-RateLimit-Remaining == 0 AND Retry-After header is present
     */
    protected PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {

        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(query);

        // Build base URL — api_key goes in URL, form params go in body
        String fullUrl = (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty())
                ? PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey()
                : PubmedXmlQuery.ESEARCH_BASE_URL;

        log.info("ESearch Query=[{}]", fullUrl);

        // Build URL-encoded form body — StandardCharsets.UTF_8 avoids checked exception
        String formData = "db=" + URLEncoder.encode(pubmedXmlQuery.getDb(), StandardCharsets.UTF_8)
                + "&retmax=" + pubmedXmlQuery.getRetMax()
                + "&usehistory=" + URLEncoder.encode(pubmedXmlQuery.getUseHistory(), StandardCharsets.UTF_8)
                + "&term=" + URLEncoder.encode(
                java.net.URLDecoder.decode(pubmedXmlQuery.getTerm(), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8)
                + "&retmode=" + URLEncoder.encode(pubmedXmlQuery.getRetMode(), StandardCharsets.UTF_8)
                + "&retstart=" + pubmedXmlQuery.getRetStart();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        return executeRequestWithRetry(request, query);
    }
    /**
     * Executes the HTTP request and handles PubMed rate limiting.
     *
     * Rate-limit logic matches original exactly:
     *  - Outer guard: X-RateLimit-Remaining header EXISTS and equals 0
     *    (orElse(-1) → absent header = -1, never triggers the block)
     *  - Inner guard: Retry-After header EXISTS and has a value
     *    (retryAfter.isPresent() matches original null + length + [0] != null checks)
     *  - Retry happens ONLY when both conditions are true — same as original
     */
    private PubmedESearchResult executeRequestWithRetry(HttpRequest request, String query) throws IOException {
        // NcbiHttp.sendWithRetry calls NcbiRateLimiter.INSTANCE.acquire() before every attempt
        // (including retries) and retries a transient IOException (e.g. a pooled connection NCBI
        // reset underneath us) instead of failing on the first attempt.
        HttpResponse<InputStream> response = NcbiHttp.sendWithRetry(httpClient, request, 4);

        // orElse(-1): absent header → -1 → block never triggers (matches original null/length guard)
        int rateLimitRemaining = (int) response.headers()
                .firstValueAsLong("X-RateLimit-Remaining")
                .orElse(-1L);

        log.info("Query: {} RateLimit-Remaining: {}", query, rateLimitRemaining);

        if (rateLimitRemaining == 0) {
            // Inner guard: only sleep+retry if Retry-After header is present
            // matches: headerRetryAfter != null && length > 0 && headerRetryAfter[0] != null
            OptionalLong retryAfter = response.headers().firstValueAsLong("Retry-After");
            if (retryAfter.isPresent()) {
                long sleepSeconds = retryAfter.getAsLong();
                log.info("Rate limit hit. Query: {} Retry-After: {} seconds", query, sleepSeconds);
                try {
                    Thread.sleep(sleepSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("InterruptedException during rate-limit pause", e);
                }
                // Retry only after confirmed sleep — matches original retry placement
                response = NcbiHttp.sendWithRetry(httpClient, request, 4);
            }
        }

        try (InputStream esearchStream = response.body()) {
            JsonNode json = objectMapper.readTree(esearchStream).get("esearchresult");
            if (json != null) {
                return objectMapper.treeToValue(json, PubmedESearchResult.class);
            }
        }

        JsonNode json = objectMapper.readTree(responseString).get("esearchresult");
        if (json == null) {
            return eSearchResult;
        }

        if (isPubMedQueryDropped(json, term)) {
            return eSearchResult; // count stays 0
        }

        eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
        log.info("esearchResults Count=[{}]", eSearchResult.getCount());
        return eSearchResult;
    }

    private String executeReadingBody(HttpPost httppost, String query) throws IOException {
        try (CloseableHttpResponse response = pubMedHttpClient.execute(httppost)) {
            if (shouldRetryAfterRateLimit(response, query)) {
                NcbiRateLimiter.INSTANCE.acquire();
                try (CloseableHttpResponse retryResponse = pubMedHttpClient.execute(httppost)) {
                    return readBody(retryResponse);
                }
            }
            return readBody(response);
        }
    }

    private static String readBody(CloseableHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        try (InputStream esearchStream = entity.getContent()) {
            IOUtils.copy(esearchStream, writer, "UTF-8");
        }
        return writer.toString();
    }

    private boolean shouldRetryAfterRateLimit(CloseableHttpResponse response, String query) {
        Header[] headerRateLimitRemaining = response.getHeaders("X-RateLimit-Remaining");
        Header[] headerRateLimit = response.getHeaders("X-RateLimit-Limit");
        Header[] headerRetryAfter = response.getHeaders("Retry-After");

        if (headerRateLimit != null && headerRateLimit.length > 0 && headerRateLimit[0] != null
                && headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null) {
            log.info("Query=[{}] {} {}", query, headerRateLimit[0].toString(), headerRateLimitRemaining[0].toString());
        }

        if (headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null
                && Integer.parseInt(headerRateLimitRemaining[0].getValue()) == 0) {
            if (headerRetryAfter != null && headerRetryAfter.length > 0 && headerRetryAfter[0] != null) {
                log.info("Query=[{}] {}", query, headerRetryAfter[0].toString());
                try {
                    Thread.sleep(Long.parseLong(headerRetryAfter[0].getValue()) * 1000L);
                } catch (InterruptedException e) {
                    log.error("InterruptedException", e);
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        }
        return false;
    }

    protected static boolean isPubMedQueryDropped(JsonNode esearchJson, String originalQuery) {
        JsonNode phraseNotFound = esearchJson.path("errorlist").path("phrasenotfound");
        if (!phraseNotFound.isArray() || phraseNotFound.size() == 0) {
            return false;
        }
        String queryTranslation = esearchJson.path("querytranslation").asText("");
        String stripped = queryTranslation
                .replaceAll("\\[(?:Author|au|All Fields)\\]", "")
                .replaceAll("\\b(AND|OR)\\b", "")
                .replaceAll("[()\"\\s]", "")
                .trim();
        if (stripped.length() <= 2) {
            log.warn("PubMed dropped query terms {} from query [{}]. QueryTranslation='{}' is trivial (stripped='{}'). Returning 0 results.",
                    phraseNotFound, originalQuery, queryTranslation, stripped);
            return true;
        }
        return false;
    }
}

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

    @Retryable(maxAttempts = 7, value = IOException.class,
        backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
    public List<PubMedArticle> retrieve(String pubMedQuery) throws IOException {

        PubmedESearchResult eSearchResult = getNumberOfPubMedArticles(pubMedQuery);
        int numberOfPubmedArticles = eSearchResult.getCount();
        List<PubMedArticle> pubMedArticles = new ArrayList<>();

        if (numberOfPubmedArticles > RETRIEVAL_THRESHOLD) {
            throw new IOException("Number of PubMed Articles retrieved " + numberOfPubmedArticles + " exceeded the threshold level " + RETRIEVAL_THRESHOLD);
        }

        if (numberOfPubmedArticles == 0) {
            return pubMedArticles;
        }

        // Bounded pool (was an unbounded newWorkStealingPool that was also never shut down).
        // The actual NCBI request rate is capped by NcbiRateLimiter; this just bounds the
        // worker threads that feed it. Configurable via NCBI_FETCH_POOL_SIZE (default 3).
        ExecutorService executor = Executors.newFixedThreadPool(fetchPoolSize());

        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery();
        pubmedXmlQuery.setTerm(pubMedQuery);

        log.info("retMax=[{}], pubMedQuery=[{}], numberOfPubmedArticles=[{}].",
                pubmedXmlQuery.getRetMax(), pubMedQuery, numberOfPubmedArticles);

        List<Callable<List<PubMedArticle>>> callables = new ArrayList<>();
        int currentRetStart = 0;

        while (numberOfPubmedArticles > 0) {
            pubmedXmlQuery.setRetStart(currentRetStart);
            if (eSearchResult.getWebenv() != null) {
                pubmedXmlQuery.setWebEnv(eSearchResult.getWebenv());
            }

            String eFetchUrl = pubmedXmlQuery.buildEFetchQuery();
            log.info("eFetchUrl=[{}].", PubmedXmlQuery.redactApiKey(eFetchUrl));

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

    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery) throws IOException {
        log.error("Exhausted retries retrieving PubMed articles for query=[{}].", pubMedQuery, e);
        throw e;
    }

    public PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {
        return executeESearch(query);
    }

    protected PubmedESearchResult executeESearch(String term) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(term);
        pubmedXmlQuery.setRetStart(0);

        String postUrl;
        if (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty()) {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey();
        } else {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL;
        }
        log.info("ESearch POST url=[{}], term=[{}]", PubmedXmlQuery.redactApiKey(postUrl), term);

        PubmedESearchResult eSearchResult = new PubmedESearchResult();

        HttpPost httppost = new HttpPost(postUrl);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("db", pubmedXmlQuery.getDb()));
        params.add(new BasicNameValuePair("retmax", String.valueOf(pubmedXmlQuery.getRetMax())));
        params.add(new BasicNameValuePair("usehistory", pubmedXmlQuery.getUseHistory()));
        params.add(new BasicNameValuePair("term", java.net.URLDecoder.decode(pubmedXmlQuery.getTerm(), "UTF-8")));
        params.add(new BasicNameValuePair("retmode", pubmedXmlQuery.getRetMode()));
        params.add(new BasicNameValuePair("retstart", String.valueOf(pubmedXmlQuery.getRetStart())));
        httppost.setEntity(new UrlEncodedFormEntity(params));
        httppost.setHeader("Content-Type", "application/x-www-form-urlencoded");
        httppost.setHeader("cache-control", "no-cache");

        // Pace every outbound esearch through the shared rate limiter before sending.
        NcbiRateLimiter.INSTANCE.acquire();
        String responseString = executeReadingBody(httppost, term);

        if (responseString == null || responseString.trim().isEmpty()
                || !responseString.trim().startsWith("{")
                || !objectMapper.readTree(responseString).has("esearchresult")) {
            log.error("Unexpected response (not JSON), possibly an HTML error page.");
            throw new IOException("PubMed eSearch returned a non-JSON/error response for query=[" + term + "]");
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

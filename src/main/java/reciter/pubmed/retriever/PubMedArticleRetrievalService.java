package reciter.pubmed.retriever;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.extern.slf4j.Slf4j;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.model.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

@Slf4j
@Service
public class PubMedArticleRetrievalService {

    /**
     * Maximum number of matching articles this service will retrieve for a single query. Queries
     * matching more than this many articles are hard-refused (see {@link #retrieve(String)}) rather
     * than fetched: such broad queries indicate an under-specified author search whose results are
     * not useful to the disambiguation engine and would impose a large, slow load on NCBI. Because
     * this threshold is well below {@link PubmedXmlQuery#DEFAULT_RETMAX} (10,000), every allowed
     * query fits in a single EFetch batch, so no pagination is required.
     */
    private static final int RETRIEVAL_THRESHOLD = 2000;

    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private CloseableHttpClient pubMedHttpClient;

    /*@Autowired
    private SAXParser saxParser;

    @Bean
    public SAXParser saxParser() throws ParserConfigurationException, SAXException {
        return SAXParserFactory.newInstance().newSAXParser();
    }*/

    //To avoid thread errors - FWK005 parse may not be called while parsing.
    //https://stackoverflow.com/questions/39658247/singleton-thread-safe-sax-parser-instance
    private final ThreadLocal<SAXParserFactory> factoryThreadLocal = new ThreadLocal<SAXParserFactory>() {
        public SAXParserFactory initialValue() {
            try {
                SAXParserFactory factory = SAXParserFactory.newInstance();
                // Harden against XXE: disallow DOCTYPE declarations and disable external
                // entity/DTD resolution. This is defense-in-depth for an XML-parsing service.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
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

    /**
     * Retrieves all PubMed articles matching {@code pubMedQuery}.
     * <p>
     * An ESearch determines the matching article count. Because the allowed count is capped at
     * {@link #RETRIEVAL_THRESHOLD} (2,000), which is well below {@link PubmedXmlQuery#DEFAULT_RETMAX}
     * (10,000), every allowed query is satisfied by a single EFetch request — there is no need to
     * paginate over retstart or fan the fetches out across a thread pool. Queries matching more than
     * the threshold are hard-refused with an {@link IOException}; the message text is matched by
     * {@code GlobalExceptionHandler} to map the refusal to a 502 response, so it must not change.
     */
    @Retryable(maxAttempts = 7, value = IOException.class,
        backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
    public List<PubMedArticle> retrieve(String pubMedQuery) throws IOException {

        PubmedESearchResult eSearchResult = getNumberOfPubMedArticles(pubMedQuery);
        int numberOfPubmedArticles = eSearchResult.getCount();

        if (numberOfPubmedArticles > RETRIEVAL_THRESHOLD) {
            throw new IOException("Number of PubMed Articles retrieved " + numberOfPubmedArticles + " exceeded the threshold level " + RETRIEVAL_THRESHOLD);
        }

        // No matches: return empty without an EFetch round-trip (the old pagination loop, gated on
        // "while (count > 0)", likewise issued no fetch for a zero-result query).
        if (numberOfPubmedArticles == 0) {
            return new ArrayList<>();
        }

        // Single EFetch using the WebEnv from the ESearch above. The allowed count always fits in
        // one retMax-sized batch, so no retstart pagination loop is required.
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery();
        pubmedXmlQuery.setTerm(pubMedQuery);
        pubmedXmlQuery.setRetStart(0);
        if (eSearchResult.getWebenv() != null) {
            pubmedXmlQuery.setWebEnv(eSearchResult.getWebenv());
        }

        String eFetchUrl = pubmedXmlQuery.buildEFetchQuery();
        log.info("retMax=[{}], pubMedQuery=[{}], numberOfPubmedArticles=[{}], eFetchUrl=[{}].",
                pubmedXmlQuery.getRetMax(), pubMedQuery, numberOfPubmedArticles,
                PubmedXmlQuery.redactApiKey(eFetchUrl));

        try {
            PubMedUriParserCallable callable =
                    new PubMedUriParserCallable(new PubmedEFetchHandler(), getSaxParser(), new InputSource(eFetchUrl));
            return new ArrayList<>(callable.call());
        } catch (IOException e) {
            throw e;
        } catch (ParserConfigurationException | SAXException e) {
            log.error("Unable to configure SAX parser for EFetch.", e);
            throw new IOException("Failed to configure EFetch parser", e);
        } catch (Exception e) {
            log.error("Unable to fetch/parse EFetch result.", e);
            throw new IOException("Failed to fetch/parse EFetch result", e);
        }
    }

    /**
     * Recovery handler invoked when {@link #retrieve(String)} exhausts all retry attempts.
     * Re-throws the final exception rather than silently swallowing it so callers still see
     * the failure, but provides a single, well-defined termination point for the retry loop.
     */
    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery) throws IOException {
        log.error("Exhausted retries retrieving PubMed articles for query=[{}].", pubMedQuery, e);
        throw e;
    }

    public PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {
        return executeESearch(query);
    }

    /**
     * Executes a single ESearch request against NCBI via HTTP POST. Handles rate-limit headers
     * (sleeping and retrying once when the remaining quota is exhausted) and applies the
     * query-drop detection (Fix #24): when PubMed silently drops a name part leaving a trivial
     * author query, the returned count is zeroed so dropped-term queries are neutralized on
     * every code path that performs an ESearch (both the count endpoint and the retrieval path).
     *
     * @param term URL-encoded Entrez query term (as stored in {@link PubmedXmlQuery#getTerm()})
     * @return the parsed {@link PubmedESearchResult}; count is 0 for trivial/dropped queries or
     *         non-JSON (e.g. HTML error page) responses.
     */
    protected PubmedESearchResult executeESearch(String term) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(term);
        pubmedXmlQuery.setRetStart(0);

        // The request is an HTTP POST to ESEARCH_BASE_URL with form-encoded params; log the
        // endpoint actually called (with api_key redacted) and the term, not a discarded GET URL.
        String postUrl;
        if (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty()) {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey();
        } else {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL;
        }
        log.info("ESearch POST url=[{}], term=[{}]", PubmedXmlQuery.redactApiKey(postUrl), term);

        PubmedESearchResult eSearchResult = new PubmedESearchResult();

        HttpPost httppost = new HttpPost(postUrl);
        // Request parameters and other properties.
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

        String responseString = executeReadingBody(httppost, term);

        if (responseString == null || responseString.trim().isEmpty()
                || !responseString.trim().startsWith("{")
                || !objectMapper.readTree(responseString).has("esearchresult")) {
            log.error("Unexpected response (not JSON), possibly an HTML error page.");
            return eSearchResult;
        }

        JsonNode json = objectMapper.readTree(responseString).get("esearchresult");
        if (json == null) {
            return eSearchResult;
        }

        // Query-drop detection (Fix #24): only act when PubMed actually reports dropped
        // phrases (errorlist.phrasenotfound) leaving a trivial query; then discard the noise.
        if (isPubMedQueryDropped(json, term)) {
            return eSearchResult; // count stays 0
        }

        eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
        log.info("esearchResults Count=[{}]", eSearchResult.getCount());
        return eSearchResult;
    }

    /**
     * Executes the ESearch POST and returns the response body. When the NCBI rate-limit quota is
     * exhausted (X-RateLimit-Remaining == 0) and a Retry-After header is present, sleeps for the
     * advertised interval and replays the request once, returning the replayed response body.
     * Response entities are always closed via try-with-resources.
     */
    private String executeReadingBody(HttpPost httppost, String query) throws IOException {
        try (CloseableHttpResponse response = pubMedHttpClient.execute(httppost)) {
            if (shouldRetryAfterRateLimit(response, query)) {
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

    /**
     * Inspects the NCBI rate-limit response headers. Returns {@code true} (after sleeping for the
     * advertised Retry-After interval) when the remaining quota is exhausted and the caller should
     * replay the request. Header access is fully null/length guarded because NCBI omits these
     * headers on error pages.
     */
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

    /**
     * Query-drop detection (Fix #24). PubMed silently drops unrecognized name parts
     * (e.g. "Charles-rawlins J[au]" becomes "J[au]"), returning many irrelevant results.
     * Detect this via PubMed's authoritative {@code errorlist.phrasenotfound} signal: only if it
     * reports dropped phrases AND the remaining {@code querytranslation} is trivially short
     * (<= 2 chars after stripping field tags, boolean operators and punctuation) are the results
     * treated as noise. This avoids false positives on legitimately short author queries.
     *
     * @param esearchJson   the "esearchresult" JSON node from PubMed's ESearch response
     * @param originalQuery the original query term (for logging)
     * @return true if the query was dropped and the results should be discarded
     */
    protected static boolean isPubMedQueryDropped(JsonNode esearchJson, String originalQuery) {
        JsonNode phraseNotFound = esearchJson.path("errorlist").path("phrasenotfound");
        if (!phraseNotFound.isArray() || phraseNotFound.size() == 0) {
            return false; // PubMed did not drop any phrases.
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

package reciter.pubmed.retriever;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import reciter.pubmed.ratelimit.NcbiRateLimiter;
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

    /**
     * ESearch {@code sort} values honored for {@code db=pubmed}. Anything else (an unknown value, a
     * blank string) is normalized to {@code null}, which means "send no sort parameter at all" —
     * i.e. it falls back to the exact pre-sort request.
     * <p>
     * Note the caller-facing {@code date} is NOT what goes on the wire. NCBI's ESearch sort key for
     * publication date is {@code pub_date}; a literal {@code sort=date} is silently ignored by
     * ESearch, which then returns its default order — verified against NCBI, where {@code sort=date}
     * returns an idlist byte-identical to sending no sort at all (and identical to sending a
     * garbage sort value), while {@code sort=pub_date} genuinely reorders. Accepting {@code date}
     * and forwarding it verbatim would therefore reproduce the exact failure this parameter exists
     * to fix: a caller asks for a ranked result and silently gets default order. So {@code date} is
     * accepted from callers and mapped to {@code pub_date} here.
     */
    private static final String SORT_RELEVANCE = "relevance";
    private static final String SORT_DATE = "date";
    private static final String SORT_PUB_DATE = "pub_date";

    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private CloseableHttpClient pubMedHttpClient;

    /** Per-pod NCBI rate limiter (issue #117); acquired before every ESearch and EFetch call. */
    @Autowired
    private NcbiRateLimiter rateLimiter;

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
                // Harden against XXE while still accepting the DOCTYPE that NCBI includes in every
                // EFetch response (<!DOCTYPE PubmedArticleSet PUBLIC ... pubmed_*.dtd>). The DOCTYPE
                // declaration itself is permitted, but all external general/parameter entity and
                // external/remote DTD resolution is disabled (plus secure processing), which closes
                // the XXE vector without rejecting valid PubMed XML. Do NOT set
                // disallow-doctype-decl=true here: it rejects every real EFetch response.
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

    /**
     * Retrieves all PubMed articles matching {@code pubMedQuery}.
     * <p>
     * An ESearch determines the matching article count. Every retrieval is satisfied by a single
     * EFetch request — there is no need to paginate over retstart or fan the fetches out across a
     * thread pool — because the number of records fetched is capped at {@link #RETRIEVAL_THRESHOLD}
     * (2,000), well below {@link PubmedXmlQuery#DEFAULT_RETMAX} (10,000), by one of two routes:
     * <ul>
     *   <li>no {@code retmax}: matched == fetched, so a query matching more than the threshold is
     *       hard-refused with a {@link RetrievalThresholdExceededException}. This is the legacy path
     *       the ReCiter engine takes, and it is unchanged.</li>
     *   <li>an explicit {@code retmax} (&le; the threshold): the fetch is bounded by {@code retmax}
     *       regardless of how many articles matched, so the matched-count refusal does not apply —
     *       "the 50 most relevant of 24,852" is a narrow fetch over a broad search.</li>
     * </ul>
     * The refusal message text is matched by {@code GlobalExceptionHandler}'s
     * {@code THRESHOLD_EXCEEDED_MARKER}, so it must not change.
     * <p>
     * THE REFUSAL IS EXCLUDED FROM RETRY, and that is not a tuning preference. It is PERMANENT: the
     * matched count is a property of the query, not of the network, so retrying cannot make it
     * smaller. As a plain {@code IOException} it was exactly what this retry policy retries — so one
     * too-broad query fired SEVEN ESearch requests at NCBI, with backoff, before failing with an
     * answer it already had on the first attempt. NCBI's unkeyed limit is 3 requests/second.
     */
    @Retryable(maxAttempts = 7, value = IOException.class,
        exclude = RetrievalThresholdExceededException.class,
        backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
    public List<PubMedArticle> retrieve(String pubMedQuery) throws IOException {
        return doRetrieve(pubMedQuery, null, null);
    }

    /**
     * Sort-aware variant of {@link #retrieve(String)}, used by the {@code query-complex} endpoint
     * when a caller asks for a ranked slice ("the top N by relevance") rather than every match.
     *
     * @param pubMedQuery URL-encoded Entrez query term
     * @param sort        ESearch sort order; only {@code relevance} and {@code date} are honored.
     *                    {@code null}, blank, or an unrecognized value means no sort parameter is
     *                    sent and the request is identical to {@link #retrieve(String)}.
     * @param retmax      caps how many records EFetch pulls back off the sorted result set;
     *                    {@code null} (or a value outside 1..{@link PubmedXmlQuery#DEFAULT_RETMAX})
     *                    leaves the default retmax in place. This is a cap, never an increase.
     */
    @Retryable(maxAttempts = 7, value = IOException.class,
        exclude = RetrievalThresholdExceededException.class,
        backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
    public List<PubMedArticle> retrieve(String pubMedQuery, String sort, Integer retmax) throws IOException {
        return doRetrieve(pubMedQuery, sort, retmax);
    }

    /**
     * Shared retrieval body behind both {@code retrieve} overloads.
     * <p>
     * Sort is applied to the <em>ESearch</em>, not the EFetch: because the search runs with
     * {@code usehistory=y}, the ESearch is what posts the (now ordered) result set to the history
     * server, and the EFetch then walks that {@code WebEnv} from {@code retstart=0}. So sorting at
     * search time plus a {@code retmax} cap at fetch time is what actually yields "the top N by
     * relevance" instead of "the first N in PubMed's default order".
     * <p>
     * When {@code sort} normalizes to {@code null} this method calls exactly the same one-argument
     * {@link #getNumberOfPubMedArticles(String)} and leaves retmax at its default, so the emitted
     * ESearch request is byte-identical to the pre-sort behavior the ReCiter engine depends on.
     */
    private List<PubMedArticle> doRetrieve(String pubMedQuery, String sort, Integer retmax) throws IOException {

        String normalizedSort = normalizeSort(sort);

        PubmedESearchResult eSearchResult = (normalizedSort == null)
                ? getNumberOfPubMedArticles(pubMedQuery)
                : getNumberOfPubMedArticles(pubMedQuery, normalizedSort);
        int numberOfPubmedArticles = eSearchResult.getCount();

        // The threshold gate exists to bound the EFETCH, which is the expensive half: without a
        // retmax, an allowed query drags back every matched record in one batch. So the gate keys on
        // the MATCHED count because, historically, matched == fetched.
        //
        // An explicit retmax breaks that identity. The caller is then asking for the top N of the
        // ordered set, and only N records are ever fetched — the cost the gate protects against no
        // longer depends on how many articles matched. Applying it anyway would hard-refuse "the 50
        // most relevant of 24,852", which is not a broad fetch; it is a narrow fetch over a broad
        // search, and it is exactly what relevance ranking is for.
        //
        // So: an explicit retmax bounds the fetch and takes over from the matched-count gate. The
        // bound itself still holds — retmax may not exceed the threshold — so no caller can use this
        // to pull back more than RETRIEVAL_THRESHOLD records. Callers that send no retmax (the
        // ReCiter engine, every legacy path) keep the original matched-count refusal untouched.
        boolean fetchIsBounded = retmax != null && retmax > 0 && retmax <= RETRIEVAL_THRESHOLD;
        if (!fetchIsBounded && numberOfPubmedArticles > RETRIEVAL_THRESHOLD) {
            // PERMANENT, and typed as such: the matched count is a property of the query, so no
            // amount of retrying will shrink it. See RetrievalThresholdExceededException.
            throw new RetrievalThresholdExceededException("Number of PubMed Articles retrieved " + numberOfPubmedArticles + " exceeded the threshold level " + RETRIEVAL_THRESHOLD);
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
        // Only ever lowers retMax: absent/invalid retmax keeps DEFAULT_RETMAX, i.e. today's EFetch.
        if (retmax != null && retmax > 0 && retmax < pubmedXmlQuery.getRetMax()) {
            pubmedXmlQuery.setRetMax(retmax);
        }

        String eFetchUrl = pubmedXmlQuery.buildEFetchQuery();
        log.info("retMax=[{}], sort=[{}], pubMedQuery=[{}], numberOfPubmedArticles=[{}], eFetchUrl=[{}].",
                pubmedXmlQuery.getRetMax(), normalizedSort, pubMedQuery, numberOfPubmedArticles,
                PubmedXmlQuery.redactApiKey(eFetchUrl));

        try {
            PubMedUriParserCallable callable =
                    new PubMedUriParserCallable(new PubmedEFetchHandler(), getSaxParser(), new InputSource(eFetchUrl), rateLimiter);
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
     * Maps a caller-supplied sort value to the literal value to put on the ESearch wire. Only two
     * orderings are supported — relevance and publication date — and everything else is ignored
     * rather than forwarded, because ESearch does not reject an unknown sort key: it silently drops
     * it and falls back to default order.
     *
     * @param sort raw caller input (may be null); {@code relevance}, or {@code date} / {@code pub_date}
     * @return {@code relevance}, {@code pub_date}, or {@code null} meaning "send no sort parameter"
     */
    protected static String normalizeSort(String sort) {
        if (sort == null || sort.trim().isEmpty()) {
            return null;
        }
        String normalized = sort.trim().toLowerCase(Locale.ROOT);
        if (SORT_RELEVANCE.equals(normalized)) {
            return SORT_RELEVANCE;
        }
        // Caller-facing "date" is translated to NCBI's actual key, "pub_date" (see SORT_PUB_DATE):
        // forwarding a literal "date" would be silently ignored by ESearch and yield default order.
        if (SORT_DATE.equals(normalized) || SORT_PUB_DATE.equals(normalized)) {
            return SORT_PUB_DATE;
        }
        log.warn("Ignoring unsupported ESearch sort value [{}]; expected '{}' or '{}'. "
                + "Falling back to PubMed's default order.", sort, SORT_RELEVANCE, SORT_DATE);
        return null;
    }

    /**
     * Recovery handler invoked when {@link #retrieve(String)} exhausts all retry attempts.
     * Re-throws the final exception rather than silently swallowing it so callers still see
     * the failure, but provides a single, well-defined termination point for the retry loop.
     * <p>
     * IT RETHROWS AN <strong>UNCHECKED</strong> EXCEPTION, DELIBERATELY. Spring Retry invokes this
     * method REFLECTIVELY, and {@code ReflectionUtils} wraps any CHECKED exception thrown by a
     * reflectively-invoked method in an {@code UndeclaredThrowableException} — a RuntimeException.
     * Rethrowing the {@code IOException} directly therefore hid it inside a wrapper that
     * {@code @ExceptionHandler(IOException.class)} could not see, the catch-all handler caught it
     * instead, and every threshold refusal was served as <strong>500 "An unexpected error
     * occurred"</strong> rather than the 502 it was carefully mapped to. See
     * {@link PubMedRetrievalException}. Do not turn this back into {@code throw e}.
     */
    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery) {
        log.error("Exhausted retries retrieving PubMed articles for query=[{}].", pubMedQuery, e);
        throw new PubMedRetrievalException(e);
    }

    /**
     * Recovery handler for {@link #retrieve(String, String, Integer)}. Spring Retry selects the
     * {@code @Recover} method whose argument arity matches the failing {@code @Retryable} method,
     * so this overload must exist alongside {@link #recoverRetrieve(IOException, String)}.
     */
    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery, String sort, Integer retmax) {
        log.error("Exhausted retries retrieving PubMed articles for query=[{}], sort=[{}], retmax=[{}].",
                pubMedQuery, sort, retmax, e);
        // UNCHECKED, for the same reason as the two-argument overload above — a checked rethrow from
        // a reflectively-invoked @Recover is wrapped in an UndeclaredThrowableException and lands in
        // the catch-all as a 500. See PubMedRetrievalException.
        throw new PubMedRetrievalException(e);
    }

    public PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {
        return executeESearch(query);
    }

    /**
     * Sort-aware ESearch. Kept separate from {@link #getNumberOfPubMedArticles(String)} so the
     * unsorted path continues to run through the exact same call chain as before.
     */
    public PubmedESearchResult getNumberOfPubMedArticles(String query, String sort) throws IOException {
        return executeESearch(query, sort);
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
        return executeESearch(term, null);
    }

    /**
     * Sort-aware {@link #executeESearch(String)}.
     * <p>
     * The {@code sort} parameter is appended <em>last</em> and only when non-null, so when no sort
     * is requested the form-encoded request body is byte-identical to the one this method emitted
     * before sort support existed. The ReCiter engine is this tool's primary caller and must not
     * see any change in behavior.
     *
     * @param sort already-normalized sort value ({@code relevance}, {@code date}, or {@code null})
     */
    protected PubmedESearchResult executeESearch(String term, String sort) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(term);
        pubmedXmlQuery.setRetStart(0);
        pubmedXmlQuery.setSort(sort);

        // The request is an HTTP POST to ESEARCH_BASE_URL with form-encoded params; log the
        // endpoint actually called (with api_key redacted) and the term, not a discarded GET URL.
        String postUrl;
        if (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty()) {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey();
        } else {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL;
        }
        log.info("ESearch POST url=[{}], term=[{}], sort=[{}]", PubmedXmlQuery.redactApiKey(postUrl), term, sort);

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
        // Appended last and only when present: an absent sort leaves the body byte-identical.
        if (pubmedXmlQuery.getSort() != null && !pubmedXmlQuery.getSort().isEmpty()) {
            params.add(new BasicNameValuePair("sort", pubmedXmlQuery.getSort()));
        }
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
        rateLimiter.acquire();
        try (CloseableHttpResponse response = pubMedHttpClient.execute(httppost)) {
            if (shouldRetryAfterRateLimit(response, query)) {
                // shouldRetryAfterRateLimit has paused the shared limiter for the advertised
                // Retry-After; this acquire() waits it out before replaying the request once.
                rateLimiter.acquire();
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
     * Inspects the NCBI rate-limit response headers. When the remaining quota is exhausted
     * (X-RateLimit-Remaining == 0) and a Retry-After header is present, it pauses the shared
     * {@link NcbiRateLimiter} for the advertised interval — so every in-pod request backs off, not
     * just this thread — and returns {@code true} so the caller replays the request once (its
     * {@link NcbiRateLimiter#acquire()} waits the pause out). Header access is fully null/length
     * guarded because NCBI omits these headers on error pages.
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
                    rateLimiter.pauseFor(Long.parseLong(headerRetryAfter[0].getValue()));
                } catch (NumberFormatException e) {
                    log.warn("Unparseable Retry-After header value [{}] for query=[{}]; skipping pause.",
                            headerRetryAfter[0].getValue(), query);
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

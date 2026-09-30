package reciter.pubmed.retriever;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import reciter.pubmed.ratelimit.NcbiRateLimiter;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

/**
 * Retrieves PubMed articles (ESearch → EFetch) and article counts (ESearch) from NCBI E-utilities.
 *
 * <p><b>Merge note (dev → master).</b> This class combines the two branches:
 * <ul>
 *   <li><b>From master (Java 17 / Boot 3):</b> the JDK {@code java.net.http} client, the eutils host,
 *       and transport-level retry of reset connections ({@link NcbiHttp}); a non-JSON ESearch
 *       response is treated as a <em>failure</em>, not as "0 results" (wcmc-its/ReCiter#689).</li>
 *   <li><b>From dev:</b> the typed, non-retried threshold refusal, {@code @Recover} with an unchecked
 *       rethrow, sort/retmax support, PubMed query-drop detection via {@code errorlist.phrasenotfound}
 *       (Fix #24), a single EFetch instead of a thread-pool fan-out, XXE-hardened SAX parsing, and
 *       api_key redaction in logs.</li>
 * </ul>
 * All ESearch traffic — the retrieval path AND the count endpoint — now goes through
 * {@link #executeESearch(String, String)}, so query-drop detection, throttling and error handling
 * can no longer drift between the two (master had a separate copy in the controller and a third in
 * {@code PubmedESearchHandler}).
 */
@Service
public class PubMedArticleRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(PubMedArticleRetrievalService.class);

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
     * The caller-facing {@code date} is NOT what goes on the wire. NCBI's ESearch sort key for
     * publication date is {@code pub_date}; a literal {@code sort=date} is silently ignored by
     * ESearch, which then returns its default order. So {@code date} is accepted from callers and
     * mapped to {@code pub_date} here.
     */
    private static final String SORT_RELEVANCE = "relevance";
    private static final String SORT_DATE = "date";
    private static final String SORT_PUB_DATE = "pub_date";

    private static final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Shared, pooled NCBI client (see {@code HttpClientConfig}). */
    private final HttpClient pubMedHttpClient;

    /** Per-pod NCBI rate limiter (issue #117); acquired before every ESearch and EFetch attempt. */
    private final NcbiRateLimiter rateLimiter;

    public PubMedArticleRetrievalService(HttpClient pubMedHttpClient, NcbiRateLimiter rateLimiter) {
        this.pubMedHttpClient = pubMedHttpClient;
        this.rateLimiter = rateLimiter;
    }

    // To avoid thread errors - FWK005 parse may not be called while parsing.
    // https://stackoverflow.com/questions/39658247/singleton-thread-safe-sax-parser-instance
    private final ThreadLocal<SAXParserFactory> factoryThreadLocal = ThreadLocal.withInitial(() -> {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // Harden against XXE while still accepting the DOCTYPE that NCBI includes in every
            // EFetch response (<!DOCTYPE PubmedArticleSet PUBLIC ... pubmed_*.dtd>). The DOCTYPE
            // declaration itself is permitted, but all external general/parameter entity and
            // external/remote DTD resolution is disabled (plus secure processing), which closes
            // the XXE vector without rejecting valid PubMed XML. Do NOT set
            // disallow-doctype-decl=true here: it rejects every real EFetch response.
            // (Merged from dev; master's factory had no XXE protection.)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory;
        } catch (Exception e) {
			throw new RuntimeException("Failed to create SAXParserFactory", e);
        }
    });

    public SAXParser getSaxParser() throws ParserConfigurationException, SAXException {
        return factoryThreadLocal.get().newSAXParser();
    }

    /**
     * Retrieves all PubMed articles matching {@code pubMedQuery}.
     * <p>
     * An ESearch determines the matching article count. Every retrieval is satisfied by a single
     * EFetch request — there is no need to paginate over retstart or fan the fetches out across a
     * thread pool (master's bounded pool was dead weight: at most one page was ever produced) —
     * because the number of records fetched is capped at {@link #RETRIEVAL_THRESHOLD} (2,000), well
     * below {@link PubmedXmlQuery#DEFAULT_RETMAX} (10,000), by one of two routes:
     * <ul>
     *   <li>no {@code retmax}: matched == fetched, so a query matching more than the threshold is
     *       hard-refused with a {@link RetrievalThresholdExceededException}. This is the legacy path
     *       the ReCiter engine takes, and it is unchanged.</li>
     *   <li>an explicit {@code retmax} (&le; the threshold): the fetch is bounded by {@code retmax}
     *       regardless of how many articles matched, so the matched-count refusal does not apply.</li>
     * </ul>
     * The refusal message text is matched by {@code GlobalExceptionHandler}'s
     * {@code THRESHOLD_EXCEEDED_MARKER}, so it must not change.
     * <p>
     * THE REFUSAL IS EXCLUDED FROM RETRY ({@code noRetryFor}). It is PERMANENT: the matched count is a
     * property of the query, not of the network, so retrying cannot make it smaller. On master it was
     * a plain {@code IOException} and was retried seven times — seven pointless ESearch calls to NCBI.
     * <p>
     * {@code retryFor} is {@code IOException} only: every transport, throttle, parse and non-JSON
     * failure is surfaced as an {@code IOException} by this class. (Master also listed
     * {@code RuntimeException}, which was only needed to catch the {@code IllegalStateException}
     * its thread-pool futures threw; with a single EFetch that path no longer exists.)
     */
    @Retryable(maxAttempts = 7, retryFor = IOException.class,
            noRetryFor = RetrievalThresholdExceededException.class,
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
    @Retryable(maxAttempts = 7, retryFor = IOException.class,
            noRetryFor = RetrievalThresholdExceededException.class,
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
     * search time plus a {@code retmax} cap at fetch time is what yields "the top N by relevance".
     * <p>
     * When {@code sort} normalizes to {@code null} this calls the one-argument
     * {@link #getNumberOfPubMedArticles(String)} and leaves retmax at its default, so the emitted
     * ESearch request is identical to the pre-sort behavior the ReCiter engine depends on.
     */
    private List<PubMedArticle> doRetrieve(String pubMedQuery, String sort, Integer retmax) throws IOException {

        String normalizedSort = normalizeSort(sort);

        PubmedESearchResult eSearchResult = (normalizedSort == null)
                ? getNumberOfPubMedArticles(pubMedQuery)
                : getNumberOfPubMedArticles(pubMedQuery, normalizedSort);
        int numberOfPubmedArticles = eSearchResult.getCount();

        // The threshold gate bounds the EFETCH, which is the expensive half. It keys on the MATCHED
        // count because, without a retmax, matched == fetched. An explicit retmax breaks that
        // identity: only N records are ever fetched, so "the 50 most relevant of 24,852" is a narrow
        // fetch over a broad search and must not be refused. retmax itself may not exceed the
        // threshold, so no caller can use this to pull back more than RETRIEVAL_THRESHOLD records.
        boolean fetchIsBounded = retmax != null && retmax > 0 && retmax <= RETRIEVAL_THRESHOLD;
        if (!fetchIsBounded && numberOfPubmedArticles > RETRIEVAL_THRESHOLD) {
            // PERMANENT, and typed as such: see RetrievalThresholdExceededException.
            throw new RetrievalThresholdExceededException("Number of PubMed Articles retrieved "
                    + numberOfPubmedArticles + " exceeded the threshold level " + RETRIEVAL_THRESHOLD);
        }

        // No matches: return empty without an EFetch round-trip.
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
            PubMedUriParserCallable callable = new PubMedUriParserCallable(new PubmedEFetchHandler(),
                    getSaxParser(), new InputSource(eFetchUrl), rateLimiter, pubMedHttpClient);
            return new ArrayList<>(callable.call());
        } catch (IOException e) {
            throw e;
        } catch (ParserConfigurationException | SAXException e) {
            log.error("Unable to configure SAX parser / parse EFetch result for url=[{}]", PubmedXmlQuery.redactApiKey(eFetchUrl), e);
            throw new IOException("Failed to parse EFetch result", e);
        } catch (Exception e) {
            log.error("Unable to fetch/parse EFetch result.", e);
            throw new IOException("Failed to fetch/parse EFetch result", e);
        }
    }

    /**
     * Maps a caller-supplied sort value to the literal value to put on the ESearch wire. Only
     * relevance and publication date are supported; everything else is ignored rather than
     * forwarded, because ESearch does not reject an unknown sort key — it silently falls back to
     * default order.
     *
     * @param sort raw caller input (may be null); {@code relevance}, or {@code date} / {@code pub_date}
     * @return {@code relevance}, {@code pub_date}, or {@code null} meaning "send no sort parameter"
     */
    protected static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        String normalized = sort.trim().toLowerCase(Locale.ROOT);
        if (SORT_RELEVANCE.equals(normalized)) {
            return SORT_RELEVANCE;
        }
        if (SORT_DATE.equals(normalized) || SORT_PUB_DATE.equals(normalized)) {
            return SORT_PUB_DATE;
        }
        log.warn("Ignoring unsupported ESearch sort value [{}]; expected '{}' or '{}'. "
                + "Falling back to PubMed's default order.", sort, SORT_RELEVANCE, SORT_DATE);
        return null;
    }

    /**
     * Recovery handler invoked when {@link #retrieve(String)} exhausts all retry attempts (or hits the
     * non-retried threshold refusal).
     * <p>
     * IT RETHROWS AN <strong>UNCHECKED</strong> EXCEPTION, DELIBERATELY. Spring Retry invokes this
     * method reflectively, and a CHECKED exception thrown from it is wrapped in an
     * {@code UndeclaredThrowableException}, which {@code @ExceptionHandler(IOException.class)} cannot
     * see — every failure would then be served as a generic 500. See {@link PubMedRetrievalException}.
     * Do not turn this back into {@code throw e}.
     */
    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery) {
        // The threshold refusal is expected and is logged (WARN) by the listener/handler; only real
        // retry exhaustion deserves an ERROR with a stack trace.
        if (!(e instanceof RetrievalThresholdExceededException)) {
            log.error("Exhausted retries retrieving PubMed articles for query=[{}].", pubMedQuery, e);
        }
        throw new PubMedRetrievalException(e);
    }

    /**
     * Recovery handler for {@link #retrieve(String, String, Integer)}. Spring Retry selects the
     * {@code @Recover} method whose argument list matches the failing {@code @Retryable} method, so
     * this overload must exist alongside {@link #recoverRetrieve(IOException, String)}.
     */
    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery, String sort, Integer retmax) {
        if (!(e instanceof RetrievalThresholdExceededException)) {
            log.error("Exhausted retries retrieving PubMed articles for query=[{}], sort=[{}], retmax=[{}].",
                    pubMedQuery, sort, retmax, e);
        }
        throw new PubMedRetrievalException(e);
    }

    /** ESearch for {@code query} (URL-encoded Entrez term) in PubMed's default order. */
    public PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {
        return executeESearch(query, null);
    }

    /**
     * Sort-aware ESearch. Kept separate from {@link #getNumberOfPubMedArticles(String)} so the
     * unsorted path continues to run through the exact same call chain as before.
     */
    public PubmedESearchResult getNumberOfPubMedArticles(String query, String sort) throws IOException {
        return executeESearch(query, sort);
    }

    /**
     * Executes a single ESearch request against NCBI via HTTP POST (form-encoded; api_key on the URL)
     * and applies query-drop detection (Fix #24).
     * <p>
     * The {@code sort} parameter is appended <em>last</em> and only when non-null, so when no sort is
     * requested the request body is identical to the one emitted before sort support existed.
     * <p>
     * A non-JSON body (an NCBI HTML error / throttle page) is a FAILURE and throws
     * {@link IOException}: it is retried by {@code @Retryable} on the retrieval path and surfaced as a
     * non-2xx on the count endpoint, so ReCiter rolls its retrievalDate watermark back instead of
     * silently skipping the window (wcmc-its/ReCiter#689). This is master's behavior; dev returned a
     * count of 0 here, which silently dropped articles.
     *
     * @param term URL-encoded Entrez query term (as stored in {@link PubmedXmlQuery#getTerm()})
     * @param sort already-normalized sort value ({@code relevance}, {@code pub_date}, or {@code null})
     * @return the parsed {@link PubmedESearchResult}; count is 0 for a dropped (trivial) query
     */
    protected PubmedESearchResult executeESearch(String term, String sort) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(term);
        pubmedXmlQuery.setRetStart(0);
        pubmedXmlQuery.setSort(sort);

        String postUrl = (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty())
                ? PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey()
                : PubmedXmlQuery.ESEARCH_BASE_URL;
        // Log the endpoint actually called with the api_key redacted (master logged it in clear text).
        log.info("ESearch POST url=[{}], term=[{}], sort=[{}]", PubmedXmlQuery.redactApiKey(postUrl), term, sort);

        StringBuilder formData = new StringBuilder()
                .append("db=").append(encode(pubmedXmlQuery.getDb()))
                .append("&retmax=").append(pubmedXmlQuery.getRetMax())
                .append("&usehistory=").append(encode(pubmedXmlQuery.getUseHistory()))
                .append("&term=").append(encode(URLDecoder.decode(pubmedXmlQuery.getTerm(), StandardCharsets.UTF_8)))
                .append("&retmode=").append(encode(pubmedXmlQuery.getRetMode()))
                .append("&retstart=").append(pubmedXmlQuery.getRetStart());
        // Appended last and only when present: an absent sort leaves the body unchanged.
        if (pubmedXmlQuery.getSort() != null && !pubmedXmlQuery.getSort().isEmpty()) {
            formData.append("&sort=").append(encode(pubmedXmlQuery.getSort()));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(postUrl))
                .timeout(NcbiHttp.REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(formData.toString()))
                .build();

        // Rate limiting, Retry-After back-off and connection-reset retry all live in NcbiHttp.
        HttpResponse<InputStream> response =
                NcbiHttp.sendWithRetry(pubMedHttpClient, request, NcbiHttp.DEFAULT_MAX_ATTEMPTS, rateLimiter);

        String responseString;
        try (InputStream body = response.body()) {
            responseString = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
        response.headers().firstValue("X-RateLimit-Remaining")
                .ifPresent(remaining -> log.debug("ESearch X-RateLimit-Remaining=[{}] term=[{}]", remaining, term));

        JsonNode root = responseString.trim().startsWith("{") ? objectMapper.readTree(responseString) : null;
        JsonNode json = (root != null) ? root.get("esearchresult") : null;
        if (json == null) {
            log.error("Unexpected ESearch response (HTTP {}, not esearchresult JSON) for term=[{}] — possibly an HTML error page.",
                    response.statusCode(), term);
            throw new IOException("PubMed eSearch returned a non-JSON/error response (HTTP "
                    + response.statusCode() + ") for term=[" + term + "]");
        }

        // Query-drop detection (Fix #24): only act when PubMed actually reports dropped
        // phrases (errorlist.phrasenotfound) leaving a trivial query; then discard the noise.
        if (isPubMedQueryDropped(json, term)) {
            return new PubmedESearchResult(); // count stays 0
        }

        PubmedESearchResult eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
        log.info("esearchResults Count=[{}]", eSearchResult.getCount());
        return eSearchResult;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Query-drop detection (Fix #24). PubMed silently drops unrecognized name parts
     * (e.g. "Charles-rawlins J[au]" becomes "J[au]"), returning many irrelevant results.
     * Detect this via PubMed's authoritative {@code errorlist.phrasenotfound} signal: only if it
     * reports dropped phrases AND the remaining {@code querytranslation} is trivially short
     * (&lt;= 2 chars after stripping field tags, boolean operators and punctuation) are the results
     * treated as noise. This avoids false positives on legitimately short author queries.
     * <p>
     * Merge note: this replaces master's controller-only {@code isValidAuthorString} heuristic, which
     * inspected only {@code querytranslation} (no {@code phrasenotfound} check) and could zero out
     * genuine short-surname searches; it also only ran on the count endpoint, not on retrieval.
     *
     * @param esearchJson   the "esearchresult" JSON node from PubMed's ESearch response
     * @param originalQuery the original query term (for logging)
     * @return true if the query was dropped and the results should be discarded
     */
    protected static boolean isPubMedQueryDropped(JsonNode esearchJson, String originalQuery) {
        JsonNode phraseNotFound = esearchJson.path("errorlist").path("phrasenotfound");
        if (!phraseNotFound.isArray() || phraseNotFound.isEmpty()) {
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

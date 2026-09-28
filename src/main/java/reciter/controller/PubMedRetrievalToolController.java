package reciter.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import reciter.model.pubmed.PubMedArticle;
import reciter.model.pubmed.PubmedESearchResult;
import reciter.pubmed.NcbiHttp;
import reciter.pubmed.model.PubMedQuery;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;

@RestController
@RequestMapping("/pubmed")
@Tag(name = "PubMedController", description = "Operations on querying the PubMed API.")
public class PubMedRetrievalToolController {
	
	private static final Logger log = LoggerFactory.getLogger(PubMedRetrievalToolController.class);

    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;

    @Operation(summary = "Query with field selection.", description = "Query PubMed with optional field selection")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved list"),
            @ApiResponse(responseCode = "401", description = "You are not authorized to view the resource"),
            @ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(responseCode = "404", description = "The resource you were trying to reach is not found")
    })
    @GetMapping(value = "/query/{query}", produces = "application/json")
    @ResponseBody
    public List<PubMedArticle> query(@PathVariable String query,
                                     @RequestParam(name = "fields", required = false) String fields) throws IOException {
        return retrieve(query, fields);
    }

    @PostMapping("/query-complex/")
    @ResponseBody
    public ResponseEntity<List<PubMedArticle>> queryComplex(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        List<PubMedArticle> pubMedArticles = query(pubMedQuery.toString(), null);
        return ResponseEntity.ok(pubMedArticles);
    }

    @PostMapping("/query-number-pubmed-articles/")
    @ResponseBody
    public int getNumberOfPubMedArticles(@RequestBody PubMedQuery pubMedQuery) throws IOException {
    	

        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(
                URLEncoder.encode(pubMedQuery.toString(), StandardCharsets.UTF_8));
        pubmedXmlQuery.setRetStart(0);

        // Build base URL — api_key in URL, form params in POST body
        String fullUrl = (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty())
                ? PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey()
                : PubmedXmlQuery.ESEARCH_BASE_URL;

        log.info("ESearch Query=[{}]", fullUrl);

        // Build URL-encoded form body
        String formData = "db="         + URLEncoder.encode(pubmedXmlQuery.getDb(), StandardCharsets.UTF_8)
                + "&retmax="     + pubmedXmlQuery.getRetMax()
                + "&usehistory=" + URLEncoder.encode(pubmedXmlQuery.getUseHistory(), StandardCharsets.UTF_8)
                + "&term="       + URLEncoder.encode(
                        java.net.URLDecoder.decode(pubmedXmlQuery.getTerm(), StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
                + "&retmode="    + URLEncoder.encode(pubmedXmlQuery.getRetMode(), StandardCharsets.UTF_8)
                + "&retstart="   + pubmedXmlQuery.getRetStart();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        // NcbiHttp.sendWithRetry calls NcbiRateLimiter.INSTANCE.acquire() before every attempt
        // (including retries) and retries a transient IOException (e.g. a pooled connection NCBI
        // reset underneath us) instead of failing on the first attempt.
        HttpResponse<InputStream> response = NcbiHttp.sendWithRetry(HTTP_CLIENT, request, 4);

        // ── Rate-limit handling — matches original condition exactly ──
        // orElse(-1): absent header → -1 → block never triggers (matches original null/length guard)
        int rateLimitRemaining = (int) response.headers()
                .firstValueAsLong("X-RateLimit-Remaining")
                .orElse(-1L);

        // Log rate-limit headers when both are present (matches original guard)
        response.headers().firstValue("X-RateLimit-Limit").ifPresent(limit ->
                log.info("Query: {} X-RateLimit-Limit: {} X-RateLimit-Remaining: {}",
                        pubMedQuery, limit, rateLimitRemaining));

        if (rateLimitRemaining == 0) {
            // Inner guard: only sleep+retry if Retry-After header is present
            // matches: headerRetryAfter != null && length > 0 && headerRetryAfter[0] != null
            OptionalLong retryAfter = response.headers().firstValueAsLong("Retry-After");
            if (retryAfter.isPresent()) {
                long sleepSeconds = retryAfter.getAsLong();
                log.info("Rate limit hit. Query: {} Retry-After: {} seconds", pubMedQuery, sleepSeconds);
                try {
                    Thread.sleep(sleepSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("InterruptedException during rate-limit pause", ie);
                }
                // Retry only after confirmed sleep — matches original retry placement
                response = NcbiHttp.sendWithRetry(HTTP_CLIENT, request, 4);
            }
        }

        // ── Parse response body ──
        // readAllBytes() replaces IOUtils.copy() + StringWriter — no Apache Commons IO
        String responseString = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        log.info("PubMed eSearch raw response: {}", responseString);

        if (responseString != null
                && !responseString.isBlank()
                && responseString.trim().startsWith("{")
                && OBJECT_MAPPER.readTree(responseString).has("esearchresult")) {

            JsonNode json = OBJECT_MAPPER.readTree(responseString).get("esearchresult");
            log.info("PubMed Response Json: {}", json);

            return resolveESearchCount(json, pubMedQuery.toString());
        }

        // A non-JSON body is an NCBI error/HTML throttle page, not a real "0 results".
        // Surface it (500 to the caller) instead of returning 0, so ReCiter's getNumberOfResults
        // sees the failure and rolls its retrievalDate watermark back rather than silently
        // skipping the window. See wcmc-its/ReCiter#689.
        log.error("Unexpected response (not JSON) — possibly an HTML error page.");
        throw new IOException("PubMed eSearch returned a non-JSON/error response for query=[" + pubMedQuery + "]");
    }

    private List<PubMedArticle> retrieve(String query, String fields) throws IOException {
        query = URLEncoder.encode(query, "UTF-8");
        log.info("Retrieving with query=[{}]", query);

        List<PubMedArticle> pubMedArticles = pubMedArticleRetrievalService.retrieve(query);
        log.info("Retrieved [{}] PubMed articles using query=[{}]", pubMedArticles.size(), query);

        // No field selection requested: return the retrieved articles directly and skip the
        // per-article Squiggly stringify -> readValue round-trip entirely.
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>(pubMedArticles);
        }

        fields = fields.toLowerCase();
        ObjectMapper objectMapper = Squiggly
                .init(new ObjectMapper(), fields)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        List<PubMedArticle> result = new ArrayList<>();
        pubMedArticles.forEach(elem -> {
            String partialObject = SquigglyUtils.stringify(objectMapper, elem);
            try {
                // On a per-item serialization failure, skip the element rather than adding null.
                result.add(objectMapper.readValue(partialObject, PubMedArticle.class));
            } catch (IOException e) {
                log.error("Unable to read value from pmid=[{}]", elem.getMedlinecitation().getMedlinecitationpmid().getPmid(), e);
            }
        });
        return result;
    }
}

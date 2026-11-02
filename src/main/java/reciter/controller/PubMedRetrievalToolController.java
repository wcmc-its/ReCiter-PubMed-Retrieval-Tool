package reciter.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.model.PubMedQuery;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;

/**
 * PubMed query endpoints.
 *
 * <p><b>Merge note:</b> keeps master's Spring Boot 3 annotations ({@code @RestController},
 * {@code @GetMapping}/{@code @PostMapping}) and explicit SLF4J logger, and takes dev's behavior:
 * <ul>
 *   <li>{@code query-complex} honours optional {@code sort}/{@code retmax} from the request body;</li>
 *   <li>{@code query-number-pubmed-articles} delegates to the service instead of carrying its own
 *       copy of the ESearch/HTTP/rate-limit code (master had ~100 duplicated lines here), so
 *       query-drop detection and error handling are identical on both endpoints;</li>
 *   <li>the Squiggly stringify → readValue round-trip is skipped when no {@code fields} filter is
 *       requested, and a per-article serialization failure is skipped instead of adding {@code null}.</li>
 * </ul>
 * Failures propagate to {@link GlobalExceptionHandler}, which maps them to clean JSON 502/500 bodies.
 */
@RestController
@RequestMapping("/pubmed")
@Tag(name = "PubMedController", description = "Operations on querying the PubMed API")
public class PubMedRetrievalToolController {

    private static final Logger log = LoggerFactory.getLogger(PubMedRetrievalToolController.class);

    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;

    @Operation(summary = "Query with field selection.", description = "Query PubMed with optional field selection")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved list"),
            @ApiResponse(responseCode = "401", description = "You are not authorized to view the resource"),
            @ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(responseCode = "404", description = "The resource you were trying to reach is not found"),
            @ApiResponse(responseCode = "502", description = "Query matched too many articles, or PubMed is unavailable")
    })
    @GetMapping(value = "/query/{query}", produces = "application/json")
    public List<PubMedArticle> query(@PathVariable String query,
                                     @RequestParam(name = "fields", required = false) String fields) throws IOException {
        return retrieve(query, fields, null, null);
    }

    /**
     * Optionally accepts {@code sort} ({@code relevance} or {@code date}) and {@code retmax} in the
     * request body, so a caller can ask for a ranked slice — "the top 50 by relevance" — instead of
     * the first N matches in PubMed's default order. Both are optional: a body that omits them
     * produces exactly the request this endpoint made before sort support existed.
     */
    @PostMapping("/query-complex/")
    public ResponseEntity<List<PubMedArticle>> queryComplex(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        List<PubMedArticle> pubMedArticles =
                retrieve(pubMedQuery.toString(), null, pubMedQuery.getSort(), pubMedQuery.getRetmax());
        return ResponseEntity.ok(pubMedArticles);
    }

    /**
     * Returns the number of PubMed articles matching the query. A non-JSON / error response from NCBI
     * is surfaced as a 502 (never as a silent {@code 0}) so ReCiter's getNumberOfResults sees the
     * failure and rolls its retrievalDate watermark back (wcmc-its/ReCiter#689).
     */
    @PostMapping("/query-number-pubmed-articles/")
    public int getNumberOfPubMedArticles(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        String encodedTerm = URLEncoder.encode(pubMedQuery.toString(), StandardCharsets.UTF_8);
        int count = pubMedArticleRetrievalService.getNumberOfPubMedArticles(encodedTerm).getCount();
        log.info("esearchResults Count=[{}] for query=[{}]", count, pubMedQuery);
        return count;
    }

    private List<PubMedArticle> retrieve(String query, String fields, String sort, Integer retmax) throws IOException {
        query = URLEncoder.encode(query, StandardCharsets.UTF_8);
        log.info("Retrieving with query=[{}], sort=[{}], retmax=[{}]", query, sort, retmax);

        // When no sort/retmax is requested, dispatch through the original single-argument service
        // method so the unsorted path — the one the ReCiter engine uses — is provably unchanged.
        List<PubMedArticle> pubMedArticles =
                (sort == null || sort.isEmpty()) && retmax == null
                        ? pubMedArticleRetrievalService.retrieve(query)
                        : pubMedArticleRetrievalService.retrieve(query, sort, retmax);
        log.info("Retrieved [{}] PubMed articles using query=[{}]", pubMedArticles.size(), query);

        // No field selection requested: return the articles directly and skip the per-article
        // Squiggly stringify -> readValue round-trip entirely.
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>(pubMedArticles);
        }

        // Same settings as before, applied through the builder / setDefaultPropertyInclusion because
        // ObjectMapper.configure(MapperFeature, ..) and setSerializationInclusion(..) are deprecated
        // in Jackson 2.21.
        ObjectMapper objectMapper = Squiggly
                .init(JsonMapper.builder()
                        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(), fields.toLowerCase())
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);

        List<PubMedArticle> result = new ArrayList<>();
        for (PubMedArticle elem : pubMedArticles) {
            String partialObject = SquigglyUtils.stringify(objectMapper, elem);
            try {
                // On a per-item serialization failure, skip the element rather than adding null.
                result.add(objectMapper.readValue(partialObject, PubMedArticle.class));
            } catch (IOException e) {
                log.error("Unable to read value from pmid=[{}]",
                        elem.getMedlinecitation().getMedlinecitationpmid().getPmid(), e);
            }
        }
        return result;
    }
}
  
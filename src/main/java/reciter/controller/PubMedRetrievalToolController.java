package reciter.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.model.PubMedQuery;
import reciter.pubmed.model.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;

@RestController
@RequestMapping("/pubmed")
@Tag(name = "PubMedController", description = "Operations on querying the PubMed API")
public class PubMedRetrievalToolController {
	
	private static final Logger log = LoggerFactory.getLogger(PubMedRetrievalToolController.class);
    
	 // ── Compiled once — Pattern is thread-safe and expensive to compile ──
    private static final Pattern BRACKET_PATTERN   = Pattern.compile("\\[(.*?)\\]");
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    // ── Shared HttpClient — reuses connection pool across all calls ──
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    
    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;
    

    @Operation(summary = "Query with field selection.", description = "Query PubMed with optional field selection")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description  = "Successfully retrieved list"),
            @ApiResponse(responseCode = "401", description = "You are not authorized to view the resource"),
            @ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(responseCode = "404", description = "The resource you were trying to reach is not found")
    })
    @GetMapping(value = "/query/{query}", produces = "application/json")
    public List<PubMedArticle> query(@PathVariable String query,
                                     @RequestParam(required = false) String fields) throws IOException {
        return retrieve(query, fields);
    }

    @PostMapping("/query-complex/")
    public ResponseEntity<List<PubMedArticle>> queryComplex(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        List<PubMedArticle> pubMedArticles = query(pubMedQuery.toString(), null);
        return ResponseEntity.ok(pubMedArticles);
    }


    @PostMapping("/query-number-pubmed-articles/")
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

        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

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
                    response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
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

            log.error("Unexpected response (not JSON) — possibly an HTML error page.");
            return 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted for query=[" + pubMedQuery + "]", e);
        }
    }

    private int resolveESearchCount(JsonNode json, String queryForLog) throws IOException {
        PubmedESearchResult eSearchResult = new PubmedESearchResult();

        String queryTranslation = json.path("querytranslation").asText();
        log.info("Query translation prior to processing: {}", queryTranslation);

        if (isValidAuthorString(queryTranslation)) {
            log.info("Entering firstNameInitial strategy query processing");

            // Split by [Author], strip connectors and whitespace, filter empty
            List<String> segments = Arrays.stream(queryTranslation.split("\\[Author\\]"))
                    .map(String::trim)
                    .map(part -> part.replaceAll("\\b(AND|OR)\\b", ""))
                    .map(part -> part.replaceAll("\\s", ""))
                    .filter(part -> !part.isEmpty())
                    .collect(Collectors.toList());

            // At least one segment must be longer than 2 characters
            boolean hasValidSegment = segments.stream().anyMatch(s -> s.length() > 2);

            if (!hasValidSegment) {
                log.error("No first-name initial found with more than 2 letters — ignoring {} records from PubMed",
                        eSearchResult.getCount());
                return 0;
            }

            eSearchResult = OBJECT_MAPPER.treeToValue(json, PubmedESearchResult.class);
            log.info("eSearchResult count for firstNameInitial strategy: {}", eSearchResult.getCount());

        } else {
            eSearchResult = OBJECT_MAPPER.treeToValue(json, PubmedESearchResult.class);
        }

        log.info("esearchResult count: {}", eSearchResult.getCount());
        return eSearchResult.getCount();
    }
    
    private List<PubMedArticle> retrieve(String query, String fields) throws IOException {
    	query = URLEncoder.encode(query, StandardCharsets.UTF_8);
    	log.info("Retrieving with query=[{}]", query);
        if (fields != null && !fields.isEmpty()) {
            fields = fields.toLowerCase();
        }
        
        ObjectMapper objectMapper = Squiggly
                .init(new ObjectMapper(), fields)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        List<PubMedArticle> result = new ArrayList<>();
        List<PubMedArticle> pubMedArticles = pubMedArticleRetrievalService.retrieve(query);
        pubMedArticles.forEach(elem -> {
            String partialObject = SquigglyUtils.stringify(objectMapper, elem);
            PubMedArticle pubMedArticle = null;
            try {
                pubMedArticle = objectMapper.readValue(partialObject, PubMedArticle.class);
            } catch (IOException e) {
            	 log.error("Unable to read value from pmid={}",elem.getMedlinecitation().getMedlinecitationpmid().getPmid(), e);
            }
            result.add(pubMedArticle);
        });
        log.info("retrieved " + pubMedArticles.size() + " PubMed articles using query=[" + query + "]");
        return result;
    }
    private static boolean isValidAuthorString(String input) {
        // Regular expression to match anything inside square brackets
    	Matcher matcher = BRACKET_PATTERN.matcher(input);        
    	boolean containsAuthor = false;

        while (matcher.find()) {
            String matchedContent = matcher.group(1).trim(); // Get the content inside []

            // Check if the content is neither empty nor anything other than "Author"
            if (!"Author".equals(matchedContent) && !"All Fields".equals(matchedContent)) {
                return false; // Found something invalid inside []
            }
            containsAuthor = true; // We found [Author]
        }

        // Return true if at least one [Author] exists, and no other value inside []
        return containsAuthor;
    }
}

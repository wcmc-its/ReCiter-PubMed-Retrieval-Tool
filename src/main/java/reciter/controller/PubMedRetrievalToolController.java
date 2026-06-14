package reciter.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.model.PubMedQuery;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;

@Slf4j
@Controller
@RequestMapping("/pubmed")
@Tag(name = "PubMed Controller", description = "Operations on querying the PubMed API.")
public class PubMedRetrievalToolController {

    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;

    @Operation(summary = "Query with field selection.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved list"),
            @ApiResponse(responseCode = "401", description = "You are not authorized to view the resource"),
            @ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(responseCode = "404", description = "The resource you were trying to reach is not found")
    })
    @RequestMapping(value = "/query/{query}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public List<PubMedArticle> query(@PathVariable String query,
                                     @RequestParam(name = "fields", required = false) String fields) throws IOException {
        return retrieve(query, fields);
    }

    @RequestMapping(value = "/query-complex/", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<List<PubMedArticle>> queryComplex(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        List<PubMedArticle> pubMedArticles = query(pubMedQuery.toString(), null);
        return ResponseEntity.ok(pubMedArticles);
    }

    /*@RequestMapping(value = "/query-doi/", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<List<PubMedArticle>> queryDoi(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        List<PubMedArticle> pubMedArticles = query(pubMedQuery.getDoi(), null);
        return ResponseEntity.ok(pubMedArticles);
    }*/

    @RequestMapping(value = "/query-number-pubmed-articles/", method = RequestMethod.POST)
    @ResponseBody
    public int getNumberOfPubMedArticles(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        // Delegate to the shared ESearch helper so query-drop detection, rate-limit handling,
        // and HTTP/timeout behavior are identical to the article-retrieval path.
        String encodedTerm = URLEncoder.encode(pubMedQuery.toString(), "UTF-8");
        int count = pubMedArticleRetrievalService.getNumberOfPubMedArticles(encodedTerm).getCount();
        log.info("esearchResults Count=[{}]", count);
        return count;
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

package reciter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/pubmed")
public class PubMedRetrievalToolController {

    private static final Logger slf4jLogger = LoggerFactory.getLogger(PubMedRetrievalToolController.class);

    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;

    @RequestMapping(value = "/query/{query}", method = RequestMethod.GET)
    @ResponseBody
    public List<PubMedArticle> query(@PathVariable String query,
                                     @RequestParam(name = "fields", required = false) String fields) throws IOException {
        return retrieve(query, fields);
    }

    private List<PubMedArticle> retrieve(String query, String fields) throws IOException {
        query = URLEncoder.encode(query, "UTF-8");
        slf4jLogger.info("Retrieving with query=[" + query + "]");
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
                slf4jLogger.error("Unable to read value from pmid=" + elem.getMedlinecitation().getMedlinecitationpmid().getPmid(), e);
            }
            result.add(pubMedArticle);
        });
        slf4jLogger.info("retrieved " + pubMedArticles.size() + " PubMed articles using query=[" + query + "]");
        return result;
    }
}

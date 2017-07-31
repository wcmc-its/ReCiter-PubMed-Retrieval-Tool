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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.model.PubMedQuery;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;
import reciter.pubmed.xmlparser.PubmedESearchHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
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

    @RequestMapping(value = "/query-complex/", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<List<PubMedArticle>> queryComplex(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        slf4jLogger.info("Querying: " + pubMedQuery);
        List<PubMedArticle> pubMedArticles = query(pubMedQuery.toString(), null);
        return ResponseEntity.ok(pubMedArticles);
    }

    @RequestMapping(value = "/query-doi/", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<List<PubMedArticle>> queryDoi(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        List<PubMedArticle> pubMedArticles = query(pubMedQuery.getDoi(), null);
        return ResponseEntity.ok(pubMedArticles);
    }

    @RequestMapping(value = "/query-number-pubmed-articles/", method = RequestMethod.POST)
    @ResponseBody
    public int getNumberOfPubMedArticles(@RequestBody PubMedQuery pubMedQuery) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(URLEncoder.encode(pubMedQuery.toString(), "UTF-8"));
        pubmedXmlQuery.setRetMax(1);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        slf4jLogger.info("ESearch Query=[" + fullUrl + "]");

        PubmedESearchHandler pubmedESearchHandler = new PubmedESearchHandler();
        InputStream esearchStream = new URL(fullUrl).openStream();

        try {
            SAXParserFactory.newInstance().newSAXParser().parse(esearchStream, pubmedESearchHandler);
        } catch (SAXException | ParserConfigurationException e) {
            slf4jLogger.error("Error parsing XML file for query=[" + pubMedQuery + "], full url=[" + fullUrl + "]", e);
        }
        return pubmedESearchHandler.getCount();
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

package reciter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
@RequestMapping("/pubmed")
@Api(value = "PubMedController", description = "Operations on querying the PubMed API.")
public class PubMedRetrievalToolController {

    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;

    @ApiOperation(value = "Query with field selection.", response = List.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successfully retrieved list"),
            @ApiResponse(code = 401, message = "You are not authorized to view the resource"),
            @ApiResponse(code = 403, message = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(code = 404, message = "The resource you were trying to reach is not found")
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
    	
    	try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			log.error("InterruptedException", e);
		}
    	
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(URLEncoder.encode(pubMedQuery.toString(), "UTF-8"));
        //pubmedXmlQuery.setRetMax(1);
        pubmedXmlQuery.setRetStart(0);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        log.info("ESearch Query=[" + fullUrl + "]");
        PubmedESearchHandler pubmedESearchHandler = new PubmedESearchHandler();
        HttpClient httpClient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(PubmedXmlQuery.ESEARCH_BASE_URL);
        //httppost.setHeader(HttpHeaders.ACCEPT, "application/xml");
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
        httppost.getParams().setParameter(ClientPNames.COOKIE_POLICY, "standard");

        //Execute and get the response.
        HttpResponse response = httpClient.execute(httppost);
        HttpEntity entity = response.getEntity();
        if (entity != null) {
            InputStream esearchStream = entity.getContent();
            try {
                SAXParserFactory.newInstance().newSAXParser().parse(esearchStream, pubmedESearchHandler);
            } catch (SAXException | ParserConfigurationException e) {
                log.error("Error parsing XML file for query=[" + pubMedQuery + "], full url=[" + fullUrl + "]", e);
            }
        }

        return pubmedESearchHandler.getCount();
    }

    private List<PubMedArticle> retrieve(String query, String fields) throws IOException {
        query = URLEncoder.encode(query, "UTF-8");
        log.info("Retrieving with query=[" + query + "]");
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
                log.error("Unable to read value from pmid=" + elem.getMedlinecitation().getMedlinecitationpmid().getPmid(), e);
            }
            result.add(pubMedArticle);
        });
        log.info("retrieved " + pubMedArticles.size() + " PubMed articles using query=[" + query + "]");
        return result;
    }
}

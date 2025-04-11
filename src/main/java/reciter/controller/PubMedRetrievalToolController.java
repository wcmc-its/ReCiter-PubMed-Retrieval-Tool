package reciter.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import org.apache.http.Header;
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
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.model.PubMedQuery;
import reciter.pubmed.model.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;

@Slf4j
@Controller
@RequestMapping("/pubmed")
@Tag(name = "PubMedController", description = "Operations on querying the PubMed API")
public class PubMedRetrievalToolController {

    @Autowired
    private PubMedArticleRetrievalService pubMedArticleRetrievalService;
    
    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Operation(summary = "Query with field selection.", description = "Query PubMed with optional field selection")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description  = "Successfully retrieved list"),
            @ApiResponse(responseCode = "401", description = "You are not authorized to view the resource"),
            @ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(responseCode = "404", description = "The resource you were trying to reach is not found")
    })
    @GetMapping(value = "/query/{query}", produces = "application/json")
    @ResponseBody
    public List<PubMedArticle> query(@PathVariable String query,
                                     @RequestParam(required = false) String fields) throws IOException {
        return retrieve(query, fields);
    }

    @PostMapping("/query-complex/")
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

    @PostMapping("/query-number-pubmed-articles/")
    @ResponseBody
    public int getNumberOfPubMedArticles(@RequestBody PubMedQuery pubMedQuery) throws IOException {
    	
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(URLEncoder.encode(pubMedQuery.toString(), "UTF-8"));
        //pubmedXmlQuery.setRetMax(1);
        pubmedXmlQuery.setRetStart(0);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        log.info("ESearch Query=[" + fullUrl + "]");
        //PubmedESearchHandler pubmedESearchHandler = new PubmedESearchHandler();
        PubmedESearchResult eSearchResult = new PubmedESearchResult();
        HttpClient httpClient = HttpClients.createDefault();
        if(pubmedXmlQuery.getApiKey() != null &&
       		  !pubmedXmlQuery.getApiKey().isEmpty()) {
        	fullUrl = PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey();
        } else {
        	fullUrl = PubmedXmlQuery.ESEARCH_BASE_URL;
        }
        
        HttpPost httppost = new HttpPost(fullUrl);
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
        httppost.setHeader("cache-control", "no-cache");

        //Execute and get the response.
        
        HttpResponse response = httpClient.execute(httppost);
        
        Header[] headerRateLimitRemaining = response.getHeaders("X-RateLimit-Remaining");
        Header[] headerRateLimit = response.getHeaders("X-RateLimit-Limit");
        Header[] headerRetryAfter = response.getHeaders("Retry-After");
        
        log.info("Query : " + pubMedQuery.toString()  + " " + headerRateLimit[0].toString() + " " + headerRateLimitRemaining[0].toString());
        
        if(headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null && Integer.parseInt(headerRateLimitRemaining[0].getValue()) == 0) {
        	if(headerRetryAfter != null && headerRetryAfter.length > 0 && headerRetryAfter[0] != null) {
        		log.info("Query : " + pubMedQuery.toString()  + " " + headerRetryAfter[0].toString());
        		try {
        			Thread.sleep(Long.parseLong(headerRetryAfter[0].getValue()) * 1000L);
        		} catch (InterruptedException e) {
        			log.error("InterruptedException", e);
        		}
        		response = httpClient.execute(httppost);
        	}
        }
		/*
		 * for (Header header : headers) {
		 * if(header.getName().equalsIgnoreCase("X-RateLimit-Limit") ||
		 * header.getName().equalsIgnoreCase("X-RateLimit-Remaining") ||
		 * header.getName().equalsIgnoreCase("Retry-After")) { log.info("Key : " +
		 * header.getName() + " ,Value : " + header.getValue()); } }
		 */
        HttpEntity entity = response.getEntity();
        if (entity != null) {
            InputStream esearchStream = entity.getContent();
			/*
			 * StringWriter writer = new StringWriter(); IOUtils.copy(esearchStream, writer,
			 * "UTF-8"); log.info(writer.toString());
			 */
            //SAXParserFactory.newInstance().newSAXParser().parse(esearchStream, pubmedESearchHandler);
            JsonNode json = objectMapper.readTree(esearchStream).get("esearchresult");
            log.info("PubMed Response Json:",json);

            // Extract the "querytranslation" field
	         String queryTranslation = json.path("querytranslation").asText();
	         log.info("query translation prior to processing:",queryTranslation);
	         // Check if the string contains [Affiliation], if it does, stop processing
	         if (isValidAuthorString(queryTranslation)) {
	        	 log.info("Entered into process firstNameInitial stragey query");
	        	 
	             // Split the string by [Author] and process each part
	             List<String> segments = //Arrays.stream(queryTranslation.split("(?<=\\[Author\\])")) // Split the string by [Author]
		            		 			 Arrays.stream(queryTranslation.split("\\[Author\\]")) // Split by [Author]
		                                       .map(String::trim)  // Trim spaces around each part
	                                           .map(part -> part.replaceAll("\\b(AND|OR)\\b", ""))  // Remove "AND" and "OR"
	                                           .map(part -> part.replaceAll("\\s", ""))  // Remove all spaces
	                                           .filter(part -> !part.isEmpty())  // Filter out empty parts
	                                           .collect(Collectors.toList());
	         
	             // Flag to check if we found a valid segment (length > 2)
	             boolean isValidSegmentFound = false;

	             // Iterate over each segment
	             for (String part : segments) {
	                 if (part.length() > 2) {
	                     isValidSegmentFound = true; // We found a valid segment
	                     break;  // Once we find a valid part, we stop further checks and proceed
	                 }
	             }
	             
	             
	             // After the loop, if no valid segment was found, throw an exception
	             if (!isValidSegmentFound) {
	            	 log.error("No First Name initial found with more than 2 letters.Hence ignoring the " + eSearchResult.getCount() + "records returned from the PubMed");
	            	 eSearchResult.setCount(0);
	                 
	             }
	             else
	             {
	            	 if(json != null) {
		     				eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
		     				log.info("eSearchResult count for the firstNameInitial strategy :",eSearchResult.getCount());
		     			} 
	             }
	         }
	         else
	         {	 
				if(json !=  null) {
					eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
				}
	         }
        }

        return eSearchResult.getCount();
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
    private static boolean isValidAuthorString(String input) {
        // Regular expression to match anything inside square brackets
        Pattern pattern = Pattern.compile("\\[(.*?)\\]");
        
        Matcher matcher = pattern.matcher(input);
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

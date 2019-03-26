package reciter.pubmed.retriever;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
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
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.model.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;
import reciter.pubmed.xmlparser.PubmedESearchHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PubMedArticleRetrievalService {

    private static final int RETRIEVAL_THRESHOLD = 2000;
    
    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
                return SAXParserFactory.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
   };
   
   public SAXParser getSaxParser() throws ParserConfigurationException, SAXException {
	   return factoryThreadLocal.get().newSAXParser();
   }

    /**
     * Initializes and starts threads that handles the retrieval process. Partition the number of articles
     * into manageable pieces and ask each thread to handle one partition.
     */
    public List<PubMedArticle> retrieve(String pubMedQuery) throws IOException {
    	
    	PubmedESearchResult eSearchResult = new PubmedESearchResult();
    	eSearchResult = getNumberOfPubMedArticles(pubMedQuery);

        int numberOfPubmedArticles = eSearchResult.getCount();//getNumberOfPubMedArticles(pubMedQuery);
        List<PubMedArticle> pubMedArticles = new ArrayList<>();

        if (numberOfPubmedArticles <= RETRIEVAL_THRESHOLD) {
            ExecutorService executor = Executors.newWorkStealingPool();
        	//ScheduledExecutorService executor = (ScheduledExecutorService) Executors.newScheduledThreadPool(10);
        	

            // Get the count (number of publications for this query).
            PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery();
            pubmedXmlQuery.setTerm(pubMedQuery);

            log.info("retMax=[{}], pubMedQuery=[{}], numberOfPubmedArticles=[{}].",
                    pubmedXmlQuery.getRetMax(), pubMedQuery, numberOfPubmedArticles);

            // Retrieve the publications retMax records at one time and store to disk.
            int currentRetStart = 0;

            List<Callable<List<PubMedArticle>>> callables = new ArrayList<>();

            // Use the retstart value to iteratively fetch all XMLs.
            while (numberOfPubmedArticles > 0) {
                // Get webenv value.
                pubmedXmlQuery.setRetStart(currentRetStart);
                //String eSearchUrl = pubmedXmlQuery.buildESearchQuery();

                //pubmedXmlQuery.setWebEnv(PubmedESearchHandler.executeESearchQuery(pubmedXmlQuery.getTerm()).getWebenv());
                if(eSearchResult.getWebenv() != null) {
                	pubmedXmlQuery.setWebEnv(eSearchResult.getWebenv());
                }

                // Use the webenv value to retrieve xml.
                String eFetchUrl = pubmedXmlQuery.buildEFetchQuery();
                log.info("eFetchUrl=[{}].", eFetchUrl);

                try {
					callables.add(new PubMedUriParserCallable(new PubmedEFetchHandler(), getSaxParser(), new InputSource(eFetchUrl)));
				} catch (ParserConfigurationException | SAXException e) {
					log.error("Exception", e);
				}

                // Update the retstart value.
                currentRetStart += pubmedXmlQuery.getRetMax();
                pubmedXmlQuery.setRetStart(currentRetStart);
                numberOfPubmedArticles -= pubmedXmlQuery.getRetMax();
            }
            
			
			/*
			 * for(Callable<List<PubMedArticle>> callable: callables) {
			 * executor.schedule(callable, 5, TimeUnit.SECONDS); }
			 */
			 

            try {
                executor.invokeAll(callables)
                        .stream()
                        .map(future -> {
                            try {
                                return future.get();
                            } catch (Exception e) {
                                log.error("Unable to retrieve result using future get.");
                                throw new IllegalStateException(e);
                            }
                        }).forEach(pubMedArticles::addAll);
            } catch (InterruptedException e) {
                log.error("Unable to invoke callable.", e);
            }
        } else {
            throw new IOException("Number of PubMed Articles retrieved " + numberOfPubmedArticles + " exceeded the threshold level " + RETRIEVAL_THRESHOLD);
        }
        return pubMedArticles;
    }

    protected PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(query);
        //pubmedXmlQuery.setRetMax(1);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        log.info("ESearch Query=[{}]", fullUrl);

        PubmedESearchHandler pubmedESearchHandler = new PubmedESearchHandler();
        PubmedESearchResult eSearchResult = new PubmedESearchResult();
        //InputStream esearchStream = new URL(fullUrl).openStream();

        HttpClient httpClient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(PubmedXmlQuery.ESEARCH_BASE_URL);
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
        
        Header[] headerRateLimitRemaining = response.getHeaders("X-RateLimit-Remaining");
        Header[] headerRateLimit = response.getHeaders("X-RateLimit-Limit");
        Header[] headerRetryAfter = response.getHeaders("Retry-After");
        
        log.info("Query : " + query  + " " + headerRateLimit[0].toString() + " " + headerRateLimitRemaining[0].toString());
        
        if(headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null && Integer.parseInt(headerRateLimitRemaining[0].getValue()) == 0) {
        	if(headerRetryAfter != null && headerRetryAfter.length > 0 && headerRetryAfter[0] != null) {
        		log.info("Query : " + query  + " " + headerRetryAfter[0].toString());
        		try {
        			Thread.sleep(Long.parseLong(headerRetryAfter[0].getValue()) * 1000L);
        		} catch (InterruptedException e) {
        			log.error("InterruptedException", e);
        		}
        		response = httpClient.execute(httppost);
        	}
        }
        
		/*
		 * Header[] headers = response.getAllHeaders(); for (Header header : headers) {
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
			if(json != null) {
				eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
			}
        }
        return eSearchResult;
    }
}

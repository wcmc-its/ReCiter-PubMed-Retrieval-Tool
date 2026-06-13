package reciter.pubmed.retriever;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.extern.slf4j.Slf4j;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.model.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

@Slf4j
@Service
public class PubMedArticleRetrievalService {

    private static final int RETRIEVAL_THRESHOLD = 2000;

    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private CloseableHttpClient pubMedHttpClient;

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
                SAXParserFactory factory = SAXParserFactory.newInstance();
                // Harden against XXE: disallow DOCTYPE declarations and disable external
                // entity/DTD resolution. This is defense-in-depth for an XML-parsing service.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                return factory;
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
    @Retryable(maxAttempts = 7, value = IOException.class,
        backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
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
            /*Retryer<List<PubMedArticle>> retryer = RetryerBuilder.<List<PubMedArticle>>newBuilder()
                    .retryIfResult(Predicates.<List<PubMedArticle>>isNull())
                    .retryIfExceptionOfType(IOException.class)
                    .retryIfRuntimeException()
                    .withWaitStrategy(WaitStrategies.fibonacciWait(100L, 15L, TimeUnit.SECONDS))
                    .withStopStrategy(StopStrategies.stopAfterAttempt(15))
                    .build();*/

            //List<RetryerCallable<List<PubMedArticle>>> callables = new ArrayList<RetryerCallable<List<PubMedArticle>>>();
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
                log.info("eFetchUrl=[{}].", PubmedXmlQuery.redactApiKey(eFetchUrl));



                try {
                	//PubMedUriParserCallable callable = new PubMedUriParserCallable(new PubmedEFetchHandler(), getSaxParser(), new InputSource(eFetchUrl));
                	//RetryerCallable<List<PubMedArticle>> retryerCallable = retryer.wrap(callable);
                	//callables.add(retryerCallable);
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
                List<java.util.concurrent.Future<List<PubMedArticle>>> futures = executor.invokeAll(callables);
                for (java.util.concurrent.Future<List<PubMedArticle>> future : futures) {
                    try {
                        pubMedArticles.addAll(future.get());
                    } catch (ExecutionException e) {
                        log.error("Unable to retrieve result using future get.");
                        Throwable cause = e.getCause();
                        if (cause instanceof IOException) {
                            throw (IOException) cause;
                        }
                        throw new IOException("Failed to fetch/parse EFetch result", cause);
                    }
                }
            } catch (InterruptedException e) {
                log.error("Unable to invoke callable.", e);
                Thread.currentThread().interrupt();
            } finally {
                executor.shutdownNow();
            }
        } else {
            throw new IOException("Number of PubMed Articles retrieved " + numberOfPubmedArticles + " exceeded the threshold level " + RETRIEVAL_THRESHOLD);
        }
        return pubMedArticles;
    }

    /**
     * Recovery handler invoked when {@link #retrieve(String)} exhausts all retry attempts.
     * Re-throws the final exception rather than silently swallowing it so callers still see
     * the failure, but provides a single, well-defined termination point for the retry loop.
     */
    @Recover
    public List<PubMedArticle> recoverRetrieve(IOException e, String pubMedQuery) throws IOException {
        log.error("Exhausted retries retrieving PubMed articles for query=[{}].", pubMedQuery, e);
        throw e;
    }

    public PubmedESearchResult getNumberOfPubMedArticles(String query) throws IOException {
        return executeESearch(query);
    }

    /**
     * Executes a single ESearch request against NCBI via HTTP POST. Handles rate-limit headers
     * (sleeping and retrying once when the remaining quota is exhausted) and applies the
     * query-drop detection (Fix #24): when PubMed silently drops a name part leaving a trivial
     * author query, the returned count is zeroed so dropped-term queries are neutralized on
     * every code path that performs an ESearch (both the count endpoint and the retrieval path).
     *
     * @param term URL-encoded Entrez query term (as stored in {@link PubmedXmlQuery#getTerm()})
     * @return the parsed {@link PubmedESearchResult}; count is 0 for trivial/dropped queries or
     *         non-JSON (e.g. HTML error page) responses.
     */
    protected PubmedESearchResult executeESearch(String term) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(term);
        pubmedXmlQuery.setRetStart(0);
        log.info("ESearch Query=[{}]", PubmedXmlQuery.redactApiKey(pubmedXmlQuery.buildESearchQuery()));

        String postUrl;
        if (pubmedXmlQuery.getApiKey() != null && !pubmedXmlQuery.getApiKey().isEmpty()) {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL + "?api_key=" + pubmedXmlQuery.getApiKey();
        } else {
            postUrl = PubmedXmlQuery.ESEARCH_BASE_URL;
        }

        PubmedESearchResult eSearchResult = new PubmedESearchResult();

        HttpPost httppost = new HttpPost(postUrl);
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
        httppost.setHeader("cache-control", "no-cache");

        String responseString = executeReadingBody(httppost, term);

        if (responseString == null || responseString.trim().isEmpty()
                || !responseString.trim().startsWith("{")
                || !objectMapper.readTree(responseString).has("esearchresult")) {
            log.error("Unexpected response (not JSON), possibly an HTML error page.");
            return eSearchResult;
        }

        JsonNode json = objectMapper.readTree(responseString).get("esearchresult");
        if (json == null) {
            return eSearchResult;
        }

        // Extract the "querytranslation" field and apply query-drop detection.
        String queryTranslation = json.path("querytranslation").asText();
        if (isValidAuthorString(queryTranslation)) {
            log.info("Entered into process firstNameInitial strategy query");
            // Split the string by [Author] and process each part.
            List<String> segments = Arrays.stream(queryTranslation.split("\\[Author\\]"))
                    .map(String::trim)
                    .map(part -> part.replaceAll("\\b(AND|OR)\\b", ""))
                    .map(part -> part.replaceAll("\\s", ""))
                    .filter(part -> !part.isEmpty())
                    .collect(Collectors.toList());

            boolean isValidSegmentFound = false;
            for (String part : segments) {
                if (part.length() > 2) {
                    isValidSegmentFound = true;
                    break;
                }
            }

            if (!isValidSegmentFound) {
                log.error("No First Name initial found with more than 2 letters. Hence ignoring the records returned from PubMed for query=[{}].", term);
                eSearchResult.setCount(0);
                return eSearchResult;
            }
        }

        eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
        log.info("esearchResults Count :" + eSearchResult.getCount());
        return eSearchResult;
    }

    /**
     * Executes the ESearch POST and returns the response body. When the NCBI rate-limit quota is
     * exhausted (X-RateLimit-Remaining == 0) and a Retry-After header is present, sleeps for the
     * advertised interval and replays the request once, returning the replayed response body.
     * Response entities are always closed via try-with-resources.
     */
    private String executeReadingBody(HttpPost httppost, String query) throws IOException {
        try (CloseableHttpResponse response = pubMedHttpClient.execute(httppost)) {
            if (shouldRetryAfterRateLimit(response, query)) {
                try (CloseableHttpResponse retryResponse = pubMedHttpClient.execute(httppost)) {
                    return readBody(retryResponse);
                }
            }
            return readBody(response);
        }
    }

    private static String readBody(CloseableHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        try (InputStream esearchStream = entity.getContent()) {
            IOUtils.copy(esearchStream, writer, "UTF-8");
        }
        return writer.toString();
    }

    /**
     * Inspects the NCBI rate-limit response headers. Returns {@code true} (after sleeping for the
     * advertised Retry-After interval) when the remaining quota is exhausted and the caller should
     * replay the request. Header access is fully null/length guarded because NCBI omits these
     * headers on error pages.
     */
    private boolean shouldRetryAfterRateLimit(CloseableHttpResponse response, String query) {
        Header[] headerRateLimitRemaining = response.getHeaders("X-RateLimit-Remaining");
        Header[] headerRateLimit = response.getHeaders("X-RateLimit-Limit");
        Header[] headerRetryAfter = response.getHeaders("Retry-After");

        if (headerRateLimit != null && headerRateLimit.length > 0 && headerRateLimit[0] != null
                && headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null) {
            log.info("Query : " + query + " " + headerRateLimit[0].toString() + " " + headerRateLimitRemaining[0].toString());
        }

        if (headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null
                && Integer.parseInt(headerRateLimitRemaining[0].getValue()) == 0) {
            if (headerRetryAfter != null && headerRetryAfter.length > 0 && headerRetryAfter[0] != null) {
                log.info("Query : " + query + " " + headerRetryAfter[0].toString());
                try {
                    Thread.sleep(Long.parseLong(headerRetryAfter[0].getValue()) * 1000L);
                } catch (InterruptedException e) {
                    log.error("InterruptedException", e);
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isValidAuthorString(String input) {
        // Regular expression to match anything inside square brackets.
        Pattern pattern = Pattern.compile("\\[(.*?)\\]");
        Matcher matcher = pattern.matcher(input);
        boolean containsAuthor = false;

        while (matcher.find()) {
            String matchedContent = matcher.group(1).trim(); // Get the content inside [].
            // Check if the content is neither empty nor anything other than "Author".
            if (!"Author".equals(matchedContent) && !"All Fields".equals(matchedContent)) {
                return false; // Found something invalid inside [].
            }
            containsAuthor = true; // We found [Author].
        }

        // Return true if at least one [Author] exists, and no other value inside [].
        return containsAuthor;
    }
}

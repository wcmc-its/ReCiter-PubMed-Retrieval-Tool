package reciter.pubmed.retriever;

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
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.querybuilder.PubmedXmlQuery;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;
import reciter.pubmed.xmlparser.PubmedESearchHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class PubMedArticleRetrievalService {

    private static final int RETRIEVAL_THRESHOLD = 2000;

    /**
     * Initializes and starts threads that handles the retrieval process. Partition the number of articles
     * into manageable pieces and ask each thread to handle one partition.
     */
    public List<PubMedArticle> retrieve(String pubMedQuery) throws IOException {

        int numberOfPubmedArticles = getNumberOfPubMedArticles(pubMedQuery);
        List<PubMedArticle> pubMedArticles = new ArrayList<>();

        if (numberOfPubmedArticles <= RETRIEVAL_THRESHOLD) {
            ExecutorService executor = Executors.newWorkStealingPool();

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
                String eSearchUrl = pubmedXmlQuery.buildESearchQuery();

                pubmedXmlQuery.setWebEnv(PubmedESearchHandler.executeESearchQuery(pubmedXmlQuery.getTerm()).getWebEnv());

                // Use the webenv value to retrieve xml.
                String eFetchUrl = pubmedXmlQuery.buildEFetchQuery();
                log.info("eFetchUrl=[{}].", eFetchUrl);

                callables.add(new PubMedUriParserCallable(new PubmedEFetchHandler(), eFetchUrl));

                // Update the retstart value.
                currentRetStart += pubmedXmlQuery.getRetMax();
                pubmedXmlQuery.setRetStart(currentRetStart);
                numberOfPubmedArticles -= pubmedXmlQuery.getRetMax();
            }

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

    protected int getNumberOfPubMedArticles(String query) throws IOException {
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(query);
        //pubmedXmlQuery.setRetMax(1);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        log.info("ESearch Query=[{}]", fullUrl);

        PubmedESearchHandler pubmedESearchHandler = new PubmedESearchHandler();
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

        HttpEntity entity = response.getEntity();

        if (entity != null) {

            InputStream esearchStream = entity.getContent();

            try {
                SAXParserFactory.newInstance().newSAXParser().parse(esearchStream, pubmedESearchHandler);
            } catch (SAXException | ParserConfigurationException e) {
                log.error("Error parsing XML file for query=[" + query + "], full url=[" + fullUrl + "]", e);
            }
        }
        return pubmedESearchHandler.getCount();
    }
}

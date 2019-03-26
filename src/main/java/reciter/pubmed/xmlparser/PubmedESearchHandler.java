package reciter.pubmed.xmlparser;

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
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reciter.pubmed.model.PubmedESearchResult;
import reciter.pubmed.querybuilder.PubmedXmlQuery;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A SAX handler for parsing the ESearch query from PubMed.
 *
 * @author Jie
 */
@Slf4j
public class PubmedESearchHandler extends DefaultHandler {

    private String webEnv;
    private int count;
    private boolean bWebEnv;
    private boolean bCount;
    private int numCountEncounteredSoFar = 0;
    
    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private StringBuilder chars = new StringBuilder();

    /**
     * Sends a query to the NCBI web site to retrieve the webEnv.
     *
     * @param eSearchUrl example query: http://www.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmax=1&usehistory=y&term=Kukafka%20R[au].
     * @return WebEnvHandler that contains the WebEnv data.
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     */
    public static PubmedESearchResult executeESearchQuery(String eSearchUrl) {
        PubmedESearchHandler webEnvHandler = new PubmedESearchHandler();
        PubmedESearchResult eSearchResult = new PubmedESearchResult();
        /*InputStream inputStream = null;
        try {
            inputStream = new URL(eSearchUrl).openStream();
        } catch (IOException e) {
            log.error("Error in executeESearchQuery", e);
        }
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(inputStream, webEnvHandler);
        } catch (Exception e) {
            log.error("Error in executeESearchQuery. url=[" + eSearchUrl + "]", e);
        }*/
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(eSearchUrl);
        pubmedXmlQuery.setRetMax(1);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        log.info("ESearch Query=[" + fullUrl + "]");
        try {
            HttpClient httpClient = HttpClients.createDefault();
            HttpPost httppost = new HttpPost(PubmedXmlQuery.ESEARCH_BASE_URL);
            //httppost.setHeader(HttpHeaders.ACCEPT, "application/xml");
            // Request parameters and other properties.
            List<NameValuePair> params = new ArrayList<NameValuePair>();
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
            Header[] headerRetryAfter = response.getHeaders("Retry-After");
            
            if(headerRateLimitRemaining != null && headerRateLimitRemaining.length > 0 && headerRateLimitRemaining[0] != null && Integer.parseInt(headerRateLimitRemaining[0].getValue()) == 0) {
            	if(headerRetryAfter != null && headerRetryAfter.length > 0 && headerRetryAfter[0] != null) {
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
                //String sanitizedStream = IOUtils.toString(esearchStream).trim().replaceFirst("^([\\W]+)<","<");
                //log.info(sanitizedStream);
                //SAXParserFactory.newInstance().newSAXParser().parse(esearchStream, webEnvHandler);
    			JsonNode json = objectMapper.readTree(esearchStream).get("esearchresult");
    			if(json != null) {
    				eSearchResult = objectMapper.treeToValue(json, PubmedESearchResult.class);
    			}
            }
        } catch (IOException e) {
            log.error("Error parsing XML file for query=[" + eSearchUrl + "], full url=[" + fullUrl + "]", e);
        }

        return eSearchResult;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

        chars.setLength(0);

        if (qName.equalsIgnoreCase("WebEnv")) {
            bWebEnv = true;
        }
        if (qName.equalsIgnoreCase("Count") && numCountEncounteredSoFar == 0) {
            numCountEncounteredSoFar++;
            bCount = true;
        }
    }

    @Override
    public void characters(char ch[], int start, int length) throws SAXException {
        if (bWebEnv) {
            chars.append(ch, start, length);
        }
        if (bCount) {
            chars.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // WebEnv
        if (bWebEnv) {
            webEnv = chars.toString();
            bWebEnv = false;
        }

        // Count.
        if (bCount) {
            count = Integer.parseInt(chars.toString());
            bCount = false;
        }
    }

    public String getWebEnv() {
        return webEnv;
    }

    public int getCount() {
        return count;
    }
}

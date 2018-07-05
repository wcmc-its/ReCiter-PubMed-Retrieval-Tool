package reciter.pubmed.xmlparser;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import reciter.pubmed.querybuilder.PubmedXmlQuery;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A SAX handler for parsing the ESearch query from PubMed.
 *
 * @author Jie
 */
public class PubmedESearchHandler extends DefaultHandler {

    private final static Logger slf4jLogger = LoggerFactory.getLogger(PubmedESearchHandler.class);

    private String webEnv;
    private int count;
    private boolean bWebEnv;
    private boolean bCount;
    private int numCountEncounteredSoFar = 0;

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
    public static PubmedESearchHandler executeESearchQuery(String eSearchUrl) {
        PubmedESearchHandler webEnvHandler = new PubmedESearchHandler();
        /*InputStream inputStream = null;
        try {
            inputStream = new URL(eSearchUrl).openStream();
        } catch (IOException e) {
            slf4jLogger.error("Error in executeESearchQuery", e);
        }
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(inputStream, webEnvHandler);
        } catch (Exception e) {
            slf4jLogger.error("Error in executeESearchQuery. url=[" + eSearchUrl + "]", e);
        }*/
        PubmedXmlQuery pubmedXmlQuery = new PubmedXmlQuery(eSearchUrl);
        pubmedXmlQuery.setRetMax(1);
        String fullUrl = pubmedXmlQuery.buildESearchQuery(); // build eSearch query.
        slf4jLogger.info("ESearch Query=[" + fullUrl + "]");
        try {
            HttpClient httpClient = HttpClients.createDefault();
            HttpPost httppost = new HttpPost(PubmedXmlQuery.ESEARCH_BASE_URL);
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

            HttpEntity entity = response.getEntity();

            if (entity != null) {
                InputStream esearchStream = entity.getContent();
                SAXParserFactory.newInstance().newSAXParser().parse(esearchStream, webEnvHandler);
            }
        } catch (SAXException | ParserConfigurationException | IOException e) {
            slf4jLogger.error("Error parsing XML file for query=[" + eSearchUrl + "], full url=[" + fullUrl + "]", e);
        }

        return webEnvHandler;
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

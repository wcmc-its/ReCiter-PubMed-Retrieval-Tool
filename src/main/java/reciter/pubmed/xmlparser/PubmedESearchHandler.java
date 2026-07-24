package reciter.pubmed.xmlparser;

import org.xml.sax.helpers.DefaultHandler;

/**
 * A SAX handler for parsing the ESearch query from PubMed.
 *
 * The live ESearch path parses the JSON response in the controller/service, so
 * the SAX parsing machinery that previously lived here is no longer invoked.
 *
 * @author Jie
 */
public class PubmedESearchHandler extends DefaultHandler {

    private String webEnv;
    private int count;

    public String getWebEnv() {
        return webEnv;
    }

    public int getCount() {
        return count;
    }
}
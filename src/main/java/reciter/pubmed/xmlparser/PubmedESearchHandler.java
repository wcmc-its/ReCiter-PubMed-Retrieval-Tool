package reciter.pubmed.xmlparser;

import org.xml.sax.helpers.DefaultHandler;

/**
 * A SAX handler for parsing the ESearch query from PubMed.
 *
 * The live ESearch path parses the JSON response in PubMedArticleRetrievalService, so
 * the SAX parsing machinery that previously lived here is no longer invoked.
 * <p>
 * Merge note: master still carried an unused static {@code executeESearchQuery} here — a third
 * copy of the ESearch HTTP/rate-limit code with no callers. It is removed as in dev.
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

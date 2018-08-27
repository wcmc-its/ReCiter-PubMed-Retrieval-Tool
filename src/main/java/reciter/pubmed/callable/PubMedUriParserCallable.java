package reciter.pubmed.callable;

import org.apache.commons.lang3.StringEscapeUtils;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    private final PubmedEFetchHandler xmlHandler;
    private final String uri;

    public PubMedUriParserCallable(PubmedEFetchHandler xmlHandler, String uri) {
        this.xmlHandler = xmlHandler;
        this.uri = uri;
    }

    public List<PubMedArticle> parse(String uri) throws ParserConfigurationException, SAXException, IOException {
        SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
        saxParser.parse(uri, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
        return parse(uri);
    }
}

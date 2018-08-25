package reciter.pubmed.callable;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.util.List;

public class PubMedUriParserCallableTest {

    private PubmedEFetchHandler xmlHandler;
    private SAXParser saxParser;
    private InputSource inputSource;
    private PubMedUriParserCallable pubMedUriParserCallable;

    @BeforeMethod
    public void setup() throws Exception {
        xmlHandler = new PubmedEFetchHandler();
        saxParser = SAXParserFactory.newInstance().newSAXParser();
        inputSource = new InputSource("src/test/resources/pubmed/callable/24694772.xml"); // new InputSource(f.toURI().toASCIIString());
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
    }

    @Test
    public void testParse() throws SAXException, IOException {
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.parse(inputSource);
    }
}

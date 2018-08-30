package reciter.pubmed.callable;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class PubMedUriParserCallableTest {

    private PubmedEFetchHandler xmlHandler;
    private SAXParser saxParser;
    private InputSource inputSource;
    private PubMedUriParserCallable pubMedUriParserCallable;

    @BeforeClass
    public void setup() throws Exception {
        xmlHandler = new PubmedEFetchHandler();
        saxParser = SAXParserFactory.newInstance().newSAXParser();
    }

    /**
     * Test that the PubMed XML handler is able to handle special characters.
     *
     * @throws SAXException
     * @throws IOException
     */
    @Test
    public void testInvalidCharacterParse() throws SAXException, IOException {
        File initialFile = new File("src/test/resources/pubmed/callable/28356292.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.parse(inputSource);
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        String articleTitle = pubMedArticle.getMedlinecitation().getArticle().getArticletitle();
        assertEquals(articleTitle, "Responses of distal nephron Na<sup>+</sup> transporters to acute volume depletion and hyperkalemia.");
    }
}

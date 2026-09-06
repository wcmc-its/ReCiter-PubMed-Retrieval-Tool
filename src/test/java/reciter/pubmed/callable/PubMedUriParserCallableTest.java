package reciter.pubmed.callable;


import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

public class PubMedUriParserCallableTest {

    private PubmedEFetchHandler xmlHandler;
    private SAXParser saxParser;
    private InputSource inputSource;
    private PubMedUriParserCallable pubMedUriParserCallable;

    @BeforeEach
    public void setup() throws Exception {
        xmlHandler = new PubmedEFetchHandler();
        saxParser = SAXParserFactory.newInstance().newSAXParser();
    }

    /**
     * Test that the PubMed XML handler is able to handle special characters.
     * @throws Exception 
     */
    @Test
    public void testInvalidCharacterParse() throws Exception {
        File initialFile = new File("src/test/resources/reciter/pubmed/callable/28356292.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        String articleTitle = pubMedArticle.getMedlinecitation().getArticle().getArticletitle();
        assertEquals(articleTitle, "<i>Responses</i> of <b>distal</b> nephron Na<sup>+</sup> transporters <sub>-</sub> to acute volume depletion and hyperkalemia.");
    }
}

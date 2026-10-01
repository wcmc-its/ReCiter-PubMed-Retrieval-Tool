package reciter.pubmed.callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import javax.xml.parsers.SAXParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

/**
 * Merge note: converted from TestNG to JUnit 5 — master's pom.xml runs only JUnit Platform tests, so
 * the TestNG version compiled but never executed.
 */
class PubMedUriParserCallableTest {

    private PubmedEFetchHandler xmlHandler;
    private SAXParser saxParser;

    @BeforeEach
    void setup() throws Exception {
        xmlHandler = new PubmedEFetchHandler();
        // The production, XXE-hardened parser: proves it still accepts real PubMed XML (with its
        // DOCTYPE) and keeps the test offline, since external DTD loading is disabled.
        saxParser = new PubMedArticleRetrievalService(null, null).getSaxParser();
    }

    /**
     * Test that the PubMed XML handler is able to handle special characters.
     */
    @Test
    void testInvalidCharacterParse() throws Exception {
        File initialFile = new File("src/test/resources/reciter/pubmed/callable/28356292.xml");
        InputSource inputSource = new InputSource(new FileInputStream(initialFile));
        // Local byte stream: no network, so no rate limiter or HttpClient is needed.
        PubMedUriParserCallable callable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, null, null);
        List<PubMedArticle> pubMedArticles = callable.call();
        String articleTitle = pubMedArticles.get(0).getMedlinecitation().getArticle().getArticletitle();
        assertEquals("<i>Responses</i> of <b>distal</b> nephron Na<sup>+</sup> transporters <sub>-</sub> to acute volume depletion and hyperkalemia.",
                articleTitle);
    }

    /** SSRF guard: an EFetch system-id pointing anywhere but the NCBI eutils host is refused. */
    @Test
    void refusesToFetchFromUnexpectedHost() {
        InputSource inputSource = new InputSource("https://example.com/efetch.fcgi?db=pubmed");
        PubMedUriParserCallable callable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, null, null);
        IOException e = assertThrows(IOException.class, callable::call);
        assertTrue(e.getMessage().contains("unexpected host"), e.getMessage());
    }
}

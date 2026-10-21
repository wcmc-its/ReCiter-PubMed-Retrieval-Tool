package reciter.pubmed.xmlparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import javax.xml.parsers.SAXParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.ratelimit.NcbiRateLimiter;

/**
 * Merge note: ported from dev's TestNG version to JUnit 5 (master's pom.xml runs only JUnit Platform
 * tests). Covers the parser fixes merged from dev, e.g. equal-contributor de-duplication.
 */
class PubmedEFetchHandlerTest {

	private PubmedEFetchHandler xmlHandler;
    private SAXParser saxParser;
    private InputSource inputSource;
    private PubMedUriParserCallable pubMedUriParserCallable;
    // These fixtures parse local files/resources (systemId is null), so the rate limiter is never
    // exercised; a disabled instance just satisfies the constructor.
    private final NcbiRateLimiter rateLimiter = new NcbiRateLimiter(2.0, false);

    @BeforeEach
    void setup() throws Exception {
        xmlHandler = new PubmedEFetchHandler();
        // The production, XXE-hardened parser: proves it still accepts real PubMed XML (with its
        // DOCTYPE) and keeps the test offline, since external DTD loading is disabled.
        saxParser = new PubMedArticleRetrievalService(null, null).getSaxParser();
    }

    /**
     * Test that the PubMed XML handler is getting correct Journal Title from xml
     * @throws Exception 
     */
    @Test
    void testJournalTitleParse() throws Exception {
        File initialFile = new File("src/test/resources/reciter/pubmed/callable/31967741.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        String journalTitle = pubMedArticle.getMedlinecitation().getArticle().getJournal().getTitle();
        assertEquals("Annals of clinical and translational neurology", journalTitle);

        initialFile = new File("src/test/resources/reciter/pubmed/callable/31746150.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        pubMedArticles = pubMedUriParserCallable.call();
        pubMedArticle = pubMedArticles.get(0);
        journalTitle = pubMedArticle.getMedlinecitation().getArticle().getJournal().getTitle();
        assertEquals("MicrobiologyOpen", journalTitle);
    }

    @Test
    void testAuthorOrcid() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("31482638.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        assertEquals("0000-0002-5762-3917", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(0).getOrcid());
        assertEquals("0000-0003-3544-2231", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(7).getOrcid());
        assertEquals("0000-0002-5887-7257", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(8).getOrcid());
        assertNull(pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(1).getOrcid());
    }

    /**
     * Equal-contributor authors (<Author EqualContrib="Y">) must be added exactly once.
     * Previously a second phantom (empty) author was added per equal-contributor tag, corrupting
     * the author list size, positions, and names. Verify de-duplication and that equalContrib is
     * set on the single, correctly-populated author object.
     */
    @Test
    void testEqualContribDeduplication() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("equalcontrib.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);

        // Exactly three authors -- no phantom/empty author injected for the equal-contributor tags.
        assertEquals(3, pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().size());

        // First equal-contributor author: name fields populated on the same object that carries equalContrib.
        assertEquals("Smith", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(0).getLastname());
        assertEquals("Alice B", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(0).getForename());
        assertEquals("Y", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(0).getEqualContrib());

        // Second equal-contributor author.
        assertEquals("Jones", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(1).getLastname());
        assertEquals("Y", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(1).getEqualContrib());

        // Non equal-contributor author: name populated, equalContrib left null.
        assertEquals("Nguyen", pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(2).getLastname());
        assertNull(pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(2).getEqualContrib());
    }

    @Test
    void testReferenceList() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("32025781.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        assertEquals(38, pubMedArticle.getMedlinecitation().getCommentscorrectionslist().size(), "The referenceList matches");
    }

    @Test
    void testArticleTitleLineBreakRemoval() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("32025781.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        assertEquals("<b> <i>Propionibacterium acnes</i> </b> Host Inflammatory Response During Periprosthetic Infection Is Joint Specific.", pubMedArticle.getMedlinecitation().getArticle().getArticletitle(), "The ArticleTitle matches");
    }

    @Test
    void testHexadecimalLiteralRemoval() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("32025781.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource, rateLimiter, null);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        String articleTitle = pubMedArticles.get(0).getMedlinecitation().getArticle().getArticletitle();
        assertEquals("<b> <i>Propionibacterium acnes</i> </b> Host Inflammatory Response During Periprosthetic Infection Is Joint Specific.", articleTitle);
    }

}
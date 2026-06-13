package reciter.pubmed.xmlparser;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.testng.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.xml.sax.InputSource;

import lombok.extern.slf4j.Slf4j;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;

@Slf4j
public class PubmedEFetchHandlerTest {
	
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
     * Test that the PubMed XML handler is getting correct Journal Title from xml
     * @throws Exception 
     */
    @Test
    public void testJournalTitleParse() throws Exception {
        File initialFile = new File("src/test/resources/reciter/pubmed/callable/31967741.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        String journalTitle = pubMedArticle.getMedlinecitation().getArticle().getJournal().getTitle();
        assertEquals(journalTitle, "Annals of clinical and translational neurology");

        initialFile = new File("src/test/resources/reciter/pubmed/callable/31746150.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        pubMedArticles = pubMedUriParserCallable.call();
        pubMedArticle = pubMedArticles.get(0);
        journalTitle = pubMedArticle.getMedlinecitation().getArticle().getJournal().getTitle();
        assertEquals(journalTitle, "MicrobiologyOpen");
    }

    @Test
    public void testAuthorOrcid() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("31482638.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        log.debug(pubMedArticle.getMedlinecitation().getArticle().getAuthorlist().get(0).getOrcid());
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
    public void testEqualContribDeduplication() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("equalcontrib.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
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
    public void testReferenceList() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("32025781.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        assertEquals(38, pubMedArticle.getMedlinecitation().getCommentscorrectionslist().size(), "The referenceList matches");
    }

    @Test
    public void testArticleTitleLineBreakRemoval() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("32025781.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        assertEquals("<b> <i>Propionibacterium acnes</i> </b> Host Inflammatory Response During Periprosthetic Infection Is Joint Specific.", pubMedArticle.getMedlinecitation().getArticle().getArticletitle(), "The ArticleTitle matches");
    }

    @Test
    public void testHexadecimalLiteralRemoval() throws Exception {
        inputSource = new InputSource(this.getClass().getResourceAsStream("32025781.xml"));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        String articleTitle = pubMedArticles.get(0).getMedlinecitation().getArticle().getArticletitle();
        assertEquals("<b> <i>Propionibacterium acnes</i> </b> Host Inflammatory Response During Periprosthetic Infection Is Joint Specific.", articleTitle);
    }

}
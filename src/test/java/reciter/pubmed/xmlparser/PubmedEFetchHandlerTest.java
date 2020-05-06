package reciter.pubmed.xmlparser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
}
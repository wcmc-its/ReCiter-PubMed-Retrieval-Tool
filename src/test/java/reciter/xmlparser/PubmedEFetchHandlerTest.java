package reciter.xmlparser;

import static org.testng.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.xml.sax.InputSource;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

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
        File initialFile = new File("src/test/resources/pubmed/callable/31967741.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        List<PubMedArticle> pubMedArticles = pubMedUriParserCallable.call();
        PubMedArticle pubMedArticle = pubMedArticles.get(0);
        String journalTitle = pubMedArticle.getMedlinecitation().getArticle().getJournal().getTitle();
        assertEquals(journalTitle, "Annals of clinical and translational neurology");

        initialFile = new File("src/test/resources/pubmed/callable/31746150.xml");
        inputSource = new InputSource(new FileInputStream(initialFile));
        pubMedUriParserCallable = new PubMedUriParserCallable(xmlHandler, saxParser, inputSource);
        pubMedArticles = pubMedUriParserCallable.call();
        pubMedArticle = pubMedArticles.get(0);
        journalTitle = pubMedArticle.getMedlinecitation().getArticle().getJournal().getTitle();
        assertEquals(journalTitle, "MicrobiologyOpen");
    }
}
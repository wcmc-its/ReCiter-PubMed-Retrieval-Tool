package reciter.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.FileInputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.xml.sax.InputSource;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.callable.PubMedUriParserCallable;
import reciter.pubmed.retriever.PubMedArticleRetrievalService;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

/**
 * Pins the {@code ?fields=} (Squiggly) behaviour of the query endpoint: field selection must keep
 * only the requested fields, be case-insensitive, and leave the unfiltered path untouched.
 */
class PubMedRetrievalToolControllerFieldsTest {

    private PubMedRetrievalToolController controller;
    private List<PubMedArticle> articles;

    @BeforeEach
    void setup() throws Exception {
        PubMedArticleRetrievalService service = new PubMedArticleRetrievalService(null, null);
        articles = new PubMedUriParserCallable(new PubmedEFetchHandler(), service.getSaxParser(),
                new InputSource(new FileInputStream("src/test/resources/reciter/pubmed/xmlparser/31482638.xml")),
                null, null).call();

        PubMedArticleRetrievalService stub = new PubMedArticleRetrievalService(null, null) {
            @Override
            public List<PubMedArticle> retrieve(String pubMedQuery) {
                return articles;
            }
        };
        controller = new PubMedRetrievalToolController();
        ReflectionTestUtils.setField(controller, "pubMedArticleRetrievalService", stub);
    }

    @Test
    void withoutFieldsReturnsFullArticles() throws Exception {
        List<PubMedArticle> result = controller.query("anything", null);
        assertEquals(articles.size(), result.size());
        assertNotNull(result.get(0).getMedlinecitation().getArticle().getArticletitle());
    }

    @Test
    void fieldsKeepsOnlyRequestedFields() throws Exception {
        List<PubMedArticle> result = controller.query("anything", "medlinecitation.medlinecitationpmid");
        assertEquals(articles.size(), result.size());
        long expectedPmid = articles.get(0).getMedlinecitation().getMedlinecitationpmid().getPmid();
        assertEquals(expectedPmid, result.get(0).getMedlinecitation().getMedlinecitationpmid().getPmid());
        assertNull(result.get(0).getMedlinecitation().getArticle(), "unrequested fields must be dropped");
    }

    @Test
    void fieldNamesAreCaseInsensitive() throws Exception {
        List<PubMedArticle> result = controller.query("anything", "MedlineCitation.MedlineCitationPmid");
        long expectedPmid = articles.get(0).getMedlinecitation().getMedlinecitationpmid().getPmid();
        assertEquals(expectedPmid, result.get(0).getMedlinecitation().getMedlinecitationpmid().getPmid());
        assertNull(result.get(0).getMedlinecitation().getArticle());
    }
}

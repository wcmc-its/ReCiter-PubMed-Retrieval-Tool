package reciter.pubmed.retriever;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reciter.model.pubmed.PubMedArticle;
import reciter.model.pubmed.PubmedESearchResult;

/**
 * Unit coverage for the load-bearing retrieval logic merged from dev: the query-drop detector
 * ({@link PubMedArticleRetrievalService#isPubMedQueryDropped(JsonNode, String)}), the
 * {@code RETRIEVAL_THRESHOLD} gate, the retmax bypass of that gate, and sort normalization.
 *
 * <p>Merge note: dev's TestNG + Mockito-spy version is ported to JUnit 5 (master's pom runs only
 * JUnit Platform tests), and the spy is replaced by an anonymous subclass that stubs the ESearch, so
 * no network call and no bytecode mocking on JDK 17 is needed.
 */
class PubMedArticleRetrievalServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode esearch(String json) throws IOException {
        // Real ESearch JSON wraps everything under "esearchresult"; isPubMedQueryDropped is handed
        // that inner node (see executeESearch), so unwrap it here.
        return MAPPER.readTree(json).get("esearchresult");
    }

    /** A service whose ESearch returns {@code count} without touching the network (no HttpClient). */
    private static PubMedArticleRetrievalService serviceWithCount(int count) {
        return new PubMedArticleRetrievalService(null, null) {
            @Override
            protected PubmedESearchResult executeESearch(String term, String sort) {
                PubmedESearchResult result = new PubmedESearchResult();
                result.setCount(count);
                return result;
            }
        };
    }

    // ---------------------------------------------------------------------
    // isPubMedQueryDropped — query-drop detection (Fix #24)
    // ---------------------------------------------------------------------

    @Test
    void queryDropped_phraseNotFoundAndTrivialTranslation() throws Exception {
        JsonNode node = esearch("{\"esearchresult\":{\"count\":\"500000\",\"querytranslation\":\"J[Author]\","
                + "\"errorlist\":{\"phrasenotfound\":[\"Charles-rawlins[Author]\"]}}}");
        assertTrue(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "Charles-rawlins J[au]"),
                "Dropped phrase leaving a trivial single-initial translation must be detected as dropped");
    }

    @Test
    void queryNotDropped_phraseNotFoundButSubstantialTranslation() throws Exception {
        JsonNode node = esearch("{\"esearchresult\":{\"count\":\"42\",\"querytranslation\":\"Kukafka[Author] AND R[Author]\","
                + "\"errorlist\":{\"phrasenotfound\":[\"somedroppedterm[Author]\"]}}}");
        assertFalse(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "Kukafka R somedroppedterm[au]"),
                "A substantial remaining translation must NOT be treated as a dropped query");
    }

    @Test
    void queryNotDropped_noErrorList() throws Exception {
        JsonNode node = esearch("{\"esearchresult\":{\"count\":\"3\",\"querytranslation\":\"J[Author]\"}}");
        assertFalse(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "J[au]"),
                "Without a phrasenotfound signal no query is considered dropped");
    }

    @Test
    void queryNotDropped_emptyPhraseNotFoundArray() throws Exception {
        JsonNode node = esearch("{\"esearchresult\":{\"count\":\"3\",\"querytranslation\":\"J[Author]\","
                + "\"errorlist\":{\"phrasenotfound\":[]}}}");
        assertFalse(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "J[au]"),
                "An empty phrasenotfound array means nothing was dropped");
    }

    @Test
    void queryDropped_translationStripsToEmpty() throws Exception {
        JsonNode node = esearch("{\"esearchresult\":{\"count\":\"900000\",\"querytranslation\":\"(J[Author]) AND (B[au])\","
                + "\"errorlist\":{\"phrasenotfound\":[\"smith-jones[Author]\"]}}}");
        assertTrue(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "Smith-Jones JB[au]"),
                "Only initials, operators and punctuation strip to <=2 chars and is a drop");
    }

    // ---------------------------------------------------------------------
    // RETRIEVAL_THRESHOLD gate
    // ---------------------------------------------------------------------

    /**
     * Over the threshold, retrieve() must hard-refuse with the TYPED, non-retried exception whose
     * message GlobalExceptionHandler keys on.
     */
    @Test
    void retrieveThrowsTypedRefusalWhenCountExceedsThreshold() {
        RetrievalThresholdExceededException e = assertThrows(RetrievalThresholdExceededException.class,
                () -> serviceWithCount(2001).retrieve("broad[au]"));
        assertEquals("Number of PubMed Articles retrieved 2001 exceeded the threshold level 2000", e.getMessage(),
                "Threshold-exceeded message must match the exact text GlobalExceptionHandler keys on");
    }

    /**
     * Exactly at the threshold the gate is not taken; the IOException that follows comes from the
     * EFetch step (no HttpClient in this unit test), never from the gate.
     */
    @Test
    void retrieveDoesNotRefuseAtExactThreshold() {
        IOException e = assertThrows(IOException.class, () -> serviceWithCount(2000).retrieve("exactly2000[au]"));
        assertFalse(e instanceof RetrievalThresholdExceededException,
                "Count equal to the threshold must not be refused by the threshold gate");
    }

    /** An explicit retmax bounds the fetch, so a broad match is not refused. */
    @Test
    void explicitRetmaxBypassesMatchedCountGate() {
        IOException e = assertThrows(IOException.class,
                () -> serviceWithCount(24852).retrieve("broad[au]", "relevance", 50));
        assertFalse(e instanceof RetrievalThresholdExceededException,
                "retmax <= threshold must bypass the matched-count refusal");
    }

    /** A retmax above the threshold does not bypass the gate. */
    @Test
    void retmaxAboveThresholdDoesNotBypassGate() {
        assertThrows(RetrievalThresholdExceededException.class,
                () -> serviceWithCount(24852).retrieve("broad[au]", "relevance", 5000));
    }

    @Test
    void retrieveReturnsEmptyForZeroCount() throws Exception {
        List<PubMedArticle> articles = serviceWithCount(0).retrieve("nomatch[au]");
        assertTrue(articles.isEmpty(), "A zero-count query must return an empty list with no EFetch");
    }

    // ---------------------------------------------------------------------
    // sort normalization
    // ---------------------------------------------------------------------

    @Test
    void normalizeSortMapsDateToPubDateAndIgnoresUnknown() {
        assertEquals("relevance", PubMedArticleRetrievalService.normalizeSort(" Relevance "));
        assertEquals("pub_date", PubMedArticleRetrievalService.normalizeSort("date"));
        assertEquals("pub_date", PubMedArticleRetrievalService.normalizeSort("pub_date"));
        assertNull(PubMedArticleRetrievalService.normalizeSort("garbage"));
        assertNull(PubMedArticleRetrievalService.normalizeSort("  "));
        assertNull(PubMedArticleRetrievalService.normalizeSort(null));
    }
}

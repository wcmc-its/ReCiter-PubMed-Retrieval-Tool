package reciter.pubmed.retriever;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.model.PubmedESearchResult;

/**
 * Unit coverage for the two pieces of load-bearing retrieval logic that previously had no tests:
 * the query-drop detector ({@link PubMedArticleRetrievalService#isPubMedQueryDropped(JsonNode, String)})
 * and the {@code RETRIEVAL_THRESHOLD} gate inside {@link PubMedArticleRetrievalService#retrieve(String)}.
 *
 * <p>These tests are pure unit tests: the query-drop cases build {@link JsonNode} inputs from JSON
 * string literals mirroring real NCBI ESearch responses, and the threshold cases use a Mockito spy
 * to stub {@code getNumberOfPubMedArticles} so no network call is made.
 */
public class PubMedArticleRetrievalServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode esearch(String json) throws IOException {
        // Real ESearch JSON wraps everything under "esearchresult"; isPubMedQueryDropped is handed
        // that inner node (see executeESearch), so unwrap it here.
        return MAPPER.readTree(json).get("esearchresult");
    }

    // ---------------------------------------------------------------------
    // isPubMedQueryDropped — query-drop detection (Fix #24 / #110 / #135)
    // ---------------------------------------------------------------------

    /**
     * phrasenotfound present AND the remaining querytranslation is trivial (a single initial
     * with field tag, which strips to <=2 chars) => the query was dropped, return true.
     */
    @Test
    public void testQueryDropped_phraseNotFoundAndTrivialTranslation() throws Exception {
        JsonNode node = esearch(
                "{\"esearchresult\":{"
                        + "\"count\":\"500000\","
                        + "\"querytranslation\":\"J[Author]\","
                        + "\"errorlist\":{\"phrasenotfound\":[\"Charles-rawlins[Author]\"]}"
                        + "}}");
        assertTrue(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "Charles-rawlins J[au]"),
                "Dropped phrase leaving a trivial single-initial translation must be detected as dropped");
    }

    /**
     * phrasenotfound present but the remaining querytranslation is substantial (a real surname) =>
     * not a drop we should neutralize, return false.
     */
    @Test
    public void testQueryNotDropped_phraseNotFoundButSubstantialTranslation() throws Exception {
        JsonNode node = esearch(
                "{\"esearchresult\":{"
                        + "\"count\":\"42\","
                        + "\"querytranslation\":\"Kukafka[Author] AND R[Author]\","
                        + "\"errorlist\":{\"phrasenotfound\":[\"somedroppedterm[Author]\"]}"
                        + "}}");
        assertFalse(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "Kukafka R somedroppedterm[au]"),
                "A substantial remaining translation must NOT be treated as a dropped query");
    }

    /**
     * No errorlist/phrasenotfound at all => nothing was dropped, return false even when the
     * translation itself is short.
     */
    @Test
    public void testQueryNotDropped_noErrorList() throws Exception {
        JsonNode node = esearch(
                "{\"esearchresult\":{"
                        + "\"count\":\"3\","
                        + "\"querytranslation\":\"J[Author]\""
                        + "}}");
        assertFalse(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "J[au]"),
                "Without a phrasenotfound signal no query is considered dropped");
    }

    /**
     * errorlist present but phrasenotfound is an empty array => no phrases dropped, return false.
     */
    @Test
    public void testQueryNotDropped_emptyPhraseNotFoundArray() throws Exception {
        JsonNode node = esearch(
                "{\"esearchresult\":{"
                        + "\"count\":\"3\","
                        + "\"querytranslation\":\"J[Author]\","
                        + "\"errorlist\":{\"phrasenotfound\":[]}"
                        + "}}");
        assertFalse(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "J[au]"),
                "An empty phrasenotfound array means nothing was dropped");
    }

    /**
     * phrasenotfound present and the remaining translation is only boolean operators / field tags /
     * punctuation, which all strip away to an empty string (<=2 chars) => detected as dropped.
     */
    @Test
    public void testQueryDropped_translationStripsToEmpty() throws Exception {
        JsonNode node = esearch(
                "{\"esearchresult\":{"
                        + "\"count\":\"900000\","
                        + "\"querytranslation\":\"(J[Author]) AND (B[au])\","
                        + "\"errorlist\":{\"phrasenotfound\":[\"smith-jones[Author]\"]}"
                        + "}}");
        assertTrue(PubMedArticleRetrievalService.isPubMedQueryDropped(node, "Smith-Jones JB[au]"),
                "A translation consisting only of single initials, operators and punctuation strips to <=2 chars and is a drop");
    }

    // ---------------------------------------------------------------------
    // RETRIEVAL_THRESHOLD gate in retrieve(String)
    // ---------------------------------------------------------------------

    /**
     * When the ESearch count exceeds RETRIEVAL_THRESHOLD (2000), retrieve() must hard-refuse with an
     * IOException whose message is matched downstream by GlobalExceptionHandler. The spy stubs the
     * count so no network call is performed.
     */
    @Test
    public void testRetrieveThrowsWhenCountExceedsThreshold() throws Exception {
        PubMedArticleRetrievalService service = spy(new PubMedArticleRetrievalService());
        PubmedESearchResult result = new PubmedESearchResult();
        result.setCount(2001);
        doReturn(result).when(service).getNumberOfPubMedArticles("broad[au]");

        try {
            service.retrieve("broad[au]");
            fail("Expected IOException when article count exceeds the retrieval threshold");
        } catch (IOException e) {
            assertEquals(e.getMessage(),
                    "Number of PubMed Articles retrieved 2001 exceeded the threshold level 2000",
                    "Threshold-exceeded message must match the exact text GlobalExceptionHandler keys on");
        }
    }

    /**
     * A boundary check: a count exactly at the threshold (2000) is NOT refused — the gate is a
     * strict greater-than. Stubbing webenv to null short-circuits the (un-mocked) EFetch path...
     * but to keep this a pure unit test with no network, we only assert that the threshold branch
     * is not taken, i.e. no IOException with the threshold message is thrown for count == 2000.
     */
    @Test
    public void testRetrieveDoesNotThrowAtExactThreshold() throws Exception {
        PubMedArticleRetrievalService service = spy(new PubMedArticleRetrievalService());
        PubmedESearchResult result = new PubmedESearchResult();
        result.setCount(2000);
        doReturn(result).when(service).getNumberOfPubMedArticles("exactly2000[au]");

        try {
            service.retrieve("exactly2000[au]");
            fail("Expected the EFetch path (not the threshold refusal) to be exercised for count == 2000");
        } catch (IOException e) {
            // Count 2000 must NOT trip the threshold gate; any IOException here comes from the
            // downstream EFetch attempt (no HTTP client wired in this unit test), never the gate.
            assertFalse(e.getMessage() != null
                            && e.getMessage().startsWith("Number of PubMed Articles retrieved"),
                    "Count equal to the threshold must not be refused by the threshold gate");
        }
    }

    /**
     * A zero-count ESearch returns an empty list immediately with no EFetch round-trip. Because the
     * count==0 branch returns before any HTTP client is touched, this runs as a pure unit test even
     * though no CloseableHttpClient is wired into the service.
     */
    @Test
    public void testRetrieveReturnsEmptyForZeroCount() throws Exception {
        PubMedArticleRetrievalService service = spy(new PubMedArticleRetrievalService());
        PubmedESearchResult result = new PubmedESearchResult();
        result.setCount(0);
        doReturn(result).when(service).getNumberOfPubMedArticles("nomatch[au]");

        List<PubMedArticle> articles = service.retrieve("nomatch[au]");
        assertNotNull(articles, "A zero-count query must return a non-null list");
        assertTrue(articles.isEmpty(), "A zero-count query must return an empty list with no EFetch");
    }
}

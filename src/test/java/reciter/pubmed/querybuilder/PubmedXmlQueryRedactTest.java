package reciter.pubmed.querybuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PubmedXmlQueryRedactTest {

    /** api_key in the middle of a query string is redacted; the rest of the string is untouched. */
    @Test
    void redactsKeyInMiddleOfQueryString() {
        String url = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?api_key=abc123&db=pubmed";

        String redacted = PubmedXmlQuery.redactApiKey(url);

        assertEquals("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?api_key=REDACTED&db=pubmed", redacted);
    }

    /** api_key at the end of the string (no trailing '&') is still fully redacted. */
    @Test
    void redactsKeyAtEndOfString() {
        String url = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&api_key=abc123";

        String redacted = PubmedXmlQuery.redactApiKey(url);

        assertEquals("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&api_key=REDACTED", redacted);
    }

    /** A URL with no api_key parameter is returned unchanged. */
    @Test
    void urlWithoutKeyIsUnchanged() {
        String url = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&term=Kukafka%20R[au]";

        String redacted = PubmedXmlQuery.redactApiKey(url);

        assertEquals(url, redacted);
    }

    /** Null input returns null — must not throw. */
    @Test
    void nullInputReturnsNull() {
        assertNull(PubmedXmlQuery.redactApiKey(null));
    }
}

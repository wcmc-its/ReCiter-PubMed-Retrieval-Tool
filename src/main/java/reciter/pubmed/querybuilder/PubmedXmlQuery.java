package reciter.pubmed.querybuilder;

import java.util.regex.Pattern;

import lombok.Data;

/**
 * Reference documentation for the various parameters in this class: http://www.ncbi.nlm.nih.gov/books/NBK25499/
 */
@Data
public class PubmedXmlQuery {

    public static final int DEFAULT_RETMAX = 10000;

    /**
     * Required Parameters.
     */
    // NCBI's current E-utilities host. The old www.ncbi.nlm.nih.gov/entrez/eutils host is
    // deprecated and flaky (intermittent HTML error pages / empty results). See wcmc-its/ReCiter-PubMed-Retrieval-Tool#166.
    public static final String EUTILS_HOST = "eutils.ncbi.nlm.nih.gov";
    public static final String ESEARCH_BASE_URL = "https://" + EUTILS_HOST + "/entrez/eutils/esearch.fcgi";
    protected static final String EFETCH_BASE_URL = "https://" + EUTILS_HOST + "/entrez/eutils/efetch.fcgi";

    /**
     * Optional Parameters.
     */
    /**
     * Database to search. Value must be a valid Entrez database name. (Default={@code pubmed})
     */
    private String db = "pubmed";

    /**
     * Entrez text query. All special characters must be URL encoded. Spaces may be replaced by '+'
     * signs. For very long queries (more than several hundred characters long), consider using
     * an HTTP POST call. (Required parameter).
     */
    private String term;

    /**
     * Total number of UIDs from the retrieved set to be shown in the XML output.
     * PubMed default is 20.
     * <p>
     * Total number of DocSums from the input set to be retrieved, up to a maximum of 10,000.
     * If the total set is larger than this maximum, the value of retstart can be iterated
     * while holding retmax constant, thereby downloading the entire set in batches of size retmax.
     */
    private int retMax = DEFAULT_RETMAX;

    /**
     * Sequential index of the first UID in the retrieved set to be shown in the XML output,
     * corresponding to the first record of the entire set. PubMed default is 0. This parameter
     * can be used in conjunction with {@link reciter.pubmed.querybuilder.PubmedXmlQuery#retMax} to download an arbitrary subset of UIDs
     * retrieved from a search.
     */
    private int retStart;

    /**
     * When {@link reciter.pubmed.querybuilder.PubmedXmlQuery#useHistory} is set to {@code true}, ESearch will post the UIDs resulting
     * from the search operation onto the PubMed history server so that they can be used
     * directly in a subsequent E-utility call. Also {@link reciter.pubmed.querybuilder.PubmedXmlQuery#useHistory} must be set to {@code true}
     * for ESearch to interpret query key values included in {@link reciter.pubmed.querybuilder.PubmedXmlQuery#term} or to accept a
     * {@link reciter.pubmed.querybuilder.PubmedXmlQuery#webEnv} as input.
     */
    private String useHistory = "y";

    /**
     * Web environment string returned from a previous ESearch, EPost or ELink call. When provided,
     * ESearch will post the results of the search operation to this pre-existing {@link reciter.pubmed.querybuilder.PubmedXmlQuery#webEnv},
     * thereby appending the results to the existing environment.
     */
    private String webEnv;
    
    private String apiKey = System.getenv("PUBMED_API_KEY");

    /**
     * Integer query key returned by a previous ESearch, EPost or Elink call.
     */
    private int queryKey = 1;

    /**
     * Returned format for query. xml or json.
     */
    private String retMode = "json";

    /**
     * Optional ESearch sort order, as a literal wire value: for {@code db=pubmed} this tool sends
     * only {@code relevance} or {@code pub_date}. Caller input is mapped to one of those (or to
     * {@code null}) by {@code PubMedArticleRetrievalService.normalizeSort} before it reaches here.
     * <p>
     * {@code null} (the default) means "send no sort parameter", so an absent sort leaves the emitted
     * ESearch request byte-identical to the pre-sort behavior the ReCiter engine relies on. Because
     * the tool searches with {@code usehistory=y}, the sort applied here determines the order of the
     * result set posted to the history server, and therefore the order in which EFetch pulls records
     * back off that {@code WebEnv}. (Merged from dev: sort/retmax support.)
     */
    private String sort;

    public PubmedXmlQuery() {
    }

    public PubmedXmlQuery(String db, String term) {
        this.db = db;
        this.term = term;
    }

    public PubmedXmlQuery(String term) {
        this.term = term;
    }

    /**
     * Constructs a ESearch query String.
     *
     * @return a String in the format http://www.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmax=1&usehistory=y&term=Kukafka%20R[au]
     */
    public String buildESearchQuery() {
        // Java 17: String.formatted() — cleaner than chained StringBuilder appends
        return ESEARCH_BASE_URL
                + buildApiKeyPrefix()
                + "db="        + db
                + "&term="     + term
                + "&retmax="   + retMax
                + "&usehistory=" + useHistory
                + "&retmode="  + retMode
                // Appended only when a sort was explicitly requested, so the default-order URL is
                // byte-identical to what this builder emitted before sort support existed.
                + (sort != null && !sort.isEmpty() ? "&sort=" + sort : "");
    }
    
    /**
     * Construct a EFetch query String.
     *
     * @return a String in the format
     * http://www.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?retmode=xml&db=pubmed&retstart=retstart&retmax=retmax&query_key=1&WebEnv=webenv
     */
    public String buildEFetchQuery() {
        return EFETCH_BASE_URL
                + buildApiKeyPrefix()
                + "db="          + db
                + "&query_key="  + queryKey
                + "&retstart="   + retStart
                + "&retmax="     + retMax
                + "&retmode=xml"
                + "&WebEnv="     + webEnv;
    }
    
    /**
     * Builds the URL prefix segment for the API key.
     *
     * Java 17: replaces duplicated nested null+empty check in both buildESearchQuery()
     * and buildEFetchQuery() with a single extracted method.
     *
     * Returns:
     *   "?api_key=KEY&"  when apiKey is set and non-empty
     *   "?"              otherwise (next segment starts with "db=")
     */
    private String buildApiKeyPrefix() {
        if (apiKey != null && !apiKey.isBlank()) {
            return "?api_key=" + apiKey + "&";
        }
        return "?";
    }

    // ── Matches "api_key=<value>" where value is anything up to the next '&', whitespace, or ']' ──
    private static final Pattern API_KEY_PATTERN = Pattern.compile("api_key=[^&\\s\\]]+");
    
    /**
     * Redacts the {@code api_key} value from a query URL so the NCBI API key is never written to
     * logs (master previously logged the ESearch URL with the key in clear text). The value is
     * replaced with {@code REDACTED} while the rest of the URL is preserved for debugging.
     * (Merged from dev.)
     *
     * @param url a query URL that may contain an {@code api_key} parameter
     * @return the URL with the api_key value redacted, or {@code null} if the input was null
     */
    public static String redactApiKey(String url) {
        if (url == null) {
            return null;
        }
        return API_KEY_PATTERN.matcher(url).replaceAll("api_key=REDACTED");
    }
}

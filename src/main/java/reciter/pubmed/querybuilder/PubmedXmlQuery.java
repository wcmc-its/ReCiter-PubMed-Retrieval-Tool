package reciter.pubmed.querybuilder;

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
    public static final String ESEARCH_BASE_URL = "https://www.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi";
    protected static final String EFETCH_BASE_URL = "https://www.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";

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
    private String retMode = "xml";

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
        StringBuilder sb = new StringBuilder();
        sb.append(ESEARCH_BASE_URL);
        sb.append("?db=");
        sb.append(db);
        sb.append("&term=");
        sb.append(term);
        sb.append("&retmax=");
        sb.append(retMax);
        sb.append("&usehistory=");
        sb.append(useHistory);
        sb.append("&retmode=");
        sb.append(retMode);
        if(apiKey != null) {
	    		if(!apiKey.isEmpty()) {
		        sb.append("&api_key=");
		        sb.append(apiKey);
	    		}
        }
        return sb.toString();
    }

    /**
     * Construct a EFetch query String.
     *
     * @return a String in the format
     * http://www.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?retmode=xml&db=pubmed&retstart=retstart&retmax=retmax&query_key=1&WebEnv=webenv
     */
    public String buildEFetchQuery() {
        StringBuilder sb = new StringBuilder();
        sb.append(EFETCH_BASE_URL);
        sb.append("?db=");
        sb.append(db);
        sb.append("&query_key=");
        sb.append(queryKey);
        sb.append("&retstart=");
        sb.append(retStart);
        sb.append("&retmax=");
        sb.append(retMax);
        sb.append("&retmode=");
        sb.append(retMode);               
        sb.append("&WebEnv=");
        sb.append(webEnv);
        if(apiKey != null) {
        		if(!apiKey.isEmpty()) {
		        sb.append("&api_key=");
		        sb.append(apiKey);
        		}
        }
        return sb.toString();
    }
}
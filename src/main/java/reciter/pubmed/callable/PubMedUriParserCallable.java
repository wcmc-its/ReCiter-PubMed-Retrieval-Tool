package reciter.pubmed.callable;

import lombok.AllArgsConstructor;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

import javax.xml.parsers.SAXParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    /** Time to establish a TCP connection to NCBI for the EFetch fetch. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Read timeout once connected, so a stalled EFetch response can never wedge a worker thread. */
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    /** Only the NCBI E-utilities host may be fetched (SSRF guard on the SAX system-id). */
    private static final String EXPECTED_HOST = "www.ncbi.nlm.nih.gov";

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final InputSource inputSource;

    public List<PubMedArticle> parse(InputSource inputSource) throws SAXException, IOException {
        //inputSource = preprocessSpecialCharacters(inputSource);
        saxParser.parse(inputSource, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
        InputSource inputSource = preprocessSpecialCharacters(this.inputSource);
        return parse(inputSource);
    }

    private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        String xml;
        if (inputSource.getSystemId() != null) {
            URL url = new URL(inputSource.getSystemId());
            if (!EXPECTED_HOST.equalsIgnoreCase(url.getHost())) {
                throw new IOException("Refusing to fetch EFetch XML from unexpected host: " + url.getHost());
            }
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            try (InputStream inputStream = connection.getInputStream()) {
                xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            xml = new String(inputSource.getByteStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        xml = xml.replace("<sup>", "&lt;sup&gt;");
        xml = xml.replace("</sup>", "&lt;/sup&gt;");
        xml = xml.replace("<sub>", "&lt;sub&gt;");
        xml = xml.replace("</sub>", "&lt;/sub&gt;");
        xml = xml.replace("<i>", "&lt;i&gt;");
        xml = xml.replace("</i>", "&lt;/i&gt;");
        xml = xml.replace("<b>", "&lt;b&gt;");
        xml = xml.replace("</b>", "&lt;/b&gt;");
        return new InputSource(new StringReader(xml));
    }
}

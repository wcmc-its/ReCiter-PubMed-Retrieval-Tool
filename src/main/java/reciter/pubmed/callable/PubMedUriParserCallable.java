package reciter.pubmed.callable;

import lombok.AllArgsConstructor;
import org.xml.sax.SAXException;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

import javax.xml.parsers.SAXParser;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final String uri;

    public List<PubMedArticle> parse(String uri) throws SAXException, IOException {
        saxParser.parse(uri, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
        return parse(uri);
    }
}

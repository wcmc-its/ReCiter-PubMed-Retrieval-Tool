package reciter.pubmed.callable;

import com.amazonaws.util.IOUtils;
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
import java.util.List;
import java.util.concurrent.Callable;

@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final InputSource inputSource;

    public List<PubMedArticle> parse(InputSource inputSource) throws SAXException, IOException {
        inputSource = preprocessSpecialCharacters(inputSource);
        saxParser.parse(inputSource, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
        return parse(inputSource);
    }

    private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        InputStream inputStream;
        if (inputSource.getSystemId() != null) {
            inputStream = new URL(inputSource.getSystemId()).openStream();
        } else {
            inputStream = inputSource.getByteStream();
        }
        String xml = IOUtils.toString(inputStream);
        xml = xml.replace("<sup>", "&lt;sup&gt;");
        xml = xml.replace("</sup>", "&lt;/sup&gt;");
        return new InputSource(new StringReader(xml));
    }
}

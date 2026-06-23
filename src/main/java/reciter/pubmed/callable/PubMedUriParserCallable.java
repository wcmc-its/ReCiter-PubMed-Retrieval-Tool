package reciter.pubmed.callable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.xml.parsers.SAXParser;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.AllArgsConstructor;
import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.xmlparser.PubmedEFetchHandler;

@AllArgsConstructor
public class PubMedUriParserCallable implements Callable<List<PubMedArticle>> {

    private final PubmedEFetchHandler xmlHandler;
    private final SAXParser saxParser;
    private final InputSource inputSource;
    
    private static final Map<String, String> TAG_REPLACEMENTS = Map.of(
            "<sup>",  "&lt;sup&gt;",
            "</sup>", "&lt;/sup&gt;",
            "<sub>",  "&lt;sub&gt;",
            "</sub>", "&lt;/sub&gt;",
            "<i>",    "&lt;i&gt;",
            "</i>",   "&lt;/i&gt;",
            "<b>",    "&lt;b&gt;",
            "</b>",   "&lt;/b&gt;"
    );
    
    private static final String TAG_PATTERN = String.join("|",
            TAG_REPLACEMENTS.keySet().stream()
                    .map(java.util.regex.Pattern::quote)
                    .toList()
    );


    public List<PubMedArticle> parse(InputSource inputSource) throws SAXException, IOException {
        saxParser.parse(inputSource, xmlHandler);
        return xmlHandler.getPubmedArticles();
    }

    public List<PubMedArticle> call() throws Exception {
    	InputSource processed = preprocessSpecialCharacters(this.inputSource);
        return parse(processed);
    }

    private InputSource preprocessSpecialCharacters(InputSource inputSource) throws IOException {
        // Resolve InputStream from either a URL (systemId) or a byte stream
        InputStream inputStream = (inputSource.getSystemId() != null)
                ? URI.create(inputSource.getSystemId()).toURL().openStream()
                : inputSource.getByteStream();

        String xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

        xml = java.util.regex.Pattern.compile(TAG_PATTERN).matcher(xml).replaceAll(match -> TAG_REPLACEMENTS.get(match.group()));

        return new InputSource(new StringReader(xml));
    }
}

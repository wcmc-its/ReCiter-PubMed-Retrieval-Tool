package reciter.pubmed.xmlparser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * A SAX handler for parsing the ESearch query from PubMed.
 *
 * @author Jie
 */
@Slf4j
public class PubmedESearchHandler extends DefaultHandler {

    private String webEnv;
    private int count;
    private boolean bWebEnv;
    private boolean bCount;
    private int numCountEncounteredSoFar = 0;

    private static ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private StringBuilder chars = new StringBuilder();

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

        chars.setLength(0);

        if (qName.equalsIgnoreCase("WebEnv")) {
            bWebEnv = true;
        }
        if (qName.equalsIgnoreCase("Count") && numCountEncounteredSoFar == 0) {
            numCountEncounteredSoFar++;
            bCount = true;
        }
    }

    @Override
    public void characters(char ch[], int start, int length) throws SAXException {
        if (bWebEnv) {
            chars.append(ch, start, length);
        }
        if (bCount) {
            chars.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // WebEnv
        if (bWebEnv) {
            webEnv = chars.toString();
            bWebEnv = false;
        }

        // Count.
        if (bCount) {
            count = Integer.parseInt(chars.toString());
            bCount = false;
        }
    }

    public String getWebEnv() {
        return webEnv;
    }

    public int getCount() {
        return count;
    }
}

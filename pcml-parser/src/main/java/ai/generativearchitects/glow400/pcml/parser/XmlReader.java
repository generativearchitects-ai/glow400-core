package ai.generativearchitects.glow400.pcml.parser;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

/**
 * Reads XML into an {@link Element} tree with the JDK parser, securely configured: the external DTD
 * subset and external entities are never loaded, and the JDK's entity expansion limits apply.
 * A {@code DOCTYPE} declaration is tolerated (RPG compilers emit one) but not processed.
 * Comments are ignored.
 */
final class XmlReader {

    private XmlReader() {
    }

    /** Thrown when the XML is not well-formed. */
    static final class MalformedXmlException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int line;
        private final int column;

        MalformedXmlException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        int line() {
            return Math.max(line, 0);
        }

        int column() {
            return Math.max(column, 0);
        }
    }

    static Element read(String sourceName, InputStream in) throws MalformedXmlException, IOException {
        TreeHandler handler = new TreeHandler(sourceName);
        try {
            SAXParser parser = newParser();
            var reader = parser.getXMLReader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.parse(new InputSource(in));
        } catch (SAXParseException e) {
            throw new MalformedXmlException(e.getMessage(), e.getLineNumber(), e.getColumnNumber());
        } catch (SAXException e) {
            throw new MalformedXmlException(e.getMessage(), 0, 0);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The JDK XML parser cannot be configured securely", e);
        }
        return handler.root;
    }

    private static SAXParser newParser() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        SAXParser parser = factory.newSAXParser();
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return parser;
    }

    /**
     * Builds the tree. SAX reports the position where an event ends, so the start of an element is
     * taken from where the previous event ended; this is correct for start tags that span lines.
     */
    private static final class TreeHandler extends DefaultHandler2 {

        private final String sourceName;
        private final Deque<Element> stack = new ArrayDeque<>();
        private Element root;
        private Locator locator;
        private int previousLine = -1;
        private int previousColumn = -1;

        TreeHandler(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        private void markEventEnd() {
            if (locator != null) {
                previousLine = locator.getLineNumber();
                previousColumn = locator.getColumnNumber();
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            int line = previousLine >= 0 ? previousLine : locator.getLineNumber();
            int column = previousLine >= 0 ? previousColumn : locator.getColumnNumber();
            Element element = new Element(qName, new SourceLocation(sourceName, Math.max(line, 0), Math.max(column, 0)));
            for (int i = 0; i < attributes.getLength(); i++) {
                element.attributes().put(attributes.getQName(i), attributes.getValue(i));
            }
            if (stack.isEmpty()) {
                root = element;
            } else {
                stack.peek().children().add(element);
            }
            stack.push(element);
            markEventEnd();
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            stack.pop();
            markEventEnd();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            markTextEnd();
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
            markTextEnd();
        }

        /** Text always ends at a '<', which the parser has already consumed when it reports the text. */
        private void markTextEnd() {
            markEventEnd();
            if (previousColumn > 1) {
                previousColumn--;
            }
        }

        @Override
        public void comment(char[] ch, int start, int length) {
            markEventEnd();
        }

        @Override
        public void processingInstruction(String target, String data) {
            markEventEnd();
        }

        @Override
        public void endDTD() {
            markEventEnd();
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }
    }
}

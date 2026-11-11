package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * The XML parser's configuration, its wiring to the tree builder, and the start positions it records. The configuration
 * is tested on its own because several of its options make no difference to a well-behaved document: a document cannot
 * show that an option was set, but the parser can.
 */
class XmlReaderTest {

    private static final String EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler";

    private static ParseResult parse(String xml) {
        return PcmlParser.parse("x.pcml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- configuration

    @Test
    void hardeningTurnsEveryOptionOffEvenWhenTheFactoryStartsWithItOn() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(true);
        factory.setXIncludeAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);
        factory.setFeature(EXTERNAL_GENERAL, true);
        factory.setFeature(EXTERNAL_PARAMETER, true);
        factory.setFeature(LOAD_EXTERNAL_DTD, true);

        assertThat(XmlReader.harden(factory)).isSameAs(factory);

        assertThat(factory.isNamespaceAware()).isFalse();
        assertThat(factory.isValidating()).isFalse();
        assertThat(factory.isXIncludeAware()).isFalse();
        assertThat(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
        assertThat(factory.getFeature(EXTERNAL_GENERAL)).isFalse();
        assertThat(factory.getFeature(EXTERNAL_PARAMETER)).isFalse();
        assertThat(factory.getFeature(LOAD_EXTERNAL_DTD)).isFalse();
    }

    @Test
    void theParserDeniesAccessToExternalDtdsAndSchemas() throws Exception {
        SAXParser parser = XmlReader.newParser();
        assertThat(parser.getProperty(XMLConstants.ACCESS_EXTERNAL_DTD)).isEqualTo("");
        assertThat(parser.getProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA)).isEqualTo("");
        XMLReader reader = parser.getXMLReader();
        assertThat(reader.getFeature(EXTERNAL_GENERAL)).isFalse();
        assertThat(reader.getFeature(EXTERNAL_PARAMETER)).isFalse();
        assertThat(reader.getFeature(LOAD_EXTERNAL_DTD)).isFalse();
        assertThat(reader.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
        assertThat(parser.isNamespaceAware()).isFalse();
        assertThat(parser.isValidating()).isFalse();
    }

    /** A factory that does what the real one does and remembers every property set on the parsers it makes. */
    private static final class RecordingFactory extends SAXParserFactory {
        private final SAXParserFactory real = SAXParserFactory.newInstance();
        private final Map<String, Object> properties = new LinkedHashMap<>();

        @Override
        public SAXParser newSAXParser() throws ParserConfigurationException, SAXException {
            SAXParser parser = real.newSAXParser();
            return new SAXParser() {
                @Override
                public org.xml.sax.Parser getParser() throws SAXException {
                    return parser.getParser();
                }

                @Override
                public XMLReader getXMLReader() throws SAXException {
                    return parser.getXMLReader();
                }

                @Override
                public boolean isNamespaceAware() {
                    return parser.isNamespaceAware();
                }

                @Override
                public boolean isValidating() {
                    return parser.isValidating();
                }

                @Override
                public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
                    properties.put(name, value);
                    parser.setProperty(name, value);
                }

                @Override
                public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
                    return parser.getProperty(name);
                }
            };
        }

        @Override
        public void setFeature(String name, boolean value)
                throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
            real.setFeature(name, value);
        }

        @Override
        public boolean getFeature(String name)
                throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
            return real.getFeature(name);
        }
    }

    @Test
    void accessToExternalDtdsAndSchemasIsDeniedExplicitlyOnTheParser() throws Exception {
        // Setting secure processing already clears these on today's JDK; they are set anyway, and this shows it.
        RecordingFactory factory = new RecordingFactory();
        XmlReader.newParser(factory);
        assertThat(factory.properties).containsExactly(
                Map.entry(XMLConstants.ACCESS_EXTERNAL_DTD, ""), Map.entry(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""));
    }

    @Test
    void theHandlerIsConnectedForContentErrorsEntitiesAndComments() throws Exception {
        XMLReader reader = XmlReader.newParser().getXMLReader();
        XmlReader.TreeHandler handler = new XmlReader.TreeHandler("x");
        XmlReader.connect(reader, handler);
        assertThat(reader.getContentHandler()).isSameAs(handler);
        assertThat(reader.getErrorHandler()).isSameAs(handler);
        assertThat(reader.getEntityResolver()).isSameAs(handler);
        assertThat(reader.getProperty(LEXICAL_HANDLER)).isSameAs(handler);
    }

    @Test
    void theHandlerResolvesEveryExternalEntityToNothing() throws Exception {
        XmlReader.TreeHandler handler = new XmlReader.TreeHandler("x");
        for (InputSource source : new InputSource[] {
                handler.resolveEntity("name", "-//PUBLIC", "file:///base/", "file:///etc/passwd"),
                handler.resolveEntity("-//PUBLIC", "file:///etc/passwd")}) {
            assertThat(source).isNotNull();
            assertThat(source.getCharacterStream()).isNotNull();
            assertThat(source.getCharacterStream().read()).isEqualTo(-1);
        }
    }

    @Test
    void recoverableXmlErrorsAreNotIgnored() {
        XmlReader.TreeHandler handler = new XmlReader.TreeHandler("x");
        SAXParseException problem = new SAXParseException("not valid", null, "id", 3, 4);
        assertThatThrownBy(() -> handler.error(problem)).isSameAs(problem);
    }

    @Test
    void aNamespaceDeclarationIsKeptAsAnAttributeLikeAnyOther() {
        ParseResult result = parse("<pcml version=\"6.0\" xmlns:x=\"urn:example\"><program name=\"P\"/></pcml>");
        assertThat(result.document().orElseThrow().attributes()).containsEntry("xmlns:x", "urn:example");
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("PCML007");
    }

    // ---- positions

    @Test
    void theRootElementStartsRightAfterADoctypeACommentOrAProcessingInstruction() {
        // A root without 'version' is reported at the root element, which shows where the parser thinks it starts.
        assertThat(parse("<!DOCTYPE pcml SYSTEM \"x.dtd\"><pcml/>").diagnostics().get(0).location())
                .isEqualTo(new SourceLocation("x.pcml", 1, 31));
        assertThat(parse("<!-- c --><pcml/>").diagnostics().get(0).location()).isEqualTo(new SourceLocation("x.pcml", 1, 11));
        assertThat(parse("<?pi x?><pcml/>").diagnostics().get(0).location()).isEqualTo(new SourceLocation("x.pcml", 1, 9));
    }

    @Test
    void positionsAreCorrectWhenAnInternalDtdMakesWhitespaceIgnorable() throws IOException {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE pcml [
                <!ELEMENT pcml (program)*>
                <!ELEMENT program (data)*>
                <!ELEMENT data EMPTY>
                <!ATTLIST pcml version CDATA #IMPLIED>
                <!ATTLIST program name CDATA #IMPLIED>
                <!ATTLIST data name CDATA #IMPLIED type CDATA #IMPLIED length CDATA #IMPLIED>
                ]>
                <pcml version="6.0">
                  <program name="P">
                    <data name="A" type="char" length="1"/>
                    <data name="B" type="char" length="1"/>
                  </program>
                </pcml>
                """;
        ParseResult result = parse(xml);
        assertThat(result.diagnostics()).isEmpty();
        var program = result.document().orElseThrow().programs().get(0);
        assertThat(program.source()).isEqualTo(new SourceLocation("x.pcml", 11, 3));
        assertThat(program.parameters()).extracting(FieldDef::source).containsExactly(
                new SourceLocation("x.pcml", 12, 5), new SourceLocation("x.pcml", 13, 5));
    }

    @Test
    void anElementThatIsTheFirstEventOfTheDocumentIsPlacedByTheParserItself() {
        // No prolog event precedes the root (an XML declaration is not reported), so the position is where its tag ends.
        SourceLocation at = parse("<pcml/>").diagnostics().get(0).location();
        assertThat(at.line()).isEqualTo(1);
        assertThat(at.column()).isEqualTo(8);
    }
}

package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Where the root element starts, also when a DOCTYPE, comments, instructions or blank lines come first (C15). Diagnostics on
 * the {@code pcml} element, such as a missing {@code version}, are reported there.
 */
class RootLocatorTest {

    private static Optional<RootLocator.Position> find(String xml) {
        return RootLocator.find(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRootAt(String xml, int line, int column) {
        assertThat(find(xml)).as(xml.replace("\n", "\\n")).contains(new RootLocator.Position(line, column));
    }

    private static SourceLocation missingVersionAt(byte[] xml) {
        ParseResult result = PcmlParser.parse("x.pcml", new ByteArrayInputStream(xml));
        return result.diagnostics().stream().filter(d -> d.code().equals("PCML004")).map(Diagnostic::location).findFirst().orElseThrow();
    }

    private static SourceLocation missingVersionAt(String xml) {
        return missingVersionAt(xml.getBytes(StandardCharsets.UTF_8));
    }

    // ---- what the brief names

    @Test
    void blankLinesBeforeTheRoot() {
        assertThat(missingVersionAt("<?xml version=\"1.0\"?>\n\n<pcml/>")).isEqualTo(new SourceLocation("x.pcml", 3, 1));
        assertThat(missingVersionAt("\n\n\n  <pcml/>")).isEqualTo(new SourceLocation("x.pcml", 4, 3));
    }

    @Test
    void aDoctypeBeforeTheRoot() {
        assertThat(missingVersionAt("<!DOCTYPE pcml SYSTEM \"pcml.dtd\">\n<pcml/>")).isEqualTo(new SourceLocation("x.pcml", 2, 1));
        assertThat(missingVersionAt("<!DOCTYPE pcml SYSTEM \"pcml.dtd\">\n\n\t<pcml/>")).isEqualTo(new SourceLocation("x.pcml", 3, 2));
    }

    @Test
    void commentsBeforeTheRoot() {
        assertThat(missingVersionAt("<!-- c -->\n\n  <pcml/>")).isEqualTo(new SourceLocation("x.pcml", 3, 3));
        assertThat(missingVersionAt("<!-- one -->\n<!-- two\nspans lines -->\n<pcml/>")).isEqualTo(new SourceLocation("x.pcml", 4, 1));
    }

    @Test
    void everyKindOfPrologTogether() {
        String xml = "<?xml version=\"1.0\"?>\n<!-- a -->\n<?target data?>\n<!DOCTYPE pcml SYSTEM \"x.dtd\">\n\n<!-- b -->\n   <pcml/>";
        assertThat(missingVersionAt(xml)).isEqualTo(new SourceLocation("x.pcml", 7, 4));
    }

    @Test
    void wrongRootAndUnknownAttributeAreReportedAtTheRootToo() {
        ParseResult wrong = PcmlParser.parse("x.pcml", new ByteArrayInputStream("<!-- c -->\n\n<other/>".getBytes(StandardCharsets.UTF_8)));
        assertThat(wrong.diagnostics()).extracting(Diagnostic::code, d -> d.location().line(), d -> d.location().column())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PCML001", 3, 1));
        ParseResult unknown = PcmlParser.parse("x.pcml", new ByteArrayInputStream(
                "<!DOCTYPE pcml SYSTEM \"x.dtd\">\n <pcml version=\"6.0\" mood=\"x\"/>".getBytes(StandardCharsets.UTF_8)));
        assertThat(unknown.diagnostics()).extracting(Diagnostic::code, d -> d.location().line(), d -> d.location().column())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PCML007", 2, 2));
    }

    @Test
    void aRootWithNothingBeforeItIsAtTheStart() {
        assertThat(missingVersionAt("<pcml/>")).isEqualTo(new SourceLocation("x.pcml", 1, 1));
        assertThat(missingVersionAt("<?xml version=\"1.0\"?><pcml/>")).isEqualTo(new SourceLocation("x.pcml", 1, 22));
    }

    // ---- scanning

    @Test
    void theDoctypeInternalSubsetMayContainMarkupCharactersInQuotesCommentsAndInstructions() {
        String xml = "<!DOCTYPE pcml [\n"
                + "  <!ENTITY e \"a>b]c\">\n"
                + "  <!ENTITY f 'x]y>'>\n"
                + "  <!-- ] > <pcml/> -->\n"
                + "  <?pi ]> ?>\n"
                + "]>\n"
                + "<pcml/>";
        assertRootAt(xml, 7, 1);
    }

    @Test
    void aDoctypeWithQuotedIdentifiersAndNoSubset() {
        assertRootAt("<!DOCTYPE pcml PUBLIC \"-//X//DTD>\" 'a>b'>\n<pcml/>", 2, 1);
    }

    @Test
    void aCommentOrInstructionMayContainTheTextOfAnElement() {
        assertRootAt("<!-- <pcml/> -->\n<?x <pcml/> ?>\n<pcml/>", 3, 1);
    }

    @Test
    void aRootNameStartsWithALetterUnderscoreColonOrANonAsciiCharacter() {
        for (String start : new String[] {"a", "Z", "_", ":", "é", "中"}) {
            assertRootAt("<!-- c -->\n<" + start + "x/>", 2, 1);
        }
    }

    @Test
    void whatIsNotAnElementStartIsNotARoot() {
        for (String xml : new String[] {"", "   ", "<!-- only a comment -->", "text<pcml/>", "< pcml/>", "<1x/>", "<-x/>", "<", "<!-- a --> <",
                "<!x><pcml/>", "<!DOCTYPE", "</pcml>"}) {
            assertThat(find(xml)).as("'" + xml + "'").isEmpty();
        }
    }

    @Test
    void anUnterminatedPrologConstructGivesNoPosition() {
        for (String xml : new String[] {"<!-- never closed <pcml/>", "<?never closed <pcml/>", "<!DOCTYPE pcml [ <!-- never closed ]> <pcml/>",
                "<!DOCTYPE pcml \"never closed <pcml/>", "<!DOCTYPE pcml [ <!ENTITY e \"never closed ]> <pcml/>",
                "<!DOCTYPE pcml [ <?never closed ]> <pcml/>", "<!DOCTYPE pcml [ ... <pcml/>", "<!DOCTYPE pcml SYSTEM 'x' <pcml/>"}) {
            assertThat(find(xml)).as(xml).isEmpty();
        }
    }

    // ---- line ends and columns

    @Test
    void aLineEndsAtLfCrOrCrLfAndAColumnCountsCharacters() {
        assertRootAt("<!-- a -->\r\n\r\n  <pcml/>", 3, 3);
        assertRootAt("<!-- a -->\r\r  <pcml/>", 3, 3);
        assertRootAt("<!-- a -->\n\r  <pcml/>", 3, 3);
        assertRootAt("<!-- a -->\r\n  <pcml/>", 2, 3);
        assertRootAt("<!-- a --> \t <pcml/>", 1, 14);
    }

    @Test
    void aColumnCountsCharactersNotBytes() {
        String comment = "<!-- größe 注文 -->";
        assertRootAt(comment + " <pcml/>", 1, comment.length() + 2);
    }

    // ---- encodings

    private static Optional<RootLocator.Position> find(String xml, Charset charset, byte... prefix) {
        byte[] body = xml.getBytes(charset);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        return RootLocator.find(all);
    }

    @Test
    void aByteOrderMarkIsNotAColumn() {
        String xml = "<?xml version=\"1.0\"?>\n<pcml/>";
        assertThat(find(xml, StandardCharsets.UTF_8, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)).contains(new RootLocator.Position(2, 1));
        assertThat(find("<!-- c --><pcml/>", StandardCharsets.UTF_8, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF))
                .contains(new RootLocator.Position(1, 11));
        assertThat(find("<!-- c --><pcml/>", StandardCharsets.UTF_16BE, (byte) 0xFE, (byte) 0xFF)).contains(new RootLocator.Position(1, 11));
        assertThat(find("<!-- c --><pcml/>", StandardCharsets.UTF_16LE, (byte) 0xFF, (byte) 0xFE)).contains(new RootLocator.Position(1, 11));
    }

    @Test
    void utf16WithoutAByteOrderMarkIsRecognisedFromTheFirstCharacters() {
        assertThat(find("<?xml version=\"1.0\"?>\n\n<pcml/>", StandardCharsets.UTF_16BE)).contains(new RootLocator.Position(3, 1));
        assertThat(find("<?xml version=\"1.0\"?>\n\n<pcml/>", StandardCharsets.UTF_16LE)).contains(new RootLocator.Position(3, 1));
    }

    @Test
    void theDeclaredEncodingIsUsed() {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<!-- größe --> <pcml/>";
        assertThat(find(xml, StandardCharsets.ISO_8859_1)).contains(new RootLocator.Position(2, 16));
        String declaration = "<?xml version='1.0' encoding = 'windows-1252' ?>";
        String comment = "<!-- \u20ac -->";
        assertThat(find(declaration + comment + "<pcml/>", Charset.forName("windows-1252")))
                .contains(new RootLocator.Position(1, declaration.length() + comment.length() + 1));
    }

    @Test
    void aMissingOrUnknownEncodingDeclaration() {
        assertThat(find("<?xml version=\"1.0\"?>\n<pcml/>")).contains(new RootLocator.Position(2, 1));
        assertThat(find("<?xml version=\"1.0\" encoding=\"no-such-encoding\"?>\n<pcml/>")).isEmpty();
        assertThat(find("<?xml version=\"1.0\" encoding=\"bad name\"?>\n<pcml/>")).contains(new RootLocator.Position(2, 1));
        // The declaration is only looked for at the very start; elsewhere it is just a processing instruction.
        String late = "<?xml version=\"1.0\" encoding=\"no-such-encoding\"?>";
        assertThat(find("\n" + late + "<pcml/>")).contains(new RootLocator.Position(2, late.length() + 1));
        assertThat(find("<!-- <?xml encoding=\"no-such-encoding\"?> -->\n<pcml/>")).contains(new RootLocator.Position(2, 1));
    }

    @Test
    void anEncodingDeclarationFarFromTheStartIsNotLookedFor() {
        String padding = " ".repeat(300);
        assertThat(find("<?xml version=\"1.0\"" + padding + " encoding=\"no-such-encoding\"?>\n<pcml/>")).contains(new RootLocator.Position(2, 1));
    }

    @Test
    void aDocumentThatIsNotUsableGivesTheParserItsOwnPosition() {
        // Unterminated prolog: the parser reports PCML001 anyway.
        ParseResult result = PcmlParser.parse("x.pcml", new ByteArrayInputStream("<!-- never closed".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("PCML001");
    }
}

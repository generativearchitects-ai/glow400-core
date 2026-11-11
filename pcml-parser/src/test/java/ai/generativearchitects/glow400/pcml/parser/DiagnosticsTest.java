package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One test per diagnostic code: code, severity and source line. */
class DiagnosticsTest {

    private record Expected(String code, Severity severity, int line) {
    }

    private static Expected e(String code, Severity severity, int line) {
        return new Expected(code, severity, line);
    }

    private static List<Expected> actual(ParseResult result) {
        return result.diagnostics().stream()
                .map(d -> new Expected(d.code(), d.severity(), d.location().line()))
                .toList();
    }

    @Test
    void pcml001NotWellFormed() {
        ParseResult result = Corpus.parse("invalid/pcml001-malformed.pcml");
        assertThat(actual(result)).containsExactly(e("PCML001", Severity.ERROR, 5));
        assertThat(result.document()).isEmpty();
    }

    @Test
    void pcml001WrongRoot() {
        ParseResult result = Corpus.parse("invalid/pcml001-wrong-root.pcml");
        assertThat(actual(result)).containsExactly(e("PCML001", Severity.ERROR, 2));
        assertThat(result.document()).isEmpty();
    }

    @Test
    void pcml002UndefinedStruct() {
        ParseResult result = Corpus.parse("invalid/pcml002-undefined-struct.pcml");
        assertThat(actual(result)).containsExactly(e("PCML002", Severity.ERROR, 4));
        assertThat(result.document()).isPresent();
    }

    @Test
    void pcml003UnresolvedCountAndLength() {
        ParseResult result = Corpus.parse("invalid/pcml003-unresolved-reference.pcml");
        assertThat(actual(result)).containsExactly(e("PCML003", Severity.ERROR, 7), e("PCML003", Severity.ERROR, 8));
    }

    @Test
    void pcml004MissingAndInvalidAttributes() {
        ParseResult result = Corpus.parse("invalid/pcml004-invalid-or-missing-attribute.pcml");
        assertThat(actual(result)).containsExactly(
                e("PCML004", Severity.ERROR, 4),   // data without name
                e("PCML004", Severity.ERROR, 5),   // data without type
                e("PCML004", Severity.ERROR, 6),   // unknown type
                e("PCML004", Severity.ERROR, 7),   // unknown usage
                e("PCML004", Severity.ERROR, 8),   // char without length
                e("PCML004", Severity.ERROR, 9),   // packed without precision
                e("PCML004", Severity.ERROR, 10),  // struct without struct attribute
                e("PCML004", Severity.ERROR, 12)); // program without name
    }

    @Test
    void pcml004KeepsEverythingThatCouldBeModelled() {
        ProgramDef program = Corpus.parse("invalid/pcml004-invalid-or-missing-attribute.pcml")
                .document().orElseThrow().programs().get(0);
        // Only the field with a bad usage survives: it is kept as INHERIT, with the bad value preserved.
        assertThat(program.parameters()).extracting(FieldDef::name).containsExactly("BADUSAGE");
        assertThat(program.parameters().get(0).attributes()).containsEntry("usage", "sideways");
    }

    @Test
    void pcml005Duplicates() {
        ParseResult result = Corpus.parse("invalid/pcml005-duplicate-names.pcml");
        assertThat(actual(result)).containsExactly(
                e("PCML005", Severity.ERROR, 6),    // second struct S
                e("PCML005", Severity.ERROR, 11),   // second field A
                e("PCML005", Severity.ERROR, 13));  // second program P
    }

    @Test
    void pcml006CircularStruct() {
        ParseResult result = Corpus.parse("invalid/pcml006-circular-struct.pcml");
        assertThat(actual(result)).containsExactly(e("PCML006", Severity.WARNING, 3));
        assertThat(result.diagnostics().get(0).message()).contains("NODE -> OTHER -> NODE");
    }

    @Test
    void pcml007UnknownElementAndAttribute() {
        ParseResult result = Corpus.parse("invalid/pcml007-unknown-element-attribute.pcml");
        assertThat(actual(result)).containsExactly(e("PCML007", Severity.INFO, 4), e("PCML007", Severity.INFO, 5));
    }

    @Test
    void selfReferenceIsCircular() {
        String xml = "<pcml version=\"6.0\">\n<struct name=\"S\">\n<data name=\"ME\" type=\"struct\" struct=\"S\"/>\n</struct>\n</pcml>";
        ParseResult result = PcmlParser.parse("inline", new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(actual(result)).containsExactly(e("PCML006", Severity.WARNING, 2));
    }

    @Test
    void dottedReferenceIntoAStructResolves() {
        String xml = """
                <pcml version="6.0">
                  <struct name="HDR"><data name="N" type="int" length="4"/></struct>
                  <program name="P">
                    <data name="H" type="struct" struct="HDR" usage="input"/>
                    <data name="ROWS" type="char" length="1" count="H.N" usage="output"/>
                    <data name="BAD" type="char" length="1" count="H.NOPE" usage="output"/>
                  </program>
                </pcml>
                """;
        ParseResult result = PcmlParser.parse("inline", new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(actual(result)).containsExactly(e("PCML003", Severity.ERROR, 6));
    }

    @Test
    void missingVersionIsReported() {
        String xml = "<pcml><program name=\"P\"/></pcml>";
        ParseResult result = PcmlParser.parse("inline", new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(actual(result)).containsExactly(e("PCML004", Severity.ERROR, 1));
        assertThat(result.document().orElseThrow().version()).isEmpty();
    }

    @Test
    void nonNumericCcsidIsKeptInTheAttributeMap() {
        String xml = "<pcml version=\"6.0\"><program name=\"P\"><data name=\"A\" type=\"char\" length=\"1\" ccsid=\"CS\"/></program></pcml>";
        ParseResult result = PcmlParser.parse("inline", new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(result.diagnostics()).isEmpty();
        FieldDef field = result.document().orElseThrow().programs().get(0).parameters().get(0);
        assertThat(field.attributes()).containsEntry("ccsid", "CS");
    }
}

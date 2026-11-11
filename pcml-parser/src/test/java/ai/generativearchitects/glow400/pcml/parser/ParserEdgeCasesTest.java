package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Error paths and corner cases that the corpus files do not exercise. */
class ParserEdgeCasesTest {

    private static ParseResult parse(String xml) {
        return PcmlParser.parse("edge.pcml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String program(String body) {
        return "<pcml version=\"6.0\">\n<program name=\"P\">\n" + body + "\n</program>\n</pcml>";
    }

    static Stream<Arguments> singleErrors() {
        return Stream.of(
                Arguments.of("varlength other than 2 or 4", program("<data name=\"A\" type=\"varchar\" length=\"5\" varlength=\"3\"/>"), "PCML004", 3),
                Arguments.of("varchar without length", program("<data name=\"A\" type=\"varchar\"/>"), "PCML004", 3),
                Arguments.of("empty length", program("<data name=\"A\" type=\"char\" length=\"\"/>"), "PCML004", 3),
                Arguments.of("length out of int range", program("<data name=\"A\" type=\"char\" length=\"99999999999\"/>"), "PCML004", 3),
                Arguments.of("empty count", program("<data name=\"A\" type=\"char\" length=\"1\" count=\"\"/>"), "PCML004", 3),
                Arguments.of("count out of int range", program("<data name=\"A\" type=\"char\" length=\"1\" count=\"99999999999\"/>"), "PCML004", 3),
                Arguments.of("non-numeric packed length", program("<data name=\"A\" type=\"packed\" length=\"X\" precision=\"2\"/>"), "PCML004", 3),
                Arguments.of("non-numeric int precision", program("<data name=\"A\" type=\"int\" length=\"4\" precision=\"X\"/>"), "PCML004", 3),
                Arguments.of("float without length", program("<data name=\"A\" type=\"float\"/>"), "PCML004", 3),
                Arguments.of("nested struct without name", program("<struct><data name=\"A\" type=\"char\" length=\"1\"/></struct>"), "PCML004", 3),
                Arguments.of("nested struct with bad usage", program("<struct name=\"N\" usage=\"up\"><data name=\"A\" type=\"char\" length=\"1\"/></struct>"), "PCML004", 3),
                Arguments.of("nested struct with empty count", program("<struct name=\"N\" count=\"\"><data name=\"A\" type=\"char\" length=\"1\"/></struct>"), "PCML004", 3),
                Arguments.of("top-level struct without name", "<pcml version=\"6.0\">\n<struct><data name=\"A\" type=\"char\" length=\"1\"/></struct>\n</pcml>", "PCML004", 2),
                Arguments.of("element inside data", program("<data name=\"A\" type=\"char\" length=\"1\"><extra/></data>"), "PCML007", 3),
                Arguments.of("unknown attribute on pcml", "<pcml version=\"6.0\" mood=\"x\"/>", "PCML007", 1),
                Arguments.of("unknown attribute on program", "<pcml version=\"6.0\">\n<program name=\"P\" mood=\"x\"/>\n</pcml>", "PCML007", 2),
                Arguments.of("unknown attribute on top-level struct", "<pcml version=\"6.0\">\n<struct name=\"S\" mood=\"x\"><data name=\"A\" type=\"char\" length=\"1\"/></struct>\n</pcml>", "PCML007", 2),
                Arguments.of("unknown attribute on nested struct", program("<struct name=\"N\" mood=\"x\"><data name=\"A\" type=\"char\" length=\"1\"/></struct>"), "PCML007", 3),
                Arguments.of("duplicate nested struct names", program("<struct name=\"N\"><data name=\"A\" type=\"char\" length=\"1\"/></struct>\n<struct name=\"N\"><data name=\"B\" type=\"char\" length=\"1\"/></struct>"), "PCML005", 4));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleErrors")
    void reportsExactlyOneDiagnostic(String description, String xml, String code, int line) {
        ParseResult result = parse(xml);
        assertThat(result.diagnostics()).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.location().line()).isEqualTo(line);
        assertThat(d.severity()).isEqualTo(code.equals("PCML007") ? Severity.INFO : Severity.ERROR);
    }

    @Test
    void varcharWithFieldReferenceLengthAndFourByteLengthPrefix() {
        ParseResult result = parse(program(
                "<data name=\"N\" type=\"int\" length=\"4\"/>\n<data name=\"A\" type=\"varchar\" length=\"N\" varlength=\"4\" ccsid=\"500\"/>"));
        assertThat(result.diagnostics()).isEmpty();
        FieldDef field = result.document().orElseThrow().programs().get(0).parameters().get(1);
        assertThat(field.wire()).isEqualTo(new WireType.VarChar(new Length.Ref("N"), Optional.of(4), Optional.of(500)));
    }

    @Test
    void zeroIsAValidCountAndLength() {
        ParseResult result = parse(program("<data name=\"A\" type=\"byte\" length=\"0\" count=\"0\"/>"));
        assertThat(result.diagnostics()).isEmpty();
        FieldDef field = result.document().orElseThrow().programs().get(0).parameters().get(0);
        assertThat(field.wire()).isEqualTo(new WireType.Byte(new Length.Fixed(0)));
        assertThat(field.count()).isEqualTo(new Count.Fixed(0));
    }

    @Test
    void diagnosticsAreOrderedByPositionNotByDiscovery() {
        // The struct is checked after the program, but comes first in the file.
        String xml = "<pcml version=\"6.0\">\n"
                + "<struct name=\"S\"><data name=\"A\" type=\"char\" length=\"NOPE\"/></struct>\n"
                + "<program name=\"P\"><data name=\"B\" type=\"char\" length=\"NOPE2\"/></program>\n"
                + "</pcml>";
        assertThat(parse(xml).diagnostics()).extracting(d -> d.location().line()).containsExactly(2, 3);
    }

    @Test
    void diagnosticsOnTheSameLineAreOrderedByColumn() {
        String xml = "<pcml version=\"6.0\"><program name=\"P\"><data name=\"A\" type=\"char\" length=\"N1\"/><data name=\"B\" type=\"char\" length=\"N2\"/></program></pcml>";
        assertThat(parse(xml).diagnostics()).extracting(d -> d.location().column()).isSorted().hasSize(2);
    }

    @Test
    void hasErrorsIsFalseForWarningsAndInfo() {
        ParseResult infoOnly = parse("<pcml version=\"6.0\" mood=\"x\"/>");
        assertThat(infoOnly.diagnostics()).isNotEmpty();
        assertThat(infoOnly.hasErrors()).isFalse();
        assertThat(parse("<pcml version=\"6.0\"/>").hasErrors()).isFalse();
        assertThat(parse(program("<data name=\"A\" type=\"x\"/>")).hasErrors()).isTrue();
    }

    @Test
    void diagnosticTextContainsLocationSeverityCodeAndMessage() {
        Diagnostic d = new Diagnostic("PCML004", Severity.ERROR, "boom", new SourceLocation("a.pcml", 3, 7));
        assertThat(d.toString()).isEqualTo("a.pcml:3:7: error PCML004: boom");
    }

    @Test
    void malformedXmlReportsLineAndColumnOfTheProblem() {
        ParseResult result = Corpus.parse("invalid/pcml001-malformed.pcml");
        SourceLocation at = result.diagnostics().get(0).location();
        assertThat(at.line()).isEqualTo(5);
        assertThat(at.column()).isPositive();
    }

    @Test
    void positionsAreCorrectAfterEndTagsInstructionsAndDoctype() {
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE pcml SYSTEM \"pcml.dtd\">\n"
                + "<?note something?><pcml version=\"6.0\"><program name=\"P\"><struct name=\"N\"><data name=\"A\" type=\"char\" length=\"1\"/></struct><data name=\"B\" type=\"char\" length=\"1\"/>\n"
                + "<?pi x?><data name=\"C\" type=\"char\" length=\"1\"/></program></pcml>";
        var parameters = parse(xml).document().orElseThrow().programs().get(0).parameters();
        assertThat(parameters).extracting(FieldDef::name).containsExactly("N", "B", "C");
        assertThat(parameters.get(1).source()).isEqualTo(new SourceLocation("edge.pcml", 3, 122));
        assertThat(parameters.get(2).source()).isEqualTo(new SourceLocation("edge.pcml", 4, 9));
    }

    // ---- references that start with the program name (PCML003)

    @Test
    void aReferenceCanBeWrittenWithTheProgramNameInFront() {
        // HDR refers to the top-level struct H; the reference goes through it.
        String xml = "<pcml version=\"6.0\">\n<struct name=\"H\"><data name=\"TOTAL\" type=\"int\" length=\"4\"/></struct>\n"
                + "<program name=\"P\">\n<data name=\"N\" type=\"int\" length=\"4\"/>\n<data name=\"HDR\" type=\"struct\" struct=\"H\"/>\n"
                + "<data name=\"A\" type=\"char\" length=\"1\" count=\"P.N\"/>\n"
                + "<data name=\"B\" type=\"char\" length=\"1\" count=\"P.HDR.TOTAL\"/>\n</program>\n</pcml>";
        assertThat(parse(xml).diagnostics()).isEmpty();
    }

    @Test
    void aReferenceWithAnotherProgramNameOrOnlyTheProgramNameIsUnresolved() {
        for (String reference : new String[] {"Q.N", "P", "P.NOPE", "P.N.X"}) {
            ParseResult result = parse(program("<data name=\"N\" type=\"int\" length=\"4\"/>\n"
                    + "<data name=\"A\" type=\"char\" length=\"1\" count=\"" + reference + "\"/>"));
            assertThat(result.diagnostics()).as(reference).extracting(Diagnostic::code, d -> d.location().line())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("PCML003", 4));
        }
    }

    // ---- numbers

    @Test
    void aValueThatMerelyStartsOrEndsLikeANumberIsAReference() {
        for (String value : new String[] {"1:", ":1", "/", "1/", "/1", "1a", "a1", "-1", "+1", "1.5", " 1", "1 "}) {
            ParseResult result = parse(program("<data name=\"A\" type=\"char\" length=\"" + value + "\"/>"));
            FieldDef field = result.document().orElseThrow().programs().get(0).parameters().get(0);
            assertThat(field.wire()).as(value).isEqualTo(new WireType.Char(new Length.Ref(value), Optional.empty()));
        }
    }

    @Test
    void everyDigitIsPartOfANumber() {
        ParseResult result = parse(program("<data name=\"A\" type=\"char\" length=\"0123456789\"/>\n"
                + "<data name=\"B\" type=\"char\" length=\"09\" count=\"90\"/>"));
        assertThat(result.diagnostics()).isEmpty();
        var parameters = result.document().orElseThrow().programs().get(0).parameters();
        assertThat(parameters.get(0).wire()).isEqualTo(new WireType.Char(new Length.Fixed(123456789), Optional.empty()));
        assertThat(parameters.get(1).wire()).isEqualTo(new WireType.Char(new Length.Fixed(9), Optional.empty()));
        assertThat(parameters.get(1).count()).isEqualTo(new Count.Fixed(90));
    }

    @Test
    void anEmptyNumberIsAnErrorWhereANumberIsRequiredAndIgnoredForCcsid() {
        ParseResult result = parse(program("<data name=\"A\" type=\"packed\" length=\"\" precision=\"2\"/>\n"
                + "<data name=\"B\" type=\"char\" length=\"1\" ccsid=\"\"/>"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PCML004", 3));
        FieldDef b = result.document().orElseThrow().programs().get(0).parameters().get(0);
        assertThat(b.name()).isEqualTo("B");
        assertThat(b.wire()).isEqualTo(new WireType.Char(new Length.Fixed(1), Optional.empty()));
        assertThat(b.attributes()).containsEntry("ccsid", "");
    }

    @Test
    void aVarcharWithoutVarlengthHasNoLengthPrefixSize() {
        ParseResult result = parse(program("<data name=\"A\" type=\"varchar\" length=\"5\"/>"));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.document().orElseThrow().programs().get(0).parameters().get(0).wire())
                .isEqualTo(new WireType.VarChar(new Length.Fixed(5), Optional.empty(), Optional.empty()));
    }

    // ---- order of diagnostics

    @Test
    void diagnosticsOnTheSameLineAreOrderedByColumnEvenWhenFoundInTheOtherOrder() {
        // Programs are checked before structs, but here the struct comes first in the line.
        String xml = "<pcml version=\"6.0\"><struct name=\"S\"><data name=\"A\" type=\"char\" length=\"X1\"/></struct>"
                + "<program name=\"P\"><data name=\"B\" type=\"char\" length=\"X2\"/></program></pcml>";
        ParseResult result = parse(xml);
        assertThat(result.diagnostics()).hasSize(2);
        assertThat(result.diagnostics().get(0).message()).contains("'A'");
        assertThat(result.diagnostics().get(1).message()).contains("'B'");
        assertThat(result.diagnostics().get(0).location().column()).isLessThan(result.diagnostics().get(1).location().column());
    }
}

package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.StructDef;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Asserts the complete model produced for every valid corpus file (source positions are tested separately). */
class CorpusModelTest {

    private static final SourceLocation ANY = new SourceLocation("-", 0, 0);
    private static final Map<String, String> NONE = Map.of();

    // ---- builders for the expected model

    private static FieldDef field(String name, WireType wire, Usage usage) {
        return new FieldDef(name, wire, usage, new Count.None(), NONE, ANY);
    }

    private static FieldDef field(String name, WireType wire, Usage usage, Count count, Map<String, String> attributes) {
        return new FieldDef(name, wire, usage, count, attributes, ANY);
    }

    private static WireType.Char chars(int length) {
        return new WireType.Char(new Length.Fixed(length), Optional.empty());
    }

    private static WireType.Int int4() {
        return new WireType.Int(4, Optional.empty());
    }

    private static WireType.Struct struct(String name) {
        return new WireType.Struct(name);
    }

    private static StructDef struct(String name, FieldDef... fields) {
        return new StructDef(name, List.of(fields), NONE, ANY);
    }

    private static ProgramDef program(String name, String path, FieldDef... parameters) {
        return new ProgramDef(name, Optional.of(path), Optional.empty(), Optional.empty(), List.of(parameters), NONE, ANY);
    }

    private static Map<String, StructDef> structs(StructDef... defs) {
        Map<String, StructDef> map = new LinkedHashMap<>();
        for (StructDef def : defs) {
            map.put(def.name(), def);
        }
        return map;
    }

    private static PcmlDocument document(Map<String, StructDef> structs, ProgramDef... programs) {
        return new PcmlDocument("6.0", structs, List.of(programs), NONE);
    }

    private static PcmlDocument parsedWithoutDiagnostics(String file) {
        ParseResult result = Corpus.parse(file);
        assertThat(result.diagnostics()).as("diagnostics of %s", file).isEmpty();
        return result.document().orElseThrow();
    }

    private static void assertModel(PcmlDocument actual, PcmlDocument expected) {
        assertThat(actual).usingRecursiveComparison()
                .withStrictTypeChecking()
                .ignoringFieldsOfTypes(SourceLocation.class)
                .isEqualTo(expected);
        assertThat(actual.structs().keySet()).containsExactlyElementsOf(expected.structs().keySet());
    }

    // ---- tests

    @Test
    void scalars() {
        PcmlDocument expected = document(structs(), program("SCALARS", "/QSYS.LIB/DEMOLIB.LIB/SCALARS.PGM",
                field("P_CHAR", chars(10), Usage.INPUT),
                field("P_CHAR_CCSID", new WireType.Char(new Length.Fixed(20), Optional.of(37)), Usage.INPUT),
                field("P_INT", new WireType.Int(4, Optional.of(31)), Usage.INPUT),
                field("P_PACKED", new WireType.Packed(9, 2), Usage.OUTPUT),
                field("P_ZONED", new WireType.Zoned(7, 0), Usage.OUTPUT),
                field("P_FLOAT", new WireType.Float(8), Usage.OUTPUT),
                field("P_BYTE", new WireType.Byte(new Length.Fixed(16)), Usage.INPUT_OUTPUT),
                field("P_DATE", new WireType.Date(), Usage.INPUT, new Count.None(),
                        Map.of("dateformat", "ISO", "dateseparator", "hyphen")),
                field("P_TIME", new WireType.Time(), Usage.INPUT, new Count.None(),
                        Map.of("timeformat", "ISO", "timeseparator", "period")),
                field("P_STAMP", new WireType.Timestamp(), Usage.OUTPUT),
                field("P_VARCHAR", new WireType.VarChar(new Length.Fixed(40), Optional.of(2), Optional.empty()), Usage.INPUT)));
        assertModel(parsedWithoutDiagnostics("scalars.pcml"), expected);
    }

    @Test
    void inputOutput() {
        PcmlDocument expected = document(structs(), program("UPDQTY", "/QSYS.LIB/DEMOLIB.LIB/UPDQTY.PGM",
                field("ITEMID", chars(8), Usage.INPUT_OUTPUT),
                field("QTY", new WireType.Packed(7, 0), Usage.INPUT_OUTPUT),
                field("STATUS", chars(1), Usage.OUTPUT)));
        assertModel(parsedWithoutDiagnostics("inputoutput.pcml"), expected);
    }

    @Test
    void structsAndInherit() {
        PcmlDocument expected = document(
                structs(
                        struct("ADDRESS", field("STREET", chars(30), Usage.INHERIT), field("CITY", chars(20), Usage.INHERIT)),
                        struct("CUSTOMER",
                                field("CUSTID", int4(), Usage.INHERIT),
                                field("HOME", struct("ADDRESS"), Usage.INHERIT),
                                field("BILLING", struct("CUSTOMER.BILLING"), Usage.INPUT)),
                        struct("CUSTOMER.BILLING", field("IBAN", chars(34), Usage.INHERIT)),
                        struct("GETCUST.NOTE", field("TEXT", chars(50), Usage.INHERIT))),
                program("GETCUST", "/QSYS.LIB/DEMOLIB.LIB/GETCUST.PGM",
                        field("REQ", struct("CUSTOMER"), Usage.INPUT),
                        field("RESP", struct("CUSTOMER"), Usage.INHERIT),
                        field("NOTE", struct("GETCUST.NOTE"), Usage.OUTPUT)));
        assertModel(parsedWithoutDiagnostics("structs.pcml"), expected);
    }

    @Test
    void arrays() {
        PcmlDocument expected = document(
                structs(struct("ROW",
                        field("CODE", chars(4), Usage.INHERIT),
                        field("AMOUNT", new WireType.Packed(9, 2), Usage.INHERIT))),
                program("LISTROWS", "/QSYS.LIB/DEMOLIB.LIB/LISTROWS.PGM",
                        field("TAGS", chars(5), Usage.INPUT, new Count.Fixed(3), NONE),
                        field("ROWS", struct("ROW"), Usage.OUTPUT, new Count.Fixed(10), NONE),
                        field("BIGROWS", struct("ROW"), Usage.OUTPUT, new Count.Fixed(9999), NONE)));
        assertModel(parsedWithoutDiagnostics("arrays.pcml"), expected);
    }

    @Test
    void referencesInCountAndLength() {
        PcmlDocument expected = document(
                structs(struct("LINE", field("LINENO", int4(), Usage.INHERIT))),
                program("DYNROWS", "/QSYS.LIB/DEMOLIB.LIB/DYNROWS.PGM",
                        field("ROWCOUNT", int4(), Usage.INPUT),
                        field("TEXTLEN", int4(), Usage.INPUT),
                        field("TEXT", new WireType.Char(new Length.Ref("TEXTLEN"), Optional.empty()), Usage.INPUT),
                        field("LINES", struct("LINE"), Usage.OUTPUT, new Count.Ref("ROWCOUNT"), NONE)));
        assertModel(parsedWithoutDiagnostics("refs.pcml"), expected);
    }

    @Test
    void twoProgramsShareAStruct() {
        PcmlDocument expected = document(
                structs(struct("KEY", field("ID", chars(10), Usage.INHERIT))),
                program("PROGA", "/QSYS.LIB/DEMOLIB.LIB/PROGA.PGM", field("K", struct("KEY"), Usage.INPUT)),
                program("PROGB", "/QSYS.LIB/DEMOLIB.LIB/PROGB.PGM",
                        field("K", struct("KEY"), Usage.INPUT),
                        field("RC", int4(), Usage.OUTPUT)));
        assertModel(parsedWithoutDiagnostics("multi-program.pcml"), expected);
    }

    @Test
    void serviceProgramEntryPointAndReturnValue() {
        PcmlDocument expected = document(structs(), new ProgramDef(
                "PRICE",
                Optional.of("/QSYS.LIB/DEMOLIB.LIB/PRICESRV.SRVPGM"),
                Optional.of("GETPRICE"),
                Optional.of("integer"),
                List.of(field("SKU", chars(12), Usage.INPUT), field("AMOUNT", new WireType.Packed(11, 2), Usage.OUTPUT)),
                Map.of("threadsafe", "true"),
                ANY));
        assertModel(parsedWithoutDiagnostics("service-program.pcml"), expected);
    }

    @Test
    void placeholderInPathIsKeptVerbatim() {
        PcmlDocument expected = document(structs(), program("ORDSTAT", "/QSYS.LIB/${LIBRARY}.LIB/ORDSTAT.PGM",
                field("ORDNO", chars(10), Usage.INPUT)));
        assertModel(parsedWithoutDiagnostics("placeholders.pcml"), expected);
    }

    @Test
    void unknownAttributesArePreserved() {
        ParseResult result = Corpus.parse("unknown-attributes.pcml");
        PcmlDocument expected = new PcmlDocument("6.0", structs(), List.of(new ProgramDef(
                "ODDITIES",
                Optional.of("/QSYS.LIB/DEMOLIB.LIB/ODDITIES.PGM"),
                Optional.empty(),
                Optional.empty(),
                List.of(
                        field("A", chars(4), Usage.INPUT, new Count.None(),
                                Map.of("init", "abcd", "trim", "right", "shade", "dark")),
                        field("B", int4(), Usage.OUTPUT, new Count.None(), Map.of("description", "a number"))),
                Map.of("parseorder", "A B", "colour", "blue"),
                ANY)),
                Map.of("description", "synthetic", "flavour", "mint"));
        assertModel(result.document().orElseThrow(), expected);

        assertThat(result.document().orElseThrow().programs().get(0).parameters().get(0).attributes().keySet())
                .containsExactly("init", "trim", "shade");
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .containsOnly(org.assertj.core.groups.Tuple.tuple("PCML007", Severity.INFO));
        assertThat(result.diagnostics()).extracting(d -> d.location().line()).containsExactly(2, 3, 4, 6, 8);
    }

    @Test
    void doctypeIsTolerated() {
        PcmlDocument expected = document(structs(), program("WITHDTD", "/QSYS.LIB/DEMOLIB.LIB/WITHDTD.PGM",
                field("X", chars(1), Usage.INPUT)));
        assertModel(parsedWithoutDiagnostics("doctype.pcml"), expected);
    }
}

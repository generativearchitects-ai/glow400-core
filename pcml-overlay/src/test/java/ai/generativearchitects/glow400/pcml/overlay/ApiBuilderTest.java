package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The merge rules and the checks of the API model, one behaviour per test. */
class ApiBuilderTest {

    private static final String HEAD = "<?xml version=\"1.0\"?>\n<pcml version=\"6.0\">\n";

    /** Parameters: ID (input), A and B (input), OUT (output), IO (inputoutput), INH (no usage), CNT (output int). */
    private static final String MIXED = HEAD + """
              <struct name="ROW"><data name="X" type="char" length="2"/><data name="Y" type="int" length="4"/></struct>
              <struct name="HDR"><data name="TOTAL" type="int" length="4"/><data name="LABEL" type="char" length="5"/></struct>
              <program name="MIXED" path="/QSYS.LIB/DEMOLIB.LIB/MIXED.PGM">
                <data name="ID" type="char" length="10" usage="input"/>
                <data name="A" type="char" length="4" usage="input"/>
                <data name="B" type="char" length="4" usage="input"/>
                <data name="OUT" type="char" length="4" usage="output"/>
                <data name="IO" type="char" length="4" usage="inputoutput"/>
                <data name="INH" type="char" length="4"/>
                <data name="CNT" type="int" length="4" usage="output"/>
                <data name="HDR" type="struct" struct="HDR" usage="output"/>
                <data name="ROWS" type="struct" struct="ROW" count="10" usage="output"/>
                <data name="TAGS" type="char" length="3" count="4" usage="output"/>
                <data name="ERR" type="char" length="2" usage="output"/>
                <data name="ERRC" type="char" length="8" usage="output"/>
              </program>
            </pcml>
            """;

    private static String yaml(String route, String method, String extra) {
        return "program: MIXED\npcml: MIXED.pcml\nroute: " + route + "\nmethod: " + method + "\nversion: 1.0.0\n" + extra;
    }

    private static RepositoryResult load(Path dir, String overlay) {
        return new Repos.Temp(dir).file("apis/A/MIXED.pcml", MIXED).file("apis/A/api.yaml", overlay).load();
    }

    private static ApiOperation single(RepositoryResult result) {
        assertThat(result.diagnostics()).as("diagnostics").isEmpty();
        return result.model().orElseThrow().operations().get(0);
    }

    private static FieldBinding binding(ApiOperation operation, String path) {
        return operation.bindings().stream().filter(b -> b.fieldPath().equals(path)).findFirst().orElseThrow();
    }

    private static List<Tuple> codes(RepositoryResult result) {
        return result.diagnostics().stream().map(d -> Tuple.tuple(d.code(), d.severity())).toList();
    }

    /** Directions that keep MIXED valid: IO and INH need one, and A/B/ID are request fields. */
    private static final String DIRECTIONS = "parameters:\n  IO: { direction: both }\n  INH: { direction: response }\n";

    // ---- merge rule 6: where a request field travels

    @Test
    void defaultLocationDependsOnTheMethod(@TempDir Path dir) {
        for (String method : List.of("GET", "DELETE")) {
            ApiOperation op = single(load(dir.resolve(method), yaml("/m", method, DIRECTIONS)));
            assertThat(binding(op, "A").location()).as(method).contains(Location.QUERY);
            assertThat(binding(op, "IO").location()).as(method).contains(Location.QUERY);
        }
        for (String method : List.of("POST", "PUT", "PATCH")) {
            ApiOperation op = single(load(dir.resolve(method), yaml("/m", method, DIRECTIONS)));
            assertThat(binding(op, "A").location()).as(method).contains(Location.BODY);
        }
    }

    @Test
    void aFieldWhoseExposedNameIsInTheRouteIsAPathParameter(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/items/{a}/x", "POST", DIRECTIONS)));
        assertThat(binding(op, "A").location()).contains(Location.PATH);
        assertThat(binding(op, "A").required()).isTrue();
        assertThat(binding(op, "B").location()).contains(Location.BODY);
        assertThat(binding(op, "B").required()).isFalse();
    }

    @Test
    void anExplicitLocationWins(@TempDir Path dir) {
        String extra = DIRECTIONS + "  ID: { in: header }\n  A: { in: query }\n  B: { in: path }\n";
        ApiOperation op = single(load(dir, yaml("/items/{b}", "POST", extra)));
        assertThat(binding(op, "ID").location()).contains(Location.HEADER);
        assertThat(binding(op, "A").location()).contains(Location.QUERY);
        assertThat(binding(op, "B").location()).contains(Location.PATH);
    }

    @Test
    void inOnAParameterWithoutARequestSideIsOvl012AtTheInValue(@TempDir Path dir) {
        // Response by usage, internal by direction, response by direction: none of them has a request side.
        for (String entry : List.of("  OUT: { in: header }\n", "  ID: { direction: internal, in: query }\n",
                "  OUT: { direction: response, in: body }\n", "  OUT: { direction: internal, in: path }\n")) {
            RepositoryResult result = load(dir.resolve(String.valueOf(entry.hashCode())), yaml("/m", "POST", DIRECTIONS + entry));
            assertThat(codes(result)).as(entry).containsExactly(Tuple.tuple("OVL012", Severity.ERROR));
            assertThat(result.diagnostics().get(0).location().line()).as(entry).isEqualTo(9);
            assertThat(result.diagnostics().get(0).location().column()).as(entry).isGreaterThan(entry.indexOf("in:") + 1);
            assertThat(result.model().orElseThrow().operations()).as(entry).isEmpty();
        }
    }

    @Test
    void inOnAParameterWithARequestSideStillWorks(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", "parameters:\n  IO: { direction: both, in: header }\n  INH: { direction: response }\n"
                + "  ID: { in: query }\n")));
        assertThat(binding(op, "IO").location()).contains(Location.HEADER);
        assertThat(binding(op, "ID").location()).contains(Location.QUERY);
        assertThat(binding(op, "INH").location()).isEmpty();
    }

    @Test
    void inOnAnInjectedParameterIsOvl012AndOvl018(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "POST", DIRECTIONS + "  A: { in: query }\n\ninject:\n  A: x\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line())
                .containsExactlyInAnyOrder(Tuple.tuple("OVL012", 9), Tuple.tuple("OVL018", 12));
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals("OVL012")).extracting(Diagnostic::message)
                .allSatisfy(m -> assertThat(m).contains("injected"));
    }

    @Test
    void requiredDefaultsToTrueForPathParametersAndCanBeOverridden(@TempDir Path dir) {
        String extra = DIRECTIONS + "  A: { required: false }\n  B: { required: true }\n";
        ApiOperation op = single(load(dir, yaml("/x/{a}", "POST", extra)));
        assertThat(binding(op, "A").required()).isFalse();
        assertThat(binding(op, "B").required()).isTrue();
        assertThat(binding(op, "ID").required()).isFalse();
    }

    // ---- merge rules 1 and 2: direction

    @Test
    void usageInputAndOutputGiveTheirDirections(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS)));
        assertThat(binding(op, "ID").direction()).isEqualTo(Direction.REQUEST);
        assertThat(binding(op, "OUT").direction()).isEqualTo(Direction.RESPONSE);
        assertThat(binding(op, "CNT").direction()).isEqualTo(Direction.RESPONSE);
    }

    @Test
    void internalIsAllowedOnInputAndOutputParameters(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  ID: { direction: internal }\n  OUT: { direction: internal }\n")));
        assertThat(binding(op, "ID").direction()).isEqualTo(Direction.INTERNAL);
        assertThat(binding(op, "ID").location()).isEmpty();
        assertThat(binding(op, "OUT").direction()).isEqualTo(Direction.INTERNAL);
    }

    @Test
    void theMatchingDirectionIsAllowedOnInputAndOutputParameters(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  ID: { direction: request }\n  OUT: { direction: response }\n")));
        assertThat(binding(op, "ID").direction()).isEqualTo(Direction.REQUEST);
        assertThat(binding(op, "OUT").direction()).isEqualTo(Direction.RESPONSE);
    }

    @Test
    void aContradictingDirectionIsOvl016(@TempDir Path dir) {
        for (String entry : List.of("  ID: { direction: response }\n", "  ID: { direction: both }\n",
                "  OUT: { direction: request }\n", "  OUT: { direction: both }\n")) {
            RepositoryResult result = load(dir.resolve(String.valueOf(entry.hashCode())), yaml("/m", "POST", DIRECTIONS + entry));
            assertThat(codes(result)).as(entry).containsExactly(Tuple.tuple("OVL016", Severity.ERROR));
            assertThat(result.model().orElseThrow().operations()).as(entry).isEmpty();
        }
    }

    @Test
    void inputOutputAndInheritWithoutDirectionAreBothWithAWarningByDefault(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "POST", ""));
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL001", Severity.WARNING), Tuple.tuple("OVL001", Severity.WARNING));
        assertThat(result.diagnostics()).extracting(Diagnostic::message)
                .anySatisfy(m -> assertThat(m).contains("'IO'").contains("inputoutput"))
                .anySatisfy(m -> assertThat(m).contains("'INH'").contains("inherit"));
        ApiOperation op = result.model().orElseThrow().operations().get(0);
        assertThat(binding(op, "IO").direction()).isEqualTo(Direction.BOTH);
        assertThat(binding(op, "INH").direction()).isEqualTo(Direction.BOTH);
    }

    @Test
    void thePolicyFailTurnsThemIntoErrors(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", "defaults:\n  inputoutput: fail\n")
                .file("apis/A/MIXED.pcml", MIXED).file("apis/A/api.yaml", yaml("/m", "POST", "")).load();
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL001", Severity.ERROR), Tuple.tuple("OVL001", Severity.ERROR));
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @Test
    void anExplicitDirectionOnInputOutputNeedsNoPolicy(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", "defaults:\n  inputoutput: fail\n")
                .file("apis/A/MIXED.pcml", MIXED).file("apis/A/api.yaml", yaml("/m", "POST", DIRECTIONS)).load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(binding(result.model().orElseThrow().operations().get(0), "IO").direction()).isEqualTo(Direction.BOTH);
    }

    // ---- injection

    @Test
    void injectedValuesAreKeptVerbatim(@TempDir Path dir) {
        String extra = DIRECTIONS + "\ninject:\n  A: 7\n  B: true\n  ID: ${tenant.region.code}\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra)));
        assertThat(binding(op, "A").injection()).contains("7");
        assertThat(binding(op, "B").injection()).contains("true");
        assertThat(binding(op, "ID").injection()).contains("${tenant.region.code}");
        assertThat(binding(op, "A").direction()).isEqualTo(Direction.INJECTED);
        assertThat(binding(op, "A").location()).isEmpty();
        assertThat(binding(op, "A").required()).isFalse();
    }

    @Test
    void aStructOrAnArrayCannotBeInjected(@TempDir Path dir) {
        for (String parameter : List.of("HDR", "TAGS")) {
            RepositoryResult result = load(dir.resolve(parameter), yaml("/m", "POST", DIRECTIONS + "\ninject:\n  " + parameter + ": x\n"));
            assertThat(codes(result)).as(parameter).containsExactly(Tuple.tuple("OVL012", Severity.ERROR));
        }
    }

    // ---- types, codec, names

    @Test
    void aTypeCannotBeSetOnAStruct(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "POST", DIRECTIONS + "  HDR: { type: string }\n"));
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL012", Severity.ERROR));
    }

    @Test
    void aTypeOnAnArrayOfScalarsAppliesToTheElements(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  TAGS: { type: integer }\n")));
        assertThat(binding(op, "TAGS").semanticType()).contains(SemanticType.INTEGER);
    }

    @Test
    void codecDefaultsComeFromTheRepositoryForCharFieldsOnly(@TempDir Path dir) {
        String gateway = "defaults:\n  char:\n    pad: left\n    padChar: \"0\"\n    nullAs: \"-\"\n";
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", gateway)
                .file("apis/A/MIXED.pcml", MIXED).file("apis/A/api.yaml", yaml("/m", "POST", DIRECTIONS)).load();
        ApiOperation op = single(result);
        assertThat(binding(op, "A").codec().pad()).isEqualTo(Pad.LEFT);
        assertThat(binding(op, "A").codec().padChar()).isEqualTo('0');
        assertThat(binding(op, "A").codec().nullAs()).isEqualTo("-");
        assertThat(binding(op, "CNT").codec().pad()).isEqualTo(Pad.NONE);
        assertThat(binding(op, "CNT").codec().nullAs()).isEmpty();
    }

    @Test
    void aVarcharIsNeverPaddedByTheCharDefaultsButStillGetsTheirPadCharAndNullAs(@TempDir Path dir) {
        String gateway = "defaults:\n  char:\n    pad: left\n    padChar: \"0\"\n    nullAs: \"-\"\n";
        String pcml = HEAD + """
              <program name="VC" path="/QSYS.LIB/DEMOLIB.LIB/VC.PGM">
                <data name="C" type="char" length="4" usage="input"/>
                <data name="V" type="varchar" length="4" usage="input"/>
              </program>
            </pcml>
            """;
        String overlay = "program: VC\npcml: VC.pcml\nroute: /vc\nmethod: POST\nversion: 1.0.0\n";
        ApiOperation op = single(new Repos.Temp(dir).file("gateway.yaml", gateway).file("apis/A/VC.pcml", pcml)
                .file("apis/A/api.yaml", overlay).load());
        assertThat(binding(op, "C").codec().pad()).isEqualTo(Pad.LEFT);
        assertThat(binding(op, "V").codec().pad()).isEqualTo(Pad.NONE);
        assertThat(binding(op, "V").codec().padChar()).isEqualTo('0');
        assertThat(binding(op, "V").codec().nullAs()).isEqualTo("-");
    }

    @Test
    void overlayCodecSettingsWinAndApplyToAnyWireType(@TempDir Path dir) {
        String extra = DIRECTIONS + "  A: { pad: right, padChar: \"#\", nullAs: \"?\", format: x }\n  CNT: { pad: left, padChar: \"0\" }\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra)));
        assertThat(binding(op, "A").codec().padChar()).isEqualTo('#');
        assertThat(binding(op, "A").codec().nullAs()).isEqualTo("?");
        assertThat(binding(op, "A").codec().format()).contains("x");
        assertThat(binding(op, "CNT").codec().pad()).isEqualTo(Pad.LEFT);
        assertThat(binding(op, "CNT").codec().padChar()).isEqualTo('0');
    }

    @Test
    void descriptionExampleAndOverlayNamesAreKept(@TempDir Path dir) {
        String extra = DIRECTIONS + "  A: { name: alpha, description: \"The A\", example: 3.5 }\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra)));
        assertThat(binding(op, "A").exposedName()).isEqualTo("alpha");
        assertThat(binding(op, "A").description()).contains("The A");
        assertThat(binding(op, "A").example()).contains("3.5");
        assertThat(binding(op, "B").description()).isEmpty();
        assertThat(binding(op, "B").example()).isEmpty();
    }

    // ---- trim

    @Test
    void dropBlankAndCountFieldTrimArrays(@TempDir Path dir) {
        String extra = DIRECTIONS + "  CNT: { direction: internal }\n  ROWS: { trim: { countField: CNT } }\n  TAGS: { trim: { dropBlank: true } }\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra)));
        assertThat(binding(op, "ROWS").trim()).contains(new Trim.CountField("CNT"));
        assertThat(binding(op, "TAGS").trim()).contains(new Trim.DropBlank());
        assertThat(binding(op, "CNT").trim()).isEmpty();
    }

    @Test
    void theCountFieldCanBeAMemberOfAStruct(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  ROWS: { trim: { countField: HDR.TOTAL } }\n")));
        assertThat(binding(op, "ROWS").trim()).contains(new Trim.CountField("HDR.TOTAL"));
    }

    @Test
    void aCountFieldMustExistAndBeAScalar(@TempDir Path dir) {
        for (String field : List.of("NOPE", "HDR", "ROWS", "TAGS", "HDR.NOPE", "CNT.X", "HDR.TOTAL.X", "ROWS.Y")) {
            RepositoryResult result = load(dir.resolve(field), yaml("/m", "POST", DIRECTIONS + "  ROWS: { trim: { countField: " + field + " } }\n"));
            assertThat(codes(result)).as(field).containsExactly(Tuple.tuple("OVL017", Severity.ERROR));
        }
    }

    @Test
    void onlyArraysCanBeTrimmed(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "POST", DIRECTIONS + "  OUT: { trim: { dropBlank: true } }\n"));
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL012", Severity.ERROR));
    }

    // ---- struct members

    @Test
    void membersFollowTheirParameterAndCanBeConfigured(@TempDir Path dir) {
        String extra = DIRECTIONS + "  ROWS:\n    name: rows\n    fields:\n      X: { name: code, required: true, description: d }\n      Y: { hidden: true, type: decimal }\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra)));
        FieldBinding rows = binding(op, "ROWS");
        assertThat(rows.children()).extracting(FieldBinding::fieldPath).containsExactly("ROWS.X", "ROWS.Y");
        assertThat(rows.children().get(0).exposedName()).isEqualTo("code");
        assertThat(rows.children().get(0).required()).isTrue();
        assertThat(rows.children().get(0).direction()).isEqualTo(Direction.RESPONSE);
        assertThat(rows.children().get(1).hidden()).isTrue();
        assertThat(rows.children().get(1).semanticType()).contains(SemanticType.DECIMAL);
        assertThat(rows.children().get(0).trim()).isEmpty();
        assertThat(binding(op, "HDR").children()).extracting(FieldBinding::exposedName).containsExactly("total", "label");
    }

    @Test
    void hiddenAppliesToMembersOnly(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS)));
        assertThat(op.bindings()).extracting(FieldBinding::hidden).containsOnly(false);
    }

    // ---- error mapping and cache

    @Test
    void theErrorMappingResolvesFieldsAndDefaultsTheStatus(@TempDir Path dir) {
        String extra = DIRECTIONS + "\nerror:\n  when: { field: ERR, in: [KO, \"7\", 8] }\n  code: ERRC\n  message: HDR.LABEL\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra)));
        assertThat(op.errorMapping()).contains(new ErrorMapping(new ErrorCondition.In("ERR", List.of("KO", "7", "8")),
                Optional.of("ERRC"), Optional.of("HDR.LABEL"), 422, Map.of()));
    }

    @Test
    void notEqualsIsSupportedAndCodeAndMessageAreOptional(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "\nerror:\n  when: { field: ERR, notEquals: OK }\n  httpStatus: 409\n")));
        assertThat(op.errorMapping()).contains(new ErrorMapping(new ErrorCondition.NotEquals("ERR", "OK"),
                Optional.empty(), Optional.empty(), 409, Map.of()));
    }

    @Test
    void errorFieldsMustExistAndBeScalars(@TempDir Path dir) {
        for (String block : List.of("when: { field: NOPE, equals: x }", "when: { field: ERR, equals: x }\n  code: NOPE",
                "when: { field: ERR, equals: x }\n  message: HDR", "when: { field: TAGS, equals: x }",
                "when: { field: ROWS.X, equals: x }")) {
            RepositoryResult result = load(dir.resolve(String.valueOf(block.hashCode())),
                    yaml("/m", "POST", DIRECTIONS + "\nerror:\n  " + block + "\n"));
            assertThat(codes(result)).as(block).containsExactly(Tuple.tuple("OVL017", Severity.ERROR));
        }
    }

    @Test
    void anErrorBlockNeedsACondition(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "POST", DIRECTIONS + "\nerror:\n  httpStatus: 409\n"));
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL012", Severity.ERROR));
        assertThat(result.diagnostics().get(0).message()).contains("'when'");
    }

    // ---- exposed names

    @Test
    void sameNameOnTheRequestAndTheResponseSideIsAllowed(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  A: { name: same }\n  OUT: { name: same }\n")));
        assertThat(binding(op, "A").exposedName()).isEqualTo("same");
        assertThat(binding(op, "OUT").exposedName()).isEqualTo("same");
    }

    @Test
    void aBothFieldSharesANameWithBothSides(@TempDir Path dir) {
        for (String clash : List.of("  A: { name: same }\n  IO: { direction: both, name: same }\n",
                "  OUT: { name: same }\n  IO: { direction: both, name: same }\n")) {
            RepositoryResult result = load(dir.resolve(String.valueOf(clash.hashCode())),
                    yaml("/m", "POST", "parameters:\n  INH: { direction: response }\n" + clash));
            assertThat(codes(result)).as(clash).containsExactly(Tuple.tuple("OVL014", Severity.ERROR));
        }
    }

    @Test
    void internalInjectedAndHiddenFieldsDoNotCountForDuplicates(@TempDir Path dir) {
        String extra = DIRECTIONS + "  A: { name: same }\n  B: { name: same, direction: internal }\n  ROWS:\n    fields:\n      X: { name: m }\n      Y: { name: m, hidden: true }\n";
        ApiOperation op = single(load(dir, yaml("/m", "POST", extra + "\ninject:\n  ID: x\n")));
        assertThat(binding(op, "B").direction()).isEqualTo(Direction.INTERNAL);
    }

    @Test
    void twoMembersWithTheSameNameAreReportedAtTheLaterOne(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "POST", DIRECTIONS + "  ROWS:\n    fields:\n      X: { name: m }\n      Y: { name: m }\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line())
                .containsExactly(Tuple.tuple("OVL014", 12));
    }

    // ---- route

    @Test
    void aPathBindingAndAPlaceholderMustCorrespondInBothDirections(@TempDir Path dir) {
        RepositoryResult missingBinding = load(dir.resolve("a"), yaml("/x/{nothing}", "POST", DIRECTIONS));
        assertThat(codes(missingBinding)).containsExactly(Tuple.tuple("OVL004", Severity.ERROR));
        RepositoryResult missingPlaceholder = load(dir.resolve("b"), yaml("/x", "POST", DIRECTIONS + "  A: { in: path }\n"));
        assertThat(codes(missingPlaceholder)).containsExactly(Tuple.tuple("OVL004", Severity.ERROR));
    }

    @Test
    void aPlaceholderCanAppearTwice(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/x/{a}/y/{a}", "POST", DIRECTIONS)));
        assertThat(binding(op, "A").location()).contains(Location.PATH);
    }

    private static final String REQUEST_STRUCT = HEAD + """
              <struct name="BODY"><data name="Q" type="char" length="2"/></struct>
              <program name="SEARCH" path="/QSYS.LIB/DEMOLIB.LIB/SEARCH.PGM">
                <data name="REQ" type="struct" struct="BODY" usage="input"/>
                <data name="IDS" type="char" length="4" count="5" usage="input"/>
              </program>
            </pcml>
            """;

    private static RepositoryResult search(Path dir, String method, String extra) {
        String overlay = "program: SEARCH\npcml: SEARCH.pcml\nroute: /search\nmethod: " + method + "\nversion: 1.0.0\n" + extra;
        return new Repos.Temp(dir).file("apis/A/SEARCH.pcml", REQUEST_STRUCT).file("apis/A/api.yaml", overlay).load();
    }

    @Test
    void structsAndArraysCannotBeSentWithGetOrDelete(@TempDir Path dir) {
        for (String method : List.of("GET", "DELETE")) {
            RepositoryResult result = search(dir.resolve(method), method, "");
            assertThat(codes(result)).as(method).containsExactly(Tuple.tuple("OVL015", Severity.ERROR), Tuple.tuple("OVL015", Severity.ERROR));
            assertThat(result.diagnostics()).extracting(Diagnostic::message)
                    .anySatisfy(m -> assertThat(m).contains("'REQ'").contains(method).contains("POST, PUT or PATCH"))
                    .anySatisfy(m -> assertThat(m).contains("'IDS'").contains(method).contains("POST, PUT or PATCH"));
        }
    }

    @Test
    void aStructOrArrayOnGetIsOvl015WhateverItsLocation(@TempDir Path dir) {
        for (String location : List.of("body", "query", "header")) {
            RepositoryResult result = search(dir.resolve(location), "GET",
                    "parameters:\n  REQ: { in: " + location + " }\n  IDS: { in: " + location + " }\n");
            assertThat(codes(result)).as(location).containsExactly(Tuple.tuple("OVL015", Severity.ERROR), Tuple.tuple("OVL015", Severity.ERROR));
            assertThat(result.diagnostics()).extracting(d -> d.location().line()).as(location).containsExactly(7, 8);
        }
    }

    @Test
    void aScalarCannotBeSentInTheBodyOfGetOrDelete(@TempDir Path dir) {
        for (String method : List.of("GET", "DELETE")) {
            RepositoryResult result = load(dir.resolve(method), yaml("/m", method, DIRECTIONS + "  A: { in: body }\n"));
            assertThat(codes(result)).as(method).containsExactly(Tuple.tuple("OVL015", Severity.ERROR));
            assertThat(result.diagnostics().get(0).message()).contains("'A'").contains(method).contains("POST, PUT or PATCH");
            assertThat(result.diagnostics().get(0).location().line()).isEqualTo(9);
        }
    }

    @Test
    void scalarsOnGetAndDeleteKeepTheirDefaultsAndMayUseQueryHeaderOrPath(@TempDir Path dir) {
        for (String method : List.of("GET", "DELETE")) {
            ApiOperation op = single(load(dir.resolve(method), yaml("/x/{b}", method,
                    DIRECTIONS + "  A: { in: header }\n  ID: { in: query }\n")));
            assertThat(binding(op, "A").location()).contains(Location.HEADER);
            assertThat(binding(op, "ID").location()).contains(Location.QUERY);
            assertThat(binding(op, "B").location()).contains(Location.PATH);
            assertThat(binding(op, "IO").location()).contains(Location.QUERY);
        }
    }

    @Test
    void aBothFieldOnGetIsInTheQueryAndNeverInTheBody(@TempDir Path dir) {
        RepositoryResult result = load(dir, yaml("/m", "GET", "parameters:\n  IO: { direction: both, in: body }\n  INH: { direction: response }\n"));
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL015", Severity.ERROR));
    }

    @Test
    void anExplicitBodyOnPostPutAndPatchIsAllowedAndMembersFollowIt(@TempDir Path dir) {
        for (String method : List.of("POST", "PUT", "PATCH")) {
            ApiOperation op = single(search(dir.resolve(method), method, "parameters:\n  REQ: { in: body }\n  IDS: { in: body }\n"));
            assertThat(binding(op, "REQ").location()).contains(Location.BODY);
            assertThat(binding(op, "IDS").location()).contains(Location.BODY);
            assertThat(binding(op, "REQ").children()).extracting(FieldBinding::location).containsOnly(Optional.of(Location.BODY));
        }
    }

    @Test
    void aStructOrArrayInAPostNeedsNoSetting(@TempDir Path dir) {
        ApiOperation op = single(search(dir, "POST", ""));
        assertThat(binding(op, "REQ").location()).contains(Location.BODY);
        assertThat(binding(op, "IDS").location()).contains(Location.BODY);
    }

    // ---- ids, strategy, tags

    @Test
    void theDefaultIdIsTheProgramNameInLowerCase(@TempDir Path dir) {
        assertThat(single(load(dir, yaml("/m", "POST", DIRECTIONS))).id()).isEqualTo("mixed");
        assertThat(single(load(dir.resolve("x"), yaml("/m", "POST", "id: given\n" + DIRECTIONS))).id()).isEqualTo("given");
    }

    @Test
    void aProgramNameThatIsNotAValidIdNeedsAnExplicitId(@TempDir Path dir) {
        String pcml = HEAD + "<program name=\"MY$PGM\"><data name=\"X\" type=\"char\" length=\"1\" usage=\"output\"/></program></pcml>\n";
        String overlay = "program: MY$PGM\npcml: P.pcml\nroute: /m\nmethod: GET\nversion: 1.0.0\n";
        RepositoryResult bad = new Repos.Temp(dir.resolve("a")).file("apis/A/P.pcml", pcml).file("apis/A/api.yaml", overlay).load();
        assertThat(codes(bad)).containsExactly(Tuple.tuple("OVL012", Severity.ERROR));
        assertThat(bad.diagnostics().get(0).location().line()).isEqualTo(1);
        RepositoryResult good = new Repos.Temp(dir.resolve("b")).file("apis/A/P.pcml", pcml).file("apis/A/api.yaml", "id: my-pgm\n" + overlay).load();
        assertThat(good.diagnostics()).isEmpty();
    }

    @Test
    void theCallStrategyIsKept(@TempDir Path dir) {
        for (String call : List.of("auto", "pcml", "raw")) {
            ApiOperation op = single(load(dir.resolve(call), yaml("/m", "POST", "call: " + call + "\n" + DIRECTIONS)));
            assertThat(op.callStrategy().name()).isEqualToIgnoringCase(call);
        }
    }

    @Test
    void theVersionSummaryDescriptionAndTagsAreKept(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", "summary: S\ndescription: D\ntags: [a, b]\n" + DIRECTIONS)));
        assertThat(op.summary()).contains("S");
        assertThat(op.description()).contains("D");
        assertThat(op.tags()).containsExactly("a", "b");
        assertThat(op.version()).isEqualTo("1.0.0");
        assertThat(op.pcmlPath()).isEqualTo("apis/A/MIXED.pcml");
        assertThat(op.overlayPath()).isEqualTo("apis/A/api.yaml");
        assertThat(op.source().file()).isEqualTo("apis/A/api.yaml");
    }

    // ---- overlay format v0.5: boolean texts

    @Test
    void aBooleanFieldHasTheDefaultTextsUnlessTheOverlaySetsThem(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  A: { type: boolean }\n  B: { type: boolean, trueValue: \"Y\", falseValue: \"N\" }\n"
                + "  ID: { type: boolean, trueValue: \"T\" }\n")));
        assertThat(binding(op, "A").codec().trueValue()).contains("1");
        assertThat(binding(op, "A").codec().falseValue()).contains("0");
        assertThat(binding(op, "B").codec().trueValue()).contains("Y");
        assertThat(binding(op, "B").codec().falseValue()).contains("N");
        assertThat(binding(op, "ID").codec().trueValue()).contains("T");
        assertThat(binding(op, "ID").codec().falseValue()).contains("0");
    }

    @Test
    void theBooleanTextsAreKeptOnOtherTypesOnlyWhenTheOverlaySetThem(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS + "  A: { trueValue: \"Y\" }\n  HDR: { falseValue: \"N\" }\n")));
        assertThat(binding(op, "A").codec().trueValue()).contains("Y");
        assertThat(binding(op, "A").codec().falseValue()).isEmpty();
        assertThat(binding(op, "B").codec().trueValue()).isEmpty();
        assertThat(binding(op, "B").codec().falseValue()).isEmpty();
        assertThat(binding(op, "HDR").codec().falseValue()).contains("N");
        assertThat(binding(op, "HDR").codec().trueValue()).isEmpty();
    }

    @Test
    void membersCanHaveBooleanTexts(@TempDir Path dir) {
        ApiOperation op = single(load(dir, yaml("/m", "POST", DIRECTIONS
                + "  ROWS:\n    fields:\n      X: { type: boolean, trueValue: \"+\", falseValue: \"-\" }\n      Y: { type: boolean }\n")));
        assertThat(binding(op, "ROWS").children().get(0).codec().trueValue()).contains("+");
        assertThat(binding(op, "ROWS").children().get(0).codec().falseValue()).contains("-");
        assertThat(binding(op, "ROWS").children().get(1).codec().trueValue()).contains("1");
    }
}

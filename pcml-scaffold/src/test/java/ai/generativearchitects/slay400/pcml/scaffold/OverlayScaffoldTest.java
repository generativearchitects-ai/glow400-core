package ai.generativearchitects.slay400.pcml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.slay400.pcml.overlay.RepositoryResult;
import ai.generativearchitects.slay400.pcml.parser.ParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The first overlay and {@code gateway.yaml}: what they say, that they load, and that they are always the same. */
class OverlayScaffoldTest {

    private static final String ORDSTATUS = """
            <?xml version="1.0"?>
            <pcml version="6.0">
              <program name="ORDSTATUS" path="/QSYS.LIB/DEMOLIB.LIB/ORDSTATUS.PGM">
                <data name="P0ORDN" type="char" length="10" usage="input"/>
                <data name="P0STAT" type="char" length="1" usage="output"/>
                <data name="P0QTY" type="packed" length="7" precision="2" usage="inputoutput"/>
                <data name="ORDER_LINE" type="char" length="3" usage="inherit"/>
              </program>
              <program name="SECOND" path="/QSYS.LIB/DEMOLIB.LIB/SECOND.PGM">
                <data name="X" type="int" length="4" usage="input"/>
              </program>
              <program name="EMPTY" path="/QSYS.LIB/DEMOLIB.LIB/EMPTY.PGM"/>
            </pcml>
            """;

    private static ScaffoldResult create(String pcml) {
        return OverlayScaffold.create("ordstatus", "ORDSTATUS.pcml", Fixtures.document(pcml));
    }

    @Test
    void writesOneOperationPerProgramWithOneLinePerParameter() {
        assertThat(create(ORDSTATUS).text()).contains("""
                pcml: ORDSTATUS.pcml
                version: 0.1.0
                title: ordstatus
                operations:
                  - id: ordstatus
                    program: ORDSTATUS
                    route: /ordstatus
                    method: POST
                    parameters:
                      P0ORDN: { name: p0ordn }
                      P0STAT: { name: p0stat }
                      P0QTY: { name: p0qty }  # inputoutput: run annotate, or add direction: request | response | both | internal
                      ORDER_LINE: { name: orderLine }  # inputoutput: run annotate, or add direction: request | response | both | internal
                  - id: second
                    program: SECOND
                    route: /second
                    method: POST
                    parameters:
                      X: { name: x }
                  - id: empty
                    program: EMPTY
                    route: /empty
                    method: POST
                """);
        assertThat(create(ORDSTATUS).diagnostics()).isEmpty();
    }

    @Test
    void theCommentIsTheOneTheEditorRemoves() {
        assertThat(OverlayScaffold.INPUTOUTPUT_COMMENT)
                .isEqualTo("# inputoutput: run annotate, or add direction: request | response | both | internal");
    }

    @Test
    void theTextLoadsWithNothingButOvl001(@TempDir Path dir) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("apis/ORDSTATUS"));
        Files.writeString(folder.resolve("ORDSTATUS.pcml"), ORDSTATUS);
        Files.writeString(folder.resolve("api.yaml"), create(ORDSTATUS).text().orElseThrow());
        Files.writeString(dir.resolve("gateway.yaml"), GatewayScaffold.create());
        RepositoryResult result = OverlayLoader.loadRepository(dir);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsOnly("OVL001");
        assertThat(result.diagnostics()).hasSize(2).allMatch(d -> d.severity() == Severity.ERROR);
        assertThat(result.model().orElseThrow().operations()).extracting(o -> o.id()).containsExactly("second", "empty");
    }

    @Test
    void everyFixtureLoadsWithNothingButOvl001(@TempDir Path dir) throws IOException {
        List<String> checked = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        int number = 0;
        for (Fixtures.Api api : Fixtures.apis()) {
            ParseResult parsed = Fixtures.parse(api.pcml().getFileName().toString(), Fixtures.read(api.pcml()));
            if (parsed.hasErrors() || parsed.document().isEmpty()) {
                continue;
            }
            ScaffoldResult scaffold = OverlayScaffold.create(api.folder(), api.pcml().getFileName().toString(), parsed.document().get());
            if (scaffold.text().isEmpty()) {
                refused.add(api.label());
                assertThat(scaffold.diagnostics()).isNotEmpty().allMatch(d -> d.code().equals("SCF003"));
                continue;
            }
            Path repo = Files.createDirectories(dir.resolve("r" + number++));
            Path folder = Files.createDirectories(repo.resolve("apis").resolve(api.folder()));
            Files.copy(api.pcml(), folder.resolve(api.pcml().getFileName().toString()));
            Files.writeString(folder.resolve("api.yaml"), scaffold.text().get());
            Files.writeString(repo.resolve("gateway.yaml"), GatewayScaffold.create());
            RepositoryResult result = OverlayLoader.loadRepository(repo);
            assertThat(result.diagnostics()).as(api.label()).extracting(Diagnostic::code).isSubsetOf("OVL001");
            assertThat(OverlayLoader.loadRepositoryForAnnotation(repo).targets()).as(api.label()).hasSizeLessThanOrEqualTo(1);
            checked.add(api.label());
        }
        assertThat(checked).hasSizeGreaterThan(10);
        assertThat(refused).as("PCML for which no overlay can be written").containsExactly("invalid/ovl013/CIRCULAR");
    }

    @Test
    void aProgramWithoutParametersHasNoParametersKey() {
        assertThat(create("<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\"/></pcml>").text().orElseThrow())
                .doesNotContain("parameters");
    }

    @Test
    void namesThatYamlWouldReadAsSomethingElseAreQuoted() {
        String pcml = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\"><data name=\"NULL\" type=\"char\" length=\"1\" usage=\"input\"/>"
                + "<data name=\"A$B\" type=\"char\" length=\"1\" usage=\"input\"/></program></pcml>";
        String text = OverlayScaffold.create("true", "a b.pcml", Fixtures.document(pcml)).text().orElseThrow();
        assertThat(text).contains("pcml: \"a b.pcml\"\n").contains("title: \"true\"\n").contains("\"NULL\": { name: \"null\" }")
                .contains("\"A$B\": { name: \"a$b\" }");
    }

    @Test
    void anIdThatStartsWithADigitIsQuotedAndLoads(@TempDir Path dir) throws IOException {
        String pcml = "<pcml version=\"6.0\"><program name=\"1ABC\" path=\"/Q\"><data name=\"X\" type=\"char\" length=\"1\" usage=\"input\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml)).text().orElseThrow();
        assertThat(text).contains("- id: \"1abc\"").contains("route: /1abc").contains("program: \"1ABC\"");
        Path folder = Files.createDirectories(dir.resolve("apis/F"));
        Files.writeString(folder.resolve("A.pcml"), pcml);
        Files.writeString(folder.resolve("api.yaml"), text);
        assertThat(OverlayLoader.loadRepository(dir).diagnostics()).isEmpty();
    }

    // ---- shapes for which there is no overlay

    @Test
    void noProgramGivesNoText() {
        ScaffoldResult result = create("<pcml version=\"6.0\"/>");
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("SCF003", Severity.ERROR));
        assertThat(result.diagnostics().get(0).message()).startsWith("The PCML file has no program");
        assertThat(result.diagnostics().get(0).location().file()).isEqualTo("ORDSTATUS.pcml");
    }

    @Test
    void aProgramNameThatIsNotAnIdGivesNoText() {
        ScaffoldResult result = create("<pcml version=\"6.0\"><program name=\"A$B\" path=\"/Q\"/></pcml>");
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).message()).startsWith("Program 'A$B': its name in lower case is not a valid operation id")
                .endsWith("; write the overlay by hand");
    }

    @Test
    void twoProgramsWithTheSameIdGiveNoText() {
        ScaffoldResult result = create("<pcml version=\"6.0\"><program name=\"AB\" path=\"/Q\"/><program name=\"Ab\" path=\"/Q\"/></pcml>");
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly(
                "Programs 'AB' and 'Ab' would both get the operation id 'ab'; write the overlay by hand");
    }

    @Test
    void twoInputsWithTheSameNameGiveNoText() {
        String pcml = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\"><data name=\"A__B\" type=\"char\" length=\"1\" usage=\"input\"/>"
                + "<data name=\"A_B\" type=\"char\" length=\"1\" usage=\"input\"/></program></pcml>";
        ScaffoldResult result = create(pcml);
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly(
                "Parameters 'A__B' and 'A_B' of program 'P' would both get the name 'aB' on the request side; write the overlay by hand");
    }

    @Test
    void twoOutputsWithTheSameNameGiveNoTextButAnInputAndAnOutputDoNot() {
        String twoOut = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\"><data name=\"A__B\" type=\"char\" length=\"1\" usage=\"output\"/>"
                + "<data name=\"A_B\" type=\"char\" length=\"1\" usage=\"output\"/></program></pcml>";
        assertThat(create(twoOut).diagnostics()).extracting(Diagnostic::message).singleElement().asString().contains("response side");
        String inOut = twoOut.replaceFirst("usage=\"output\"", "usage=\"input\"");
        assertThat(create(inOut).text()).isPresent();
    }

    @Test
    void parametersThatNeedADirectionMayClashBecauseTheFixIsAName() {
        String pcml = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\"><data name=\"A__B\" type=\"char\" length=\"1\" usage=\"inputoutput\"/>"
                + "<data name=\"A_B\" type=\"char\" length=\"1\" usage=\"inputoutput\"/></program></pcml>";
        assertThat(create(pcml).text()).isPresent();
    }

    @Test
    void aStructThatRefersToItselfGivesNoText() {
        String pcml = "<pcml version=\"6.0\"><struct name=\"N\"><data name=\"NEXT\" type=\"struct\" struct=\"N\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"X\" type=\"struct\" struct=\"N\" usage=\"input\"/></program></pcml>";
        ScaffoldResult result = create(pcml);
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly(
                "Parameter 'X' of program 'P' uses a struct that is not defined or that refers to itself; write the overlay by hand");
    }

    @Test
    void aStructWithStructsBelowItIsFine() {
        String pcml = "<pcml version=\"6.0\"><struct name=\"A\"><data name=\"B\" type=\"struct\" struct=\"B\"/></struct>"
                + "<struct name=\"B\"><data name=\"C\" type=\"char\" length=\"1\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"X\" type=\"struct\" struct=\"A\" usage=\"input\"/>"
                + "<data name=\"Y\" type=\"struct\" struct=\"A\" usage=\"output\"/></program></pcml>";
        assertThat(create(pcml).text()).isPresent();
    }

    // ---- trim boundaries and recursion (kickoff 14, Part A; review of PR #20, "to change" #4)

    @Test
    void aTopLevelArrayExactlyAtTheThresholdGetsTrimButOneLessDoesNot() {
        String pcml = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\">"
                + "<data name=\"AT\" type=\"char\" length=\"2\" usage=\"output\" count=\"10\"/>"
                + "<data name=\"BELOW\" type=\"char\" length=\"2\" usage=\"output\" count=\"9\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 10).text().orElseThrow();
        assertThat(text).contains("AT:\n").contains("trim:\n").contains("BELOW: { name: below }\n");
    }

    @Test
    void aThresholdOfZeroStillConsidersEveryArray() {
        String pcml = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\">"
                + "<data name=\"A\" type=\"char\" length=\"2\" usage=\"output\" count=\"1\"/></program></pcml>";
        assertThat(OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 0).text().orElseThrow()).contains("trim:\n");
    }

    @Test
    void aThresholdOfZeroStillSearchesAStructsMembers() {
        String pcml = "<pcml version=\"6.0\"><struct name=\"S\"><data name=\"BIG\" type=\"char\" length=\"2\" count=\"1\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"Y\" type=\"struct\" struct=\"S\" usage=\"output\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 0).text().orElseThrow();
        assertThat(text).contains("fields:\n").contains("BIG:\n").contains("trim:\n");
    }

    @Test
    void aNestedMemberExactlyAtTheThresholdGetsTrimButOneLessDoesNot() {
        String pcml = "<pcml version=\"6.0\"><struct name=\"S\"><data name=\"AT\" type=\"char\" length=\"2\" count=\"10\"/>"
                + "<data name=\"BELOW\" type=\"char\" length=\"2\" count=\"9\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"X\" type=\"struct\" struct=\"S\" usage=\"output\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 10).text().orElseThrow();
        assertThat(text).contains("fields:\n").contains("AT:\n").doesNotContain("BELOW:\n");
    }

    // needsTrim's own "definition == null || structStack.contains(...)" guard (mirroring the equivalent guard in
    // ApiBuilder.children()/markFieldMatches) cannot be exercised through create(): resolves() already walks the
    // same struct-reference graph for every top-level parameter first, rejecting an undefined or self-referential
    // struct before writeField/needsTrim is ever called on it (SCF003). Confirmed by trying both as tests here: each
    // one made create() return no text at all, before reaching needsTrim. Left undocumented in code (it would just
    // repeat this comment) and named as an equivalent/unreachable mutant in the pull request description instead.

    @Test
    void aSingleStructOccurrenceGetsBlockFormOnlyBecauseANestedArrayNeedsTrim() {
        // Y itself is not an array (Count.None): it only gets block form, with a nested 'fields:', because
        // needsTrim finds BIG inside S -- isolating needsTrim's "a member needs trim" return from writeField's own,
        // independent ownLarge check (which BIGGRID's and other fixtures' own largeness would otherwise mask).
        String pcml = "<pcml version=\"6.0\"><struct name=\"S\"><data name=\"SMALL\" type=\"char\" length=\"2\"/>"
                + "<data name=\"BIG\" type=\"char\" length=\"2\" count=\"50\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"Y\" type=\"struct\" struct=\"S\" usage=\"output\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 10).text().orElseThrow();
        assertThat(text).contains("Y:\n").contains("fields:\n").contains("BIG:\n").contains("trim:\n").doesNotContain("SMALL:\n");
    }

    @Test
    void aStructWithNoLargeMembersStaysFlat() {
        String pcml = "<pcml version=\"6.0\"><struct name=\"S\"><data name=\"A\" type=\"char\" length=\"2\"/>"
                + "<data name=\"B\" type=\"char\" length=\"2\" count=\"5\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"Y\" type=\"struct\" struct=\"S\" usage=\"output\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 10).text().orElseThrow();
        assertThat(text).contains("Y: { name: y }\n").doesNotContain("fields:").doesNotContain("trim:");
    }

    @Test
    void needsTrimRecursesThroughAMemberThatIsItselfAStructToFindALargeArrayTwoLevelsDown() {
        // Every other test here calls needsTrim on a plain array (scalar or struct) member directly, which returns
        // at its very first check (the field's own count). None of them make needsTrim recurse into ANOTHER struct
        // it finds along the way (the only place it looks up a definition and checks the cycle guard, or loops over
        // a struct's own members with an early return): C here is a single struct occurrence (not itself an array),
        // forcing exactly that. CELL's first member (SMALL) does not need trim, so the loop genuinely continues
        // past a false before BIG, its second member, makes it return true.
        String pcml = "<pcml version=\"6.0\"><struct name=\"CELL\"><data name=\"SMALL\" type=\"char\" length=\"2\"/>"
                + "<data name=\"BIG\" type=\"char\" length=\"2\" count=\"50\"/></struct>"
                + "<struct name=\"ROW\"><data name=\"C\" type=\"struct\" struct=\"CELL\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"Y\" type=\"struct\" struct=\"ROW\" usage=\"output\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 10).text().orElseThrow();
        assertThat(text).contains("Y:\n").contains("C:\n").contains("BIG:\n").contains("trim:\n").doesNotContain("SMALL:\n");
    }

    @Test
    void needsTrimRecursesThroughAMemberThatIsItselfAStructAndCorrectlyFindsNothing() {
        // The contrasting case: recursion through a struct member (same shape as the test above) whose own search
        // genuinely completes with no large array anywhere, so Y must stay flat.
        String pcml = "<pcml version=\"6.0\"><struct name=\"CELL\"><data name=\"SMALL\" type=\"char\" length=\"2\"/></struct>"
                + "<struct name=\"ROW\"><data name=\"C\" type=\"struct\" struct=\"CELL\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"Y\" type=\"struct\" struct=\"ROW\" usage=\"output\"/></program></pcml>";
        String text = OverlayScaffold.create("f", "A.pcml", Fixtures.document(pcml), 10).text().orElseThrow();
        assertThat(text).contains("Y: { name: y }\n").doesNotContain("fields:").doesNotContain("trim:");
    }

    // ---- golden files and determinism

    @Test
    void theSameInputGivesTheSameBytes() {
        assertThat(create(ORDSTATUS).text()).isEqualTo(create(ORDSTATUS).text());
        assertThat(GatewayScaffold.create()).isEqualTo(GatewayScaffold.create());
    }

    @Test
    void goldenFiles() {
        for (String label : List.of("inputoutput/UPDQTY", "kitchen-sink/CATALOG", "large-arrays/BIGGRID")) {
            Fixtures.Api api = Fixtures.apis().stream().filter(a -> a.label().equals(label)).findFirst().orElseThrow();
            String file = api.pcml().getFileName().toString();
            ScaffoldResult result = OverlayScaffold.create(api.folder(), file, Fixtures.parse(file, Fixtures.read(api.pcml())).document().orElseThrow());
            Golden.check("overlay-" + label.replace('/', '-') + ".yaml", result.text().orElseThrow());
        }
        Golden.check("gateway.yaml", GatewayScaffold.create());
    }
}

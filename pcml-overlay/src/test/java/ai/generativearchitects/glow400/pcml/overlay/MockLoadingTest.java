package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mock files, {@code apis/<FOLDER>/mock/<operation id>.yaml}: reading them while loading the repository (MCK001, MCK002). */
class MockLoadingTest {

    private static final String MOCK = "apis/ORD/mock/ordstatus.yaml";

    private static Repos.Temp repo(Path dir, String mock) {
        return new Repos.Temp(dir).api("ORD", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n")).file(MOCK, mock);
    }

    private static List<Tuple> found(RepositoryResult result) {
        return result.diagnostics().stream().map(d -> Tuple.tuple(d.code(), d.severity(), d.location().file(), d.location().line(), d.location().column())).toList();
    }

    private static Optional<MockDefinition> mock(RepositoryResult result) {
        return result.model().orElseThrow().apis().stream().flatMap(a -> a.mocks().stream()).findFirst();
    }

    // ---- the fixture

    @Test
    void theFixtureIsReadWithEveryCaseAndValue() {
        RepositoryResult result = Repos.load("mocked");
        assertThat(result.diagnostics()).isEmpty();
        ApiDefinition api = result.model().orElseThrow().apis().get(0);
        assertThat(api.mocks()).hasSize(1);
        MockDefinition mock = api.mocks().get(0);
        assertThat(mock.operationId()).isEqualTo("ordstatus");
        assertThat(mock.source()).hasToString("apis/ORDERS/mock/ordstatus.yaml:1:1");
        assertThat(mock.cases()).extracting(c -> c.name().orElseThrow()).containsExactly("shipped order", "unknown order", "slow", "host down");
        MockCase shipped = mock.cases().get(0);
        assertThat(shipped.when()).containsOnlyKeys("P0ORDN");
        assertThat(shipped.when().get("P0ORDN")).isEqualTo(new MockValue.Whole(BigInteger.valueOf(1001), shipped.source()));
        assertThat(shipped.when().get("P0ORDN").source()).hasToString("apis/ORDERS/mock/ordstatus.yaml:4:15");
        assertThat(shipped.returns().keySet()).containsExactly("P0STAT", "P0LINES");
        assertThat(shipped.returns().get("P0STAT")).isEqualTo(new MockValue.Text("S", shipped.source()));
        MockValue.Array lines = (MockValue.Array) shipped.returns().get("P0LINES");
        assertThat(lines.elements()).hasSize(1);
        assertThat(((MockValue.Struct) lines.elements().get(0)).members()).containsOnlyKeys("SKU", "QTY");
        assertThat(((MockValue.Struct) lines.elements().get(0)).members().get("QTY")).isEqualTo(new MockValue.Text("2.00", shipped.source()));
        assertThat(shipped.source()).hasToString("apis/ORDERS/mock/ordstatus.yaml:2:5");
        assertThat(mock.cases().get(1).returns().get("P0ERR")).isEqualTo(new MockValue.Text("404", shipped.source()));
        assertThat(mock.cases().get(2).delayMs()).contains(1500);
        assertThat(mock.cases().get(2).fails()).isEmpty();
        assertThat(mock.cases().get(3).fails()).contains(MockFailure.UNAVAILABLE);
        assertThat(mock.cases().get(3).delayMs()).isEmpty();
        assertThat(api.operations()).hasSize(2);
    }

    @Test
    void aRepositoryWithoutMockFilesLoadsAsBefore() {
        for (String fixture : List.of("minimal", "kitchen-sink", "structs", "operations-list")) {
            RepositoryResult result = Repos.load(fixture);
            assertThat(result.model().orElseThrow().apis()).allSatisfy(api -> assertThat(api.mocks()).isEmpty());
        }
    }

    @Test
    void anEmptyMockFolderOrOneWithOtherFilesGivesNoMock(@TempDir Path dir) throws IOException {
        Repos.Temp repo = new Repos.Temp(dir).api("ORD", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n"));
        Files.createDirectories(dir.resolve("apis/ORD/mock"));
        assertThat(repo.load().diagnostics()).isEmpty();
        repo.file("apis/ORD/mock/README.md", "notes").file("apis/ORD/mock/ordstatus.json", "{}").file("apis/ORD/mock/data.txt", "x");
        RepositoryResult result = repo.load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(mock(result)).isEmpty();
    }

    // ---- MCK001: the file is not valid

    private static void invalid(Path dir, String mock, String message) {
        RepositoryResult result = repo(dir, mock).load();
        assertThat(result.diagnostics()).as(mock).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo("MCK001");
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.message()).isEqualTo(message);
        assertThat(d.location().file()).isEqualTo(MOCK);
        assertThat(mock(result)).as("an invalid file is not loaded").isEmpty();
    }

    @Test
    void aFileThatIsNotYamlIsMck001(@TempDir Path dir) {
        RepositoryResult result = repo(dir, "cases: [").load();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).code()).isEqualTo("MCK001");
        assertThat(result.diagnostics().get(0).message()).startsWith("Not valid YAML: ");
        assertThat(result.diagnostics().get(0).location().file()).isEqualTo(MOCK);
    }

    @Test
    void anEmptyFileOrOneThatIsNotAMappingIsMck001(@TempDir Path dir) {
        invalid(dir, "", "The mock file is empty: it needs 'cases'");
        invalid(dir.resolve("b"), "# nothing\n", "The mock file is empty: it needs 'cases'");
        invalid(dir.resolve("c"), "- a\n", "the mock file must be a mapping");
    }

    @Test
    void casesAreRequiredAndMustBeAList(@TempDir Path dir) {
        assertThat(repo(dir, "other: 1\n").load().diagnostics()).extracting(Diagnostic::message).containsExactly(
                "Unknown key 'other' in the mock file; allowed keys: cases", "Missing required key 'cases'");
        invalid(dir.resolve("b"), "cases: 3\n", "'cases' must be a list");
        invalid(dir.resolve("c"), "cases:\n  a: 1\n", "'cases' must be a list");
    }

    @Test
    void aMockFileWithoutCasesKeyIsMck001(@TempDir Path dir) {
        RepositoryResult result = repo(dir, "{}\n").load();
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly("Missing required key 'cases'");
    }

    @Test
    void anEmptyListOfCasesIsValid(@TempDir Path dir) {
        RepositoryResult result = repo(dir, "cases: []\n").load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(mock(result).orElseThrow().cases()).isEmpty();
    }

    @Test
    void anUnknownKeyOrAShapeThatIsNotACaseIsMck001(@TempDir Path dir) {
        invalid(dir, "cases:\n  - nmae: x\n", "Unknown key 'nmae' in a case; allowed keys: delayMs, fails, name, returns, when");
        invalid(dir.resolve("b"), "cases:\n  - x\n", "a case must be a mapping");
        invalid(dir.resolve("c"), "cases:\n  - when: 3\n", "'when' must be a mapping");
        invalid(dir.resolve("d"), "cases:\n  - returns: [a]\n", "'returns' must be a mapping");
    }

    @Test
    void aNameMustNotBeBlankOrNotText(@TempDir Path dir) {
        invalid(dir, "cases:\n  - name: \"  \"\n", "'name' must not be empty");
        invalid(dir.resolve("b"), "cases:\n  - name: [a]\n", "'name' must be a string");
    }

    @Test
    void delayMsIsFrom0To60000(@TempDir Path dir) {
        assertThat(mock(repo(dir.resolve("a"), "cases:\n  - delayMs: 0\n").load()).orElseThrow().cases().get(0).delayMs()).contains(0);
        assertThat(mock(repo(dir.resolve("b"), "cases:\n  - delayMs: 60000\n").load()).orElseThrow().cases().get(0).delayMs()).contains(60000);
        invalid(dir.resolve("c"), "cases:\n  - delayMs: -1\n", "'delayMs' must be from 0 to 60000, not -1");
        invalid(dir.resolve("d"), "cases:\n  - delayMs: 60001\n", "'delayMs' must be from 0 to 60000, not 60001");
        invalid(dir.resolve("e"), "cases:\n  - delayMs: slow\n", "'delayMs' must be a whole number");
        invalid(dir.resolve("f"), "cases:\n  - delayMs: 1.5\n", "'delayMs' must be a whole number");
    }

    @Test
    void failsIsUnavailableOrTimeoutAndHasNoReturns(@TempDir Path dir) {
        assertThat(mock(repo(dir.resolve("a"), "cases:\n  - fails: timeout\n    delayMs: 100\n").load()).orElseThrow().cases().get(0).fails()).contains(MockFailure.TIMEOUT);
        assertThat(mock(repo(dir.resolve("b"), "cases:\n  - fails: unavailable\n    returns: {}\n").load()).orElseThrow().cases().get(0).fails()).contains(MockFailure.UNAVAILABLE);
        invalid(dir.resolve("c"), "cases:\n  - fails: down\n", "'fails' must be one of unavailable, timeout, not 'down'");
        invalid(dir.resolve("d"), "cases:\n  - fails: timeout\n    returns: { P0STAT: S }\n", "A case with 'fails' has no 'returns'");
    }

    @Test
    void aValueIsAScalarAStructOrAListAndNotNull(@TempDir Path dir) {
        invalid(dir, "cases:\n  - returns: { P0STAT: }\n", "the value of 'P0STAT' must not be empty");
        invalid(dir.resolve("b"), "cases:\n  - returns: { P0STAT: ~ }\n", "the value of 'P0STAT' must not be empty");
        invalid(dir.resolve("c"), "cases:\n  - returns: { P0LINES: [ { SKU: } ] }\n", "the value of 'SKU' must not be empty");
        invalid(dir.resolve("d"), "cases:\n  - returns: { P0LINES: [ ~ ] }\n", "an element of a list must not be empty");
        invalid(dir.resolve("e"), "cases:\n  - when: { P0ORDN: 0x1F }\n", "the value of 'P0ORDN' must be a whole number written in decimal digits");
    }

    @Test
    void everyKindOfScalarIsKeptAsWritten(@TempDir Path dir) {
        RepositoryResult result = repo(dir, "cases:\n  - returns:\n      A: text\n      B: \"12\"\n      C: 12\n      D: -7\n      E: true\n      F: False\n      G: 1.5\n      H: 1e3\n      I: 123456789012345678901234567890\n      J: ${X}\n      K: 2026-01-31\n").load();
        assertThat(result.diagnostics()).isEmpty();
        Map<String, MockValue> values = mock(result).orElseThrow().cases().get(0).returns();
        assertThat(values.get("A")).isInstanceOf(MockValue.Text.class);
        assertThat(((MockValue.Text) values.get("B")).value()).isEqualTo("12");
        assertThat(values.get("C")).isInstanceOf(MockValue.Whole.class);
        assertThat(((MockValue.Whole) values.get("D")).value()).isEqualTo(BigInteger.valueOf(-7));
        assertThat(((MockValue.Bool) values.get("E")).value()).isTrue();
        assertThat(((MockValue.Bool) values.get("F")).value()).isFalse();
        assertThat(((MockValue.Real) values.get("G")).literal()).isEqualTo("1.5");
        assertThat(((MockValue.Real) values.get("H")).literal()).isEqualTo("1e3");
        assertThat(((MockValue.Whole) values.get("I")).value()).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(((MockValue.Text) values.get("J")).value()).isEqualTo("${X}");
        assertThat(((MockValue.Text) values.get("K")).value()).isEqualTo("2026-01-31");
    }

    @Test
    void aCaseWithNothingInItMatchesEverythingAndKeepsItsOrder(@TempDir Path dir) {
        MockDefinition mock = mock(repo(dir, "cases:\n  - {}\n  - name: b\n    when: { Z: 1, A: 2 }\n").load()).orElseThrow();
        assertThat(mock.cases().get(0).name()).isEmpty();
        assertThat(mock.cases().get(0).when()).isEmpty();
        assertThat(mock.cases().get(1).when().keySet()).containsExactly("Z", "A");
    }

    @Test
    void anAliasIsReadAsItsValueAndARecursiveOneIsMck001(@TempDir Path dir) {
        MockDefinition mock = mock(repo(dir, "cases:\n  - returns: { A: &x text, B: *x }\n").load()).orElseThrow();
        assertThat(mock.cases().get(0).returns().get("B")).isEqualTo(new MockValue.Text("text", mock.source()));
        RepositoryResult recursive = repo(dir.resolve("r"), "cases:\n  - returns: &r { A: *r }\n").load();
        assertThat(recursive.diagnostics()).extracting(Diagnostic::code).containsExactly("MCK001");
    }

    @Test
    void aFileOfMoreThan1000CasesIsMck001ButOneOfExactly1000IsNot(@TempDir Path dir) {
        String thousand = "cases:\n" + "  - {}\n".repeat(1000);
        assertThat(mock(repo(dir.resolve("a"), thousand).load()).orElseThrow().cases()).hasSize(1000);
        RepositoryResult result = repo(dir.resolve("b"), thousand + "  - {}\n").load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::message).containsExactly(Tuple.tuple("MCK001", "A mock file has at most 1000 cases"));
        assertThat(found(result).get(0).toList()).containsSubsequence("MCK001", Severity.ERROR, MOCK, 1);
    }

    @Test
    void aFileOfMoreThan1MiBIsMck001ButOneOfExactly1MiBIsRead(@TempDir Path dir) {
        String head = "cases:\n  - name: ";
        String exact = head + "x".repeat((int) MockParser.MAX_BYTES - head.length() - 1) + "\n";
        assertThat(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(1024 * 1024);
        assertThat(repo(dir.resolve("a"), exact).load().diagnostics()).isEmpty();
        RepositoryResult result = repo(dir.resolve("b"), exact + "#\n").load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::message).containsExactly(Tuple.tuple("MCK001", "A mock file has at most 1048576 bytes"));
    }

    @Test
    void aFileThatIsNotUtf8IsMck001(@TempDir Path dir) throws IOException {
        Repos.Temp repo = repo(dir, "cases: []\n");
        Files.write(dir.resolve(MOCK), new byte[] {'c', 'a', 's', 'e', 's', ':', ' ', (byte) 0xff, (byte) 0xfe});
        RepositoryResult result = repo.load();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).code()).isEqualTo("MCK001");
        assertThat(result.diagnostics().get(0).message()).startsWith("Cannot read the mock file as UTF-8 text: ");
    }

    @Test
    void aMockFileThatLeadsOutOfTheRepositoryIsMck001(@TempDir Path dir) throws IOException {
        Path repoDir = Files.createDirectories(dir.resolve("repo"));
        Path outside = Files.writeString(dir.resolve("outside.yaml"), "cases: []\n");
        new Repos.Temp(repoDir).api("ORD", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n"));
        Files.createDirectories(repoDir.resolve("apis/ORD/mock"));
        try {
            Files.createSymbolicLink(repoDir.resolve(MOCK), outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        RepositoryResult result = OverlayLoader.loadRepository(repoDir);
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::message).containsExactly(Tuple.tuple("MCK001", "The mock file must be inside the repository"));
        assertThat(mock(result)).isEmpty();
    }

    // ---- MCK002: the file name

    @Test
    void aFileThatIsNotNamedAfterAnOperationIsMck002(@TempDir Path dir) {
        RepositoryResult result = repo(dir, "cases: []\n").file("apis/ORD/mock/other.yaml", "cases: []\n").load();
        assertThat(result.diagnostics()).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo("MCK002");
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.message()).isEqualTo("The mock file other.yaml is not named after an operation of this API: mock files are named <operation id>.yaml, and the operations are [ordstatus]");
        assertThat(d.location()).hasToString("apis/ORD/mock/other.yaml:1:1");
        assertThat(mock(result)).isPresent();
    }

    @Test
    void theIdIsTheOneTheLoaderDerivesAndTheExtensionIsYaml(@TempDir Path dir) {
        RepositoryResult result = repo(dir, "cases: []\n").file("apis/ORD/mock/ordstatus.yml", "cases: []\n").file("apis/ORD/mock/ORDSTATUS.yaml", "cases: []\n")
                .file("apis/ORD/mock/ordstatus.YAML", "cases: []\n").load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("MCK002", "MCK002", "MCK002");
        assertThat(result.diagnostics()).extracting(d -> d.location().file()).containsExactlyInAnyOrder("apis/ORD/mock/ordstatus.yml", "apis/ORD/mock/ORDSTATUS.yaml", "apis/ORD/mock/ordstatus.YAML");
        assertThat(mock(result).orElseThrow().operationId()).isEqualTo("ordstatus");
    }

    @Test
    void everyOperationOfAListCanHaveItsFileAndOnlyTheirs() {
        RepositoryResult result = Repos.load("mocked");
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().apis().get(0).operations()).extracting(o -> o.id()).containsExactly("ordstatus", "update-quantity");
    }

    @Test
    void aFileForAnOperationThatIsLeftOutForErrorsIsNotAttached(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir).api("ORD", Repos.Temp.overlay("parameters:\n  NOPE: {}\n")).file(MOCK, "cases: []\n");
        RepositoryResult result = repo.load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL002").doesNotContain("MCK001", "MCK002");
        assertThat(result.model().orElseThrow().apis()).isEmpty();
    }

    @Test
    void aMockFileOfADuplicateOperationIsDroppedWithIt(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir).api("A", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n"))
                .api("B", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n")).file("apis/B/mock/ordstatus.yaml", "cases: []\n");
        RepositoryResult result = repo.load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL014");
        assertThat(result.model().orElseThrow().apis()).hasSize(1);
        assertThat(result.model().orElseThrow().apis().get(0).mocks()).isEmpty();
    }

    @Test
    void theInvalidFixturesGiveTheirCode() {
        assertThat(found(Repos.load("invalid/mck001"))).containsExactly(Tuple.tuple("MCK001", Severity.ERROR, "apis/ORDERS/mock/ordstatus.yaml", 3, 5));
        assertThat(found(Repos.load("invalid/mck002"))).containsExactly(Tuple.tuple("MCK002", Severity.ERROR, "apis/ORDERS/mock/orders.yaml", 1, 1));
    }

    @Test
    void theNamesAndValuesAreNotCheckedWhileLoading() {
        for (String fixture : List.of("invalid/mck003", "invalid/mck004", "invalid/mck005")) {
            RepositoryResult result = Repos.load(fixture);
            assertThat(result.diagnostics()).as(fixture).isEmpty();
            assertThat(result.model().orElseThrow().apis().get(0).mocks()).as(fixture).hasSize(1);
        }
    }
}

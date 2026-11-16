package ai.generativearchitects.slay400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.cli.Support.Result;
import ai.generativearchitects.slay400.mock.MockFile;
import ai.generativearchitects.slay400.mock.MockReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mock definitions on the command line: {@code validate} reports MCK diagnostics, {@code generate} and {@code bundle} carry the files. */
class MockCommandsTest {

    private static final String OVL006 = "apis/ORDERS/api.yaml:26:5: warning OVL006: Operation update-quantity has no error mapping: a business error reported by the program is returned as a success\n";

    @Test
    void validateAcceptsTheMockOfTheBrief(@TempDir Path dir) {
        Support.repo("mocked", dir.resolve("repo"));
        Result result = Support.run(dir, "validate", "--repo", "repo");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo(OVL006 + "0 errors, 1 warning, 0 notes\n");
    }

    @Test
    void validateReportsTheMck001AndMck002OfTheLoaderAtTheMockFile(@TempDir Path dir) {
        Support.repo("invalid/mck001", dir.resolve("a"));
        Result one = Support.run(dir, "validate", "--repo", "a");
        assertThat(one.exit()).isEqualTo(1);
        assertThat(one.out()).contains("apis/ORDERS/mock/ordstatus.yaml:3:5: error MCK001: Unknown key 'retruns' in a case; allowed keys: delayMs, fails, name, returns, when");
        Support.repo("invalid/mck002", dir.resolve("b"));
        Result two = Support.run(dir, "validate", "--repo", "b");
        assertThat(two.exit()).isEqualTo(1);
        assertThat(two.out()).contains("apis/ORDERS/mock/orders.yaml:1:1: error MCK002: The mock file orders.yaml is not named after an operation of this API");
    }

    @Test
    void validateReportsMck003Mck004AndMck005FromTheCodecsPointOfView(@TempDir Path dir) {
        Support.repo("invalid/mck003", dir.resolve("c"));
        Result three = Support.run(dir, "validate", "--repo", "c");
        assertThat(three.exit()).isEqualTo(1);
        assertThat(three.out()).contains("apis/ORDERS/mock/ordstatus.yaml:2:19: error MCK003: In case 1: 'NOPE' is not a parameter of the program ORDSTATUS\n")
                .contains("error MCK003: In case 3: 'P0ORDN' is not returned by the program: it is an input parameter\n").endsWith("4 errors, 1 warning, 0 notes\n");
        Support.repo("invalid/mck004", dir.resolve("d"));
        Result four = Support.run(dir, "validate", "--repo", "d");
        assertThat(four.out()).contains("apis/ORDERS/mock/ordstatus.yaml:3:24: error MCK004: In case 1: the value for P0STAT cannot be carried by the field (VAL003: The value is too long: at most 1 characters)\n")
                .doesNotContain("SECRET");
        Support.repo("invalid/mck005", dir.resolve("e"));
        Result five = Support.run(dir, "validate", "--repo", "e");
        assertThat(five.exit()).isZero();
        assertThat(five.out()).contains("apis/ORDERS/mock/ordstatus.yaml:4:5: warning MCK005: The case 'same' can never match: the case 'first' before it has the same 'when'\n");
        assertThat(Support.run(dir, "validate", "--repo", "e", "--fail-on", "warning").exit()).isEqualTo(1);
    }

    @Test
    void validateReportsAWrongCountAsMck004AndADifferingOneAsMck006(@TempDir Path dir) {
        Support.repo("invalid/mck004-count", dir.resolve("f"));
        Result four = Support.run(dir, "validate", "--repo", "f");
        assertThat(four.exit()).isEqualTo(1);
        assertThat(four.out()).contains("apis/LISTLINES/mock/listlines.yaml:4:24: error MCK004: In case 'too many': the value for P0NROW is not a count that the list P0ROWS can hold: "
                + "it is negative or more than the 500 elements it holds\n");
        Support.repo("invalid/mck006", dir.resolve("g"));
        Result six = Support.run(dir, "validate", "--repo", "g");
        assertThat(six.exit()).isZero();
        assertThat(six.out()).contains("apis/LISTLINES/mock/listlines.yaml:9:15: warning MCK006: In case 'two of three': the count P0NROW is not the number of elements given for P0ROWS (3)\n");
        assertThat(Support.run(dir, "validate", "--repo", "g", "--fail-on", "warning").exit()).isEqualTo(1);
    }

    @Test
    void theReportOfAMockProblemIsStillTheOnlyThingOnStandardOutputInEveryFormat(@TempDir Path dir) {
        Support.repo("invalid/mck003", dir.resolve("c"));
        Result json = Support.run(dir, "validate", "--repo", "c", "--format", "json");
        assertThat(json.out()).startsWith("{").endsWith("}\n").contains("\"MCK003\"");
        assertThat(json.err()).isEmpty();
        assertThat(Support.run(dir, "validate", "--repo", "c", "--format", "sarif").out()).contains("\"ruleId\": \"MCK003\"");
    }

    @Test
    void generateWritesTheMockFileNextToTheOthersAndNothingForAnApiWithoutMocks(@TempDir Path dir) throws IOException {
        Support.repo("mocked", dir.resolve("repo"));
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isZero();
        assertThat(result.err()).contains("wrote out/mock/orders.json\n").contains("wrote out/descriptor.json\n").contains("wrote out/openapi/orders.yaml\n");
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "descriptor.json", "mock", "mock/orders.json", "openapi", "openapi/orders.yaml");
        MockFile file = MockReader.read(new ByteArrayInputStream(Files.readAllBytes(dir.resolve("out/mock/orders.json"))), "mock/orders.json").file().orElseThrow();
        assertThat(file.api()).isEqualTo("orders");
        assertThat(file.operations()).hasSize(1);
        assertThat(file.operations().get(0).cases()).hasSize(4);
        Support.repo("minimal", dir.resolve("plain"));
        assertThat(Support.run(dir, "generate", "--repo", "plain", "--out", "plainout").exit()).isZero();
        assertThat(Support.names(dir.resolve("plainout"))).containsExactly("", "descriptor.json", "openapi", "openapi/ordstatus.yaml");
    }

    @Test
    void generateWithoutMockAmongTheEmittersWritesNoMockFile(@TempDir Path dir) {
        Support.repo("mocked", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [descriptor, openapi]\n");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "out").exit()).isZero();
        assertThat(Support.names(dir.resolve("out"))).doesNotContain("mock", "mock/orders.json");
    }

    @Test
    void theDescriptorAndTheOpenApiDocumentAreTheSameBytesWithOrWithoutTheMockFiles(@TempDir Path dir) throws IOException {
        Support.repo("mocked", dir.resolve("with"));
        Support.repo("mocked", dir.resolve("without"));
        try (var files = Files.walk(dir.resolve("without/apis/ORDERS/mock"))) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        assertThat(Support.run(dir, "generate", "--repo", "with", "--out", "a").exit()).isZero();
        assertThat(Support.run(dir, "generate", "--repo", "without", "--out", "b").exit()).isZero();
        assertThat(Support.bytes(dir.resolve("a/descriptor.json"))).isEqualTo(Support.bytes(dir.resolve("b/descriptor.json")));
        assertThat(Support.bytes(dir.resolve("a/openapi/orders.yaml"))).isEqualTo(Support.bytes(dir.resolve("b/openapi/orders.yaml")));
        assertThat(Support.names(dir.resolve("b"))).doesNotContain("mock");
    }

    @Test
    void generateIsTheSameBytesTwice(@TempDir Path dir) {
        Support.repo("mocked", dir.resolve("repo"));
        Support.run(dir, "generate", "--repo", "repo", "--out", "one");
        Support.run(dir, "generate", "--repo", "repo", "--out", "two");
        assertThat(Support.bytes(dir.resolve("one/mock/orders.json"))).isEqualTo(Support.bytes(dir.resolve("two/mock/orders.json")));
    }

    @Test
    void bundleCarriesTheMockFilesAndTheirSourcesWhenMockIsAmongTheEmitters(@TempDir Path dir) throws IOException {
        Support.repo("mocked", dir.resolve("repo"));
        Result result = Support.run(dir, "bundle", "--repo", "repo", "--name", "orders", "--version", "1.0.0", "--out", "out/orders.rpgapi");
        assertThat(result.exit()).isZero();
        List<String> names = zipNames(dir.resolve("out/orders.rpgapi"));
        assertThat(names).contains("mock/orders.json", "apis/ORDERS/mock/ordstatus.yaml", "descriptor.json", "openapi/orders.yaml");
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [descriptor, openapi]\n");
        assertThat(Support.run(dir, "bundle", "--repo", "repo", "--name", "orders", "--version", "1.0.0", "--out", "out/b2.rpgapi").exit()).isZero();
        assertThat(zipNames(dir.resolve("out/b2.rpgapi"))).noneMatch(n -> n.contains("mock"));
    }

    private static List<String> zipNames(Path file) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            return zip.stream().map(java.util.zip.ZipEntry::getName).toList();
        }
    }

    @Test
    void initIsUnchangedAndCreatesNoMockFiles(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Result result = Support.run(dir, "init");
        assertThat(result.exit()).isZero();
        assertThat(Support.names(dir)).noneMatch(n -> n.contains("mock"));
        assertThat(Support.read(dir.resolve("gateway.yaml"))).endsWith("emitters:\n  - descriptor\n  - openapi\n  - mock\n");
        Support.repo("mocked", dir.resolve("repo"));
        Result again = Support.run(dir, "init", "--repo", "repo");
        assertThat(again.err()).contains("kept repo/gateway.yaml".replace("kept repo/gateway.yaml", "created repo/gateway.yaml")).contains("kept repo/apis/ORDERS/api.yaml");
        assertThat(Support.names(dir.resolve("repo"))).contains("apis/ORDERS/mock/ordstatus.yaml").doesNotContain("mock");
    }

    @Test
    void annotateStopsOnAMockError(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.run(dir, "init");
        Support.write(dir.resolve("apis/GETBAL/mock/other.yaml"), "cases: []\n");
        Result result = Support.runWithInput(dir, "request\n", "annotate");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).contains("error MCK002");
    }
}

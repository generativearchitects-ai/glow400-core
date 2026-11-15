package ai.generativearchitects.glow400.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reader of {@code mock/<api>.json} as the reader of untrusted input: one diagnostic and no file for anything wrong. */
class MockReaderTest {

    private static final String HEAD = "{\"mockSchemaVersion\": 1, \"api\": \"a\", \"operations\": ";

    private static MockReadResult read(String json) {
        return MockReader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "mock/a.json");
    }

    private static String ops(String operations) {
        return HEAD + operations + "}";
    }

    private static String cases(String cases) {
        return ops("[{\"id\": \"o\", \"cases\": " + cases + "}]");
    }

    private static String oneCase(String body) {
        return cases("[{" + body + "}]");
    }

    private static void bad(String json, String message) {
        MockReadResult result = read(json);
        assertThat(result.file()).as(json).isEmpty();
        assertThat(result.diagnostics()).as(json).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo("MCK001");
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.message()).as(json).isEqualTo(message);
    }

    // ---- a valid file

    @Test
    void aMinimalFileHasNoOperations() {
        MockFile file = read(ops("[]")).file().orElseThrow();
        assertThat(file.api()).isEqualTo("a");
        assertThat(file.operations()).isEmpty();
    }

    @Test
    void everyKeyIsReadAndEveryPartIsOptionalButTheIdAndTheCases() {
        MockFile file = read(ops("[{\"id\": \"o\", \"cases\": [{}, {\"name\": \"n\", \"when\": {\"A\": 1}, \"returns\": {\"B\": [1.5, true, \"x\", {\"C\": 2}]}, "
                + "\"delayMs\": 7, \"fails\": null}]}]").replace(", \"fails\": null", "")).file().orElseThrow();
        assertThat(file.operations()).hasSize(1);
        assertThat(file.operations().get(0).operationId()).isEqualTo("o");
        assertThat(file.operations().get(0).cases().get(0).name()).isEmpty();
        assertThat(file.operations().get(0).cases().get(0).when()).isEmpty();
        MockValue.Array b = (MockValue.Array) file.operations().get(0).cases().get(1).returns().get("B");
        assertThat(b.elements().get(0)).isInstanceOf(MockValue.Real.class);
        assertThat(((MockValue.Real) b.elements().get(0)).literal()).isEqualTo("1.5");
        assertThat(((MockValue.Bool) b.elements().get(1)).value()).isTrue();
        assertThat(((MockValue.Text) b.elements().get(2)).value()).isEqualTo("x");
        assertThat(((MockValue.Struct) b.elements().get(3)).members().get("C")).isInstanceOf(MockValue.Whole.class);
        assertThat(file.operations().get(0).cases().get(1).delayMs()).contains(7);
    }

    @Test
    void failsIsUnavailableOrTimeoutAndDelayIsFrom0To60000() {
        assertThat(read(oneCase("\"fails\": \"unavailable\", \"delayMs\": 0")).file().orElseThrow().operations().get(0).cases().get(0).fails()).contains(MockFailure.UNAVAILABLE);
        assertThat(read(oneCase("\"fails\": \"timeout\", \"delayMs\": 60000")).file().orElseThrow().operations().get(0).cases().get(0).delayMs()).contains(60000);
    }

    @Test
    void everythingReadHasThePositionOfTheFile() {
        MockFile file = MockReader.read(new ByteArrayInputStream(oneCase("\"when\": {\"A\": 1}").getBytes(StandardCharsets.UTF_8)), "x.json").file().orElseThrow();
        assertThat(file.operations().get(0).source()).hasToString("x.json:1:1");
        assertThat(file.operations().get(0).cases().get(0).source()).hasToString("x.json:1:1");
        assertThat(file.operations().get(0).cases().get(0).when().get("A").source()).hasToString("x.json:1:1");
    }

    // ---- not JSON, or not a document of this version

    @Test
    void textThatIsNotJsonIsMck001WithItsPlace() {
        MockReadResult result = read("{\"mockSchemaVersion\": ");
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
        assertThat(result.diagnostics().get(0).location().file()).isEqualTo("mock/a.json");
        assertThat(result.diagnostics().get(0).location().line()).isEqualTo(1);
        MockReadResult second = read("{\n\n  \"a\" 1}");
        assertThat(second.diagnostics().get(0).location().line()).isEqualTo(3);
    }

    @Test
    void anEmptyDocumentAContentAfterTheEndAndARepeatedKeyAreMck001() {
        assertThat(read("").diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
        assertThat(read("{} {}").diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
        assertThat(read("{\"mockSchemaVersion\": 1, \"mockSchemaVersion\": 1}").diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
    }

    @Test
    void aDocumentThatCannotBeReadIsMck001() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk on fire: /secret/path");
            }
        };
        MockReadResult result = MockReader.read(broken, "mock/a.json");
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The document cannot be read: IOException");
        assertThat(result.diagnostics().get(0).location()).hasToString("mock/a.json:1:1");
    }

    @Test
    void theVersionMustBeOneWholeNumberAndTheKeysThoseOfTheVersion() {
        bad("[]", "(document): expected an object");
        bad("{}", "(document): missing key 'mockSchemaVersion'");
        bad("{\"mockSchemaVersion\": \"1\"}", "mockSchemaVersion: expected a whole number");
        bad("{\"mockSchemaVersion\": 1.0}", "mockSchemaVersion: expected a whole number");
        bad("{\"mockSchemaVersion\": 2, \"api\": \"a\", \"operations\": []}", "mockSchemaVersion: unsupported mock schema version 2; this reader supports version 1");
        bad("{\"mockSchemaVersion\": 1}", "(document): missing key 'api'");
        bad("{\"mockSchemaVersion\": 1, \"api\": 3}", "api: expected a string");
        bad("{\"mockSchemaVersion\": 1, \"api\": \" \", \"operations\": []}", "api: must not be empty");
        bad("{\"mockSchemaVersion\": 1, \"api\": \"a\"}", "(document): missing key 'operations'");
        bad("{\"mockSchemaVersion\": 1, \"api\": \"a\", \"operations\": {}}", "operations: expected an array");
        bad(ops("[]").replace("}", ", \"extra\": 1}"), "(document): unknown key 'extra'");
    }

    @Test
    void aBigVersionIsNotTheVersion() {
        bad("{\"mockSchemaVersion\": 100000000000000000000, \"api\": \"a\", \"operations\": []}", "mockSchemaVersion: unsupported mock schema version 100000000000000000000; this reader supports version 1");
    }

    @Test
    void anOperationNeedsAnIdAndCasesAndHasNothingElse() {
        bad(ops("[3]"), "operations[0]: expected an object");
        bad(ops("[{}]"), "operations[0]: missing key 'id'");
        bad(ops("[{\"id\": 3}]"), "operations[0].id: expected a string");
        bad(ops("[{\"id\": \" \", \"cases\": []}]"), "operations[0].id: must not be empty");
        bad(ops("[{\"id\": \"o\"}]"), "operations[0]: missing key 'cases'");
        bad(ops("[{\"id\": \"o\", \"cases\": {}}]"), "operations[0].cases: expected an array");
        bad(ops("[{\"id\": \"o\", \"cases\": [], \"x\": 1}]"), "operations[0]: unknown key 'x'");
        bad(ops("[{\"id\": \"o\", \"cases\": []}, {\"id\": \"o\", \"cases\": []}]"), "operations[1].id: another operation has this id");
    }

    @Test
    void aCaseHasOnlyItsKeysAndRightValues() {
        bad(cases("[3]"), "operations[0].cases[0]: expected an object");
        bad(oneCase("\"nmae\": 1"), "operations[0].cases[0]: unknown key 'nmae'");
        bad(oneCase("\"name\": 3"), "operations[0].cases[0].name: expected a string");
        bad(oneCase("\"name\": \" \""), "operations[0].cases[0].name: must not be empty");
        bad(oneCase("\"when\": []"), "operations[0].cases[0].when: expected an object");
        bad(oneCase("\"returns\": 3"), "operations[0].cases[0].returns: expected an object");
        bad(oneCase("\"delayMs\": \"5\""), "operations[0].cases[0].delayMs: expected a whole number from 0 to 60000");
        bad(oneCase("\"delayMs\": 1.5"), "operations[0].cases[0].delayMs: expected a whole number from 0 to 60000");
        bad(oneCase("\"delayMs\": -1"), "operations[0].cases[0].delayMs: expected a whole number from 0 to 60000");
        bad(oneCase("\"delayMs\": 60001"), "operations[0].cases[0].delayMs: expected a whole number from 0 to 60000");
        bad(oneCase("\"delayMs\": 99999999999999999999"), "operations[0].cases[0].delayMs: expected a whole number from 0 to 60000");
        bad(oneCase("\"fails\": 3"), "operations[0].cases[0].fails: expected a string");
        bad(oneCase("\"fails\": \"down\""), "operations[0].cases[0].fails: expected one of [unavailable, timeout]");
        bad(oneCase("\"fails\": \"timeout\", \"returns\": {\"A\": 1}"), "operations[0].cases[0]: a case with 'fails' has no 'returns'");
    }

    @Test
    void aFailingCaseMayHaveAnEmptyReturns() {
        assertThat(read(oneCase("\"fails\": \"timeout\", \"returns\": {}")).file()).isPresent();
    }

    @Test
    void aValueIsNeverNullAndTheMessageNamesItsPlace() {
        bad(oneCase("\"returns\": {\"A\": null}"), "operations[0].cases[0].returns.A: a value must not be null");
        bad(oneCase("\"returns\": {\"A\": [1, null]}"), "operations[0].cases[0].returns.A[1]: a value must not be null");
        bad(oneCase("\"when\": {\"A\": {\"B\": null}}"), "operations[0].cases[0].when.A.B: a value must not be null");
    }

    @Test
    void thereAreAtMost1000CasesToAnOperation() {
        String thousand = "[" + "{},".repeat(999) + "{}]";
        assertThat(read(cases(thousand)).file().orElseThrow().operations().get(0).cases()).hasSize(1000);
        bad(cases("[" + "{},".repeat(1000) + "{}]"), "operations[0].cases: at most 1000 cases");
    }

    @Test
    void aDocumentOverTheLimitsOfTheJsonReaderIsMck001() {
        String deep = "[".repeat(300) + "]".repeat(300);
        MockReadResult nested = read(deep);
        assertThat(nested.file()).isEmpty();
        assertThat(nested.diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
        String longString = "{\"mockSchemaVersion\": 1, \"api\": \"" + "a".repeat(1_000_001) + "\", \"operations\": []}";
        assertThat(read(longString).diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
    }

    @Test
    void aDocumentOver16MiBIsMck001WithoutHoldingItInMemory() {
        InputStream endless = new InputStream() {
            private long sent;

            @Override
            public int read() {
                if (sent == 0) {
                    sent++;
                    return '[';
                }
                return sent++ % 2 == 1 ? '1' : ',';
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                for (int i = 0; i < len; i++) {
                    buffer[off + i] = (byte) read();
                }
                return len;
            }
        };
        MockReadResult result = MockReader.read(endless, "mock/a.json");
        assertThat(result.file()).isEmpty();
        assertThat(result.diagnostics().get(0).message()).startsWith("The document is not valid JSON: ");
    }

    @Test
    void numbersAreWholeNumbersOfAnySizeOrRealAndNothingTakesItsTypeFromTheInput() {
        MockFile file = read(oneCase("\"returns\": {\"A\": 123456789012345678901234567890, \"B\": 1e3, \"C\": -0.5, \"D\": \"java.lang.Runtime\"}")).file().orElseThrow();
        assertThat(file.operations().get(0).cases().get(0).returns().get("A")).isInstanceOf(MockValue.Whole.class);
        assertThat(((MockValue.Whole) file.operations().get(0).cases().get(0).returns().get("A")).value()).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(file.operations().get(0).cases().get(0).returns().get("B")).isInstanceOf(MockValue.Real.class);
        assertThat(file.operations().get(0).cases().get(0).returns().get("C")).isInstanceOf(MockValue.Real.class);
        assertThat(file.operations().get(0).cases().get(0).returns().get("D")).isEqualTo(new MockValue.Text("java.lang.Runtime", file.operations().get(0).source()));
    }

    @Test
    void theArgumentsAreNotOptionalAndTheFileHasTheRulesOfItsRecords() {
        assertThatThrownBy(() -> MockReader.read(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MockReader.read(new ByteArrayInputStream(new byte[0]), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockFile(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockFile(" ", List.of())).isInstanceOf(IllegalArgumentException.class).hasMessage("api must not be blank");
        assertThatThrownBy(() -> new MockFile("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockReadResult(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockReadResult(java.util.Optional.empty(), null)).isInstanceOf(NullPointerException.class);
    }
}

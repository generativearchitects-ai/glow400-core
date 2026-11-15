package ai.generativearchitects.glow400.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmitResult;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.codec.Properties;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/** The {@code mock} emitter: what it writes, that it is the same every time, and that the reader gives back what was written. */
class MockEmitterTest {

    private static final ApiRepositoryModel MODEL = Fixtures.model("mocked");

    private static EmittedFile emitted(ApiRepositoryModel model) {
        EmitResult result = new MockEmitter().emit(model);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.files()).hasSize(1);
        return result.files().get(0);
    }

    private static MockReadResult read(EmittedFile file) {
        return MockReader.read(new ByteArrayInputStream(file.content()), file.path());
    }

    @Test
    void theEmitterIsCalledMockAndIsFoundLikeTheOthers() {
        assertThat(new MockEmitter().name()).isEqualTo("mock");
        assertThat(ServiceLoader.load(Emitter.class).stream().map(p -> p.get().name())).contains("mock", "descriptor");
        assertThat(MockEmitter.FOLDER).isEqualTo("mock/");
    }

    @Test
    void oneFileForTheApiWithTheCasesInFileOrderMatchesTheGoldenFile() {
        EmittedFile file = emitted(MODEL);
        assertThat(file.path()).isEqualTo("mock/orders.json");
        Golden.check("orders.json", new String(file.content(), StandardCharsets.UTF_8));
    }

    @Test
    void theSameModelGivesTheSameBytes() {
        assertThat(emitted(MODEL).content()).isEqualTo(emitted(Fixtures.model("mocked")).content());
    }

    @Test
    void anApiWithoutAMockFileGetsNoFileAndARepositoryWithoutOneGetsNone() {
        for (String fixture : List.of("minimal", "kitchen-sink", "structs", "operations-list")) {
            EmitResult result = new MockEmitter().emit(Fixtures.model(fixture));
            assertThat(result.files()).as(fixture).isEmpty();
            assertThat(result.diagnostics()).isEmpty();
        }
    }

    @Test
    void whatIsWrittenIsReadBackIntoEqualDefinitions() {
        MockReadResult result = read(emitted(MODEL));
        assertThat(result.diagnostics()).isEmpty();
        MockFile file = result.file().orElseThrow();
        assertThat(file.api()).isEqualTo("orders");
        assertThat(file.operations()).isEqualTo(MODEL.apis().get(0).mocks());
        assertThat(file.operations().get(0).cases().get(0).returns().get("P0LINES")).isEqualTo(MODEL.apis().get(0).mocks().get(0).cases().get(0).returns().get("P0LINES"));
        assertThat(file.operations().get(0).source()).hasToString("mock/orders.json:1:1");
    }

    @Test
    void aWholeNumberOfAnySizeAndEveryKindOfValueGoThroughTheFile() {
        SourceLocationHolder at = new SourceLocationHolder();
        BigInteger big = new BigInteger("123456789012345678901234567890");
        Map<String, MockValue> values = new java.util.LinkedHashMap<>();
        values.put("A", new MockValue.Whole(big, at.at));
        values.put("B", new MockValue.Whole(BigInteger.valueOf(Long.MAX_VALUE), at.at));
        values.put("C", new MockValue.Whole(BigInteger.valueOf(Long.MIN_VALUE), at.at));
        values.put("D", new MockValue.Bool(false, at.at));
        values.put("E", new MockValue.Text("a \"quoted\" é text\n", at.at));
        values.put("F", new MockValue.Array(List.of(new MockValue.Struct(Map.of("G", new MockValue.Whole(BigInteger.ZERO, at.at)), at.at)), at.at));
        MockDefinition definition = new MockDefinition("op", List.of(new MockCase(Optional.of("n"), Map.of(), values, Optional.of(60000), Optional.empty(), at.at),
                new MockCase(Optional.empty(), values, Map.of(), Optional.empty(), Optional.of(MockFailure.TIMEOUT), at.at)), at.at);
        String json = ai.generativearchitects.glow400.emit.descriptor.Json.write(MockJson.tree("x", List.of(definition)));
        MockReadResult result = MockReader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "mock/x.json");
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.file().orElseThrow().operations()).containsExactly(definition);
    }

    private static final class SourceLocationHolder {
        final ai.generativearchitects.glow400.pcml.model.SourceLocation at = new ai.generativearchitects.glow400.pcml.model.SourceLocation("a.yaml", 1, 1);
    }

    @Test
    void aValueThatNoFieldTakesIsNotWritten() {
        MockValue real = new MockValue.Real("1.5", new ai.generativearchitects.glow400.pcml.model.SourceLocation("a.yaml", 1, 1));
        MockDefinition definition = new MockDefinition("op", List.of(new MockCase(Optional.empty(), Map.of(), Map.of("A", real), Optional.empty(), Optional.empty(), real.source())), real.source());
        assertThatThrownBy(() -> MockJson.tree("x", List.of(definition))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A number with a fraction is not a valid mock value, so it is not written");
    }

    @Test
    void randomDefinitionsOfEveryOperationGoThroughTheFileAndComeBackEqual() {
        List<ApiOperation> operations = new ArrayList<>();
        for (String name : Fixtures.VALID) {
            operations.addAll(Fixtures.model(name).operations());
        }
        int[] checked = {0};
        Properties.check("mock file round trip", g -> {
            ApiOperation operation = g.pick(operations);
            MockDefinition definition = MockGen.definition(g, operation, Map.of());
            String json = ai.generativearchitects.glow400.emit.descriptor.Json.write(MockJson.tree("api", List.of(definition)));
            assertThat(ai.generativearchitects.glow400.emit.descriptor.Json.write(MockJson.tree("api", List.of(definition)))).isEqualTo(json);
            MockReadResult result = MockReader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "mock/api.json");
            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.file().orElseThrow().operations()).containsExactly(definition);
            checked[0]++;
        });
        assertThat(checked[0]).isEqualTo(Properties.DEFAULT_CASES);
    }
}

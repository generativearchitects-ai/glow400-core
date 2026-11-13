package ai.generativearchitects.glow400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Model, descriptor, model again: the same API model except for source positions and the resolved call strategy. */
class RoundTripTest {

    private static byte[] descriptor(ApiRepositoryModel model) {
        EmittedFile file = new DescriptorEmitter().emit(model).files().get(0);
        return file.content();
    }

    private static ApiRepositoryModel read(byte[] bytes) {
        DescriptorResult result = DescriptorReader.read(new ByteArrayInputStream(bytes));
        assertThat(result.diagnostics()).isEmpty();
        return result.model().orElseThrow();
    }

    /** What the descriptor holds: the call strategy resolved, the rest as loaded. */
    private static ApiOperation resolved(ApiOperation o) {
        return new ApiOperation(o.id(), o.version(), o.summary(), o.description(), o.tags(), o.program(), o.pcmlPath(), o.overlayPath(),
                o.route(), o.method(), DescriptorWriter.resolvedCall(o), o.bindings(), o.errorMapping(), o.cache(), o.source());
    }

    @Test
    void everyValidFixtureComesBackAsTheSameModel() {
        for (String repository : Repositories.VALID) {
            ApiRepositoryModel original = Repositories.model(repository);
            ApiRepositoryModel back = read(descriptor(original));
            assertThat(back.apis()).as(repository).hasSameSizeAs(original.apis());
            for (int i = 0; i < original.apis().size(); i++) {
                var expected = original.apis().get(i);
                var actual = back.apis().get(i);
                assertThat(actual.name()).isEqualTo(expected.name());
                assertThat(actual.title()).isEqualTo(expected.title());
                assertThat(actual.version()).isEqualTo(expected.version());
                assertThat(actual.overlayPath()).isEqualTo(expected.overlayPath());
                assertThat(actual.operations()).as(repository).usingRecursiveComparison()
                        .ignoringFieldsOfTypes(SourceLocation.class)
                        .isEqualTo(expected.operations().stream().map(RoundTripTest::resolved).toList());
            }
            assertThat(back.defaults().inputOutput()).isEqualTo(original.defaults().inputOutput());
            assertThat(back.defaults().largeArrayThreshold()).isEqualTo(original.defaults().largeArrayThreshold());
        }
    }

    @Test
    void theDescriptorOfTheModelReadBackIsByteIdentical() {
        for (String repository : Repositories.VALID) {
            byte[] first = descriptor(Repositories.model(repository));
            assertThat(descriptor(read(first))).as(repository).isEqualTo(first);
        }
    }

    @Test
    void everyElementReadFromADescriptorHasTheSameLocationNamingTheDescriptor() {
        ApiRepositoryModel back = read(descriptor(Repositories.model("kitchen-sink")));
        SourceLocation here = new SourceLocation("descriptor.json", 0, 0);
        for (ApiOperation operation : back.operations()) {
            assertThat(operation.source()).isEqualTo(here);
            assertThat(operation.program().source()).isEqualTo(here);
            operation.bindings().forEach(b -> assertThat(b.source()).isEqualTo(here));
            operation.program().parameters().forEach(p -> assertThat(p.source()).isEqualTo(here));
        }
    }

    @Test
    void theCallStrategyIsResolvedInTheDescriptor() {
        ApiRepositoryModel structs = read(descriptor(Repositories.model("structs")));
        assertThat(structs.operations()).extracting(ApiOperation::callStrategy).containsOnly(CallStrategy.RAW);
        ApiRepositoryModel minimal = read(descriptor(Repositories.model("minimal")));
        assertThat(minimal.operations()).extracting(ApiOperation::callStrategy).containsOnly(CallStrategy.PCML);
        for (String repository : Repositories.VALID) {
            assertThat(read(descriptor(Repositories.model(repository))).operations()).extracting(ApiOperation::callStrategy)
                    .doesNotContain(CallStrategy.AUTO);
        }
    }

    @Test
    void aModelReadFromADescriptorValidatesAndEveryOperationHasACodec() {
        for (String repository : Repositories.VALID) {
            ApiRepositoryModel back = read(descriptor(Repositories.model(repository)));
            List<Diagnostic> diagnostics = Validator.validate(back);
            assertThat(diagnostics).as(repository).noneMatch(d -> d.severity() == Severity.ERROR);
            for (ApiOperation operation : back.operations()) {
                OperationCodec.of(operation);
            }
        }
    }

    @Test
    void anExplicitCallStrategyIsKept() {
        ApiRepositoryModel model = Repositories.model("minimal");
        ApiOperation raw = model.operations().get(0);
        ApiOperation forced = new ApiOperation(raw.id(), raw.version(), raw.summary(), raw.description(), raw.tags(), raw.program(),
                raw.pcmlPath(), raw.overlayPath(), raw.route(), raw.method(), CallStrategy.RAW, raw.bindings(), raw.errorMapping(),
                raw.cache(), raw.source());
        ApiRepositoryModel changed = new ApiRepositoryModel(model.defaults(), List.of(forced));
        assertThat(read(descriptor(changed)).operations().get(0).callStrategy()).isEqualTo(CallStrategy.RAW);
    }
}

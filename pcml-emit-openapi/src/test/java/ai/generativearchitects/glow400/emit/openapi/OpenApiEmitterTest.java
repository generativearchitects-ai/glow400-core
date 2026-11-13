package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmitResult;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The OpenAPI emitter: golden files, determinism, discovery. */
class OpenApiEmitterTest {

    @ParameterizedTest
    @ValueSource(strings = {"action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink", "inputoutput",
            "minimal", "operations-list", "structs"})
    void theDocumentsOfEveryValidFixtureAreTheCommittedOnes(String repository) {
        EmitResult result = new OpenApiEmitter().emit(Repositories.model(repository));
        assertThat(result.diagnostics()).isEmpty();
        ApiRepositoryModel model = Repositories.model(repository);
        assertThat(result.files()).extracting(EmittedFile::path)
                .containsExactlyElementsOf(model.apis().stream().map(api -> "openapi/" + api.name() + ".yaml").toList());
        for (EmittedFile file : result.files()) {
            GoldenFiles.check(repository + "/" + file.path(), file.content());
        }
    }

    @Test
    void everyValidFixtureIsCovered() {
        assertThat(Repositories.VALID).containsExactlyInAnyOrder("action-parameter", "body-struct", "dclcase", "defaults-both", "injected",
                "kitchen-sink", "inputoutput", "minimal", "operations-list", "structs");
    }

    @Test
    void emittingTwiceGivesTheSameBytes() {
        for (String repository : Repositories.VALID) {
            ApiRepositoryModel model = Repositories.model(repository);
            assertThat(new OpenApiEmitter().emit(model)).isEqualTo(new OpenApiEmitter().emit(model));
        }
    }

    @Test
    void aModelLoadedFromAnotherPathGivesTheSameBytes(@TempDir Path dir) throws Exception {
        Path source = Fixtures.path("kitchen-sink");
        try (var files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path target = dir.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        ApiRepositoryModel copy = ai.generativearchitects.glow400.pcml.overlay.OverlayLoader.loadRepository(dir).model().orElseThrow();
        assertThat(new OpenApiEmitter().emit(copy)).isEqualTo(new OpenApiEmitter().emit(Repositories.model("kitchen-sink")));
    }

    @Test
    void theDocumentsHaveNoAbsolutePathTimestampHostOrUser() {
        String home = System.getProperty("user.home");
        for (String repository : Repositories.VALID) {
            for (EmittedFile file : new OpenApiEmitter().emit(Repositories.model(repository)).files()) {
                String text = new String(file.content(), StandardCharsets.UTF_8);
                assertThat(text).doesNotContain(home).doesNotContain("/tmp/").doesNotContain("\r").endsWith("\n");
                assertThat(text).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
            }
        }
    }

    @Test
    void theEmitterIsFoundByNameWithServiceLoader() {
        assertThat(ServiceLoader.load(Emitter.class)).extracting(Emitter::name).contains("openapi");
        assertThat(new OpenApiEmitter().name()).isEqualTo("openapi");
    }

    @Test
    void bothEmittersAreFoundTogetherByName() {
        assertThat(ServiceLoader.load(Emitter.class)).extracting(Emitter::name).contains("descriptor", "openapi");
    }
}

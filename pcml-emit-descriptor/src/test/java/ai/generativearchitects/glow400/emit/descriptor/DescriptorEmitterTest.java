package ai.generativearchitects.glow400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmitResult;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The descriptor emitter: golden files, determinism, discovery. */
class DescriptorEmitterTest {

    private static EmitResult emit(String repository) {
        return new DescriptorEmitter().emit(Repositories.model(repository));
    }

    @ParameterizedTest
    @ValueSource(strings = {"action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink", "inputoutput",
            "minimal", "operations-list", "structs"})
    void theDescriptorOfEveryValidFixtureIsTheCommittedOne(String repository) {
        EmitResult result = emit(repository);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).path()).isEqualTo("descriptor.json");
        GoldenFiles.check(repository + "/descriptor.json", result.files().get(0).content());
    }

    @Test
    void everyValidFixtureIsCovered() {
        assertThat(Repositories.VALID).containsExactlyInAnyOrder("action-parameter", "body-struct", "dclcase", "defaults-both",
                "injected", "kitchen-sink", "inputoutput", "minimal", "operations-list", "structs");
    }

    @Test
    void emittingTwiceGivesTheSameBytes() {
        for (String repository : Repositories.VALID) {
            ApiRepositoryModel model = Repositories.model(repository);
            assertThat(new DescriptorEmitter().emit(model).files().get(0).content())
                    .isEqualTo(new DescriptorEmitter().emit(model).files().get(0).content());
        }
    }

    @Test
    void aModelLoadedFromAnotherPathGivesTheSameBytes(@TempDir Path dir) throws Exception {
        Path source = Fixtures.path("structs");
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
        assertThat(new DescriptorEmitter().emit(copy).files().get(0).content())
                .isEqualTo(new DescriptorEmitter().emit(Repositories.model("structs")).files().get(0).content());
    }

    @Test
    void theDescriptorHasNoAbsolutePathTimestampHostOrUser() {
        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        for (String repository : Repositories.VALID) {
            String text = new String(emit(repository).files().get(0).content(), StandardCharsets.UTF_8);
            assertThat(text).doesNotContain(home).doesNotContain("/tmp/").doesNotContain("\r").endsWith("}\n");
            assertThat(text).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}T");
            if (user != null && user.length() > 3) {
                assertThat(text).doesNotContain(user);
            }
            assertThat(text).contains("\"generator\": {\n    \"name\": \"glow400-core\",\n    \"version\": \"" + GoldenFiles.projectVersion() + "\"\n  }");
        }
    }

    @Test
    void theEmitterIsFoundByNameWithServiceLoader() {
        assertThat(ServiceLoader.load(Emitter.class)).extracting(Emitter::name).contains("descriptor");
        assertThat(new DescriptorEmitter().name()).isEqualTo("descriptor");
    }
}

package ai.generativearchitects.glow400.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.emit.descriptor.DescriptorEmitter;
import ai.generativearchitects.glow400.emit.openapi.OpenApiEmitter;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code generate}: what is written, and every way it refuses to write. */
class GenerateTaskTest {

    private static List<EmittedFile> direct(Path repo) {
        ApiRepositoryModel model = OverlayLoader.loadRepository(repo).model().orElseThrow();
        List<EmittedFile> files = new ArrayList<>(new DescriptorEmitter().emit(model).files());
        files.addAll(new OpenApiEmitter().emit(model).files());
        return files;
    }

    private static TaskResult generate(Path base, Path repo, Path out) throws IOException {
        return new Tasks(base).generate(repo, out);
    }

    @Test
    void theFilesWrittenAreWhatTheEmittersProduceDirectlyAndAreListed(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("kitchen-sink", dir.resolve("repo"));
        TaskResult result = generate(dir, repo, dir.resolve("build/api"));
        assertThat(result.hasError()).isFalse();
        List<EmittedFile> expected = direct(repo);
        for (EmittedFile file : expected) {
            assertThat(Support.bytes(dir.resolve("build/api").resolve(file.path()))).as(file.path()).isEqualTo(file.content());
        }
        assertThat(result.written()).containsExactlyInAnyOrder(dir.resolve("build/api/descriptor.json"), dir.resolve("build/api/openapi/catalog.yaml"));
        assertThat(result.written()).allMatch(Path::isAbsolute);
    }

    @Test
    void theMockFileIsWrittenWhenTheRepositoryHasMocks(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("mocked", dir.resolve("repo"));
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(result.written()).contains(dir.resolve("out/mock/orders.json"));
        Path plain = Support.repo("minimal", dir.resolve("plain"));
        assertThat(generate(dir, plain, dir.resolve("plainout")).written()).noneMatch(p -> p.toString().contains("mock"));
    }

    @Test
    void existingFilesAreOverwrittenAndNothingIsDeleted(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/descriptor.json"), "old");
        Support.write(dir.resolve("out/keep.txt"), "keep");
        assertThat(generate(dir, repo, dir.resolve("out")).hasError()).isFalse();
        EmittedFile descriptor = direct(repo).stream().filter(f -> f.path().equals("descriptor.json")).findFirst().orElseThrow();
        assertThat(Support.bytes(dir.resolve("out/descriptor.json"))).isEqualTo(descriptor.content());
        assertThat(Support.read(dir.resolve("out/keep.txt"))).isEqualTo("keep");
    }

    @Test
    void theEmittersNamedInGatewayYamlAreTheOnesThatRunEachOnce(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("gateway.yaml"), "emitters: [openapi, openapi]\n");
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(result.hasError()).isFalse();
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "openapi", "openapi/ordstatus.yaml");
    }

    @Test
    void anOutputFolderInsideTheRepositoryButNotInApisIsAllowed(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        assertThat(generate(dir, repo, repo.resolve("apis-out")).hasError()).isFalse();
    }

    @Test
    void anInvalidRepositoryWritesNothing(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(result.hasError()).isTrue();
        assertThat(result.written()).isEmpty();
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void aNamedEmitterThatIsNotAvailableIsCli001AtItsPositionAndNothingIsWritten(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("gateway.yaml"), "emitters:\n  - descriptor\n  - nothing\n  - java\n");
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).contains("gateway.yaml:3:5: CLI001: The emitter 'nothing' named in gateway.yaml is not available",
                "gateway.yaml:4:5: CLI001: The emitter 'java' named in gateway.yaml is not available");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void anEmitterThatReportsAnErrorStopsTheTaskBeforeAnythingIsWritten(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("gateway.yaml"), "emitters: [descriptor, broken]\n");
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).contains("apis/X/api.yaml:3:1: EMT001: The broken emitter always fails");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void twoEmittersWritingTheSamePathIsCli002(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("gateway.yaml"), "emitters: [descriptor, clash]\n");
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).contains("out/descriptor.json:1:1: CLI002: Two emitted files have the path descriptor.json");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void theOutputFolderCannotBeTheRepositoryRootOrInsideApis(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        assertThat(Support.lines(generate(dir, repo, repo).diagnostics())).contains("repo:1:1: CLI002: The output folder repo is the repository root");
        assertThat(Support.lines(generate(dir, repo, repo.resolve("./x/..")).diagnostics())).anyMatch(l -> l.contains("is the repository root"));
        assertThat(Support.lines(generate(dir, repo, repo.resolve("apis")).diagnostics())).contains("repo/apis:1:1: CLI002: The output folder repo/apis is inside apis/");
        assertThat(Support.lines(generate(dir, repo, repo.resolve("apis/ORDSTATUS/out")).diagnostics())).anyMatch(l -> l.contains("is inside apis/"));
        assertThat(Support.lines(generate(dir, repo, repo.resolve("apis/new")).diagnostics())).anyMatch(l -> l.contains("is inside apis/"));
        Files.createSymbolicLink(dir.resolve("alias"), repo);
        assertThat(Support.lines(generate(dir, repo, dir.resolve("alias")).diagnostics())).anyMatch(l -> l.contains("is the repository root"));
        assertThat(Support.lines(generate(dir, repo, dir.resolve("alias/apis")).diagnostics())).anyMatch(l -> l.contains("is inside apis/"));
        assertThat(Support.names(repo)).doesNotContain("descriptor.json", "openapi");
    }

    @Test
    void aFolderInsideTheOutputThatLeadsOutsideIsCli002AndNothingIsWritten(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("gateway.yaml"), "emitters: [descriptor, linked]\n");
        Files.createDirectories(dir.resolve("out"));
        Files.createDirectories(dir.resolve("elsewhere"));
        Files.createSymbolicLink(dir.resolve("out/linked"), dir.resolve("elsewhere"));
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).contains("out/linked/inside.txt:1:1: CLI002: The path linked/inside.txt leads out of the output folder");
        assertThat(Support.names(dir.resolve("elsewhere"))).containsExactly("");
        assertThat(Files.exists(dir.resolve("out/descriptor.json"))).isFalse();
        assertThat(result.written()).isEmpty();
        Files.delete(dir.resolve("out/linked"));
        Files.createDirectories(dir.resolve("out/real"));
        Files.createSymbolicLink(dir.resolve("out/linked"), dir.resolve("out/real"));
        assertThat(generate(dir, repo, dir.resolve("out")).hasError()).isFalse();
        assertThat(Files.exists(dir.resolve("out/real/inside.txt"))).isTrue();
        Files.delete(dir.resolve("out/descriptor.json"));
        Files.createSymbolicLink(dir.resolve("out/descriptor.json"), dir.resolve("elsewhere/target.json"));
        assertThat(Support.lines(generate(dir, repo, dir.resolve("out")).diagnostics())).anyMatch(l -> l.contains("The path descriptor.json leads out of the output folder"));
        assertThat(Files.exists(dir.resolve("elsewhere/target.json"))).isFalse();
    }

    @Test
    void anOutputFolderThatIsALinkToNowhereIsCli003(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Files.createSymbolicLink(dir.resolve("out"), dir.resolve("nowhere"));
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).anyMatch(l -> l.matches("out:1:1: CLI003: The output folder out cannot be written: [a-z ]+"));
        assertThat(Files.exists(dir.resolve("nowhere"))).isFalse();
    }

    @Test
    void anOutputFolderThatIsAFileIsCli003(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out"), "a file");
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).contains("out:1:1: CLI003: The output folder out cannot be written: not a folder");
        assertThat(Support.read(dir.resolve("out"))).isEqualTo("a file");
    }

    @Test
    void aFileThatCannotBeWrittenIsCli003WithThePathAndTheReasonNeverTheContentAndWhatWasWrittenIsListed(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/openapi"), "a file where a folder must be");
        TaskResult result = generate(dir, repo, dir.resolve("out"));
        assertThat(Support.lines(result.diagnostics())).anyMatch(l -> l.matches("out/openapi/ordstatus.yaml:1:1: CLI003: The file out/openapi/ordstatus.yaml could not be written: [a-z ]+"));
        assertThat(result.diagnostics().toString()).doesNotContain("openapi: 3.1.0").doesNotContain(dir.toString());
        assertThat(result.written()).containsExactly(dir.resolve("out/descriptor.json"));
    }
}

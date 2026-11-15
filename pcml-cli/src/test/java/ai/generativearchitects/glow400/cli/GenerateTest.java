package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.cli.Support.Result;
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
class GenerateTest {

    private static List<EmittedFile> direct(Path repo) {
        ApiRepositoryModel model = OverlayLoader.loadRepository(repo).model().orElseThrow();
        List<EmittedFile> files = new ArrayList<>(new DescriptorEmitter().emit(model).files());
        files.addAll(new OpenApiEmitter().emit(model).files());
        return files;
    }

    // ---- success

    @Test
    void theFilesWrittenAreWhatTheEmittersProduceDirectly(@TempDir Path dir) {
        Path repo = Support.repo("kitchen-sink", dir.resolve("repo"));
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "build/api");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).endsWith("0 notes\n").doesNotContain("wrote");
        List<EmittedFile> expected = direct(repo);
        for (EmittedFile file : expected) {
            assertThat(Support.bytes(dir.resolve("build/api").resolve(file.path()))).as(file.path()).isEqualTo(file.content());
        }
        assertThat(Support.names(dir.resolve("build/api"))).hasSize(expected.size() + 1 + 1).contains("descriptor.json", "openapi", "openapi/catalog.yaml");
        assertThat(result.err().lines().toList()).containsExactlyInAnyOrder("wrote build/api/descriptor.json", "wrote build/api/openapi/catalog.yaml");
    }

    @Test
    void existingFilesAreOverwrittenAndNothingIsDeleted(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/descriptor.json"), "old");
        Support.write(dir.resolve("out/keep.txt"), "keep");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "out").exit()).isZero();
        EmittedFile descriptor = direct(repo).stream().filter(f -> f.path().equals("descriptor.json")).findFirst().orElseThrow();
        assertThat(Support.bytes(dir.resolve("out/descriptor.json"))).isEqualTo(descriptor.content());
        assertThat(Support.read(dir.resolve("out/keep.txt"))).isEqualTo("keep");
    }

    @Test
    void theEmittersNamedInGatewayYamlAreTheOnesThatRunEachOnce(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [openapi, openapi]\n");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "out").exit()).isZero();
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "openapi", "openapi/ordstatus.yaml");
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: []\n");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "empty").exit()).isZero();
        assertThat(Files.exists(dir.resolve("empty"))).isFalse();
    }

    @Test
    void anOutputFolderInsideTheRepositoryButNotInApisIsAllowed(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "repo/build").exit()).isZero();
        assertThat(Files.exists(repo.resolve("build/descriptor.json"))).isTrue();
    }

    @Test
    void withJsonTheStandardOutputIsTheReportAndTheFilesWrittenAreOnTheErrorStream(@TempDir Path dir) throws IOException {
        Support.repo("minimal", dir.resolve("repo"));
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out", "--format", "json");
        assertThat(result.exit()).isZero();
        assertThat(ai.generativearchitects.glow400.emit.descriptor.Json.read(new java.io.ByteArrayInputStream(result.out().getBytes()))).isInstanceOf(java.util.Map.class);
        assertThat(result.err()).contains("wrote out/descriptor.json");
    }

    // ---- nothing is written

    @Test
    void anInvalidRepositoryWritesNothingAndReportsWhatWasFound(@TempDir Path dir) {
        Support.repo("invalid/ovl012", dir.resolve("repo"));
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error OVL012");
        assertThat(result.err()).isEmpty();
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void aNamedEmitterThatIsNotAvailableIsCli001AtItsPositionAndNothingIsWritten(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters:\n  - descriptor\n  - mock\n  - java\n");
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("gateway.yaml:3:5: error CLI001: The emitter 'mock' named in gateway.yaml is not available")
                .contains("gateway.yaml:4:5: error CLI001: The emitter 'java' named in gateway.yaml is not available").endsWith("2 errors, 1 warning, 0 notes\n");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "out", "--format", "sarif").out()).contains("\"ruleId\": \"CLI001\"");
    }

    @Test
    void anEmitterThatReportsAnErrorStopsTheCommandBeforeAnythingIsWritten(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [descriptor, broken]\n");
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("apis/X/api.yaml:3:1: error EMT001: The broken emitter always fails");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void twoEmittersWritingTheSamePathIsCli002(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [descriptor, clash]\n");
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error CLI002: Two emitted files have the path descriptor.json");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void theOutputFolderCannotBeTheRepositoryRootOrInsideApis(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Result root = Support.run(dir, "generate", "--repo", "repo", "--out", "repo");
        assertThat(root.exit()).isEqualTo(1);
        assertThat(root.out()).contains("error CLI002: The output folder repo is the repository root");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "repo/./x/..").out()).contains("is the repository root");
        Result apis = Support.run(dir, "generate", "--repo", "repo", "--out", "repo/apis");
        assertThat(apis.out()).contains("error CLI002: The output folder repo/apis is inside apis/");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "repo/apis/ORDSTATUS/out").out()).contains("is inside apis/");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "repo/apis/new").out()).contains("is inside apis/");
        // a link to the repository root is the repository root
        Files.createSymbolicLink(dir.resolve("alias"), repo);
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "alias").out()).contains("is the repository root");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "alias/apis").out()).contains("is inside apis/");
        assertThat(Support.names(repo)).doesNotContain("descriptor.json", "openapi");
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "repo/apis-out").exit()).isZero();
    }

    @Test
    void aFolderInsideTheOutputThatLeadsOutsideIsCli002AndNothingIsWritten(@TempDir Path dir) throws IOException {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [descriptor, linked]\n");
        Files.createDirectories(dir.resolve("out"));
        Files.createDirectories(dir.resolve("elsewhere"));
        Files.createSymbolicLink(dir.resolve("out/linked"), dir.resolve("elsewhere"));
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error CLI002: The path linked/inside.txt leads out of the output folder");
        assertThat(Support.names(dir.resolve("elsewhere"))).containsExactly("");
        assertThat(Files.exists(dir.resolve("out/descriptor.json"))).isFalse();
        // a link that stays inside the output folder is fine
        Files.delete(dir.resolve("out/linked"));
        Files.createDirectories(dir.resolve("out/real"));
        Files.createSymbolicLink(dir.resolve("out/linked"), dir.resolve("out/real"));
        assertThat(Support.run(dir, "generate", "--repo", "repo", "--out", "out").exit()).isZero();
        assertThat(Files.exists(dir.resolve("out/real/inside.txt"))).isTrue();
        // a file that is itself a link to somewhere else
        Files.delete(dir.resolve("out/descriptor.json"));
        Files.createSymbolicLink(dir.resolve("out/descriptor.json"), dir.resolve("elsewhere/target.json"));
        Result viaFile = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(viaFile.out()).contains("The path descriptor.json leads out of the output folder");
        assertThat(Files.exists(dir.resolve("elsewhere/target.json"))).isFalse();
    }

    @Test
    void anOutputFolderThatIsAFileIsCli003(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out"), "a file");
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error CLI003: The output folder out cannot be written: not a folder");
        assertThat(Support.read(dir.resolve("out"))).isEqualTo("a file");
    }

    @Test
    void aFileThatCannotBeWrittenIsCli003WithThePathAndTheReasonNeverTheContent(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/openapi"), "a file where a folder must be");
        Result result = Support.run(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsPattern("error CLI003: The file out/openapi/ordstatus.yaml could not be written: [a-z ]+");
        assertThat(result.out()).doesNotContain("openapi: 3.1.0").doesNotContain(dir.toString());
    }

    @Test
    void aRelativeOutputFolderIsRelativeToTheWorkingDirectory(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        assertThat(Support.run(repo, "generate", "--out", "../elsewhere/out").exit()).isZero();
        assertThat(Files.exists(dir.resolve("elsewhere/out/descriptor.json"))).isTrue();
    }
}

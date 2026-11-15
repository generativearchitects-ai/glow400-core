package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.bundle.BundleInfo;
import ai.generativearchitects.glow400.bundle.BundleWriter;
import ai.generativearchitects.glow400.cli.Support.Result;
import ai.generativearchitects.glow400.emit.descriptor.DescriptorEmitter;
import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.emit.descriptor.Tool;
import ai.generativearchitects.glow400.emit.openapi.OpenApiEmitter;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code bundle}: what is written, how, and every way it refuses to write. */
class BundleTest {

    private static final String[] ARGS = {"bundle", "--repo", "repo", "--name", "orders", "--version", "2026.11.14-1", "--out", "out/orders.rpgapi"};

    private static byte[] direct(Path repo, BundleInfo info) {
        ApiRepositoryModel model = OverlayLoader.loadRepository(repo).model().orElseThrow();
        List<EmittedFile> files = new ArrayList<>(new DescriptorEmitter().emit(model).files());
        files.addAll(new OpenApiEmitter().emit(model).files());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BundleWriter.write(repo, model, files, info, out);
        return out.toByteArray();
    }

    private static Map<String, byte[]> entries(byte[] bundle) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                entries.put(e.getName(), zin.readAllBytes());
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(byte[] bytes) throws IOException {
        return (Map<String, Object>) Json.read(new ByteArrayInputStream(bytes));
    }

    // ---- success

    @Test
    void theBundleIsWhatTheEmittersAndTheWriterProduceDirectly(@TempDir Path dir) {
        Path repo = Support.repo("kitchen-sink", dir.resolve("repo"));
        Result result = Support.run(dir, ARGS);
        assertThat(result.exit()).isZero();
        assertThat(result.out()).endsWith("0 notes\n");
        assertThat(result.err()).isEqualTo("wrote out/orders.rpgapi\n");
        assertThat(Support.bytes(dir.resolve("out/orders.rpgapi"))).isEqualTo(direct(repo, new BundleInfo("orders", "2026.11.14-1", Optional.empty(), Optional.empty())));
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "orders.rpgapi");
    }

    @Test
    void theBundleIsTheGoldenBundleOfKickoff06ForTheSameFixtureExceptForTheVersionOfTheTool(@TempDir Path dir) throws IOException {
        Support.repo("minimal", dir.resolve("repo"));
        assertThat(Support.run(dir, ARGS).exit()).isZero();
        Map<String, byte[]> made = entries(Support.bytes(dir.resolve("out/orders.rpgapi")));
        Map<String, byte[]> golden;
        try (InputStream in = BundleTest.class.getResourceAsStream("/golden/minimal.rpgapi")) {
            assertThat(in).isNotNull();
            golden = entries(in.readAllBytes());
        }
        assertThat(made.keySet()).containsExactlyElementsOf(golden.keySet());
        for (String path : made.keySet()) {
            if (path.equals("manifest.json")) {
                continue;
            }
            byte[] expected = golden.get(path);
            if (path.equals("descriptor.json")) {
                expected = new String(expected, StandardCharsets.UTF_8).replace("0.0.0-golden", Tool.version()).getBytes(StandardCharsets.UTF_8);
            }
            assertThat(made.get(path)).as(path).isEqualTo(expected);
        }
        // the manifest names the tool by its version and so holds the checksum of a descriptor that names it too
        Map<String, Object> madeManifest = json(made.get("manifest.json"));
        Map<String, Object> goldenManifest = json(golden.get("manifest.json"));
        for (Map<String, Object> manifest : List.of(madeManifest, goldenManifest)) {
            manifest.remove("generator");
            manifest.remove("contentDigest");
            @SuppressWarnings("unchecked") List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
            files.removeIf(f -> f.get("path").equals("descriptor.json"));
        }
        assertThat(madeManifest).isEqualTo(goldenManifest);
    }

    @Test
    void theSourceOptionsAreWrittenToTheManifest(@TempDir Path dir) throws IOException {
        Support.repo("minimal", dir.resolve("repo"));
        Result result = Support.run(dir, "bundle", "--repo", "repo", "--name", "o", "--version", "1", "--out", "b.rpgapi", "--source-repository", "git@example:o/r",
                "--source-revision=abc123");
        assertThat(result.exit()).isZero();
        Map<String, Object> manifest = json(entries(Support.bytes(dir.resolve("b.rpgapi"))).get("manifest.json"));
        assertThat(manifest.get("source")).isEqualTo(Map.of("repository", "git@example:o/r", "revision", "abc123"));
        assertThat(manifest.get("name")).isEqualTo("o");
        assertThat(manifest.get("version")).isEqualTo("1");
    }

    @Test
    void theEmittersNamedInGatewayYamlDoNotMatterAndAnExistingBundleIsReplaced(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("repo/gateway.yaml"), "emitters: [mock]\n");
        Support.write(dir.resolve("out/orders.rpgapi"), "an old bundle");
        Result result = Support.run(dir, ARGS);
        assertThat(result.exit()).isZero();
        assertThat(Support.bytes(dir.resolve("out/orders.rpgapi"))).startsWith((byte) 'P', (byte) 'K');
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "orders.rpgapi");
    }

    @Test
    void withSarifTheStandardOutputIsOneDocumentAndTheFileWrittenIsOnTheErrorStream(@TempDir Path dir) throws IOException {
        Support.repo("minimal", dir.resolve("repo"));
        String[] args = ARGS.clone();
        Result result = Support.run(dir, concat(args, "--format", "sarif"));
        assertThat(result.exit()).isZero();
        assertThat(Json.read(new ByteArrayInputStream(result.out().getBytes(StandardCharsets.UTF_8)))).isInstanceOf(Map.class);
        assertThat(result.err()).isEqualTo("wrote out/orders.rpgapi\n");
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- nothing is written

    @Test
    void anInvalidRepositoryWritesNoBundleAndNoFolder(@TempDir Path dir) {
        Support.repo("invalid/ovl012", dir.resolve("repo"));
        Result result = Support.run(dir, ARGS);
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error OVL012");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void anInvalidNameOrVersionIsBdl006AndNothingIsWritten(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Result name = Support.run(dir, "bundle", "--repo", "repo", "--name", "Orders", "--version", "1", "--out", "out/b.rpgapi");
        assertThat(name.exit()).isEqualTo(1);
        assertThat(name.out()).contains("error BDL006: The bundle name must match [a-z0-9][a-z0-9._-]*");
        Result version = Support.run(dir, "bundle", "--repo", "repo", "--name", "orders", "--version", "one two", "--out", "out/b.rpgapi");
        assertThat(version.exit()).isEqualTo(1);
        assertThat(version.out()).contains("error BDL006: The bundle version must be 1 to 64 characters without white space");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void aBundleFileThatCannotBeWrittenIsCli003AndLeavesNothingBehind(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/orders.rpgapi/inside.txt"), "the target is a folder");
        Result result = Support.run(dir, ARGS);
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsPattern("error CLI003: The file out/orders.rpgapi could not be written: [a-z ]+");
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "orders.rpgapi", "orders.rpgapi/inside.txt");
        Support.write(dir.resolve("blocker"), "a file");
        Result parent = Support.run(dir, "bundle", "--repo", "repo", "--name", "o", "--version", "1", "--out", "blocker/b.rpgapi");
        assertThat(parent.exit()).isEqualTo(1);
        assertThat(parent.out()).contains("error CLI003: The file blocker/b.rpgapi could not be written: ");
        assertThat(Support.read(dir.resolve("blocker"))).isEqualTo("a file");
    }

    @Test
    void aSourceFileThatLeavesTheRepositoryStopsTheCommandBeforeAnythingIsWritten(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        // the loader already refuses a link out of the repository, so the bundle writer never sees it
        Path pcml = repo.resolve("apis/ORDSTATUS/ORDSTATUS.pcml");
        Files.move(pcml, dir.resolve("outside.pcml"));
        Files.createSymbolicLink(pcml, dir.resolve("outside.pcml"));
        Result result = Support.run(dir, ARGS);
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error OVL012: 'pcml' must point to a file inside the repository");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }
}

package ai.generativearchitects.glow400.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.bundle.BundleInfo;
import ai.generativearchitects.glow400.bundle.BundleWriter;
import ai.generativearchitects.glow400.emit.descriptor.DescriptorEmitter;
import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.emit.openapi.OpenApiEmitter;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
class BundleTaskTest {

    private static BundleRequest request(Path target) {
        return new BundleRequest("orders", "2026.11.14-1", target, Optional.empty(), Optional.empty());
    }

    private static byte[] direct(Path repo, BundleInfo info, boolean mock) {
        ApiRepositoryModel model = OverlayLoader.loadRepository(repo).model().orElseThrow();
        List<EmittedFile> files = new ArrayList<>(new DescriptorEmitter().emit(model).files());
        files.addAll(new OpenApiEmitter().emit(model).files());
        if (mock) {
            files.addAll(new ai.generativearchitects.glow400.mock.MockEmitter().emit(model).files());
        }
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

    @Test
    void theBundleIsWhatTheEmittersAndTheWriterProduceDirectly(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("kitchen-sink", dir.resolve("repo"));
        TaskResult result = new Tasks(dir).bundle(repo, request(dir.resolve("out/orders.rpgapi")));
        assertThat(result.hasError()).isFalse();
        assertThat(result.written()).containsExactly(dir.resolve("out/orders.rpgapi"));
        assertThat(entries(Support.bytes(dir.resolve("out/orders.rpgapi")))).containsOnlyKeys(entries(direct(repo, new BundleInfo("orders", "2026.11.14-1", Optional.empty(), Optional.empty()), true)).keySet());
        Map<String, byte[]> a = entries(Support.bytes(dir.resolve("out/orders.rpgapi")));
        Map<String, byte[]> b = entries(direct(repo, new BundleInfo("orders", "2026.11.14-1", Optional.empty(), Optional.empty()), true));
        a.forEach((name, bytes) -> assertThat(bytes).as(name).isEqualTo(b.get(name)));
    }

    @Test
    void theMockFilesAreInTheBundleWhenTheRepositoryNamesMockAndNotOtherwise(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("mocked", dir.resolve("repo"));
        assertThat(new Tasks(dir).bundle(repo, request(dir.resolve("a.rpgapi"))).hasError()).isFalse();
        assertThat(entries(Support.bytes(dir.resolve("a.rpgapi"))).keySet()).contains("mock/orders.json", "apis/ORDERS/mock/ordstatus.yaml");
        Support.write(repo.resolve("gateway.yaml"), "emitters: [descriptor, openapi]\n");
        assertThat(new Tasks(dir).bundle(repo, request(dir.resolve("b.rpgapi"))).hasError()).isFalse();
        assertThat(entries(Support.bytes(dir.resolve("b.rpgapi"))).keySet()).noneMatch(n -> n.contains("mock"));
    }

    @Test
    void theSourceOptionsAreWrittenToTheManifest(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        BundleRequest request = new BundleRequest("orders", "1", dir.resolve("o.rpgapi"), Optional.of("https://example.test/orders"), Optional.of("abc123"));
        assertThat(new Tasks(dir).bundle(repo, request).hasError()).isFalse();
        String manifest = new String(entries(Support.bytes(dir.resolve("o.rpgapi"))).get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(manifest).contains("https://example.test/orders").contains("abc123");
        assertThat(Json.read(new ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isInstanceOf(Map.class);
    }

    @Test
    void anExistingBundleIsReplacedAndNoTemporaryFileIsLeft(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/orders.rpgapi"), "an old bundle");
        assertThat(new Tasks(dir).bundle(repo, request(dir.resolve("out/orders.rpgapi"))).hasError()).isFalse();
        assertThat(Support.bytes(dir.resolve("out/orders.rpgapi"))).startsWith((byte) 'P', (byte) 'K');
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "orders.rpgapi");
    }

    @Test
    void anInvalidRepositoryWritesNoBundleAndNoFolder(@TempDir Path dir) {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        TaskResult result = new Tasks(dir).bundle(repo, request(dir.resolve("out/orders.rpgapi")));
        assertThat(result.hasError()).isTrue();
        assertThat(result.written()).isEmpty();
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void anInvalidNameOrVersionIsBdl006AndNothingIsWritten(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        TaskResult name = new Tasks(dir).bundle(repo, new BundleRequest("Orders", "1", dir.resolve("out/b.rpgapi"), Optional.empty(), Optional.empty()));
        assertThat(Support.lines(name.diagnostics())).anyMatch(l -> l.contains("BDL006: The bundle name must match [a-z0-9][a-z0-9._-]*"));
        TaskResult version = new Tasks(dir).bundle(repo, new BundleRequest("orders", "one two", dir.resolve("out/b.rpgapi"), Optional.empty(), Optional.empty()));
        assertThat(Support.lines(version.diagnostics())).anyMatch(l -> l.contains("BDL006: The bundle version must be 1 to 64 characters without white space"));
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void aBundleFileThatCannotBeWrittenIsCli003AndLeavesNothingBehind(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("out/orders.rpgapi/inside.txt"), "the target is a folder");
        TaskResult result = new Tasks(dir).bundle(repo, request(dir.resolve("out/orders.rpgapi")));
        assertThat(Support.lines(result.diagnostics())).anyMatch(l -> l.matches("out/orders.rpgapi:1:1: CLI003: The file out/orders.rpgapi could not be written: [a-z ]+"));
        assertThat(result.written()).isEmpty();
        assertThat(Support.names(dir.resolve("out"))).containsExactly("", "orders.rpgapi", "orders.rpgapi/inside.txt");
        Support.write(dir.resolve("blocker"), "a file");
        TaskResult parent = new Tasks(dir).bundle(repo, request(dir.resolve("blocker/b.rpgapi")));
        assertThat(Support.lines(parent.diagnostics())).anyMatch(l -> l.startsWith("blocker/b.rpgapi:1:1: CLI003: The file blocker/b.rpgapi could not be written: "));
        assertThat(Support.read(dir.resolve("blocker"))).isEqualTo("a file");
    }

    @Test
    void aSourceFileThatLeavesTheRepositoryStopsTheTaskBeforeAnythingIsWritten(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Path pcml = repo.resolve("apis/ORDSTATUS/ORDSTATUS.pcml");
        Files.move(pcml, dir.resolve("outside.pcml"));
        Files.createSymbolicLink(pcml, dir.resolve("outside.pcml"));
        TaskResult result = new Tasks(dir).bundle(repo, request(dir.resolve("out/orders.rpgapi")));
        assertThat(Support.lines(result.diagnostics())).anyMatch(l -> l.contains("OVL012: 'pcml' must point to a file inside the repository"));
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void theRequestRefusesNulls() {
        Path p = Path.of("x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BundleRequest(null, "1", p, Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("name");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BundleRequest("n", null, p, Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("version");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BundleRequest("n", "1", null, Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("target");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BundleRequest("n", "1", p, null, Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("sourceRepository");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BundleRequest("n", "1", p, Optional.empty(), null)).isInstanceOf(NullPointerException.class).hasMessage("sourceRevision");
    }
}

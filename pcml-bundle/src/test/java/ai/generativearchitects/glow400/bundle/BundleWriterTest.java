package ai.generativearchitects.glow400.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The writer: layout, manifest, determinism, and every BDL006 condition. */
class BundleWriterTest {

    private static ByteArrayOutputStream out = new ByteArrayOutputStream();

    private static BundleResult write(Path root, ApiRepositoryModel model, List<EmittedFile> emitted, BundleInfo info, ByteArrayOutputStream sink) {
        return BundleWriter.write(root, model, emitted, info, sink);
    }

    private static List<String> messages(BundleResult result) {
        return result.diagnostics().stream().map(Diagnostic::message).toList();
    }

    // ---- layout and manifest

    @Test
    void theBundleHasTheManifestFirstThenEveryFileSortedAndNothingElse(@TempDir Path dir) {
        Path root = Support.copy("kitchen-sink", dir);
        byte[] bundle = Support.bundle(root, Support.INFO);
        assertThat(Support.entries(bundle).keySet()).containsExactly("manifest.json", "apis/CATALOG/CATALOG.pcml", "apis/CATALOG/api.yaml",
                "descriptor.json", "openapi/catalog.yaml");
    }

    @Test
    void thePcmlAndOverlayFilesAreTheSourceBytesAtTheirRepositoryPaths(@TempDir Path dir) throws IOException {
        Path root = Support.copy("minimal", dir);
        Map<String, byte[]> entries = Support.entries(Support.bundle(root, Support.INFO));
        for (String path : entries.keySet()) {
            if (path.startsWith("apis/")) {
                assertThat(entries.get(path)).as(path).isEqualTo(Files.readAllBytes(root.resolve(path)));
            }
        }
        assertThat(entries.keySet()).anyMatch(p -> p.startsWith("apis/") && p.endsWith(".pcml")).anyMatch(p -> p.endsWith("api.yaml"));
    }

    @Test
    void theGatewayFileIsIncludedWhenTheRepositoryHasOne(@TempDir Path dir) {
        assertThat(Support.entries(Support.bundle(Support.copy("defaults-both", dir.resolve("a")), Support.INFO))).containsKey("gateway.yaml");
        assertThat(Support.entries(Support.bundle(Support.copy("minimal", dir.resolve("b")), Support.INFO))).doesNotContainKey("gateway.yaml");
    }

    @Test
    void theManifestHoldsTheBundleTheApisAndTheChecksumsOfEveryOtherEntry(@TempDir Path dir) {
        Path root = Support.copy("operations-list", dir);
        ApiRepositoryModel model = Support.model(root);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleInfo info = new BundleInfo("orders", "2026.11.14-1", Optional.of("repo"), Optional.of("abc123"));
        Manifest manifest = write(root, model, Support.emitted(model), info, sink).manifest().orElseThrow();
        assertThat(manifest.bundleSchemaVersion()).isEqualTo(1);
        assertThat(manifest.descriptorSchemaVersion()).isEqualTo(1);
        assertThat(manifest.name()).isEqualTo("orders");
        assertThat(manifest.version()).isEqualTo("2026.11.14-1");
        assertThat(manifest.generatorName()).isEqualTo("glow400-core");
        assertThat(manifest.generatorVersion()).isEqualTo(BundleFormat.generatorVersion()).isNotBlank();
        assertThat(manifest.repository()).contains("repo");
        assertThat(manifest.revision()).contains("abc123");
        assertThat(manifest.apis()).extracting(Manifest.ApiEntry::name).containsExactly(model.apis().get(0).name());
        assertThat(manifest.apis().get(0).title()).isEqualTo(model.apis().get(0).title());
        assertThat(manifest.apis().get(0).version()).isEqualTo(model.apis().get(0).version());
        Map<String, byte[]> entries = Support.entries(sink.toByteArray());
        assertThat(manifest.files()).extracting(Manifest.FileEntry::path).isSorted().containsExactlyInAnyOrderElementsOf(
                entries.keySet().stream().filter(p -> !p.equals("manifest.json")).toList());
        for (Manifest.FileEntry file : manifest.files()) {
            assertThat(file.size()).isEqualTo(entries.get(file.path()).length);
            assertThat(file.sha256()).isEqualTo(BundleFormat.sha256(entries.get(file.path())));
        }
        assertThat(manifest.contentDigest()).isEqualTo(BundleFormat.contentDigest(manifest.files())).startsWith("sha256:");
        // the manifest in the file says the same
        String json = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"bundleSchemaVersion\": 1").contains("\"repository\": \"repo\"").contains("\"contentDigest\": \"" + manifest.contentDigest() + "\"");
    }

    @Test
    void theSourceIsNullsWhenTheCallerGivesNone(@TempDir Path dir) {
        String json = new String(Support.entries(Support.bundle(Support.copy("minimal", dir), Support.INFO)).get("manifest.json"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"source\": {\n    \"repository\": null,\n    \"revision\": null\n  }");
    }

    @Test
    void theContentDigestIsTheDigestOfOneLinePerFile() {
        List<Manifest.FileEntry> files = List.of(new Manifest.FileEntry("a", 1, "aa".repeat(32)), new Manifest.FileEntry("b", 2, "bb".repeat(32)));
        String expected = BundleFormat.sha256(("aa".repeat(32) + "  a\n" + "bb".repeat(32) + "  b\n").getBytes(StandardCharsets.UTF_8));
        assertThat(BundleFormat.contentDigest(files)).isEqualTo("sha256:" + expected);
        assertThat(BundleFormat.sha256(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    // ---- determinism

    @Test
    void theZipHoldsStoredEntriesWithOneConstantTimeAndNothingElse(@TempDir Path dir) throws IOException {
        byte[] bundle = Support.bundle(Support.copy("kitchen-sink", dir), Support.INFO);
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(bundle))) {
            for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                assertThat(e.getMethod()).as(e.getName()).isEqualTo(ZipEntry.STORED);
                assertThat(e.getExtra()).as(e.getName()).isNull();
                assertThat(e.getComment()).as(e.getName()).isNull();
                assertThat(e.isDirectory()).isFalse();
                assertThat(e.getName()).doesNotContain("\\").doesNotStartWith("/");
                assertThat(e.getTimeLocal()).isEqualTo(java.time.LocalDateTime.of(1980, 1, 1, 0, 0, 2));
                zin.readAllBytes();
            }
        }
        String text = new String(bundle, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain(System.getProperty("user.name") == null || System.getProperty("user.name").length() < 4 ? "\u0000\u0000x" : System.getProperty("user.name"));
    }

    @Test
    void writingTwiceAndFromACopyAtAnotherPathGivesTheSameBytes(@TempDir Path dir) {
        Path first = Support.copy("kitchen-sink", dir.resolve("one"));
        Path second = Support.copy("kitchen-sink", dir.resolve("another/place"));
        byte[] a = Support.bundle(first, Support.INFO);
        assertThat(Support.bundle(first, Support.INFO)).isEqualTo(a);
        assertThat(Support.bundle(second, Support.INFO)).isEqualTo(a);
    }

    @Test
    void aGoldenBundleIsComparedByteForByte(@TempDir Path dir) throws IOException {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleResult result = BundleWriter.write(root, model, Support.emitted(model), Support.INFO, sink, "0.0.0-golden");
        assertThat(result.manifest()).isPresent();
        // the emitted descriptor carries the project version, which differs from build to build: pin it for the comparison
        byte[] bundle = sink.toByteArray();
        Map<String, byte[]> entries = Support.entries(bundle);
        String descriptor = new String(entries.get("descriptor.json"), StandardCharsets.UTF_8).replace(
                "\"version\": \"" + BundleFormat.generatorVersion() + "\"", "\"version\": \"0.0.0-golden\"");
        List<EmittedFile> pinned = new ArrayList<>();
        for (EmittedFile file : Support.emitted(model)) {
            pinned.add(file.path().equals("descriptor.json") ? new EmittedFile("descriptor.json", descriptor.getBytes(StandardCharsets.UTF_8)) : file);
        }
        ByteArrayOutputStream golden = new ByteArrayOutputStream();
        BundleWriter.write(root, model, pinned, Support.INFO, golden, "0.0.0-golden");
        if (Boolean.getBoolean("glow400.updateGolden")) {
            Path file = Path.of("src/test/resources/golden/minimal.rpgapi");
            Files.createDirectories(file.getParent());
            Files.write(file, golden.toByteArray());
            return;
        }
        try (java.io.InputStream in = BundleWriterTest.class.getResourceAsStream("/golden/minimal.rpgapi")) {
            assertThat(in).as("write the golden bundle with -Dglow400.updateGolden=true").isNotNull();
            assertThat(golden.toByteArray()).isEqualTo(in.readAllBytes());
        }
    }

    // ---- BDL006

    @Test
    void anInvalidNameOrVersionIsBdl006AndNothingIsWritten(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        for (BundleInfo bad : List.of(new BundleInfo("Orders", "1", Optional.empty(), Optional.empty()),
                new BundleInfo("", "1", Optional.empty(), Optional.empty()), new BundleInfo("-x", "1", Optional.empty(), Optional.empty()),
                new BundleInfo("a b", "1", Optional.empty(), Optional.empty()), new BundleInfo("orders", "", Optional.empty(), Optional.empty()),
                new BundleInfo("orders", "1 2", Optional.empty(), Optional.empty()), new BundleInfo("orders", "1\t2", Optional.empty(), Optional.empty()),
                new BundleInfo("orders", "v".repeat(65), Optional.empty(), Optional.empty()))) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            BundleResult result = write(root, model, Support.emitted(model), bad, sink);
            assertThat(result.manifest()).isEmpty();
            assertThat(result.diagnostics()).isNotEmpty().allSatisfy(d -> {
                assertThat(d.code()).isEqualTo("BDL006");
                assertThat(d.severity()).isEqualTo(Severity.ERROR);
                assertThat(d.location()).isEqualTo(new SourceLocation("manifest.json", 1, 1));
            });
            assertThat(sink.size()).isZero();
        }
        assertThat(write(root, model, Support.emitted(model), new BundleInfo("a", "v".repeat(64), Optional.empty(), Optional.empty()), new ByteArrayOutputStream()).manifest()).isPresent();
        assertThat(write(root, model, Support.emitted(model), new BundleInfo("a", "x", Optional.empty(), Optional.empty()), new ByteArrayOutputStream()).manifest()).isPresent();
        assertThat(write(root, model, Support.emitted(model), new BundleInfo("0.a_b-c", "v1", Optional.empty(), Optional.empty()), new ByteArrayOutputStream()).manifest())
                .isPresent();
    }

    @Test
    void theMessageAboutAnInvalidNameDoesNotRepeatIt(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        BundleResult result = write(root, model, Support.emitted(model), new BundleInfo("SECRET NAME", "SECRET VERSION", Optional.empty(), Optional.empty()),
                new ByteArrayOutputStream());
        assertThat(messages(result)).hasSize(2).allSatisfy(m -> assertThat(m).doesNotContain("SECRET"));
    }

    @Test
    void aMissingDescriptorAmongTheEmittedFilesIsBdl006(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        List<EmittedFile> withoutDescriptor = Support.emitted(model).stream().filter(f -> !f.path().equals("descriptor.json")).toList();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleResult result = write(root, model, withoutDescriptor, Support.INFO, sink);
        assertThat(messages(result)).containsExactly("The emitted files do not include descriptor.json");
        assertThat(sink.size()).isZero();
    }

    @Test
    void aPathCollisionIsBdl006(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        List<EmittedFile> twice = new ArrayList<>(Support.emitted(model));
        twice.add(new EmittedFile("descriptor.json", new byte[] {1}));
        assertThat(messages(write(root, model, twice, Support.INFO, new ByteArrayOutputStream()))).containsExactly("Two emitted files have the path descriptor.json");
        String pcml = model.operations().get(0).pcmlPath();
        List<EmittedFile> overSource = new ArrayList<>(Support.emitted(model));
        overSource.add(new EmittedFile(pcml, new byte[] {1}));
        assertThat(messages(write(root, model, overSource, Support.INFO, new ByteArrayOutputStream())))
                .containsExactly("An emitted file collides with the source file " + pcml);
        List<EmittedFile> overManifest = new ArrayList<>(Support.emitted(model));
        overManifest.add(new EmittedFile("manifest.json", new byte[] {1}));
        assertThat(messages(write(root, model, overManifest, Support.INFO, new ByteArrayOutputStream()))).containsExactly("An emitted file collides with manifest.json");
    }

    @Test
    void anEmittedFileOutsideTheLayoutOrWithAnUnsafePathIsBdl006(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        for (String path : List.of("extra.txt", "mock/a.json", "openapi/UPPER.yaml", "openapi/a/b.yaml")) {
            List<EmittedFile> files = new ArrayList<>(Support.emitted(model));
            files.add(new EmittedFile(path, new byte[] {1}));
            assertThat(messages(write(root, model, files, Support.INFO, new ByteArrayOutputStream()))).as(path)
                    .containsExactly("The path " + path + " is not allowed by the layout of a bundle");
        }
    }

    @Test
    void aSourceFileThatIsMissingOrOutsideTheRepositoryIsBdl006(@TempDir Path dir) throws IOException {
        Path root = Support.copy("minimal", dir.resolve("repo"));
        ApiRepositoryModel model = Support.model(root);
        String pcml = model.operations().get(0).pcmlPath();
        Files.delete(root.resolve(pcml));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertThat(messages(write(root, model, Support.emitted(model), Support.INFO, sink))).containsExactly("The source file " + pcml + " cannot be read");
        assertThat(sink.size()).isZero();
        // a symbolic link to a file outside the repository
        Path outside = dir.resolve("outside.pcml");
        Files.writeString(outside, "<pcml/>");
        Files.createSymbolicLink(root.resolve(pcml), outside);
        assertThat(messages(write(root, model, Support.emitted(model), Support.INFO, new ByteArrayOutputStream())))
                .containsExactly("The source file " + pcml + " is outside the repository");
        // a symbolic link to a file inside the repository is fine
        Files.delete(root.resolve(pcml));
        Files.createSymbolicLink(root.resolve(pcml), root.resolve("apis/ORDSTATUS/api.yaml").toAbsolutePath());
        assertThat(write(root, model, Support.emitted(model), Support.INFO, new ByteArrayOutputStream()).manifest()).isPresent();
    }

    @Test
    void aSourcePathThatIsNotInTheLayoutOrIsUnsafeIsBdl006(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        for (String bad : List.of("../x.pcml", "/etc/x.pcml", "apis/A/x.txt", "x.pcml", "apis//a.pcml")) {
            ApiOperation op = model.operations().get(0);
            ApiOperation moved = new ApiOperation(op.id(), op.version(), op.summary(), op.description(), op.tags(), op.program(), bad,
                    op.overlayPath(), op.route(), op.method(), op.callStrategy(), op.bindings(), op.errorMapping(), op.cache(), op.source());
            ApiRepositoryModel changed = new ApiRepositoryModel(model.defaults(), List.of(moved));
            BundleResult result = write(root, changed, Support.emitted(model), Support.INFO, new ByteArrayOutputStream());
            assertThat(result.manifest()).as(bad).isEmpty();
            assertThat(messages(result)).as(bad).hasSize(1).allSatisfy(m -> assertThat(m).startsWith("The path " + bad + " is not allowed"));
        }
    }

    @Test
    void aRepositoryRootThatCannotBeReadIsBdl006(@TempDir Path dir) {
        ApiRepositoryModel model = Support.model(Support.copy("minimal", dir.resolve("real")));
        BundleResult result = write(dir.resolve("missing"), model, Support.emitted(model), Support.INFO, new ByteArrayOutputStream());
        assertThat(messages(result)).containsExactly("The repository root cannot be read");
    }

    @Test
    void aBundleWithTooManyEntriesOrBytesIsBdl006(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        List<EmittedFile> many = new ArrayList<>(Support.emitted(model));
        for (int i = 0; i < BundleFormat.Limits.STANDARD.maxEntries(); i++) {
            many.add(new EmittedFile("openapi/x" + i + ".yaml", new byte[0]));
        }
        assertThat(messages(write(root, model, many, Support.INFO, new ByteArrayOutputStream())))
                .containsExactly("The bundle would have more than 4096 entries");
        assertThat(BundleFormat.Limits.STANDARD.maxEntryBytes()).isEqualTo(16L * 1024 * 1024);
        assertThat(BundleFormat.Limits.STANDARD.maxTotalBytes()).isEqualTo(128L * 1024 * 1024);
    }

    @Test
    void aFailingOutputIsAnUncheckedIoException(@TempDir Path dir) {
        Path root = Support.copy("minimal", dir);
        ApiRepositoryModel model = Support.model(root);
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("closed");
            }
        };
        assertThatThrownBy(() -> BundleWriter.write(root, model, Support.emitted(model), Support.INFO, broken)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void theTypesRejectNull(@TempDir Path dir) {
        assertThatThrownBy(() -> BundleWriter.write(null, null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleInfo(null, "1", Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleInfo("a", null, Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleInfo("a", "1", null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleInfo("a", "1", Optional.empty(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleResult(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThat(BundleFormat.EXTENSION).isEqualTo(".rpgapi");
    }
}

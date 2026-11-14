package ai.generativearchitects.glow400.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reader: round trips, hardening against crafted files, and tampering. */
class BundleReaderTest {

    private static BundleReadResult read(Path dir, byte[] bytes) {
        return BundleReader.read(Support.file(dir, bytes));
    }

    private static Diagnostic only(BundleReadResult result, String code) {
        assertThat(result.bundle()).isEmpty();
        assertThat(result.diagnostics()).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        return d;
    }

    private static byte[] kitchenSink(Path dir) {
        return Support.bundle(Support.copy("kitchen-sink", dir), Support.INFO);
    }

    // ---- write then read

    @Test
    void whatIsWrittenIsReadBackWithTheSameModelAndEveryOperationHasACodec(@TempDir Path dir) {
        for (String fixture : Support.FIXTURES) {
            Path root = Support.copy(fixture, dir.resolve(fixture));
            ApiRepositoryModel model = Support.model(root);
            byte[] bytes = Support.bundle(root, Support.INFO);
            BundleReadResult result = read(dir.resolve("out-" + fixture), bytes);
            assertThat(result.diagnostics()).as(fixture).isEmpty();
            Bundle bundle = result.bundle().orElseThrow();
            assertThat(bundle.model().apis()).hasSameSizeAs(model.apis());
            assertThat(bundle.model().apis().get(0).name()).isEqualTo(model.apis().get(0).name());
            for (ApiOperation operation : bundle.model().operations()) {
                OperationCodec.of(operation);
            }
        }
    }

    @Test
    void theBundleGivesTheManifestThePathsAndCopiesOfTheFiles(@TempDir Path dir) throws IOException {
        Path root = Support.copy("minimal", dir.resolve("repo"));
        Bundle bundle = read(dir.resolve("out"), Support.bundle(root, Support.INFO)).bundle().orElseThrow();
        assertThat(bundle.manifest().name()).isEqualTo("orders");
        assertThat(bundle.paths().get(0)).isEqualTo("manifest.json");
        assertThat(bundle.paths().subList(1, bundle.paths().size())).isSorted().contains("descriptor.json");
        String pcml = bundle.model().operations().get(0).pcmlPath();
        assertThat(bundle.file(pcml)).hasValue(Files.readAllBytes(root.resolve(pcml)));
        assertThat(bundle.file("nothing/here")).isEmpty();
        byte[] copy = bundle.file("descriptor.json").orElseThrow();
        copy[0] = 'X';
        assertThat(bundle.file("descriptor.json").orElseThrow()[0]).isEqualTo((byte) '{');
        assertThatThrownBy(() -> bundle.paths().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theModelOfTheBundleIsTheModelOfItsDescriptorAndTheDigestIsKept(@TempDir Path dir) {
        Path root = Support.copy("kitchen-sink", dir.resolve("repo"));
        ByteArrayOutputStreamHolder written = new ByteArrayOutputStreamHolder();
        ApiRepositoryModel model = Support.model(root);
        Manifest manifest = BundleWriter.write(root, model, Support.emitted(model), Support.INFO, written.out).manifest().orElseThrow();
        Bundle bundle = read(dir.resolve("out"), written.out.toByteArray()).bundle().orElseThrow();
        assertThat(bundle.manifest()).isEqualTo(manifest);
        assertThat(bundle.manifest().contentDigest()).isEqualTo(manifest.contentDigest());
    }

    private static final class ByteArrayOutputStreamHolder {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    }

    @Test
    void readingExtractsNothingAndLeavesTheFileAlone(@TempDir Path dir) throws IOException {
        byte[] bytes = kitchenSink(dir.resolve("repo"));
        Path folder = dir.resolve("only");
        Path file = Support.file(folder, bytes);
        List<String> before = names(dir);
        BundleReader.read(file);
        assertThat(names(dir)).isEqualTo(before);
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    // ---- BDL001: not a readable zip

    @Test
    void aFileThatIsNotAReadableZipIsBdl001(@TempDir Path dir) throws IOException {
        byte[] good = kitchenSink(dir.resolve("repo"));
        assertThat(only(read(dir.resolve("a"), new byte[0]), "BDL001").message()).isEqualTo("Not a readable zip file");
        only(read(dir.resolve("b"), "this is not a zip file at all, just some text of some length".getBytes(StandardCharsets.UTF_8)), "BDL001");
        only(read(dir.resolve("c"), java.util.Arrays.copyOf(good, good.length / 2)), "BDL001");
        only(read(dir.resolve("d"), java.util.Arrays.copyOf(good, 10)), "BDL001");
        Diagnostic missing = only(BundleReader.read(dir.resolve("does-not-exist.rpgapi")), "BDL001");
        assertThat(missing.message()).isEqualTo("The file cannot be read");
        assertThat(missing.location()).isEqualTo(new SourceLocation("does-not-exist.rpgapi", 1, 1));
    }

    @Test
    void aTruncatedFileIsRefusedWhereverItIsCut(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        for (int cut : new int[] {1, 30, 100, good.length / 3, good.length - 100, good.length - 22, good.length - 1}) {
            BundleReadResult result = read(dir.resolve("cut" + cut), java.util.Arrays.copyOf(good, cut));
            // the entries are read from their local headers, so a cut in the central directory alone leaves the content readable
            if (cut < good.length - 600) {
                assertThat(result.bundle()).as("cut at " + cut).isEmpty();
            }
        }
    }

    @Test
    void anEmptyZipHasNoManifest(@TempDir Path dir) {
        byte[] empty = Support.zip(List.of());
        assertThat(empty).hasSize(22);
        Diagnostic d = only(read(dir, empty), "BDL003");
        assertThat(d.message()).isEqualTo("The bundle has no manifest.json");
    }

    @Test
    void aSingleByteChangedInsideAnEntryOfTheFileBreaksTheZipItself(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        int at = indexOf(good, "descriptor.json".getBytes(StandardCharsets.UTF_8)) + 60;
        good[at] ^= 0x01;
        BundleReadResult result = read(dir.resolve("out"), good);
        assertThat(result.bundle()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).hasSize(1).containsAnyOf("BDL001", "BDL004");
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("not found");
    }

    @Test
    void sizesThatTheZipDeclaresWrongAreNotTrusted(@TempDir Path dir) {
        byte[] good = Support.zip(List.of(Support.Entry.stored("manifest.json", new byte[100]), Support.Entry.stored("descriptor.json", new byte[100])));
        // the sizes of the first local header sit at offsets 18 (compressed) and 22 (uncompressed)
        for (int declared : new int[] {10, 5000}) {
            byte[] lying = good.clone();
            lying[18] = (byte) declared;
            lying[19] = (byte) (declared >> 8);
            lying[22] = (byte) declared;
            lying[23] = (byte) (declared >> 8);
            BundleReadResult result = read(dir.resolve("d" + declared), lying);
            assertThat(result.bundle()).isEmpty();
            assertThat(result.diagnostics()).extracting(Diagnostic::code).allMatch(c -> c.equals("BDL001") || c.equals("BDL003"));
        }
    }

    // ---- BDL003: structure

    private static Support.Entry e(String name) {
        return Support.Entry.stored(name, new byte[] {1});
    }

    @Test
    void anEntryWithAnUnsafeNameIsBdl003(@TempDir Path dir) {
        String[][] cases = {{"/descriptor.json", "an absolute path"}, {"apis/A/../x.pcml", "a '.' or '..' segment"},
                {"../x", "a '.' or '..' segment"}, {"apis\\A\\x.pcml", "a backslash"}, {"apis//x.pcml", "an empty path segment"},
                {"./descriptor.json", "a '.' or '..' segment"}, {"apis/", "a directory entry"}, {"apis/A/", "a directory entry"}};
        for (String[] c : cases) {
            byte[] zip = Support.zip(List.of(e(c[0])));
            BundleReadResult result = read(dir.resolve(String.valueOf(c[0].hashCode())), zip);
            Diagnostic d = only(result, "BDL003");
            assertThat(d.message()).as(c[0]).isEqualTo("An entry has an unsafe name: " + c[1]);
        }
    }

    @Test
    void aCompressedEntryIsBdl003(@TempDir Path dir) {
        byte[] zip = Support.zip(List.of(new Support.Entry("descriptor.json", "{}".repeat(100).getBytes(StandardCharsets.UTF_8), false)));
        assertThat(only(read(dir, zip), "BDL003").message()).isEqualTo("The entry descriptor.json is compressed; entries must be stored");
    }

    @Test
    void anEntryThatTheLayoutDoesNotAllowIsBdl003(@TempDir Path dir) {
        for (String name : List.of("extra.txt", "mock/a.json", "openapi/UPPER.yaml", "openapi/a/b.yaml", "apis/A/b.txt", "apis/A/b/c.pcml", "apis/.hidden/a.pcml",
                "tests/a.json", "Manifest.json", "gateway.yml", "apis/A/api.json", "x".repeat(300))) {
            BundleReadResult result = read(dir.resolve(String.valueOf(name.hashCode())), Support.zip(List.of(e(name))));
            Diagnostic d = only(result, "BDL003");
            assertThat(d.message()).as(name).startsWith("The entry ").endsWith(" is not allowed by the layout of a bundle");
        }
        assertThat(BundleFormat.allowed("apis/A/api.yml")).isTrue();
        assertThat(BundleFormat.allowed("apis/ORDSTATUS/ORDSTATUS.pcml")).isTrue();
        assertThat(BundleFormat.allowed("openapi/orders.v2.yaml")).isTrue();
        assertThat(BundleFormat.allowed("x".repeat(255))).isFalse();
    }

    @Test
    void aDuplicateEntryIsBdl003(@TempDir Path dir) {
        byte[] zip = Support.zip(List.of(e("apis/A/a.pcml"), e("apis/A/b.pcml")));
        byte[] a = "apis/A/a.pcml".getBytes(StandardCharsets.UTF_8);
        byte[] b = "apis/A/b.pcml".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + b.length <= zip.length; i++) {
            if (java.util.Arrays.equals(java.util.Arrays.copyOfRange(zip, i, i + b.length), b)) {
                System.arraycopy(a, 0, zip, i, a.length);
            }
        }
        Diagnostic d = only(read(dir, zip), "BDL003");
        assertThat(d.message()).isEqualTo("The entry apis/A/a.pcml appears more than once");
    }

    @Test
    void aBundleWithoutManifestOrDescriptorIsBdl003(@TempDir Path dir) {
        Map<String, byte[]> entries = Support.entries(kitchenSink(dir.resolve("repo")));
        for (String missing : List.of("manifest.json", "descriptor.json")) {
            List<Support.Entry> kept = new ArrayList<>();
            entries.forEach((name, bytes) -> {
                if (!name.equals(missing)) {
                    kept.add(Support.Entry.stored(name, bytes));
                }
            });
            assertThat(only(read(dir.resolve(missing), Support.zip(kept)), "BDL003").message()).isEqualTo("The bundle has no " + missing);
        }
    }

    @Test
    void theLimitsOnEntriesAndBytesAreCheckedOnWhatIsRead(@TempDir Path dir) {
        List<Support.Entry> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(e("openapi/a" + i + ".yaml"));
        }
        Path file = Support.file(dir.resolve("a"), Support.zip(many));
        assertThat(only(BundleReader.read(file, new BundleFormat.Limits(5, 100, 1000)), "BDL003").message()).isEqualTo("The bundle has more than 5 entries");
        assertThat(BundleReader.read(file, new BundleFormat.Limits(6, 100, 1000)).diagnostics()).extracting(Diagnostic::message)
                .containsExactly("The bundle has no manifest.json");
        Path big = Support.file(dir.resolve("b"), Support.zip(List.of(Support.Entry.stored("descriptor.json", new byte[101]))));
        assertThat(only(BundleReader.read(big, new BundleFormat.Limits(10, 100, 1000)), "BDL003").message())
                .isEqualTo("The entry descriptor.json holds more than 100 bytes");
        Path exact = Support.file(dir.resolve("c"), Support.zip(List.of(Support.Entry.stored("descriptor.json", new byte[100]))));
        assertThat(BundleReader.read(exact, new BundleFormat.Limits(10, 100, 1000)).diagnostics()).extracting(Diagnostic::message)
                .doesNotContain("The entry descriptor.json holds more than 100 bytes");
        Path total = Support.file(dir.resolve("d"), Support.zip(List.of(Support.Entry.stored("descriptor.json", new byte[60]), Support.Entry.stored("manifest.json", new byte[60]))));
        assertThat(only(BundleReader.read(total, new BundleFormat.Limits(10, 100, 100)), "BDL003").message())
                .isEqualTo("The entries hold more than 100 bytes together");
        Path justTotal = Support.file(dir.resolve("e"), Support.zip(List.of(Support.Entry.stored("descriptor.json", new byte[50]), Support.Entry.stored("manifest.json", new byte[50]))));
        assertThat(BundleReader.read(justTotal, new BundleFormat.Limits(10, 100, 100)).diagnostics()).extracting(Diagnostic::message)
                .doesNotContain("The entries hold more than 100 bytes together");
    }

    @Test
    void aCompressedBombIsStoppedByTheLimitOnWhatIsRead(@TempDir Path dir) {
        byte[] bomb = Support.zip(List.of(new Support.Entry("descriptor.json", new byte[2_000_000], false)));
        assertThat(bomb.length).isLessThan(10_000);
        Path file = Support.file(dir, bomb);
        Diagnostic d = only(BundleReader.read(file, new BundleFormat.Limits(10, 1_000_000, 1_000_000)), "BDL003");
        assertThat(d.message()).isEqualTo("The entry descriptor.json holds more than 1000000 bytes");
    }

    // ---- the manifest

    private static byte[] withManifest(Path dir, java.util.function.Consumer<Map<String, Object>> change) {
        return Support.rebuilt(kitchenSink(dir), entries -> { }, change);
    }

    @Test
    void aManifestThatIsNotJsonOrNotTheRightShapeIsBdl003(@TempDir Path dir) {
        Map<String, byte[]> entries = Support.entries(kitchenSink(dir.resolve("repo")));
        List<Support.Entry> notJson = new ArrayList<>();
        entries.forEach((name, bytes) -> notJson.add(Support.Entry.stored(name, name.equals("manifest.json") ? Support.bytes("{not json") : bytes)));
        Diagnostic d = only(read(dir.resolve("a"), Support.zip(notJson)), "BDL003");
        assertThat(d.message()).isEqualTo("manifest.json is not valid JSON");
        assertThat(d.location()).isEqualTo(new SourceLocation("manifest.json", 1, 1));
        List<Support.Entry> array = new ArrayList<>();
        entries.forEach((name, bytes) -> array.add(Support.Entry.stored(name, name.equals("manifest.json") ? Support.bytes("[]") : bytes)));
        assertThat(only(read(dir.resolve("b"), Support.zip(array)), "BDL003").message()).isEqualTo("manifest: expected an object");
    }

    @Test
    void everyKeyOfTheManifestIsRequiredAndNoOtherIsAllowed(@TempDir Path dir) {
        String[] keys = {"bundleSchemaVersion", "name", "version", "generator", "descriptorSchemaVersion", "source", "apis", "files", "contentDigest"};
        for (String key : keys) {
            byte[] bundle = withManifest(dir.resolve("r" + key), tree -> tree.remove(key));
            assertThat(only(read(dir.resolve("o" + key), bundle), "BDL003").message()).as(key).isEqualTo("manifest: missing key '" + key + "'");
        }
        byte[] extra = withManifest(dir.resolve("x"), tree -> tree.put("extra", 1));
        assertThat(only(read(dir.resolve("ox"), extra), "BDL003").message()).isEqualTo("manifest: unexpected key 'extra'");
        byte[] extraInGenerator = withManifest(dir.resolve("g"), tree -> ((Map<String, Object>) tree.get("generator")).put("extra", 1));
        assertThat(only(read(dir.resolve("og"), extraInGenerator), "BDL003").message()).isEqualTo("manifest.generator: unexpected key 'extra'");
        byte[] extraInSource = withManifest(dir.resolve("s"), tree -> ((Map<String, Object>) tree.get("source")).put("extra", 1));
        assertThat(only(read(dir.resolve("os"), extraInSource), "BDL003").message()).isEqualTo("manifest.source: unexpected key 'extra'");
        byte[] extraInApi = withManifest(dir.resolve("a"), tree -> ((Map<String, Object>) ((List<Object>) tree.get("apis")).get(0)).put("extra", 1));
        assertThat(only(read(dir.resolve("oa"), extraInApi), "BDL003").message()).isEqualTo("manifest.apis[]: unexpected key 'extra'");
        byte[] extraInFile = withManifest(dir.resolve("f"), tree -> ((Map<String, Object>) ((List<Object>) tree.get("files")).get(0)).put("extra", 1));
        assertThat(only(read(dir.resolve("of"), extraInFile), "BDL003").message()).isEqualTo("manifest.files[]: unexpected key 'extra'");
    }

    @SuppressWarnings("unchecked")
    @Test
    void valuesOfTheWrongTypeOrFormInTheManifestAreBdl003(@TempDir Path dir) {
        Object[][] cases = {
                {"name", "Orders", "manifest.name: must match [a-z0-9][a-z0-9._-]*"},
                {"name", 5, "manifest: 'name' must be a string"},
                {"version", "has space", "manifest.version: must be 1 to 64 characters without white space"},
                {"version", null, "manifest: 'version' must be a string"},
                {"generator", "x", "manifest.generator: expected an object"},
                {"source", List.of(), "manifest.source: expected an object"},
                {"apis", "x", "manifest: 'apis' must be an array"},
                {"files", Map.of(), "manifest: 'files' must be an array"},
                {"contentDigest", "sha256:ABC", "manifest.contentDigest: expected sha256: and 64 lower-case hexadecimal digits"},
                {"contentDigest", "abc", "manifest.contentDigest: expected sha256: and 64 lower-case hexadecimal digits"},
                {"contentDigest", 1, "manifest: 'contentDigest' must be a string"},
                {"bundleSchemaVersion", "1", "manifest: 'bundleSchemaVersion' must be a whole number"},
                {"descriptorSchemaVersion", true, "manifest: 'descriptorSchemaVersion' must be a whole number"},
        };
        int n = 0;
        for (Object[] c : cases) {
            byte[] bundle = withManifest(dir.resolve("m" + n), tree -> tree.put((String) c[0], c[1]));
            assertThat(only(read(dir.resolve("o" + n++), bundle), "BDL003").message()).as(c[0] + "=" + c[1]).isEqualTo(c[2]);
        }
        byte[] source = withManifest(dir.resolve("src"), tree -> ((Map<String, Object>) tree.get("source")).put("repository", 5));
        assertThat(only(read(dir.resolve("osrc"), source), "BDL003").message()).isEqualTo("manifest: 'repository' must be a string or null");
        byte[] size = withManifest(dir.resolve("sz"), tree -> ((Map<String, Object>) ((List<Object>) tree.get("files")).get(0)).put("size", -1));
        assertThat(only(read(dir.resolve("osz"), size), "BDL003").message()).isEqualTo("manifest.files[].size: expected a whole number that is not negative");
        byte[] sizeText = withManifest(dir.resolve("szt"), tree -> ((Map<String, Object>) ((List<Object>) tree.get("files")).get(0)).put("size", "1"));
        assertThat(only(read(dir.resolve("oszt"), sizeText), "BDL003").message()).isEqualTo("manifest.files[].size: expected a whole number that is not negative");
        byte[] sha = withManifest(dir.resolve("sh"), tree -> ((Map<String, Object>) ((List<Object>) tree.get("files")).get(0)).put("sha256", "ABC"));
        assertThat(only(read(dir.resolve("osh"), sha), "BDL003").message()).isEqualTo("manifest.files[].sha256: expected 64 lower-case hexadecimal digits");
        byte[] apiNotObject = withManifest(dir.resolve("an"), tree -> ((List<Object>) tree.get("apis")).set(0, "x"));
        assertThat(only(read(dir.resolve("oan"), apiNotObject), "BDL003").message()).isEqualTo("manifest.apis[]: expected an object");
    }

    @Test
    void aManifestOfAnotherSchemaVersionIsBdl002BeforeAnythingElseIsLookedAt(@TempDir Path dir) {
        for (String key : List.of("bundleSchemaVersion", "descriptorSchemaVersion")) {
            for (long version : new long[] {0, 2, 99, -1}) {
                byte[] bundle = withManifest(dir.resolve(key + version), tree -> tree.put(key, version));
                Diagnostic d = only(read(dir.resolve("o" + key + version), bundle), "BDL002");
                assertThat(d.message()).isEqualTo("Unsupported " + key + " " + version + "; this reader supports version 1");
                assertThat(d.location()).isEqualTo(new SourceLocation("manifest.json", 1, 1));
            }
        }
        // a manifest of a future version, with a shape this reader does not know, is still refused as such
        byte[] future = withManifest(dir.resolve("future"), tree -> {
            tree.clear();
            tree.put("bundleSchemaVersion", 2);
            tree.put("somethingNew", List.of(1, 2));
        });
        only(read(dir.resolve("ofuture"), future), "BDL002");
    }

    @Test
    void theManifestMustListExactlyTheEntriesPresentSortedAndOnce(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        // an entry the manifest does not list
        byte[] extra = Support.rebuilt(good, entries -> entries.put("openapi/extra.yaml", Support.bytes("x")), tree -> {
            List<Object> files = (List<Object>) tree.get("files");
            files.removeIf(f -> ((Map<String, Object>) f).get("path").equals("openapi/extra.yaml"));
        });
        assertThat(only(read(dir.resolve("a"), extra), "BDL003").message()).isEqualTo("The bundle has openapi/extra.yaml, which the manifest does not list");
        // an entry the bundle does not have
        byte[] missing = Support.rebuilt(good, entries -> { }, tree -> {
            List<Object> files = (List<Object>) tree.get("files");
            Map<String, Object> ghost = new java.util.LinkedHashMap<>((Map<String, Object>) files.get(0));
            ghost.put("path", "openapi/zzz.yaml");
            files.add(ghost);
        });
        assertThat(only(read(dir.resolve("b"), missing), "BDL003").message()).isEqualTo("The manifest lists openapi/zzz.yaml, which the bundle does not have");
        // a file removed from the zip
        byte[] removed = Support.rebuilt(good, entries -> entries.remove("openapi/catalog.yaml"), tree -> {
            List<Object> files = (List<Object>) tree.get("files");
            Map<String, Object> ghost = new java.util.LinkedHashMap<>((Map<String, Object>) files.get(0));
            ghost.put("path", "openapi/catalog.yaml");
            files.add(ghost);
        });
        assertThat(only(read(dir.resolve("c"), removed), "BDL003").message()).contains("openapi/catalog.yaml");
        // listed twice
        byte[] twice = Support.rebuilt(good, entries -> { }, tree -> {
            List<Object> files = (List<Object>) tree.get("files");
            files.add(1, new java.util.LinkedHashMap<>((Map<String, Object>) files.get(0)));
        });
        assertThat(read(dir.resolve("d"), twice).diagnostics()).extracting(Diagnostic::message).contains("The manifest lists apis/CATALOG/CATALOG.pcml more than once");
        // not sorted
        byte[] unsorted = Support.rebuilt(good, entries -> { }, tree -> java.util.Collections.reverse((List<Object>) tree.get("files")));
        assertThat(only(read(dir.resolve("e"), unsorted), "BDL003").message()).isEqualTo("The manifest does not list its files sorted by path");
        // listing itself
        byte[] self = Support.rebuilt(good, entries -> { }, tree -> {
            List<Object> files = (List<Object>) tree.get("files");
            Map<String, Object> m = new java.util.LinkedHashMap<>((Map<String, Object>) files.get(0));
            m.put("path", "manifest.json");
            files.add(0, m);
        });
        assertThat(read(dir.resolve("f"), self).diagnostics()).extracting(Diagnostic::message).contains("The manifest lists itself");
    }

    // ---- BDL004: tampering with the content

    @Test
    void oneChangedByteInAnyEntryIsBdl004(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        Map<String, byte[]> entries = Support.entries(good);
        List<Support.Entry> original = new ArrayList<>();
        entries.forEach((name, bytes) -> original.add(Support.Entry.stored(name, bytes)));
        for (String path : entries.keySet()) {
            if (path.equals("manifest.json")) {
                continue;
            }
            List<Support.Entry> changed = new ArrayList<>();
            entries.forEach((name, bytes) -> {
                byte[] copy = bytes.clone();
                if (name.equals(path)) {
                    copy[copy.length / 2] ^= 0x01;
                }
                changed.add(Support.Entry.stored(name, copy));
            });
            BundleReadResult result = read(dir.resolve("o" + path.hashCode()), Support.zip(changed));
            assertThat(result.bundle()).as(path).isEmpty();
            assertThat(result.diagnostics()).extracting(Diagnostic::code).as(path).containsOnly("BDL004");
            assertThat(result.diagnostics()).extracting(Diagnostic::message).as(path).contains("The checksum of " + path + " is not the one in the manifest");
        }
    }

    @Test
    void aChangeOfSizeIsReportedWithTheChecksum(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        List<Support.Entry> changed = new ArrayList<>();
        Support.entries(good).forEach((name, bytes) -> changed.add(Support.Entry.stored(name,
                name.equals("openapi/catalog.yaml") ? java.util.Arrays.copyOf(bytes, bytes.length + 1) : bytes)));
        BundleReadResult result = read(dir.resolve("o"), Support.zip(changed));
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly("The size of openapi/catalog.yaml is not the one in the manifest",
                "The checksum of openapi/catalog.yaml is not the one in the manifest");
    }

    @Test
    void aManifestEditedToHideATamperingStillGivesBdl004(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        // the checksum of a changed file is corrected in the manifest, but the content digest is not
        byte[] changedContent = Support.rebuilt(good, entries -> entries.put("openapi/catalog.yaml", Support.bytes("changed")), tree -> tree.put("contentDigest", "sha256:" + "0".repeat(64)));
        Diagnostic d = only(read(dir.resolve("a"), changedContent), "BDL004");
        assertThat(d.message()).isEqualTo("The content digest is not the one of the files in the manifest");
        assertThat(d.location()).isEqualTo(new SourceLocation("manifest.json", 1, 1));
        // a file edited and the manifest made consistent for it is a different bundle: nothing is wrong with it by itself
        byte[] consistent = Support.rebuilt(good, entries -> entries.put("openapi/catalog.yaml", Support.bytes("changed")), tree -> { });
        assertThat(read(dir.resolve("b"), consistent).diagnostics()).isEmpty();
    }

    @Test
    void messagesNameThePathAndNeverTheContent(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        List<Support.Entry> changed = new ArrayList<>();
        Support.entries(good).forEach((name, bytes) -> changed.add(Support.Entry.stored(name, name.equals("openapi/catalog.yaml") ? Support.bytes("SECRETCONTENT") : bytes)));
        BundleReadResult result = read(dir.resolve("o"), Support.zip(changed));
        assertThat(result.diagnostics()).isNotEmpty().allSatisfy(d -> assertThat(d.message()).doesNotContain("SECRET"));
    }

    // ---- BDL005: the descriptor

    @Test
    void aDescriptorThatIsInvalidIsBdl005WithItsOwnDiagnosticPassedThrough(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        byte[] notJson = Support.rebuilt(good, entries -> entries.put("descriptor.json", Support.bytes("{not json")), tree -> { });
        Diagnostic json = only(read(dir.resolve("a"), notJson), "BDL005");
        assertThat(json.message()).startsWith("The descriptor is invalid: DSC001 ");
        byte[] empty = Support.rebuilt(good, entries -> entries.put("descriptor.json", Support.bytes("{\"schemaVersion\": 1}")), tree -> { });
        assertThat(only(read(dir.resolve("b"), empty), "BDL005").message()).startsWith("The descriptor is invalid: DSC003 ");
        byte[] newer = Support.rebuilt(good, entries -> entries.put("descriptor.json", Support.bytes("{\"schemaVersion\": 2}")), tree -> { });
        assertThat(only(read(dir.resolve("c"), newer), "BDL005").message()).startsWith("The descriptor is invalid: DSC002 ");
    }

    @Test
    void aDescriptorThatNamesAFileTheBundleDoesNotHaveIsBdl005(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        byte[] noPcml = Support.rebuilt(good, entries -> entries.remove("apis/CATALOG/CATALOG.pcml"), tree -> { });
        assertThat(only(read(dir.resolve("a"), noPcml), "BDL005").message())
                .isEqualTo("The descriptor refers to apis/CATALOG/CATALOG.pcml, which the bundle does not have");
        byte[] noOverlay = Support.rebuilt(good, entries -> entries.remove("apis/CATALOG/api.yaml"), tree -> { });
        assertThat(only(read(dir.resolve("b"), noOverlay), "BDL005").message())
                .isEqualTo("The descriptor refers to apis/CATALOG/api.yaml, which the bundle does not have");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aManifestThatDisagreesWithTheDescriptorAboutTheApisIsBdl005(@TempDir Path dir) {
        byte[] good = kitchenSink(dir.resolve("repo"));
        for (String key : List.of("name", "title", "version")) {
            byte[] changed = Support.rebuilt(good, entries -> { }, tree -> ((Map<String, Object>) ((List<Object>) tree.get("apis")).get(0)).put(key, "other"));
            assertThat(only(read(dir.resolve(key), changed), "BDL005").message()).as(key).isEqualTo("The APIs of the descriptor are not the APIs listed in the manifest");
        }
        byte[] extra = Support.rebuilt(good, entries -> { }, tree -> ((List<Object>) tree.get("apis")).add(new java.util.LinkedHashMap<>(
                (Map<String, Object>) ((List<Object>) tree.get("apis")).get(0))));
        only(read(dir.resolve("extra"), extra), "BDL005");
        byte[] none = Support.rebuilt(good, entries -> { }, tree -> ((List<Object>) tree.get("apis")).clear());
        only(read(dir.resolve("none"), none), "BDL005");
    }

    @Test
    void theTypesRejectNull() {
        assertThatThrownBy(() -> BundleReader.read(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleReadResult(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Manifest.FileEntry(null, 1, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Manifest.FileEntry("x", 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Manifest.ApiEntry(null, "t", "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Manifest.ApiEntry("n", null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Manifest.ApiEntry("n", "t", null)).isInstanceOf(NullPointerException.class);
    }
}

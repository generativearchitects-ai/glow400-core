package ai.generativearchitects.glow400.bundle;

import ai.generativearchitects.glow400.emit.descriptor.DescriptorEmitter;
import ai.generativearchitects.glow400.emit.openapi.OpenApiEmitter;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Repositories, bundles and crafted zip files for the tests. */
final class Support {

    static final List<String> FIXTURES = List.of("action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink",
            "inputoutput", "minimal", "operations-list", "structs");
    static final BundleInfo INFO = new BundleInfo("orders", "2026.11.14-1", Optional.empty(), Optional.empty());

    private Support() {
    }

    /** A copy of a fixture repository on the real file system. */
    static Path copy(String fixture, Path target) {
        Path source = Fixtures.path(fixture);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(file, to);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    static ApiRepositoryModel model(Path root) {
        RepositoryResult result = OverlayLoader.loadRepository(root);
        if (result.hasErrors()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.model().orElseThrow();
    }

    static List<EmittedFile> emitted(ApiRepositoryModel model) {
        List<EmittedFile> files = new ArrayList<>(new DescriptorEmitter().emit(model).files());
        files.addAll(new OpenApiEmitter().emit(model).files());
        return files;
    }

    /** The bytes of the bundle of a repository on disk. */
    static byte[] bundle(Path root, BundleInfo info) {
        ApiRepositoryModel model = model(root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BundleResult result = BundleWriter.write(root, model, emitted(model), info, out);
        if (result.manifest().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return out.toByteArray();
    }

    static Path file(Path dir, byte[] bytes) {
        try {
            Path file = dir.resolve("test.rpgapi");
            Files.createDirectories(dir);
            Files.write(file, bytes);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- crafted zip files

    /** One entry of a zip file to craft. */
    record Entry(String name, byte[] content, boolean stored) {
        static Entry stored(String name, byte[] content) {
            return new Entry(name, content, true);
        }
    }

    static byte[] zip(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                ZipEntry entry = new ZipEntry(e.name());
                if (e.stored()) {
                    CRC32 crc = new CRC32();
                    crc.update(e.content());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.content().length);
                    entry.setCompressedSize(e.content().length);
                    entry.setCrc(crc.getValue());
                }
                zos.putNextEntry(entry);
                zos.write(e.content());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The entries of a bundle, by name, in the order of the file. */
    static Map<String, byte[]> entries(byte[] bundle) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                entries.put(e.getName(), zin.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    /**
     * A bundle made of the entries of another with changes, and with the manifest made consistent again from the new content,
     * so that only what the test changes can be wrong. {@code afterManifest} may then change the manifest tree.
     */
    @SuppressWarnings("unchecked")
    static byte[] rebuilt(byte[] bundle, Consumer<Map<String, byte[]>> change, Consumer<Map<String, Object>> afterManifest) {
        Map<String, byte[]> entries = entries(bundle);
        entries.remove(BundleFormat.MANIFEST);
        change.accept(entries);
        Map<String, byte[]> sorted = new java.util.TreeMap<>(entries);
        List<Manifest.FileEntry> files = new ArrayList<>();
        sorted.forEach((path, bytes) -> files.add(new Manifest.FileEntry(path, bytes.length, BundleFormat.sha256(bytes))));
        Manifest old = BundleReader.read(file(javaTemp(), bundle)).bundle().orElseThrow().manifest();
        Manifest manifest = new Manifest(old.bundleSchemaVersion(), old.name(), old.version(), old.generatorName(), old.generatorVersion(),
                old.descriptorSchemaVersion(), old.repository(), old.revision(), old.apis(), files, BundleFormat.contentDigest(files));
        Map<String, Object> tree = ManifestJson.tree(manifest);
        afterManifest.accept(tree);
        List<Entry> out = new ArrayList<>();
        out.add(Entry.stored(BundleFormat.MANIFEST, ai.generativearchitects.glow400.emit.descriptor.Json.write(tree).getBytes(StandardCharsets.UTF_8)));
        sorted.forEach((path, bytes) -> out.add(Entry.stored(path, bytes)));
        return zip(out);
    }

    private static Path javaTemp() {
        try {
            return Files.createTempDirectory("glow400-bundle-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

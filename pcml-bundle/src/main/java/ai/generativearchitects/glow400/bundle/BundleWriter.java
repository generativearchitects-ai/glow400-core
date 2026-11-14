package ai.generativearchitects.glow400.bundle;

import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a repository's outputs into one bundle: a zip file with a manifest, the descriptor, the OpenAPI documents, and the
 * PCML and overlay files at their repository paths. The same inputs give the same bytes: entries in a fixed order, stored
 * without compression, with one constant modification time and nothing else in the zip, and no timestamp, host or user name
 * anywhere.
 */
public final class BundleWriter {

    /**
     * The modification time of every entry: the earliest time a zip file can hold, taken two seconds into 1980 because the JDK
     * writes the exact start of 1980 as an extra field.
     */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0, 2);
    private static final SourceLocation AT = new SourceLocation(BundleFormat.MANIFEST, 1, 1);

    private BundleWriter() {
    }

    /**
     * Writes a bundle.
     *
     * @param repositoryRoot the repository the model was loaded from; the PCML, overlay and gateway files are read from it
     * @param model          a model with no error from loading and validation
     * @param emitted        the files the emitters produced; {@code descriptor.json} must be one of them
     * @param info           the name and version of the bundle
     * @param out            where the zip file is written; nothing is written when there is an error
     * @return the manifest, or the {@code BDL006} diagnostics that stopped the writer
     * @throws UncheckedIOException if {@code out} cannot be written
     */
    public static BundleResult write(Path repositoryRoot, ApiRepositoryModel model, List<EmittedFile> emitted, BundleInfo info, OutputStream out) {
        return write(repositoryRoot, model, emitted, info, out, BundleFormat.generatorVersion());
    }

    /** Like the public method, with the version of the generator given, so that a test can write the same bytes in any build. */
    static BundleResult write(Path repositoryRoot, ApiRepositoryModel model, List<EmittedFile> emitted, BundleInfo info, OutputStream out,
                              String generatorVersion) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(emitted, "emitted");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(out, "out");
        List<Diagnostic> problems = new ArrayList<>();
        info.problems().forEach(message -> problems.add(error(message)));

        Map<String, byte[]> entries = new TreeMap<>();
        for (EmittedFile file : emitted) {
            String path = file.path();
            if (entries.containsKey(path)) {
                problems.add(error("Two emitted files have the path " + path));
            } else if (!check(path, problems)) {
                continue;
            } else if (path.equals(BundleFormat.MANIFEST)) {
                problems.add(error("An emitted file collides with " + BundleFormat.MANIFEST));
            } else {
                entries.put(path, file.content());
            }
        }
        if (!entries.containsKey(BundleFormat.DESCRIPTOR)) {
            problems.add(error("The emitted files do not include " + BundleFormat.DESCRIPTOR));
        }
        sources(repositoryRoot, model, entries, problems);
        if (entries.size() + 1 > BundleFormat.Limits.STANDARD.maxEntries()) {
            problems.add(error("The bundle would have more than " + BundleFormat.Limits.STANDARD.maxEntries() + " entries"));
        }
        long total = entries.values().stream().mapToLong(b -> b.length).sum();
        if (total > BundleFormat.Limits.STANDARD.maxTotalBytes()) {
            problems.add(error("The bundle would hold more than " + BundleFormat.Limits.STANDARD.maxTotalBytes() + " bytes"));
        }
        if (!problems.isEmpty()) {
            return new BundleResult(Optional.empty(), problems);
        }

        List<Manifest.FileEntry> files = new ArrayList<>();
        entries.forEach((path, bytes) -> files.add(new Manifest.FileEntry(path, bytes.length, BundleFormat.sha256(bytes))));
        List<Manifest.ApiEntry> apis = new ArrayList<>();
        model.apis().forEach(api -> apis.add(new Manifest.ApiEntry(api.name(), api.title(), api.version())));
        Manifest manifest = new Manifest(BundleFormat.SCHEMA_VERSION, info.name(), info.version(), "glow400-core", generatorVersion,
                BundleFormat.SCHEMA_VERSION, info.repository(), info.revision(), apis, files, BundleFormat.contentDigest(files));

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zip, StandardCharsets.UTF_8)) {
            entry(zos, BundleFormat.MANIFEST, Json.write(ManifestJson.tree(manifest)).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                entry(zos, e.getKey(), e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            out.write(zip.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new BundleResult(Optional.of(manifest), List.of());
    }

    /** Adds the PCML and overlay files of every API, and the gateway file when there is one. */
    private static void sources(Path root, ApiRepositoryModel model, Map<String, byte[]> entries, List<Diagnostic> problems) {
        Set<String> paths = new LinkedHashSet<>();
        for (ApiDefinition api : model.apis()) {
            paths.add(api.overlayPath());
            for (ApiOperation operation : api.operations()) {
                paths.add(operation.pcmlPath());
            }
        }
        Path real;
        try {
            real = root.toRealPath();
        } catch (IOException e) {
            problems.add(error("The repository root cannot be read"));
            return;
        }
        if (Files.isRegularFile(real.resolve(BundleFormat.GATEWAY))) {
            paths.add(BundleFormat.GATEWAY);
        }
        for (String path : paths) {
            if (!check(path, problems)) {
                continue;
            }
            if (entries.containsKey(path)) {
                problems.add(error("An emitted file collides with the source file " + path));
                continue;
            }
            try {
                Path file = real.resolve(path).toRealPath();
                if (!file.startsWith(real)) {
                    problems.add(error("The source file " + path + " is outside the repository"));
                } else if (!Files.isRegularFile(file) || Files.size(file) > BundleFormat.Limits.STANDARD.maxEntryBytes()) {
                    problems.add(error("The source file " + path + " is not a regular file of at most " + BundleFormat.Limits.STANDARD.maxEntryBytes() + " bytes"));
                } else {
                    entries.put(path, Files.readAllBytes(file));
                }
            } catch (IOException e) {
                problems.add(error("The source file " + path + " cannot be read"));
            }
        }
    }

    /** Whether a path can be an entry: safe, and in the layout. */
    private static boolean check(String path, List<Diagnostic> problems) {
        String unsafe = BundleFormat.unsafe(path);
        if (!unsafe.isEmpty()) {
            problems.add(error("The path " + path + " is not allowed in a bundle: " + unsafe));
            return false;
        }
        if (!BundleFormat.allowed(path)) {
            problems.add(error("The path " + path + " is not allowed by the layout of a bundle"));
            return false;
        }
        return true;
    }

    private static void entry(ZipOutputStream zos, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(EPOCH);
        zos.putNextEntry(entry);
        zos.write(bytes);
        zos.closeEntry();
    }

    private static Diagnostic error(String message) {
        return new Diagnostic("BDL006", Severity.ERROR, message, AT);
    }
}

package ai.generativearchitects.glow400.bundle;

import ai.generativearchitects.glow400.emit.descriptor.DescriptorReader;
import ai.generativearchitects.glow400.emit.descriptor.DescriptorResult;
import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a bundle, treating it as untrusted input. Nothing is extracted to the file system: the entries are read into memory
 * while the limits are checked on the bytes actually read, never on the sizes the zip declares. An entry is refused if its
 * name is absolute, has a {@code ..} segment, a backslash or an empty segment, is a directory, repeats another, is compressed,
 * or is not in the layout. Every size and SHA-256 is checked against the manifest, the manifest must list exactly the entries
 * present, the content digest must match, and the descriptor must agree with the manifest and with the files in the bundle.
 */
public final class BundleReader {

    private BundleReader() {
    }

    /** What went wrong in the zip itself, with the diagnostics found so far. */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super(null, null, false, false);
        }
    }

    /**
     * Reads and verifies a bundle.
     *
     * @return the bundle, or the diagnostics {@code BDL001} to {@code BDL005}; nothing is thrown for a bad file
     */
    public static BundleReadResult read(Path bundleFile) {
        return read(bundleFile, BundleFormat.Limits.STANDARD);
    }

    /** Like the public method, with the limits given, so that a test can break them with a small file. */
    static BundleReadResult read(Path bundleFile, BundleFormat.Limits limits) {
        Objects.requireNonNull(bundleFile, "bundleFile");
        String name = String.valueOf(bundleFile.getFileName());
        SourceLocation file = new SourceLocation(name, 1, 1);
        SourceLocation manifestAt = new SourceLocation(BundleFormat.MANIFEST, 1, 1);
        List<Diagnostic> found = new ArrayList<>();

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try {
            readEntries(bundleFile, file, entries, found, limits);
        } catch (Stop stop) {
            return failure(found);
        }
        if (!found.isEmpty()) {
            return failure(found);
        }
        if (!entries.containsKey(BundleFormat.MANIFEST)) {
            found.add(diagnostic("BDL003", "The bundle has no " + BundleFormat.MANIFEST, file));
            return failure(found);
        }
        if (!entries.containsKey(BundleFormat.DESCRIPTOR)) {
            found.add(diagnostic("BDL003", "The bundle has no " + BundleFormat.DESCRIPTOR, file));
            return failure(found);
        }

        Manifest manifest;
        try {
            manifest = ManifestJson.read(Json.read(new ByteArrayInputStream(entries.get(BundleFormat.MANIFEST))));
        } catch (ManifestJson.Invalid invalid) {
            found.add(diagnostic(invalid.code(), invalid.getMessage(), manifestAt));
            return failure(found);
        } catch (IOException e) {
            found.add(diagnostic("BDL003", "manifest.json is not valid JSON", manifestAt));
            return failure(found);
        }

        verifyListing(manifest, entries, manifestAt, found);
        if (!found.isEmpty()) {
            return failure(found);
        }
        verifyContent(manifest, entries, manifestAt, found);
        if (!found.isEmpty()) {
            return failure(found);
        }

        DescriptorResult descriptor = DescriptorReader.read(new ByteArrayInputStream(entries.get(BundleFormat.DESCRIPTOR)));
        if (descriptor.model().isEmpty()) {
            descriptor.diagnostics().forEach(d -> found.add(diagnostic("BDL005", "The descriptor is invalid: " + d.code() + " " + d.message(), manifestAt)));
            return failure(found);
        }
        ApiRepositoryModel model = descriptor.model().get();
        verifyDescriptor(manifest, model, entries, manifestAt, found);
        if (!found.isEmpty()) {
            return failure(found);
        }
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        ordered.put(BundleFormat.MANIFEST, entries.get(BundleFormat.MANIFEST));
        new TreeMap<>(entries).forEach((path, bytes) -> {
            if (!path.equals(BundleFormat.MANIFEST)) {
                ordered.put(path, bytes);
            }
        });
        return new BundleReadResult(Optional.of(new Bundle(manifest, model, ordered)), List.of());
    }

    // ------------------------------------------------------------------ the zip

    private static void readEntries(Path bundleFile, SourceLocation at, Map<String, byte[]> entries, List<Diagnostic> found,
                                    BundleFormat.Limits limits) {
        long fileSize;
        try {
            fileSize = Files.size(bundleFile);
        } catch (IOException e) {
            found.add(diagnostic("BDL001", "The file cannot be read", at));
            throw new Stop();
        }
        long total = 0;
        int count = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(bundleFile)); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > limits.maxEntries()) {
                    found.add(diagnostic("BDL003", "The bundle has more than " + limits.maxEntries() + " entries", at));
                    throw new Stop();
                }
                String path = entry.getName();
                byte[] content = read(zip, found, at, path, limits);
                total += content.length;
                if (total > limits.maxTotalBytes()) {
                    found.add(diagnostic("BDL003", "The entries hold more than " + limits.maxTotalBytes() + " bytes together", at));
                    throw new Stop();
                }
                String unsafe = BundleFormat.unsafe(path);
                if (!unsafe.isEmpty()) {
                    found.add(diagnostic("BDL003", "An entry has an unsafe name: " + unsafe, at));
                } else if (entry.getMethod() != ZipEntry.STORED) {
                    found.add(diagnostic("BDL003", "The entry " + path + " is compressed; entries must be stored", at));
                } else if (!BundleFormat.allowed(path)) {
                    found.add(diagnostic("BDL003", "The entry " + path + " is not allowed by the layout of a bundle", at));
                } else if (entries.putIfAbsent(path, content) != null) {
                    found.add(diagnostic("BDL003", "The entry " + path + " appears more than once", at));
                }
            }
        } catch (IOException e) {
            found.add(diagnostic("BDL001", "Not a readable zip file", at));
            throw new Stop();
        }
        if (count == 0 && !isEmptyZip(bundleFile, fileSize)) {
            found.add(diagnostic("BDL001", "Not a readable zip file", at));
            throw new Stop();
        }
    }

    /** An empty zip file is only its end record, which is 22 bytes. */
    private static boolean isEmptyZip(Path file, long size) {
        if (size != 22) {
            return false;
        }
        try {
            byte[] head = Files.readAllBytes(file);
            return head[0] == 'P' && head[1] == 'K' && head[2] == 5 && head[3] == 6;
        } catch (IOException e) {
            return false;
        }
    }

    /** The content of the current entry, counted as it is read: at most the limit for one entry. */
    private static byte[] read(ZipInputStream zip, List<Diagnostic> found, SourceLocation at, String path, BundleFormat.Limits limits)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        for (int n = zip.read(buffer); n >= 0; n = zip.read(buffer)) {
            size += n;
            if (size > limits.maxEntryBytes()) {
                found.add(diagnostic("BDL003", "The entry " + path + " holds more than " + limits.maxEntryBytes() + " bytes", at));
                throw new Stop();
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ the manifest against the entries

    private static void verifyListing(Manifest manifest, Map<String, byte[]> entries, SourceLocation at, List<Diagnostic> found) {
        Set<String> listed = new LinkedHashSet<>();
        String previous = null;
        boolean unsortedReported = false;
        for (Manifest.FileEntry file : manifest.files()) {
            if (!listed.add(file.path())) {
                found.add(diagnostic("BDL003", "The manifest lists " + file.path() + " more than once", at));
            } else if (previous != null && previous.compareTo(file.path()) > 0 && !unsortedReported) {
                found.add(diagnostic("BDL003", "The manifest does not list its files sorted by path", at));
                unsortedReported = true;
            }
            previous = file.path();
            if (file.path().equals(BundleFormat.MANIFEST)) {
                found.add(diagnostic("BDL003", "The manifest lists itself", at));
            } else if (!entries.containsKey(file.path())) {
                found.add(diagnostic("BDL003", "The manifest lists " + file.path() + ", which the bundle does not have", at));
            }
        }
        for (String path : entries.keySet()) {
            if (!path.equals(BundleFormat.MANIFEST) && !listed.contains(path)) {
                found.add(diagnostic("BDL003", "The bundle has " + path + ", which the manifest does not list", at));
            }
        }
    }

    private static void verifyContent(Manifest manifest, Map<String, byte[]> entries, SourceLocation at, List<Diagnostic> found) {
        for (Manifest.FileEntry file : manifest.files()) {
            byte[] bytes = entries.get(file.path());
            if (bytes.length != file.size()) {
                found.add(diagnostic("BDL004", "The size of " + file.path() + " is not the one in the manifest", at));
            }
            if (!BundleFormat.sha256(bytes).equals(file.sha256())) {
                found.add(diagnostic("BDL004", "The checksum of " + file.path() + " is not the one in the manifest", at));
            }
        }
        if (!BundleFormat.contentDigest(manifest.files()).equals(manifest.contentDigest())) {
            found.add(diagnostic("BDL004", "The content digest is not the one of the files in the manifest", at));
        }
    }

    // ------------------------------------------------------------------ the descriptor against the manifest and the files

    private static void verifyDescriptor(Manifest manifest, ApiRepositoryModel model, Map<String, byte[]> entries, SourceLocation at,
                                         List<Diagnostic> found) {
        List<Manifest.ApiEntry> described = new ArrayList<>();
        model.apis().forEach(api -> described.add(new Manifest.ApiEntry(api.name(), api.title(), api.version())));
        if (!described.equals(manifest.apis())) {
            found.add(diagnostic("BDL005", "The APIs of the descriptor are not the APIs listed in the manifest", at));
        }
        Set<String> missing = new LinkedHashSet<>();
        for (ApiDefinition api : model.apis()) {
            if (!entries.containsKey(api.overlayPath())) {
                missing.add(api.overlayPath());
            }
            for (ApiOperation operation : api.operations()) {
                if (!entries.containsKey(operation.pcmlPath())) {
                    missing.add(operation.pcmlPath());
                }
            }
        }
        missing.forEach(path -> found.add(diagnostic("BDL005", "The descriptor refers to " + path + ", which the bundle does not have", at)));
    }

    private static BundleReadResult failure(List<Diagnostic> found) {
        return new BundleReadResult(Optional.empty(), found);
    }

    private static Diagnostic diagnostic(String code, String message, SourceLocation at) {
        return new Diagnostic(code, Severity.ERROR, message, at);
    }
}

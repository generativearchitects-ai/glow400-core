package ai.generativearchitects.glow400.bundle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The manifest of a bundle, {@code manifest.json}.
 *
 * @param bundleSchemaVersion     the version of the bundle format
 * @param name                    the bundle name
 * @param version                 the bundle version label
 * @param generatorName           the program that wrote the bundle
 * @param generatorVersion        its version
 * @param descriptorSchemaVersion the schema version of {@code descriptor.json}
 * @param repository              the source repository, if known
 * @param revision                the source revision, if known
 * @param apis                    the APIs of the bundle, in descriptor order
 * @param files                   every entry except the manifest, sorted by path
 * @param contentDigest           {@code sha256:} and the digest of the files' paths and checksums
 */
public record Manifest(int bundleSchemaVersion, String name, String version, String generatorName, String generatorVersion,
                       int descriptorSchemaVersion, Optional<String> repository, Optional<String> revision, List<ApiEntry> apis,
                       List<FileEntry> files, String contentDigest) {

    /** An API of the bundle. */
    public record ApiEntry(String name, String title, String version) {

        /** @throws NullPointerException if an argument is {@code null} */
        public ApiEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(version, "version");
        }
    }

    /** A file of the bundle: its path, its size in bytes and the SHA-256 of its content in lower-case hexadecimal. */
    public record FileEntry(String path, long size, String sha256) {

        /** @throws NullPointerException if a text is {@code null} */
        public FileEntry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** @throws NullPointerException if an argument is {@code null} */
    public Manifest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(generatorName, "generatorName");
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(revision, "revision");
        apis = List.copyOf(apis);
        files = List.copyOf(files);
        Objects.requireNonNull(contentDigest, "contentDigest");
    }
}

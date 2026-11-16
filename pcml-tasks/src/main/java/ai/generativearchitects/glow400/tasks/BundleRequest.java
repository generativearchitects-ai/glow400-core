package ai.generativearchitects.glow400.tasks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link Tasks#bundle} is asked for.
 *
 * @param name             the name of the bundle, written to its manifest
 * @param version          the version of the bundle, written to its manifest
 * @param target           the bundle file to write
 * @param sourceRepository where the repository comes from, if it is to be recorded in the manifest
 * @param sourceRevision   the revision of the repository, if it is to be recorded in the manifest
 */
public record BundleRequest(String name, String version, Path target, Optional<String> sourceRepository, Optional<String> sourceRevision) {

    /** @throws NullPointerException if an argument is {@code null} */
    public BundleRequest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceRepository, "sourceRepository");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
    }
}

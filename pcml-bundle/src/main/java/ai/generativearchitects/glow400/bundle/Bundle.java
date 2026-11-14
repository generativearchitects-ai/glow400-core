package ai.generativearchitects.glow400.bundle;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A bundle that has been read and verified: its manifest, the model its descriptor describes, and every file in memory.
 * Nothing is extracted to the file system.
 */
public final class Bundle {

    private final Manifest manifest;
    private final ApiRepositoryModel model;
    private final Map<String, byte[]> files;

    Bundle(Manifest manifest, ApiRepositoryModel model, Map<String, byte[]> files) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.model = Objects.requireNonNull(model, "model");
        this.files = new LinkedHashMap<>(files);
    }

    /** The manifest. */
    public Manifest manifest() {
        return manifest;
    }

    /** The model read from {@code descriptor.json}. */
    public ApiRepositoryModel model() {
        return model;
    }

    /** The paths of every entry, {@code manifest.json} first, then the others sorted. */
    public List<String> paths() {
        return List.copyOf(files.keySet());
    }

    /** A copy of the bytes of an entry, or empty if the bundle has none by that path. */
    public Optional<byte[]> file(String path) {
        byte[] bytes = files.get(path);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }
}

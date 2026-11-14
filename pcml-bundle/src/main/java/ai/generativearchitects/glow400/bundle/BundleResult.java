package ai.generativearchitects.glow400.bundle;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link BundleWriter} did: the manifest of the bundle it wrote, or the diagnostics ({@code BDL006}) that stopped it.
 *
 * @param manifest    the manifest; empty when nothing was written
 * @param diagnostics what stopped the writer
 */
public record BundleResult(Optional<Manifest> manifest, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public BundleResult {
        Objects.requireNonNull(manifest, "manifest");
        diagnostics = List.copyOf(diagnostics);
    }
}

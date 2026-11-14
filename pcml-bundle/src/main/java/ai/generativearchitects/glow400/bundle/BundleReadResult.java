package ai.generativearchitects.glow400.bundle;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link BundleReader} made of a file: the verified bundle, or the diagnostics ({@code BDL001} to {@code BDL005}).
 *
 * @param bundle      the bundle; empty when there is a diagnostic
 * @param diagnostics what is wrong with the file
 */
public record BundleReadResult(Optional<Bundle> bundle, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public BundleReadResult {
        Objects.requireNonNull(bundle, "bundle");
        diagnostics = List.copyOf(diagnostics);
    }
}

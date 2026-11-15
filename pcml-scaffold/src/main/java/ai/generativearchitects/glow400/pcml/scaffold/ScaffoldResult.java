package ai.generativearchitects.glow400.pcml.scaffold;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link OverlayScaffold#create}.
 *
 * @param text        the complete file; empty if no valid overlay can be written, in which case {@code diagnostics} says why
 * @param diagnostics what prevents a text, or nothing
 */
public record ScaffoldResult(Optional<String> text, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ScaffoldResult {
        Objects.requireNonNull(text, "text");
        diagnostics = List.copyOf(diagnostics);
    }
}

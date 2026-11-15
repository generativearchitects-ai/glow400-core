package ai.generativearchitects.glow400.pcml.scaffold;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link OverlayEditor#apply}.
 *
 * @param text        the new text: the old text, unchanged, when no edit could be applied; empty if the safety check
 *                    refused the edited file ({@code SCF002}) or the file cannot be read, so that there is no partial result
 * @param applied     the edits that were applied, in the order given; empty when {@code text} is empty
 * @param diagnostics {@code SCF001} for each edit that was left out, and {@code SCF002} for a refusal
 */
public record EditResult(Optional<String> text, List<ParameterEdit> applied, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public EditResult {
        Objects.requireNonNull(text, "text");
        applied = List.copyOf(applied);
        diagnostics = List.copyOf(diagnostics);
    }
}

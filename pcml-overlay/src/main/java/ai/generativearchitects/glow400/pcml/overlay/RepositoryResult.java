package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of loading a customer repository.
 *
 * <p>The model is present whenever at least {@code gateway.yaml} could be interpreted (an absent file counts as
 * interpreted). Operations with errors are left out of it and reported in the diagnostics.
 *
 * @param model       the API model, if the repository could be read
 * @param diagnostics findings, ordered by file, line and column
 */
public record RepositoryResult(Optional<ApiRepositoryModel> model, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public RepositoryResult {
        Objects.requireNonNull(model, "model");
        diagnostics = List.copyOf(diagnostics);
    }

    /** Returns {@code true} if any diagnostic has severity {@link Severity#ERROR}. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}

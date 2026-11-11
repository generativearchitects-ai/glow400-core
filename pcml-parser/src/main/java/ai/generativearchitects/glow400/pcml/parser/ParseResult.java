package ai.generativearchitects.glow400.pcml.parser;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of parsing one PCML source.
 *
 * <p>A document is present whenever the XML is well-formed and its root element is {@code pcml},
 * even if diagnostics of severity {@link Severity#ERROR} were reported; in that case the document
 * holds everything that could be modelled.
 *
 * @param document    the call model, if the source could be read as PCML
 * @param diagnostics findings ordered by source position
 */
public record ParseResult(Optional<PcmlDocument> document, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ParseResult {
        Objects.requireNonNull(document, "document");
        diagnostics = List.copyOf(diagnostics);
    }

    /** Returns {@code true} if any diagnostic has severity {@link Severity#ERROR}. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}

package ai.generativearchitects.glow400.mock;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link MockReader} made of a document: the file when the document is valid, and the {@code MCK001} diagnostics when it
 * is not.
 *
 * @param file        the file; empty when there is a diagnostic
 * @param diagnostics what is wrong with the document
 */
public record MockReadResult(Optional<MockFile> file, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public MockReadResult {
        Objects.requireNonNull(file, "file");
        diagnostics = List.copyOf(diagnostics);
    }
}

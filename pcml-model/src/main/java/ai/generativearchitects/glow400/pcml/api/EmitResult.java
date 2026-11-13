package ai.generativearchitects.glow400.pcml.api;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;

/**
 * What an {@link Emitter} produced.
 *
 * @param files       the files, in a fixed order
 * @param diagnostics problems found while emitting
 */
public record EmitResult(List<EmittedFile> files, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public EmitResult {
        files = List.copyOf(files);
        diagnostics = List.copyOf(diagnostics);
    }
}

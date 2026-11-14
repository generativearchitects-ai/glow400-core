package ai.generativearchitects.glow400.diff;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;

/**
 * The comparison of a released repository with the current one.
 *
 * @param apis        one change set per API that is in both, in the order of the current repository
 * @param diagnostics {@code API002} to {@code API006}, ordered by file, line, column and code
 */
public record DiffResult(List<ApiChangeSet> apis, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public DiffResult {
        apis = List.copyOf(apis);
        diagnostics = List.copyOf(diagnostics);
    }
}

package ai.generativearchitects.slay400.tasks;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a task came to.
 *
 * @param diagnostics everything found, in the order found: load and validation first, then what the task itself found
 * @param written     the files the task wrote, as absolute paths; empty when it wrote nothing
 */
public record TaskResult(List<Diagnostic> diagnostics, List<Path> written) {

    /** @throws NullPointerException if an argument is {@code null} */
    public TaskResult {
        diagnostics = List.copyOf(diagnostics);
        written = List.copyOf(written);
    }

    /** Whether any diagnostic is an error. */
    public boolean hasError() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    /** Whether any diagnostic is at or above a severity: {@code ERROR} is the highest, then {@code WARNING}, then {@code INFO}. */
    public boolean has(Severity lowest) {
        Objects.requireNonNull(lowest, "lowest");
        return diagnostics.stream().anyMatch(d -> d.severity().ordinal() <= lowest.ordinal());
    }
}

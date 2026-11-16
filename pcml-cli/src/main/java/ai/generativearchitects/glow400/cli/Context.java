package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.InputStream;
import ai.generativearchitects.glow400.tasks.Tasks;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What a command works with: the options, the streams, the repository and the diagnostics found so far. */
final class Context {

    final Options options;
    final Path workingDirectory;
    final InputStream in;
    final PrintStream out;
    final PrintStream err;
    final Path repo;
    final List<Diagnostic> diagnostics = new ArrayList<>();
    /** The pipeline tasks, showing paths relative to the working directory. */
    final Tasks tasks;

    Context(Options options, Path workingDirectory, InputStream in, PrintStream out, PrintStream err) {
        this.options = options;
        this.workingDirectory = workingDirectory;
        this.in = in;
        this.out = out;
        this.err = err;
        this.repo = workingDirectory.resolve(options.value("--repo").orElse(".")).toAbsolutePath().normalize();
        this.tasks = new Tasks(workingDirectory);
    }

    boolean hasError() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    void error(String code, String message, SourceLocation at) {
        diagnostics.add(new Diagnostic(code, Severity.ERROR, message, at));
    }

    /** A warning that is about a path: it points at that path, as shown to the person, at 1:1. */
    void warning(String code, String message, Path path) {
        diagnostics.add(new Diagnostic(code, Severity.WARNING, message, new SourceLocation(shown(path), 1, 1)));
    }

    /** An error that is about a path: it points at that path, as shown to the person, at 1:1. */
    void error(String code, String message, Path path) {
        error(code, message, new SourceLocation(shown(path), 1, 1));
    }

    /** The path of a file as shown to the person: relative to the working directory when it is under it. */
    String shown(Path path) {
        return tasks.shown(path);
    }
}

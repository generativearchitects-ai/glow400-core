package ai.generativearchitects.glow400.report;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a command found, ready to be written in any of the three formats.
 *
 * @param command     the command that made the report, such as {@code validate}
 * @param base        the repository root: a file under it is written relative to it
 * @param diagnostics the diagnostics, in any order; every format sorts them
 */
public record Report(String command, Path base, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public Report {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(base, "base");
        diagnostics = List.copyOf(diagnostics);
    }

    /** The diagnostics in the order of the formats: by the file as shown, line, column, then code. */
    public List<Diagnostic> sorted() {
        return diagnostics.stream().sorted(Comparator.comparing((Diagnostic d) -> display(d.location().file()))
                .thenComparing(d -> d.location().line()).thenComparing(d -> d.location().column()).thenComparing(Diagnostic::code)).toList();
    }

    /** How many diagnostics have a severity. */
    long count(Severity severity) {
        return diagnostics.stream().filter(d -> d.severity() == severity).count();
    }

    /**
     * The path of a file for output: relative to the base with {@code /} separators when it is under it, and as given
     * otherwise. A path that is already relative is taken to be relative to the base.
     */
    String display(String file) {
        return relative(file).orElse(file);
    }

    /** The path relative to the base when the file is under it. */
    java.util.Optional<String> relative(String file) {
        Path path;
        try {
            path = Path.of(file);
        } catch (java.nio.file.InvalidPathException e) {
            return java.util.Optional.empty();
        }
        if (!path.isAbsolute()) {
            return java.util.Optional.of(file.replace(java.io.File.separatorChar, '/'));
        }
        Path root = base.toAbsolutePath().normalize();
        Path normal = path.normalize();
        if (!normal.startsWith(root) || normal.equals(root)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(root.relativize(normal).toString().replace(java.io.File.separatorChar, '/'));
    }
}

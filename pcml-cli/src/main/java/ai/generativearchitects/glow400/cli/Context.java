package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmitResult;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import ai.generativearchitects.glow400.pcml.validation.Validator;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/** What a command works with: the options, the streams, the repository and the diagnostics found so far. */
final class Context {

    /** Where a diagnostic about {@code gateway.yaml} goes when it has no better position. */
    static final SourceLocation GATEWAY = new SourceLocation("gateway.yaml", 1, 1);

    final Options options;
    final Path workingDirectory;
    final InputStream in;
    final PrintStream out;
    final PrintStream err;
    final Path repo;
    final List<Diagnostic> diagnostics = new ArrayList<>();
    ApiRepositoryModel model;

    Context(Options options, Path workingDirectory, InputStream in, PrintStream out, PrintStream err) {
        this.options = options;
        this.workingDirectory = workingDirectory;
        this.in = in;
        this.out = out;
        this.err = err;
        this.repo = workingDirectory.resolve(options.value("--repo").orElse(".")).toAbsolutePath().normalize();
    }

    /** Loads the repository and validates it; false when there is an error, and the command must stop. */
    boolean loadAndValidate() {
        RepositoryResult loaded = OverlayLoader.loadRepository(repo);
        diagnostics.addAll(loaded.diagnostics());
        if (loaded.model().isPresent()) {
            model = loaded.model().get();
            diagnostics.addAll(Validator.validate(model));
        }
        return !hasError();
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

    /** The emitters that exist, by name. The first one found with a name is the one used. */
    static Map<String, Emitter> emitters() {
        Map<String, Emitter> found = new HashMap<>();
        for (Emitter emitter : ServiceLoader.load(Emitter.class, Context.class.getClassLoader())) {
            found.putIfAbsent(emitter.name(), emitter);
        }
        return found;
    }

    /**
     * Runs the emitters with these names, each once, in this order. Their diagnostics are kept and their files returned; empty if
     * an emitter reports an error.
     */
    Optional<List<EmittedFile>> emit(List<String> names, Map<String, Emitter> available) {
        List<EmittedFile> files = new ArrayList<>();
        for (String name : names) {
            EmitResult result = Objects.requireNonNull(available.get(name), "The emitter " + name + " is not available").emit(model);
            diagnostics.addAll(result.diagnostics());
            files.addAll(result.files());
        }
        return hasError() ? Optional.empty() : Optional.of(files);
    }

    /** The path of a file as shown to the person: relative to the working directory when it is under it. */
    String shown(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path base = workingDirectory.toAbsolutePath().normalize();
        String text = absolute.startsWith(base) && !absolute.equals(base) ? base.relativize(absolute).toString() : absolute.toString();
        return ai.generativearchitects.glow400.report.TextReport.escape(text.replace(java.io.File.separatorChar, '/'));
    }
}

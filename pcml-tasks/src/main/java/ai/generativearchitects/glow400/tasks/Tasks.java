package ai.generativearchitects.glow400.tasks;

import ai.generativearchitects.glow400.bundle.BundleInfo;
import ai.generativearchitects.glow400.bundle.BundleReadResult;
import ai.generativearchitects.glow400.bundle.BundleReader;
import ai.generativearchitects.glow400.bundle.BundleResult;
import ai.generativearchitects.glow400.bundle.BundleWriter;
import ai.generativearchitects.glow400.diff.ApiDiff;
import ai.generativearchitects.glow400.diff.DiffResult;
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
import ai.generativearchitects.glow400.report.TextReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The four pipeline tasks. A task loads and validates the repository first; if that finds an error it does nothing else, and
 * a task that fails writes nothing, except that a write that fails half way leaves the files already written.
 *
 * <p>Paths in messages, and in the locations of {@code CLI001} to {@code CLI003}, are shown relative to a base directory
 * when they are inside it: the working directory of the command line, the base directory of a Maven project. Everything
 * shown is escaped as in the text report.
 */
public final class Tasks {

    private static final List<String> BUNDLE_EMITTERS = List.of("descriptor", "openapi");
    private static final String MOCK = "mock";

    /** Where a diagnostic about {@code gateway.yaml} goes when it has no better position. */
    private static final SourceLocation GATEWAY = new SourceLocation("gateway.yaml", 1, 1);

    private final Path base;

    /** @param baseDirectory what paths are shown relative to */
    public Tasks(Path baseDirectory) {
        this.base = Objects.requireNonNull(baseDirectory, "baseDirectory").toAbsolutePath().normalize();
    }

    /** The path of a file as shown to the person: relative to the base directory when it is under it, escaped. */
    public String shown(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String text = absolute.startsWith(base) && !absolute.equals(base) ? base.relativize(absolute).toString() : absolute.toString();
        return TextReport.escape(text.replace(java.io.File.separatorChar, '/'));
    }

    // ------------------------------------------------------------------ the state of one run

    /** The diagnostics and the model of one task while it runs. */
    private final class Run {
        final Path repository;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<Path> written = new ArrayList<>();
        ApiRepositoryModel model;

        Run(Path repository) {
            this.repository = repository.toAbsolutePath().normalize();
        }

        /** Loads the repository and validates it; false when there is an error, and the task must stop. */
        boolean loadAndValidate() {
            RepositoryResult loaded = OverlayLoader.loadRepository(repository);
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

        /** An error that is about a path: it points at that path, as shown to the person, at 1:1. */
        void error(String code, String message, Path path) {
            error(code, message, new SourceLocation(shown(path), 1, 1));
        }

        /**
         * Runs the emitters with these names, each once, in this order. Their diagnostics are kept and their files returned;
         * empty if an emitter reports an error.
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

        TaskResult result() {
            return new TaskResult(diagnostics, written);
        }
    }

    /** The emitters that exist, by name. The first one found with a name is the one used. */
    private static Map<String, Emitter> emitters() {
        Map<String, Emitter> found = new HashMap<>();
        for (Emitter emitter : ServiceLoader.load(Emitter.class, Tasks.class.getClassLoader())) {
            found.putIfAbsent(emitter.name(), emitter);
        }
        return found;
    }

    // ------------------------------------------------------------------ validate

    /** Loads the repository and validates it. Writes nothing. */
    public TaskResult validate(Path repository) {
        Run run = new Run(repository);
        run.loadAndValidate();
        return run.result();
    }

    // ------------------------------------------------------------------ generate

    /**
     * Runs the emitters named by {@code gateway.yaml} (default: descriptor, openapi and mock) and writes their files under
     * one folder. Nothing is written if there is an error. Existing files are overwritten; nothing is deleted.
     *
     * @param outputDirectory not the repository root, not inside {@code apis/}, and a folder or a path that can become one
     * @throws IOException if the file system cannot even be asked where the repository is (not a finding about the repository)
     */
    public TaskResult generate(Path repository, Path outputDirectory) throws IOException {
        Run run = new Run(repository);
        if (!run.loadAndValidate()) {
            return run.result();
        }
        List<String> names = new ArrayList<>(new LinkedHashSet<>(run.model.defaults().emitters()));
        Map<String, Emitter> available = emitters();
        for (String name : names) {
            if (!available.containsKey(name)) {
                List<String> all = run.model.defaults().emitters();
                List<SourceLocation> at = run.model.defaults().emitterSources();
                SourceLocation where = at.isEmpty() ? GATEWAY : at.get(all.indexOf(name));
                run.error("CLI001", "The emitter '" + name + "' named in gateway.yaml is not available", where);
            }
        }
        if (run.hasError()) {
            return run.result();
        }
        Optional<List<EmittedFile>> emitted = run.emit(names, available);
        if (emitted.isEmpty()) {
            return run.result();
        }
        Path out = outputDirectory.toAbsolutePath().normalize();
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (EmittedFile file : emitted.get()) {
            if (files.putIfAbsent(file.path(), file.content()) != null) {
                run.error("CLI002", "Two emitted files have the path " + file.path(), out.resolve(file.path()));
            }
        }
        checkOutputFolder(run, out);
        if (run.hasError()) {
            return run.result();
        }
        Path physicalOut = Writing.physical(out);
        for (String path : files.keySet()) {
            if (!inside(out.resolve(path), physicalOut)) {
                run.error("CLI002", "The path " + path + " leads out of the output folder", out.resolve(path));
            }
        }
        if (run.hasError()) {
            return run.result();
        }
        write(run, out, files);
        return run.result();
    }

    /** Whether a file would be written inside the output folder, through any symbolic links on the way. */
    private static boolean inside(Path file, Path physicalOut) {
        try {
            return Writing.physical(file).startsWith(physicalOut);
        } catch (IOException e) {
            return false; // a link that leads nowhere
        }
    }

    /** The output folder cannot be the repository root, nor inside {@code apis/}, nor a file. */
    private void checkOutputFolder(Run run, Path out) throws IOException {
        Path root = Writing.physical(run.repository);
        String shown = shown(out);
        Path target;
        try {
            target = Writing.physical(out);
        } catch (IOException e) {
            run.error("CLI003", "The output folder " + shown + " cannot be written: " + Writing.reason(e), out);
            return;
        }
        if (target.equals(root)) {
            run.error("CLI002", "The output folder " + shown + " is the repository root", out);
            return;
        }
        if (target.startsWith(root.resolve("apis"))) {
            run.error("CLI002", "The output folder " + shown + " is inside apis/", out);
            return;
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            run.error("CLI003", "The output folder " + shown + " cannot be written: not a folder", out);
        }
    }

    private void write(Run run, Path out, Map<String, byte[]> files) {
        Set<Path> written = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = out.resolve(file.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
                written.add(target);
            } catch (IOException e) {
                run.error("CLI003", "The file " + shown(target) + " could not be written: " + Writing.reason(e), target);
                run.written.addAll(written);
                return;
            }
        }
        run.written.addAll(written);
    }

    // ------------------------------------------------------------------ bundle

    /**
     * Runs the descriptor and OpenAPI emitters, and the mock emitter when the repository names it, and packs their files with
     * the PCML and overlay files into one bundle. The file is written to a temporary file and moved into place, so a failed
     * run leaves no partial bundle.
     */
    public TaskResult bundle(Path repository, BundleRequest request) {
        Objects.requireNonNull(request, "request");
        Run run = new Run(repository);
        if (!run.loadAndValidate()) {
            return run.result();
        }
        // the emitters are on the class path; if they are not, the packaging is broken and the task fails as an unexpected failure
        Map<String, Emitter> available = emitters();
        List<String> names = new ArrayList<>(BUNDLE_EMITTERS);
        if (run.model.defaults().emitters().contains(MOCK)) {
            names.add(MOCK);
        }
        Optional<List<EmittedFile>> emitted = run.emit(names, available);
        if (emitted.isEmpty()) {
            return run.result();
        }
        BundleInfo info = new BundleInfo(request.name(), request.version(), request.sourceRepository(), request.sourceRevision());
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        BundleResult result = BundleWriter.write(run.repository, run.model, emitted.get(), info, bundle);
        run.diagnostics.addAll(result.diagnostics());
        if (result.manifest().isEmpty()) {
            return run.result();
        }
        Path target = request.target().toAbsolutePath().normalize();
        writeBundle(run, target, bundle.toByteArray());
        return run.result();
    }

    private void writeBundle(Run run, Path target, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".bundle-", ".tmp");
            Files.write(temporary, bytes);
            Writing.move(temporary, target);
            run.written.add(target);
        } catch (IOException e) {
            run.error("CLI003", "The file " + shown(target) + " could not be written: " + Writing.reason(e), target);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // the bundle is not there either way; nothing more can be done about a temporary file
                }
            }
        }
    }

    // ------------------------------------------------------------------ diff

    /** Compares the repository with a released bundle and reports what changed for the consumers of each API. Writes nothing. */
    public TaskResult diff(Path repository, Path releasedBundle) {
        Run run = new Run(repository);
        if (!run.loadAndValidate()) {
            return run.result();
        }
        BundleReadResult released = BundleReader.read(releasedBundle.toAbsolutePath().normalize());
        run.diagnostics.addAll(released.diagnostics());
        if (released.bundle().isEmpty()) {
            return run.result();
        }
        DiffResult result = ApiDiff.compare(released.bundle().get().model(), run.model);
        run.diagnostics.addAll(result.diagnostics());
        return run.result();
    }
}

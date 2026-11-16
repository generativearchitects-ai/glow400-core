package ai.generativearchitects.slay400.cli;

import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.overlay.AnnotationTarget;
import ai.generativearchitects.slay400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.slay400.pcml.overlay.OverlayText;
import ai.generativearchitects.slay400.pcml.overlay.UndirectedParameter;
import ai.generativearchitects.slay400.pcml.scaffold.EditResult;
import ai.generativearchitects.slay400.pcml.scaffold.OverlayEditor;
import ai.generativearchitects.slay400.pcml.scaffold.ParameterEdit;
import ai.generativearchitects.slay400.pcml.validation.Validator;
import ai.generativearchitects.slay400.report.TextReport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code annotate}: asks, for every {@code inputoutput} parameter that has no direction, which way it goes, and writes the
 * answers into {@code api.yaml} without disturbing the rest of the file. The questions go to the error stream, the answers
 * are read one line at a time from the input.
 */
final class AnnotateCommand implements Command {

    private static final List<Direction> DIRECTIONS = List.of(Direction.REQUEST, Direction.RESPONSE, Direction.BOTH, Direction.INTERNAL);

    /** The end of the input has been reached: no more questions. */
    private static final class EndOfInput extends Exception {
        private static final long serialVersionUID = 1L;

        EndOfInput() {
            super(null, null, false, false);
        }
    }

    @Override
    public void run(Context context) throws IOException, UsageException {
        boolean dryRun = context.options.flag("--dry-run");
        Optional<String> api = context.options.value("--api");
        if (api.isPresent() && !hasFolder(context, api.get())) {
            throw new UsageException("There is no folder '" + Options.shown(api.get()) + "' under apis/");
        }

        OverlayLoader.Annotation loaded = OverlayLoader.loadRepositoryForAnnotation(context.repo);
        List<Diagnostic> state = new ArrayList<>(loaded.result().diagnostics());
        loaded.result().model().ifPresent(model -> state.addAll(Validator.validate(model)));
        if (state.stream().anyMatch(d -> d.severity() == Severity.ERROR && !d.code().equals("OVL001"))) {
            context.diagnostics.addAll(state); // the repository has a problem that no answer can fix: stop before the first question
            return;
        }

        List<AnnotationTarget> targets = loaded.targets().stream().filter(t -> api.isEmpty() || folderOf(t).equalsIgnoreCase(api.get())).toList();
        if (targets.isEmpty()) {
            context.err.println("nothing to annotate");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(context.in, StandardCharsets.UTF_8));
        boolean ended = false;
        for (AnnotationTarget target : targets) {
            if (ended) {
                break;
            }
            Path file = context.repo.resolve(target.overlayPath());
            byte[] original;
            String text;
            try {
                original = Files.readAllBytes(file);
                text = new String(original, StandardCharsets.UTF_8);
            } catch (IOException e) {
                context.error("CLI003", "The file " + context.shown(file) + " could not be read: " + Writing.reason(e), file);
                return;
            }
            List<ParameterEdit> edits = new ArrayList<>();
            ended = ask(context, in, target, text, edits);
            if (edits.isEmpty()) {
                continue;
            }
            EditResult result = applyEach(text, target, edits);
            context.diagnostics.addAll(result.diagnostics());
            if (!dryRun && !result.applied().isEmpty() && result.text().isPresent() && changedSince(context, file, original)) {
                context.warning("CLI005", "The file " + context.shown(file)
                        + " was changed while the questions were asked, so nothing was written to it", file);
                continue;
            }
            boolean written = !result.applied().isEmpty() && result.text().isPresent();
            if (written && !dryRun && !write(context, file, result.text().get())) {
                return; // the lines of what was not written are not printed
            }
            for (ParameterEdit applied : result.applied()) {
                context.err.println((dryRun ? "would set " : "") + progress(target, applied));
            }
        }
        context.diagnostics.addAll(context.tasks.validate(context.repo).diagnostics());
    }

    /** Asks the questions of one file; the edits are added in order. Returns whether the input has ended. */
    private static boolean ask(Context context, BufferedReader in, AnnotationTarget target, String text, List<ParameterEdit> edits)
            throws IOException {
        OverlayText.Loaded current = OverlayText.load(target.overlayPath(), text, target.pcml(), target.defaults());
        for (UndirectedParameter parameter : target.undirected()) {
            FieldDef field = parameter.field();
            String exposed = current.operations().stream().filter(o -> o.id().equals(parameter.operationId()))
                    .flatMap(o -> o.bindings().stream()).filter(b -> b.fieldPath().equals(field.name())).findFirst()
                    .map(b -> b.exposedName()).orElse(OverlayText.exposedName(field.name()));
            context.err.println(label(parameter) + "  " + Prompts.describe(field) + "  " + usage(field));
            Optional<Direction> direction = Optional.empty();
            Optional<SemanticType> type = Optional.empty();
            Optional<String> name = Optional.empty();
            try {
                direction = direction(context, in);
                if (direction.isEmpty()) {
                    continue; // skipped
                }
                if (direction.get() != Direction.INTERNAL) {
                    type = type(context, in, field);
                    name = name(context, in, exposed);
                }
            } catch (EndOfInput e) {
                context.err.println();
                if (direction.isPresent()) {
                    edits.add(new ParameterEdit(parameter.operationId(), field.name(), direction, type, name));
                }
                return true;
            }
            edits.add(new ParameterEdit(parameter.operationId(), field.name(), direction, type, name));
        }
        return false;
    }

    private static Optional<Direction> direction(Context context, BufferedReader in) throws IOException, EndOfInput {
        while (true) {
            String answer = read(context, in, "  direction [request/response/both/internal, Enter to skip]: ");
            if (answer.isBlank()) {
                return Optional.empty();
            }
            Optional<Direction> chosen = Prompts.choose(answer, DIRECTIONS, d -> d.name().toLowerCase(Locale.ROOT));
            if (chosen.isPresent()) {
                return chosen;
            }
            context.err.println("  Please answer request, response, both or internal, or the beginning of one of them.");
        }
    }

    private static Optional<SemanticType> type(Context context, BufferedReader in, FieldDef field) throws IOException, EndOfInput {
        List<SemanticType> allowed = OverlayEditor.allowedTypes(field);
        Optional<SemanticType> standard = OverlayText.defaultType(field.wire());
        if (standard.isEmpty() || allowed.stream().noneMatch(t -> !standard.equals(Optional.of(t)))) {
            return Optional.empty(); // a struct takes no type, and there is nothing to choose when only the default is possible
        }
        String choices = String.join("/", allowed.stream().map(t -> t.name().toLowerCase(Locale.ROOT)).toList());
        String prompt = "  type [" + choices + ", Enter for " + standard.get().name().toLowerCase(Locale.ROOT) + "]: ";
        while (true) {
            String answer = read(context, in, prompt);
            if (answer.isBlank()) {
                return Optional.empty();
            }
            Optional<SemanticType> chosen = Prompts.choose(answer, allowed, t -> t.name().toLowerCase(Locale.ROOT));
            if (chosen.isPresent()) {
                return chosen.equals(standard) ? Optional.empty() : chosen;
            }
            context.err.println("  Please answer " + choices.replace("/", ", ") + ", or the beginning of one of them.");
        }
    }

    private static Optional<String> name(Context context, BufferedReader in, String current) throws IOException, EndOfInput {
        String prompt = "  name [Enter for " + TextReport.escape(current) + "]: ";
        while (true) {
            String answer = read(context, in, prompt).strip();
            if (answer.isEmpty()) {
                return Optional.empty();
            }
            if (OverlayText.isValidExposedName(answer)) {
                return answer.equals(current) ? Optional.empty() : Optional.of(answer);
            }
            context.err.println("  A name must not be blank or have control characters.");
        }
    }

    /** Shows the question and reads the answer, one line. */
    private static String read(Context context, BufferedReader in, String prompt) throws IOException, EndOfInput {
        context.err.print(prompt);
        context.err.flush();
        String line = in.readLine();
        if (line == null) {
            throw new EndOfInput();
        }
        return line;
    }

    private static String label(UndirectedParameter parameter) {
        return TextReport.escape(parameter.programName()) + "." + TextReport.escape(parameter.field().name());
    }

    private static String usage(FieldDef field) {
        return field.usage() == Usage.INPUT_OUTPUT ? "inputoutput" : "inherit";
    }

    /** {@code ORDSTATUS.P0ORDN  inputoutput -> request, integer}: the type only when it was set. */
    private static String progress(AnnotationTarget target, ParameterEdit edit) {
        UndirectedParameter parameter = target.undirected().stream()
                .filter(u -> u.operationId().equals(edit.operationId()) && u.field().name().equals(edit.parameter())).findFirst().orElseThrow();
        return label(parameter) + "  " + usage(parameter.field()) + " -> "
                + edit.direction().orElseThrow().name().toLowerCase(Locale.ROOT)
                + edit.type().map(t -> ", " + t.name().toLowerCase(Locale.ROOT)).orElse("");
    }

    /**
     * The edits of a file go to the editor together. If the safety check refuses the result, they are applied one at a time, in
     * the order they were answered, and each one the check accepts is kept; every refused edit is reported (SCF002) with its
     * operation and its parameter, so that one bad answer does not discard the good ones.
     */
    private static EditResult applyEach(String text, AnnotationTarget target, List<ParameterEdit> edits) {
        EditResult all = OverlayEditor.apply(text, target.overlayPath(), target.pcml(), edits);
        if (all.text().isPresent()) {
            return all;
        }
        String current = text;
        List<ParameterEdit> applied = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ParameterEdit edit : edits) {
            EditResult one = OverlayEditor.apply(current, target.overlayPath(), target.pcml(), List.of(edit));
            if (one.text().isPresent()) {
                current = one.text().get();
                applied.addAll(one.applied());
                diagnostics.addAll(one.diagnostics());
                continue;
            }
            for (Diagnostic d : one.diagnostics()) {
                diagnostics.add(d.code().equals("SCF002")
                        ? new Diagnostic("SCF002", Severity.ERROR, "Operation '" + edit.operationId() + "', parameter '" + edit.parameter()
                        + "' was not edited: the edited file was refused" + d.message().substring(d.message().indexOf(':')), d.location())
                        : d);
            }
        }
        return new EditResult(Optional.of(current), applied, diagnostics);
    }

    /** Whether the file is no longer what the questions were based on. A file that cannot be read counts as changed. */
    private static boolean changedSince(Context context, Path file, byte[] original) {
        try {
            return !java.util.Arrays.equals(Files.readAllBytes(file), original);
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean write(Context context, Path file, String text) {
        try {
            Path real = Writing.physical(file);
            if (!real.startsWith(Writing.physical(context.repo))) {
                context.error("CLI003", "The file " + context.shown(file) + " could not be written: it leads out of the repository", file);
                return false;
            }
            Writing.replace(real, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            context.error("CLI003", "The file " + context.shown(file) + " could not be written: " + Writing.reason(e), file);
            return false;
        }
    }

    private static String folderOf(AnnotationTarget target) {
        String[] parts = target.overlayPath().split("/");
        return parts[1];
    }

    private static boolean hasFolder(Context context, String name) {
        Path apis = context.repo.resolve("apis");
        if (!Files.isDirectory(apis)) {
            return false;
        }
        try (Stream<Path> folders = Files.list(apis)) {
            return folders.anyMatch(f -> Files.isDirectory(f) && f.getFileName().toString().equalsIgnoreCase(name));
        } catch (IOException e) {
            return false;
        }
    }
}

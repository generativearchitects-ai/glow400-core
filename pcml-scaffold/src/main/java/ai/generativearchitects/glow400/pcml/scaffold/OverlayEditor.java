package ai.generativearchitects.glow400.pcml.scaffold;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.codec.ConfigurationIssue;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.WireType;
import ai.generativearchitects.glow400.pcml.overlay.FieldSettings;
import ai.generativearchitects.glow400.pcml.overlay.Located;
import ai.generativearchitects.glow400.pcml.overlay.OperationSettings;
import ai.generativearchitects.glow400.pcml.overlay.OverlayFile;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLayout;
import ai.generativearchitects.glow400.pcml.overlay.OverlayText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sets {@code direction}, {@code type} and {@code name} in the parameter entries of an overlay file, at the positions
 * the overlay parser reports, without writing the document again: every byte outside the edited entries is kept. A
 * shape of entry that cannot be changed that way is left alone and reported as {@code SCF001}.
 *
 * <p>Whatever is written is checked before it is returned: the new text is loaded again, in memory, and is accepted only
 * if it gives no diagnostic that the old text did not give (apart from {@code OVL001}, which goes away) and the API model
 * differs only in the parameters that were edited. Otherwise there is no text and the diagnostic is {@code SCF002}.
 */
public final class OverlayEditor {

    /** What the loader is given as defaults: {@code OVL001} is an error, and the model is built regardless. */
    private static final GatewayDefaults DEFAULTS = defaults();
    private static final Pattern INPUTOUTPUT_COMMENT = Pattern.compile("[ \\t]*# inputoutput:.*");
    /** Added to the order of a change that must come after the others made at the same place. */
    private static final int APPENDED = 1_000_000;
    private static final SourceLocation NEUTRAL = new SourceLocation(".", 0, 0);

    private OverlayEditor() {
    }

    private static GatewayDefaults defaults() {
        GatewayDefaults standard = GatewayDefaults.standard();
        return new GatewayDefaults(standard.charCodec(), InputOutputPolicy.FAIL, standard.emitters(),
                standard.largeArrayThreshold(), standard.emitterSources());
    }

    // ------------------------------------------------------------------ types

    /**
     * The semantic types the codec can encode for a parameter of this wire type, by the rule {@code OVL003} applies,
     * in the order of {@link SemanticType}. A struct takes no type.
     *
     * @param parameter a top-level parameter of a program
     * @return the types, possibly none
     */
    public static List<SemanticType> allowedTypes(FieldDef parameter) {
        Objects.requireNonNull(parameter, "parameter");
        if (parameter.wire() instanceof WireType.Struct) {
            return List.of();
        }
        ProgramDef program = new ProgramDef("P", Optional.empty(), Optional.empty(), Optional.empty(), List.of(parameter),
                Map.of(), parameter.source());
        PcmlDocument document = new PcmlDocument("6.0", Map.of(), List.of(program), Map.of());
        List<SemanticType> allowed = new ArrayList<>();
        for (SemanticType type : SemanticType.values()) {
            String overlay = "pcml: p.pcml\nversion: 0.1.0\nprogram: P\nroute: /p\nmethod: POST\nparameters:\n  "
                    + YamlText.scalar(parameter.name()) + ": { direction: request, type: " + type.name().toLowerCase(java.util.Locale.ROOT)
                    + " }\n";
            OverlayText.Loaded loaded = OverlayText.load("p.yaml", overlay, document, DEFAULTS);
            if (loaded.diagnostics().isEmpty() && loaded.operations().size() == 1
                    && OperationCodec.check(loaded.operations().get(0)).stream().map(ConfigurationIssue::fieldPath)
                    .noneMatch(parameter.name()::equals)) {
                allowed.add(type);
            }
        }
        return allowed;
    }

    // ------------------------------------------------------------------ apply

    /**
     * @param apiYamlText the overlay file
     * @param sourceName  the name used in diagnostics
     * @param pcml        the PCML the overlay names
     * @param edits       the changes to make
     * @return the new text and what was applied; see {@link EditResult}
     */
    public static EditResult apply(String apiYamlText, String sourceName, PcmlDocument pcml, List<ParameterEdit> edits) {
        Objects.requireNonNull(apiYamlText, "apiYamlText");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(pcml, "pcml");
        Objects.requireNonNull(edits, "edits");
        OverlayText.Loaded before = OverlayText.load(sourceName, apiYamlText, pcml, DEFAULTS);
        Optional<OverlayLayout> layout = OverlayLayout.read(apiYamlText);
        if (before.file().isEmpty() || layout.isEmpty()) {
            return new EditResult(Optional.empty(), List.of(), List.of(new Diagnostic("SCF002", Severity.ERROR,
                    "The overlay file cannot be read, so it was not edited", new SourceLocation(sourceName, 1, 1))));
        }

        Planner planner = new Planner(apiYamlText, sourceName, pcml, before.file().get(), layout.get());
        List<ParameterEdit> applied = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (ParameterEdit edit : edits) {
            if (!targets.add(edit.operationId() + '\u0000' + edit.parameter())) {
                planner.skip(edit, null, "there is more than one edit for this parameter");
            } else if (planner.plan(edit)) {
                applied.add(edit);
            }
        }
        List<Diagnostic> diagnostics = new ArrayList<>(planner.diagnostics);
        if (applied.isEmpty()) {
            return new EditResult(Optional.of(apiYamlText), List.of(), diagnostics);
        }
        String text = planner.result();
        Optional<String> refusal = refusal(before, OverlayText.load(sourceName, text, pcml, DEFAULTS), applied);
        if (refusal.isPresent()) {
            diagnostics.add(new Diagnostic("SCF002", Severity.ERROR, "The edited file was refused and nothing was written: "
                    + refusal.get(), new SourceLocation(sourceName, 1, 1)));
            return new EditResult(Optional.empty(), List.of(), diagnostics);
        }
        return new EditResult(Optional.of(text), applied, diagnostics);
    }

    // ------------------------------------------------------------------ the safety check

    private static Optional<String> refusal(OverlayText.Loaded before, OverlayText.Loaded after, List<ParameterEdit> applied) {
        Map<String, Integer> expected = new HashMap<>();
        for (Diagnostic d : before.diagnostics()) {
            expected.merge(key(d), 1, Integer::sum);
        }
        for (Diagnostic d : after.diagnostics()) {
            if (expected.merge(key(d), -1, Integer::sum) < 0) {
                return Optional.of("the new text gives a diagnostic that the old one did not (" + d.code() + ")");
            }
        }
        Set<String> edited = new HashSet<>();
        for (ParameterEdit edit : applied) {
            edited.add(edit.operationId() + '\u0000' + edit.parameter());
        }
        if (before.operations().size() != after.operations().size()) {
            return Optional.of("the new text builds a different set of operations");
        }
        for (int i = 0; i < before.operations().size(); i++) {
            ApiOperation old = before.operations().get(i);
            ApiOperation now = after.operations().get(i);
            if (!old.id().equals(now.id()) || !strip(old, edited).equals(strip(now, edited))) {
                return Optional.of("the new text changes the operation '" + old.id() + "' beyond the edited parameters");
            }
        }
        for (ParameterEdit edit : applied) {
            Optional<FieldBinding> binding = after.operations().stream().filter(o -> o.id().equals(edit.operationId()))
                    .flatMap(o -> o.bindings().stream()).filter(b -> b.fieldPath().equals(edit.parameter())).findFirst();
            if (binding.isEmpty()) {
                return Optional.of("the edited parameter '" + edit.parameter() + "' cannot be found in the new model");
            }
            FieldBinding b = binding.get();
            boolean same = edit.direction().map(d -> d == b.direction()).orElse(true)
                    && edit.type().map(t -> b.semanticType().equals(Optional.of(t))).orElse(true)
                    && edit.name().map(b.exposedName()::equals).orElse(true);
            if (!same) {
                return Optional.of("the edited parameter '" + edit.parameter() + "' does not have what was asked for");
            }
        }
        return Optional.empty();
    }

    private static String key(Diagnostic d) {
        return d.code() + '\u0000' + d.message();
    }

    /** The operation without positions and without the bindings of the edited parameters, to compare two loads. */
    private static ApiOperation strip(ApiOperation op, Set<String> edited) {
        List<FieldBinding> kept = new ArrayList<>();
        for (FieldBinding b : op.bindings()) {
            if (!edited.contains(op.id() + '\u0000' + b.fieldPath())) {
                kept.add(withoutPosition(b));
            }
        }
        return new ApiOperation(op.id(), op.version(), op.summary(), op.description(), op.tags(), op.program(), op.pcmlPath(),
                op.overlayPath(), op.route(), op.method(), op.callStrategy(), kept, op.errorMapping(), op.cache(), NEUTRAL);
    }

    private static FieldBinding withoutPosition(FieldBinding b) {
        List<FieldBinding> children = b.children().stream().map(OverlayEditor::withoutPosition).toList();
        return new FieldBinding(b.fieldPath(), b.field(), b.direction(), b.injection(), b.exposedName(), b.location(),
                b.semanticType(), b.codec(), b.required(), b.description(), b.example(), b.hidden(), b.trim(), children, NEUTRAL);
    }

    // ------------------------------------------------------------------ planning the text changes

    /** A change to the text: the characters from {@code start} to {@code end} become {@code text}. */
    private record Change(int start, int end, String text, int order) {
    }

    private static final class Planner {
        private final String text;
        private final String sourceName;
        private final PcmlDocument pcml;
        private final OverlayFile file;
        private final OverlayLayout layout;
        private final List<Change> changes = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private int order;

        Planner(String text, String sourceName, PcmlDocument pcml, OverlayFile file, OverlayLayout layout) {
            this.text = text;
            this.sourceName = sourceName;
            this.pcml = pcml;
            this.file = file;
            this.layout = layout;
        }

        /** Reports an edit that is left out. {@code at} may be {@code null}. */
        void skip(ParameterEdit edit, SourceLocation at, String why) {
            diagnostics.add(new Diagnostic("SCF001", Severity.WARNING, "Operation '" + edit.operationId() + "', parameter '"
                    + edit.parameter() + "' was not edited: " + why + "; edit it by hand",
                    at == null ? new SourceLocation(sourceName, 1, 1) : at));
        }

        /** Plans one edit; returns whether it can be applied. Nothing is planned if it cannot. */
        boolean plan(ParameterEdit edit) {
            Optional<OperationSettings> operation = file.operations().stream()
                    .filter(o -> OverlayText.operationId(file, o).filter(edit.operationId()::equals).isPresent()).findFirst();
            if (operation.isEmpty()) {
                skip(edit, null, "the file has no such operation");
                return false;
            }
            SourceLocation operationAt = operation.get().source();
            Optional<FieldDef> field = operation.get().program().or(file.shared()::program).map(Located::value)
                    .flatMap(name -> pcml.programs().stream().filter(p -> p.name().equals(name)).findFirst())
                    .flatMap(p -> p.parameters().stream().filter(f -> f.name().equals(edit.parameter())).findFirst());
            if (field.isEmpty()) {
                skip(edit, operationAt, "the program has no such parameter");
                return false;
            }
            if (edit.type().isPresent() && !allowedTypes(field.get()).contains(edit.type().get())) {
                skip(edit, operationAt, "its type cannot be '" + edit.type().get().name().toLowerCase(java.util.Locale.ROOT) + "'");
                return false;
            }
            Optional<OverlayLayout.Operation> where = layout.operationAt(operationAt.line(), operationAt.column());
            if (where.isEmpty()) {
                skip(edit, operationAt, "the operation cannot be located in the text");
                return false;
            }
            FieldSettings settings = operation.get().parameters().get(edit.parameter());
            SourceLocation at = settings == null ? operationAt : settings.source();
            Optional<OverlayLayout.Parameters> parameters = where.get().parameters();
            OverlayLayout.Entry entry = parameters.map(p -> p.entries().get(edit.parameter())).orElse(null);
            if (entry == null) {
                if (parameters.isEmpty()) {
                    skip(edit, operationAt, where.get().why());
                    return false;
                }
                appendEntry(parameters.get(), edit);
                return true;
            }
            Optional<String> why = switch (entry.shape()) {
                case FLOW -> editFlow(entry, edit);
                case BLOCK -> editBlock(entry, edit);
                case UNSUPPORTED -> Optional.of(entry.why());
            };
            if (why.isPresent()) {
                skip(edit, at, why.get());
                return false;
            }
            return true;
        }

        /** The keys to write, in the order {@code direction}, {@code type}, {@code name}, with their YAML text. */
        private static Map<String, String[]> wanted(ParameterEdit edit) {
            Map<String, String[]> keys = new LinkedHashMap<>();
            edit.direction().ifPresent(d -> keys.put("direction", pair(d.name().toLowerCase(java.util.Locale.ROOT))));
            edit.type().ifPresent(t -> keys.put("type", pair(t.name().toLowerCase(java.util.Locale.ROOT))));
            edit.name().ifPresent(n -> keys.put("name", new String[] {n, YamlText.scalar(n)}));
            return keys;
        }

        private static String[] pair(String value) {
            return new String[] {value, value};
        }

        /** A new line {@code key: value} for each key. */
        private void appendEntry(OverlayLayout.Parameters parameters, ParameterEdit edit) {
            StringBuilder line = new StringBuilder(YamlText.scalar(edit.parameter())).append(": { ");
            line.append(String.join(", ", wanted(edit).entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()[1]).toList()));
            // After whatever else is added at the same place: the lines that finish the last entry come first.
            insertLines(parameters.end(), parameters.indent(), List.of(line.append(" }").toString()), APPENDED);
        }

        /** Returns why the entry cannot be edited, or empty after planning the change. */
        private Optional<String> editFlow(OverlayLayout.Entry entry, ParameterEdit edit) {
            Map<String, String[]> wanted = wanted(edit);
            List<Change> mine = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String[]> want : wanted.entrySet()) {
                Optional<OverlayLayout.Pair> pair = entry.pairs().stream().filter(p -> p.key().equals(want.getKey())).findFirst();
                if (pair.isEmpty()) {
                    missing.add(want.getKey() + ": " + want.getValue()[1]);
                } else if (!pair.get().simple()) {
                    return Optional.of("the value of '" + want.getKey() + "' is not a plain or quoted text on one line");
                } else if (!pair.get().value().equals(want.getValue()[0])) {
                    mine.add(new Change(pair.get().valueStart(), pair.get().valueEnd(), want.getValue()[1], order++));
                }
            }
            if (!missing.isEmpty()) {
                if (entry.pairs().isEmpty()) {
                    mine.add(new Change(entry.open(), entry.close() + 1, "{ " + String.join(", ", missing) + " }", order++));
                } else {
                    OverlayLayout.Pair last = entry.pairs().get(entry.pairs().size() - 1);
                    if (last.valueEnd() <= last.valueStart()) {
                        return Optional.of("the last key of the entry has no value");
                    }
                    mine.add(new Change(last.valueEnd(), last.valueEnd(), ", " + String.join(", ", missing), order++));
                }
            }
            if (edit.direction().isPresent()) {
                int lineEnd = lineEnd(entry.close() + 1);
                if (INPUTOUTPUT_COMMENT.matcher(text.substring(entry.close() + 1, lineEnd)).matches()) {
                    mine.add(new Change(entry.close() + 1, lineEnd, "", order++));
                }
            }
            changes.addAll(mine);
            return Optional.empty();
        }

        private Optional<String> editBlock(OverlayLayout.Entry entry, ParameterEdit edit) {
            Map<String, String[]> wanted = wanted(edit);
            List<Change> mine = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String[]> want : wanted.entrySet()) {
                Optional<OverlayLayout.Pair> pair = entry.pairs().stream().filter(p -> p.key().equals(want.getKey())).findFirst();
                if (pair.isEmpty()) {
                    missing.add(want.getKey() + ": " + want.getValue()[1]);
                } else if (!pair.get().simple()) {
                    return Optional.of("the value of '" + want.getKey() + "' is not a plain or quoted text on one line");
                } else if (!pair.get().value().equals(want.getValue()[0])) {
                    mine.add(new Change(pair.get().valueStart(), pair.get().valueEnd(), want.getValue()[1], order++));
                }
            }
            changes.addAll(mine);
            if (!missing.isEmpty()) {
                insertLines(entry.end(), entry.indent(), missing, 0);
            }
            return Optional.empty();
        }

        // ---- lines

        private int lineEnd(int from) {
            int i = from;
            while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                i++;
            }
            return i;
        }

        /** Adds whole lines after the line that holds {@code offset}, ending them as that line ends. */
        private void insertLines(int offset, int indent, List<String> lines, int rank) {
            int end = lineEnd(offset);
            String pad = " ".repeat(indent);
            StringBuilder block = new StringBuilder();
            if (end < text.length()) {
                String terminator = text.startsWith("\r\n", end) ? "\r\n" : String.valueOf(text.charAt(end));
                for (String line : lines) {
                    block.append(pad).append(line).append(terminator);
                }
                int at = end + terminator.length();
                changes.add(new Change(at, at, block.toString(), rank + order++));
            } else {
                String terminator = firstTerminator();
                for (String line : lines) {
                    block.append(terminator).append(pad).append(line);
                }
                changes.add(new Change(end, end, block.toString(), rank + order++));
            }
        }

        /** How lines end in this file: as the first one does; a line feed if there is none. */
        private String firstTerminator() {
            int i = lineEnd(0);
            if (i >= text.length()) {
                return "\n";
            }
            return text.startsWith("\r\n", i) ? "\r\n" : String.valueOf(text.charAt(i));
        }

        /** The text with every planned change made. */
        String result() {
            StringBuilder out = new StringBuilder(text);
            // From the end of the text to its start; at one position the later change goes first, so the earlier ends up first.
            changes.sort(Comparator.comparingInt(Change::start).thenComparingInt(Change::order).reversed());
            for (Change change : changes) {
                out.replace(change.start(), change.end(), change.text());
            }
            return out.toString();
        }
    }
}

package ai.generativearchitects.slay400.pcml.scaffold;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.PcmlDocument;
import ai.generativearchitects.slay400.pcml.model.ProgramDef;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import ai.generativearchitects.slay400.pcml.model.StructDef;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.model.WireType;
import ai.generativearchitects.slay400.pcml.overlay.OverlayText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Writes the first {@code api.yaml} for a PCML file: one operation per program, one line per top-level parameter, and
 * nothing else. The text loads without an error other than {@code OVL001} (a parameter that is {@code inputoutput}
 * has no direction yet), or no text is written and a {@code SCF003} diagnostic says which shape of PCML is in the way.
 */
public final class OverlayScaffold {

    /** The comment at the end of the line of a parameter that still needs a direction. {@link OverlayEditor} removes it. */
    public static final String INPUTOUTPUT_COMMENT =
            "# inputoutput: run annotate, or add direction: request | response | both | internal";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private OverlayScaffold() {
    }

    /**
     * @param folderName   the folder of the API, used as the title
     * @param pcmlFileName the PCML file, as the overlay names it: relative to the overlay file
     * @param pcml         the parsed PCML
     * @return the overlay text, or the reasons there is none
     */
    public static ScaffoldResult create(String folderName, String pcmlFileName, PcmlDocument pcml) {
        return create(folderName, pcmlFileName, pcml, ai.generativearchitects.slay400.pcml.api.GatewayDefaults.DEFAULT_LARGE_ARRAY_THRESHOLD);
    }

    /**
     * @param folderName          the folder of the API, used as the title
     * @param pcmlFileName        the PCML file, as the overlay names it: relative to the overlay file
     * @param pcml                the parsed PCML
     * @param largeArrayThreshold a fixed response array with at least this many elements, at any nesting level, gets
     *                            a drop-blank {@code trim} written for it (kickoff 14, Part A)
     * @return the overlay text, or the reasons there is none
     */
    public static ScaffoldResult create(String folderName, String pcmlFileName, PcmlDocument pcml, int largeArrayThreshold) {
        java.util.Objects.requireNonNull(folderName, "folderName");
        java.util.Objects.requireNonNull(pcmlFileName, "pcmlFileName");
        java.util.Objects.requireNonNull(pcml, "pcml");
        List<Diagnostic> problems = new ArrayList<>();
        if (pcml.programs().isEmpty()) {
            problems.add(problem(new SourceLocation(pcmlFileName, 0, 0), "The PCML file has no program: there is nothing to write an operation for"));
        }
        Map<String, ProgramDef> ids = new HashMap<>();
        for (ProgramDef program : pcml.programs()) {
            String id = program.name().toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                problems.add(problem(program.source(), "Program '" + program.name() + "': its name in lower case is not a valid "
                        + "operation id (letters, digits, '.', '_' and '-', starting with a letter or digit)"));
            }
            ProgramDef earlier = ids.putIfAbsent(id, program);
            if (earlier != null) {
                problems.add(problem(program.source(), "Programs '" + earlier.name() + "' and '" + program.name()
                        + "' would both get the operation id '" + id + "'"));
            }
            clashes(program, problems);
            for (FieldDef parameter : program.parameters()) {
                if (!resolves(parameter, pcml, new java.util.ArrayDeque<>())) {
                    problems.add(problem(parameter.source(), "Parameter '" + parameter.name() + "' of program '" + program.name()
                            + "' uses a struct that is not defined or that refers to itself"));
                }
            }
        }
        if (!problems.isEmpty()) {
            return new ScaffoldResult(Optional.empty(), problems);
        }

        StringBuilder out = new StringBuilder();
        out.append("pcml: ").append(YamlText.scalar(pcmlFileName)).append('\n');
        out.append("version: 0.1.0\n");
        out.append("title: ").append(YamlText.scalar(folderName)).append('\n');
        out.append("operations:\n");
        for (ProgramDef program : pcml.programs()) {
            String id = program.name().toLowerCase(Locale.ROOT);
            out.append("  - id: ").append(YamlText.scalar(id)).append('\n');
            out.append("    program: ").append(YamlText.scalar(program.name())).append('\n');
            out.append("    route: /").append(id).append('\n'); // an id is letters, digits, '.', '_' and '-': a plain scalar
            out.append("    method: POST\n");
            if (!program.parameters().isEmpty()) {
                out.append("    parameters:\n");
                for (FieldDef parameter : program.parameters()) {
                    writeParameter(out, "      ", parameter, pcml, largeArrayThreshold);
                }
            }
        }
        return new ScaffoldResult(Optional.of(out.toString()), List.of());
    }

    /** A parameter the loader reports {@code OVL001} for while there is no direction. */
    static boolean needsDirection(FieldDef parameter) {
        return parameter.usage() == Usage.INPUT_OUTPUT || parameter.usage() == Usage.INHERIT;
    }

    /**
     * Writes one top-level parameter: the flat, one-line form, or a block with a {@code trim} when the parameter, or
     * a struct member at any depth, is a large fixed array (kickoff 14, Part A). Trim is considered for every
     * parameter except a request-only one ({@code usage="input"}): an {@code inputoutput} or undirected parameter is
     * the common case the RPG compiler writes, so it gets the same trim as an {@code output} one, with the
     * "run annotate" comment kept alongside it (trailing the {@code name:} line, in block form too — see
     * {@link OverlayEditor}'s block-editing path, which now removes it from there the same way it already did for
     * the flat form).
     */
    private static void writeParameter(StringBuilder out, String indent, FieldDef parameter, PcmlDocument pcml, int threshold) {
        String exposedName = OverlayText.exposedName(parameter.name());
        String comment = needsDirection(parameter) ? INPUTOUTPUT_COMMENT : null;
        int eligible = parameter.usage() == Usage.INPUT ? -1 : threshold;
        writeField(out, indent, parameter, pcml, eligible, exposedName, comment);
    }

    /**
     * Writes a parameter or struct member, in flat or block form. {@code threshold} is negative when trim is never
     * considered for this field or anything under it (the request side). {@code trailingComment} trails the
     * {@code name:} line when not {@code null}; only a top-level parameter ever has one.
     */
    private static void writeField(StringBuilder out, String indent, FieldDef field, PcmlDocument pcml, int threshold,
                                    String exposedName, String trailingComment) {
        boolean ownLarge = threshold >= 0 && field.count() instanceof Count.Fixed fixed && fixed.value() >= threshold;
        List<FieldDef> membersToWrite = List.of();
        if (threshold >= 0 && field.wire() instanceof WireType.Struct struct) {
            StructDef definition = pcml.structs().get(struct.structName());
            if (definition != null) {
                List<FieldDef> found = new ArrayList<>();
                for (FieldDef member : definition.fields()) {
                    if (needsTrim(member, pcml, threshold, new ArrayDeque<>())) {
                        found.add(member);
                    }
                }
                membersToWrite = found;
            }
        }
        if (!ownLarge && membersToWrite.isEmpty()) {
            out.append(indent).append(YamlText.scalar(field.name())).append(": { name: ")
                    .append(YamlText.scalar(exposedName)).append(" }");
            if (trailingComment != null) {
                out.append("  ").append(trailingComment);
            }
            out.append('\n');
            return;
        }
        out.append(indent).append(YamlText.scalar(field.name())).append(":\n");
        String inner = indent + "  ";
        out.append(inner).append("name: ").append(YamlText.scalar(exposedName));
        if (trailingComment != null) {
            out.append("  ").append(trailingComment);
        }
        out.append('\n');
        if (ownLarge) {
            int n = ((Count.Fixed) field.count()).value();
            out.append(inner).append("# this array declares ").append(n).append(" elements (>= the large-array threshold, ")
                    .append(threshold).append("); trim drops the blank trailing ones so a call returns only the filled part.\n");
            out.append(inner).append("# If the program has a count field for it, 'trim: { countField: <name> }' is the better rule.\n");
            out.append(inner).append("trim:\n");
            out.append(inner).append("  dropBlank: true\n");
        }
        if (!membersToWrite.isEmpty()) {
            out.append(inner).append("fields:\n");
            for (FieldDef member : membersToWrite) {
                writeField(out, inner + "  ", member, pcml, threshold, OverlayText.exposedName(member.name()), null);
            }
        }
    }

    /** Whether a field, or something inside it at any depth, is a fixed array at or above {@code threshold}. */
    private static boolean needsTrim(FieldDef field, PcmlDocument pcml, int threshold, Deque<String> structStack) {
        if (field.count() instanceof Count.Fixed fixed && fixed.value() >= threshold) {
            return true;
        }
        if (!(field.wire() instanceof WireType.Struct struct)) {
            return false;
        }
        StructDef definition = pcml.structs().get(struct.structName());
        if (definition == null || structStack.contains(struct.structName())) {
            return false;
        }
        structStack.push(struct.structName());
        try {
            for (FieldDef member : definition.fields()) {
                if (needsTrim(member, pcml, threshold, structStack)) {
                    return true;
                }
            }
            return false;
        } finally {
            structStack.pop();
        }
    }

    /**
     * Two parameters that can only be on the same side of the API and would get the same name ({@code OVL014}). A
     * parameter that needs a direction is left out: the person who gives it one can also give it another name.
     */
    private static void clashes(ProgramDef program, List<Diagnostic> problems) {
        for (boolean request : new boolean[] {true, false}) {
            Map<String, FieldDef> seen = new HashMap<>();
            for (FieldDef parameter : program.parameters()) {
                if (parameter.usage() != (request ? Usage.INPUT : Usage.OUTPUT)) {
                    continue;
                }
                String name = OverlayText.exposedName(parameter.name());
                FieldDef earlier = seen.putIfAbsent(name, parameter);
                if (earlier != null) {
                    problems.add(problem(parameter.source(), "Parameters '" + earlier.name() + "' and '" + parameter.name()
                            + "' of program '" + program.name() + "' would both get the name '" + name + "' on the "
                            + (request ? "request" : "response") + " side"));
                }
            }
        }
    }

    /** Whether the struct behind a field, and every struct below it, is defined and does not contain itself. */
    private static boolean resolves(FieldDef field, PcmlDocument pcml, java.util.Deque<String> path) {
        if (!(field.wire() instanceof WireType.Struct struct)) {
            return true;
        }
        StructDef definition = pcml.structs().get(struct.structName());
        if (definition == null || path.contains(struct.structName())) {
            return false;
        }
        path.push(struct.structName());
        boolean ok = definition.fields().stream().allMatch(member -> resolves(member, pcml, path));
        path.pop();
        return ok;
    }

    private static Diagnostic problem(SourceLocation at, String message) {
        return new Diagnostic("SCF003", Severity.ERROR, message + "; write the overlay by hand", at);
    }
}

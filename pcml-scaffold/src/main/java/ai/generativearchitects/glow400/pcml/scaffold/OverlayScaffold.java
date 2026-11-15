package ai.generativearchitects.glow400.pcml.scaffold;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.StructDef;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import ai.generativearchitects.glow400.pcml.overlay.OverlayText;
import java.util.ArrayList;
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
                    out.append("      ").append(YamlText.scalar(parameter.name())).append(": { name: ")
                            .append(YamlText.scalar(OverlayText.exposedName(parameter.name()))).append(" }");
                    if (needsDirection(parameter)) {
                        out.append("  ").append(INPUTOUTPUT_COMMENT);
                    }
                    out.append('\n');
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

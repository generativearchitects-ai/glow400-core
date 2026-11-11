package ai.generativearchitects.glow400.pcml.parser;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.StructDef;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Turns an {@link Element} tree whose root is {@code pcml} into a {@link PcmlDocument}, collecting
 * diagnostics instead of throwing.
 */
final class ModelBuilder {

    // Elements and attributes of the PCML definition (pcml.dtd of JTOpen 21.0.7).
    private static final Set<String> PCML_ATTRIBUTES = Set.of("version", "description");
    private static final Set<String> STRUCT_ATTRIBUTES = Set.of(
            "name", "usage", "count", "minvrm", "maxvrm", "offset", "offsetfrom", "outputsize", "description");
    private static final Set<String> PROGRAM_ATTRIBUTES = Set.of(
            "name", "path", "parseorder", "entrypoint", "returnvalue", "threadsafe", "epccsid", "description");
    private static final Set<String> DATA_ATTRIBUTES = Set.of(
            "name", "usage", "count", "minvrm", "maxvrm", "offset", "offsetfrom", "outputsize", "type",
            "length", "precision", "ccsid", "init", "struct", "varlength", "passby", "bidistringtype",
            "trim", "chartype", "dateformat", "timeformat", "dateseparator", "timeseparator", "boolean",
            "description");

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, StructDef> structs = new LinkedHashMap<>();
    private final List<ProgramDef> programs = new ArrayList<>();
    private final Set<String> programNames = new HashSet<>();

    ModelBuilder(String file) {
        // The file name travels inside every SourceLocation created by XmlReader.
    }

    List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    PcmlDocument build(Element root) {
        Map<String, String> attributes = new LinkedHashMap<>(root.attributes());
        String version = attributes.remove("version");
        if (version == null) {
            error("PCML004", "Missing required attribute 'version' on <pcml>", root.location());
            version = "";
        }
        reportUnknownAttributes(root, PCML_ATTRIBUTES);

        for (Element child : root.children()) {
            switch (child.name()) {
                case "struct" -> topLevelStruct(child);
                case "program" -> program(child);
                default -> unknownElement(child, root);
            }
        }

        checkStructReferences();
        checkFieldReferences();
        checkCircularStructs();

        diagnostics.sort(Comparator
                .comparingInt((Diagnostic d) -> d.location().line())
                .thenComparingInt(d -> d.location().column()));
        return new PcmlDocument(version, structs, programs, attributes);
    }

    // ---------------------------------------------------------------- programs and structs

    private void program(Element element) {
        String name = nonBlank(element.attributes().get("name"));
        if (name == null) {
            error("PCML004", "Missing required attribute 'name' on <program>", element.location());
            return;
        }
        reportUnknownAttributes(element, PROGRAM_ATTRIBUTES);
        Map<String, String> rest = new LinkedHashMap<>(element.attributes());
        rest.remove("name");
        Optional<String> path = Optional.ofNullable(rest.remove("path"));
        Optional<String> entryPoint = Optional.ofNullable(rest.remove("entrypoint"));
        Optional<String> returnValue = Optional.ofNullable(rest.remove("returnvalue"));

        if (!programNames.add(name)) {
            error("PCML005", "Duplicate program '" + name + "'", element.location());
        }
        List<FieldDef> parameters = members(element, name);
        programs.add(new ProgramDef(name, path, entryPoint, returnValue, parameters, rest, element.location()));
    }

    private void topLevelStruct(Element element) {
        String name = nonBlank(element.attributes().get("name"));
        if (name == null) {
            error("PCML004", "Missing required attribute 'name' on <struct>", element.location());
            return;
        }
        reportUnknownAttributes(element, STRUCT_ATTRIBUTES);
        if (structs.containsKey(name)) {
            error("PCML005", "Duplicate struct '" + name + "'", element.location());
            return;
        }
        Map<String, String> rest = new LinkedHashMap<>(element.attributes());
        rest.remove("name");
        structs.put(name, null); // reserves the position so that nested structs follow their parent
        List<FieldDef> fields = members(element, name);
        structs.put(name, new StructDef(name, fields, rest, element.location()));
    }

    /** The fields of a program or struct element, in document order. */
    private List<FieldDef> members(Element parent, String qualifiedParent) {
        List<FieldDef> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element child : parent.children()) {
            Optional<FieldDef> field = switch (child.name()) {
                case "data" -> data(child);
                case "struct" -> nestedStruct(child, qualifiedParent);
                default -> {
                    unknownElement(child, parent);
                    yield Optional.empty();
                }
            };
            field.ifPresent(f -> {
                if (!names.add(f.name())) {
                    error("PCML005", "Duplicate field '" + f.name() + "' in '" + qualifiedParent + "'", f.source());
                }
                fields.add(f);
            });
        }
        return fields;
    }

    /** A struct element inside a program or struct: a struct definition and the field that stands for it. */
    private Optional<FieldDef> nestedStruct(Element element, String qualifiedParent) {
        String name = nonBlank(element.attributes().get("name"));
        if (name == null) {
            error("PCML004", "Missing required attribute 'name' on <struct>", element.location());
            return Optional.empty();
        }
        reportUnknownAttributes(element, STRUCT_ATTRIBUTES);
        Map<String, String> rest = new LinkedHashMap<>(element.attributes());
        rest.remove("name");
        boolean valid = true;

        Usage usage = Usage.INHERIT;
        if (rest.containsKey("usage")) {
            Optional<Usage> parsed = parseUsage(rest.get("usage"));
            if (parsed.isPresent()) {
                usage = parsed.get();
                rest.remove("usage");
            } else {
                error("PCML004", "Unknown usage '" + rest.get("usage") + "' on <struct>", element.location());
            }
        }
        Count count = new Count.None();
        if (rest.containsKey("count")) {
            Optional<Count> parsed = parseCount(rest.get("count"), element);
            if (parsed.isPresent()) {
                count = parsed.get();
                rest.remove("count");
            } else {
                valid = false;
            }
        }

        String qualified = qualifiedParent + "." + name;
        if (!structs.containsKey(qualified)) {
            structs.put(qualified, null);
            List<FieldDef> fields = members(element, qualified);
            structs.put(qualified, new StructDef(qualified, fields, Map.of(), element.location()));
        } // else: a duplicate field name, reported by the caller

        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(new FieldDef(name, new WireType.Struct(qualified), usage, count, rest, element.location()));
    }

    // ---------------------------------------------------------------- data

    private Optional<FieldDef> data(Element element) {
        for (Element child : element.children()) {
            unknownElement(child, element);
        }
        reportUnknownAttributes(element, DATA_ATTRIBUTES);

        boolean valid = true;
        String name = nonBlank(element.attributes().get("name"));
        if (name == null) {
            error("PCML004", "Missing required attribute 'name' on <data>", element.location());
            valid = false;
        }
        String type = element.attributes().get("type");
        if (type == null) {
            error("PCML004", "Missing required attribute 'type' on <data>", element.location());
            valid = false;
        }

        Map<String, String> rest = new LinkedHashMap<>(element.attributes());
        rest.remove("name");
        rest.remove("type");

        Usage usage = Usage.INHERIT;
        if (rest.containsKey("usage")) {
            Optional<Usage> parsed = parseUsage(rest.get("usage"));
            if (parsed.isPresent()) {
                usage = parsed.get();
                rest.remove("usage");
            } else {
                error("PCML004", "Unknown usage '" + rest.get("usage") + "' on <data>", element.location());
            }
        }

        Count count = new Count.None();
        if (rest.containsKey("count")) {
            Optional<Count> parsed = parseCount(rest.get("count"), element);
            if (parsed.isPresent()) {
                count = parsed.get();
                rest.remove("count");
            } else {
                valid = false;
            }
        }

        Optional<WireType> wire = Optional.empty();
        if (type != null) {
            wire = wireType(element, type, rest);
            if (wire.isEmpty()) {
                valid = false;
            }
        }
        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(new FieldDef(name, wire.get(), usage, count, rest, element.location()));
    }

    /** Builds the wire type, consuming the attributes it models from {@code rest}. */
    private Optional<WireType> wireType(Element e, String type, Map<String, String> rest) {
        return switch (type) {
            case "char" -> requiredLength(e, type, rest).map(length -> new WireType.Char(length, optionalCcsid(rest)));
            case "byte" -> requiredLength(e, type, rest).map(WireType.Byte::new);
            case "varchar" -> {
                Optional<Length> length = requiredLength(e, type, rest);
                Optional<Optional<Integer>> varLength = optionalVarLength(e, rest);
                if (length.isEmpty() || varLength.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(new WireType.VarChar(length.get(), varLength.get(), optionalCcsid(rest)));
            }
            case "int" -> {
                Optional<Integer> length = requiredInt(e, type, "length", rest);
                Optional<Optional<Integer>> precision = optionalInt(e, "precision", rest);
                if (length.isEmpty() || precision.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(new WireType.Int(length.get(), precision.get()));
            }
            case "packed", "zoned" -> {
                Optional<Integer> length = requiredInt(e, type, "length", rest);
                Optional<Integer> precision = requiredInt(e, type, "precision", rest);
                if (length.isEmpty() || precision.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(type.equals("packed")
                        ? new WireType.Packed(length.get(), precision.get())
                        : new WireType.Zoned(length.get(), precision.get()));
            }
            case "float" -> requiredInt(e, type, "length", rest).map(WireType.Float::new);
            case "struct" -> {
                String structName = nonBlank(rest.get("struct"));
                if (structName == null) {
                    error("PCML004", "Missing required attribute 'struct' on <data> of type 'struct'", e.location());
                    yield Optional.empty();
                }
                rest.remove("struct");
                yield Optional.of(new WireType.Struct(structName));
            }
            case "date" -> Optional.of(new WireType.Date());
            case "time" -> Optional.of(new WireType.Time());
            case "timestamp" -> Optional.of(new WireType.Timestamp());
            default -> {
                error("PCML004", "Unknown type '" + type + "' on <data>", e.location());
                yield Optional.empty();
            }
        };
    }

    private Optional<Length> requiredLength(Element e, String type, Map<String, String> rest) {
        String raw = rest.get("length");
        if (raw == null) {
            error("PCML004", "Missing required attribute 'length' on <data> of type '" + type + "'", e.location());
            return Optional.empty();
        }
        if (raw.isBlank()) {
            error("PCML004", "Attribute 'length' on <data> is empty", e.location());
            return Optional.empty();
        }
        Length length;
        if (isNumber(raw)) {
            OptionalInt number = toInt(raw);
            if (number.isEmpty()) {
                error("PCML004", "Attribute 'length' is out of range: " + raw, e.location());
                return Optional.empty();
            }
            length = new Length.Fixed(number.getAsInt());
        } else {
            length = new Length.Ref(raw);
        }
        rest.remove("length");
        return Optional.of(length);
    }

    private Optional<Integer> requiredInt(Element e, String type, String attribute, Map<String, String> rest) {
        String raw = rest.get(attribute);
        if (raw == null) {
            error("PCML004", "Missing required attribute '" + attribute + "' on <data> of type '" + type + "'", e.location());
            return Optional.empty();
        }
        OptionalInt number = isNumber(raw) ? toInt(raw) : OptionalInt.empty();
        if (number.isEmpty()) {
            error("PCML004", "Attribute '" + attribute + "' on <data> of type '" + type + "' must be a number: '" + raw + "'",
                    e.location());
            return Optional.empty();
        }
        rest.remove(attribute);
        return Optional.of(number.getAsInt());
    }

    /** Empty outer optional: the attribute is present but invalid (already reported). */
    private Optional<Optional<Integer>> optionalInt(Element e, String attribute, Map<String, String> rest) {
        String raw = rest.get(attribute);
        if (raw == null) {
            return Optional.of(Optional.empty());
        }
        OptionalInt number = isNumber(raw) ? toInt(raw) : OptionalInt.empty();
        if (number.isEmpty()) {
            error("PCML004", "Attribute '" + attribute + "' on <data> must be a number: '" + raw + "'", e.location());
            return Optional.empty();
        }
        rest.remove(attribute);
        return Optional.of(Optional.of(number.getAsInt()));
    }

    private Optional<Optional<Integer>> optionalVarLength(Element e, Map<String, String> rest) {
        String raw = rest.get("varlength");
        if (raw == null) {
            return Optional.of(Optional.empty());
        }
        if (!raw.equals("2") && !raw.equals("4")) {
            error("PCML004", "Unknown varlength '" + raw + "' on <data>, expected 2 or 4", e.location());
            return Optional.empty();
        }
        rest.remove("varlength");
        return Optional.of(Optional.of(Integer.parseInt(raw)));
    }

    /** A numeric ccsid is modelled; a non-numeric one (a reference to a field) stays in the attribute map. */
    private static Optional<Integer> optionalCcsid(Map<String, String> rest) {
        String raw = rest.get("ccsid");
        if (raw != null && isNumber(raw)) {
            OptionalInt number = toInt(raw);
            if (number.isPresent()) {
                rest.remove("ccsid");
                return Optional.of(number.getAsInt());
            }
        }
        return Optional.empty();
    }

    private Optional<Count> parseCount(String raw, Element e) {
        if (raw.isBlank()) {
            error("PCML004", "Attribute 'count' on <" + e.name() + "> is empty", e.location());
            return Optional.empty();
        }
        if (!isNumber(raw)) {
            return Optional.of(new Count.Ref(raw));
        }
        OptionalInt number = toInt(raw);
        if (number.isEmpty()) {
            error("PCML004", "Attribute 'count' is out of range: " + raw, e.location());
            return Optional.empty();
        }
        return Optional.of(new Count.Fixed(number.getAsInt()));
    }

    private static Optional<Usage> parseUsage(String raw) {
        return switch (raw) {
            case "input" -> Optional.of(Usage.INPUT);
            case "output" -> Optional.of(Usage.OUTPUT);
            case "inputoutput" -> Optional.of(Usage.INPUT_OUTPUT);
            case "inherit" -> Optional.of(Usage.INHERIT);
            default -> Optional.empty();
        };
    }

    // ---------------------------------------------------------------- semantic checks

    /** PCML002. */
    private void checkStructReferences() {
        forEachScope((fields, program) -> {
            for (FieldDef field : fields) {
                if (field.wire() instanceof WireType.Struct s && !structs.containsKey(s.structName())) {
                    error("PCML002", "Field '" + field.name() + "' references struct '" + s.structName()
                            + "', which is not defined", field.source());
                }
            }
        });
    }

    /** PCML003. */
    private void checkFieldReferences() {
        forEachScope((fields, program) -> {
            for (FieldDef field : fields) {
                if (field.count() instanceof Count.Ref ref) {
                    checkReference("count", ref.field(), field, fields, program);
                }
                Length length = switch (field.wire()) {
                    case WireType.Char c -> c.length();
                    case WireType.VarChar v -> v.length();
                    case WireType.Byte b -> b.length();
                    default -> null;
                };
                if (length instanceof Length.Ref ref) {
                    checkReference("length", ref.field(), field, fields, program);
                }
            }
        });
    }

    private void checkReference(String attribute, String target, FieldDef field, List<FieldDef> scope, ProgramDef program) {
        String[] path = target.split("\\.");
        boolean found = resolve(scope, path, 0, new HashSet<>());
        if (!found && program != null && path.length > 1 && path[0].equals(program.name())) {
            found = resolve(program.parameters(), path, 1, new HashSet<>());
        }
        if (!found) {
            error("PCML003", "The " + attribute + " of field '" + field.name() + "' references '" + target
                    + "', which does not exist in the same scope", field.source());
        }
    }

    private boolean resolve(List<FieldDef> scope, String[] path, int index, Set<String> visiting) {
        for (FieldDef candidate : scope) {
            if (!candidate.name().equals(path[index])) {
                continue;
            }
            if (index == path.length - 1) {
                return true;
            }
            if (candidate.wire() instanceof WireType.Struct s) {
                StructDef def = structs.get(s.structName());
                if (def != null && visiting.add(s.structName())) {
                    boolean found = resolve(def.fields(), path, index + 1, visiting);
                    visiting.remove(s.structName());
                    if (found) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** PCML006: every cycle in the struct reference graph is reported once, at the struct where it was entered. */
    private void checkCircularStructs() {
        Map<String, Integer> state = new HashMap<>(); // absent: unvisited, 1: on the stack, 2: done
        Set<Set<String>> reported = new HashSet<>();
        for (String name : List.copyOf(structs.keySet())) {
            visit(name, state, new ArrayList<>(), reported);
        }
    }

    private void visit(String name, Map<String, Integer> state, List<String> stack, Set<Set<String>> reported) {
        StructDef def = structs.get(name);
        if (def == null || state.getOrDefault(name, 0) == 2) {
            return;
        }
        state.put(name, 1);
        stack.add(name);
        for (String next : referencedStructs(def)) {
            int nextState = state.getOrDefault(next, 0);
            if (nextState == 1) {
                List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(next), stack.size()));
                if (reported.add(new HashSet<>(cycle))) {
                    List<String> shown = new ArrayList<>(cycle);
                    shown.add(next);
                    warning("PCML006", "Circular struct reference: " + String.join(" -> ", shown),
                            structs.get(next).source());
                }
            } else if (nextState == 0) {
                visit(next, state, stack, reported);
            }
        }
        stack.remove(stack.size() - 1);
        state.put(name, 2);
    }

    private Set<String> referencedStructs(StructDef def) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldDef field : def.fields()) {
            if (field.wire() instanceof WireType.Struct s && structs.containsKey(s.structName())) {
                names.add(s.structName());
            }
        }
        return names;
    }

    @FunctionalInterface
    private interface ScopeVisitor {
        void visit(List<FieldDef> fields, ProgramDef program);
    }

    /** Visits every scope: the parameters of each program and the fields of each struct. */
    private void forEachScope(ScopeVisitor visitor) {
        for (ProgramDef program : programs) {
            visitor.visit(program.parameters(), program);
        }
        for (StructDef struct : structs.values()) {
            visitor.visit(struct.fields(), null);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void reportUnknownAttributes(Element element, Set<String> known) {
        for (String attribute : element.attributes().keySet()) {
            if (!known.contains(attribute)) {
                info("PCML007", "Unknown attribute '" + attribute + "' on <" + element.name()
                        + "> is preserved in the attribute map", element.location());
            }
        }
    }

    private void unknownElement(Element child, Element parent) {
        info("PCML007", "Unknown element <" + child.name() + "> inside <" + parent.name() + "> is ignored",
                child.location());
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Whether the text is a non-empty sequence of decimal digits, and nothing else. */
    static boolean isNumber(String raw) {
        if (raw.isEmpty()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static OptionalInt toInt(String digits) {
        try {
            return OptionalInt.of(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private void error(String code, String message, SourceLocation location) {
        diagnostics.add(new Diagnostic(code, Severity.ERROR, message, location));
    }

    private void warning(String code, String message, SourceLocation location) {
        diagnostics.add(new Diagnostic(code, Severity.WARNING, message, location));
    }

    private void info(String code, String message, SourceLocation location) {
        diagnostics.add(new Diagnostic(code, Severity.INFO, message, location));
    }
}

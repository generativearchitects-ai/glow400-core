package ai.generativearchitects.slay400.pcml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.api.GatewayDefaults;
import ai.generativearchitects.slay400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.codec.Gen;
import ai.generativearchitects.slay400.pcml.codec.Properties;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.PcmlDocument;
import ai.generativearchitects.slay400.pcml.model.ProgramDef;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.model.WireType;
import ai.generativearchitects.slay400.pcml.overlay.OverlayText;
import ai.generativearchitects.slay400.pcml.parser.ParseResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * For overlays built from the fixtures with random comments, blank lines, quoting, indentation, line endings and shapes
 * of entry, and random valid edits: either an edit is left out (with {@code SCF001}) and the text is unchanged where it
 * concerns, or (a) every line outside the edited entries is kept, byte for byte, (b) the reloaded model shows exactly
 * the changes asked for, and (c) the same edits applied again change nothing.
 */
class OverlayEditorPropertyTest {

    private static final GatewayDefaults FAIL = new GatewayDefaults(GatewayDefaults.standard().charCodec(), InputOutputPolicy.FAIL,
            GatewayDefaults.standard().emitters(), GatewayDefaults.standard().largeArrayThreshold(), GatewayDefaults.standard().emitterSources());
    private static final SourceLocation NEUTRAL = new SourceLocation(".", 0, 0);

    /** The programs of the fixtures that an overlay can be written for. */
    private static List<Source> programs() {
        List<Source> programs = new ArrayList<>();
        for (Fixtures.Api api : Fixtures.apis()) {
            String file = api.pcml().getFileName().toString();
            ParseResult parsed = Fixtures.parse(file, Fixtures.read(api.pcml()));
            if (parsed.hasErrors() || parsed.document().isEmpty()) {
                continue;
            }
            PcmlDocument document = parsed.document().get();
            if (OverlayScaffold.create(api.folder(), file, document).text().isEmpty()) {
                continue;
            }
            for (ProgramDef program : document.programs()) {
                if (!program.parameters().isEmpty()) {
                    programs.add(new Source(file, document, program));
                }
            }
        }
        return programs;
    }

    private record Source(String pcmlFile, PcmlDocument document, ProgramDef program) {
    }

    /** An entry of the generated overlay: the parameter and the lines it occupies, from the first to before the last. */
    private record Entry(FieldDef parameter, int first, int end) {
    }

    private record Built(String text, String eol, Source source, String operationId, List<Entry> entries, List<String> lines) {
    }

    private static boolean chance(Gen g, int percent) {
        return g.intIn(1, 100) <= percent;
    }

    private static String quote(Gen g, String value) {
        return switch (g.intIn(0, 2)) {
            case 0 -> value;
            case 1 -> "'" + value + "'";
            default -> "\"" + value + "\"";
        };
    }

    private static String spaces(int n) {
        return " ".repeat(n);
    }

    private static List<Direction> directionsFor(Usage usage) {
        return switch (usage) {
            case INPUT -> List.of(Direction.REQUEST, Direction.INTERNAL);
            case OUTPUT -> List.of(Direction.RESPONSE, Direction.INTERNAL);
            default -> List.of(Direction.REQUEST, Direction.RESPONSE, Direction.BOTH, Direction.INTERNAL);
        };
    }

    // ------------------------------------------------------------------ building an overlay

    private static Built build(Gen g, Source source) {
        List<String> lines = new ArrayList<>();
        boolean listForm = g.bool();
        String operationId = source.program().name().toLowerCase(Locale.ROOT);
        int base;
        lines.add("pcml: " + quote(g, source.pcmlFile()));
        lines.add("version: 1.0.0");
        if (chance(g, 30)) {
            lines.add("# " + g.text(Gen.LETTERS_AND_SPACES, 0, 12));
        }
        if (listForm) {
            lines.add("operations:");
            lines.add("  - id: " + operationId);
            lines.add("    program: " + source.program().name());
            lines.add("    route: /" + operationId);
            lines.add("    method: POST");
            lines.add("    parameters:");
            base = 6;
        } else {
            lines.add("program: " + source.program().name());
            lines.add("route: /" + operationId);
            lines.add("method: POST");
            lines.add("parameters:");
            base = 0;
        }
        int step = g.intIn(1, 4);
        int indent = base + (listForm ? 0 : step);
        int step2 = g.intIn(1, 4);
        List<Entry> entries = new ArrayList<>();
        List<FieldDef> parameters = source.program().parameters();
        int written = 0;
        for (int i = 0; i < parameters.size(); i++) {
            FieldDef p = parameters.get(i);
            boolean last = i == parameters.size() - 1 && written == 0;
            if (!last && chance(g, 30)) {
                continue;
            }
            if (chance(g, 35)) {
                lines.add(chance(g, 50) ? "" : spaces(g.intIn(0, 3)));
            }
            if (chance(g, 35)) {
                lines.add(spaces(g.intIn(0, indent + 2)) + "# " + g.text(Gen.LETTERS_AND_SPACES, 0, 15));
            }
            int first = lines.size();
            entry(g, lines, p, indent, step2);
            entries.add(new Entry(p, first, lines.size()));
            written++;
        }
        if (chance(g, 40)) {
            lines.add(spaces(base == 0 ? 0 : 4) + "summary: " + quote(g, "s"));
        }
        String eol = g.bool() ? "\n" : "\r\n";
        String text = String.join(eol, lines) + (chance(g, 75) ? eol : "");
        if (chance(g, 5)) {
            text = "﻿" + text;
        }
        return new Built(text, eol, source, operationId, entries, lines);
    }

    /** One entry in a random shape. Everything written is valid. */
    private static void entry(Gen g, List<String> lines, FieldDef p, int indent, int step2) {
        String key = quote(g, p.name());
        if (key.startsWith("'") || key.startsWith("\"")) {
            key = p.name(); // keys stay plain: a quoted key is only a spelling, tested elsewhere
        }
        List<String> pairs = new ArrayList<>();
        if (chance(g, 60)) {
            pairs.add("name: " + quote(g, OverlayText.exposedName(p.name())));
        }
        boolean hasDirection = chance(g, 25);
        if (hasDirection) {
            pairs.add(0, "direction: " + quote(g, g.pick(directionsFor(p.usage())).name().toLowerCase(Locale.ROOT)));
        }
        List<SemanticType> types = OverlayEditor.allowedTypes(p);
        if (!types.isEmpty() && chance(g, 12)) {
            pairs.add("type: " + quote(g, g.pick(types).name().toLowerCase(Locale.ROOT)));
        }
        if (chance(g, 15)) {
            pairs.add("description: " + quote(g, g.text(Gen.LETTERS, 1, 6)));
        }
        String pad = spaces(indent);
        int shape = g.intIn(0, 99);
        if (shape < 5) { // not supported: an anchor
            lines.add(pad + key + ": &a" + g.intIn(0, 9) + " { " + String.join(", ", pairs) + " }");
        } else if (shape < 8) { // not supported: a flow mapping over two lines
            lines.add(pad + key + ": { name: " + OverlayText.exposedName(p.name()) + ",");
            lines.add(pad + spaces(step2) + "description: d }");
        } else if (shape < 55) { // flow on one line
            String inner = pairs.isEmpty() ? (g.bool() ? "" : " ") : " " + String.join(", ", pairs) + " ";
            String comment = chance(g, 30) ? spaces(g.intIn(1, 3)) + "# " + g.text(Gen.LETTERS_AND_SPACES, 0, 12) : "";
            if (!hasDirection && comment.isEmpty() && OverlayScaffold.needsDirection(p) && chance(g, 50)) {
                comment = "  " + OverlayScaffold.INPUTOUTPUT_COMMENT;
            }
            lines.add(pad + key + ": {" + inner + "}" + comment);
        } else if (!pairs.isEmpty()) { // block
            lines.add(pad + key + ":" + (chance(g, 20) ? "  # " + g.text(Gen.LETTERS, 0, 8) : ""));
            for (String pair : pairs) {
                if (chance(g, 20)) {
                    lines.add(spaces(indent + step2) + "# " + g.text(Gen.LETTERS_AND_SPACES, 0, 10));
                }
                lines.add(spaces(indent + step2) + pair + (chance(g, 20) ? "  # " + g.text(Gen.LETTERS, 0, 8) : ""));
                if (chance(g, 10)) {
                    lines.add("");
                }
            }
        } else {
            lines.add(pad + key + ": {}");
        }
    }

    // ------------------------------------------------------------------ edits

    private static List<ParameterEdit> edits(Gen g, Built built) {
        List<ParameterEdit> edits = new ArrayList<>();
        int number = 0;
        for (FieldDef p : built.source().program().parameters()) {
            boolean wanted = OverlayScaffold.needsDirection(p) ? chance(g, 70) : chance(g, 25);
            if (!wanted) {
                continue;
            }
            Optional<Direction> direction = chance(g, 80) ? Optional.of(g.pick(directionsFor(p.usage()))) : Optional.empty();
            List<SemanticType> types = OverlayEditor.allowedTypes(p);
            Optional<SemanticType> type = !types.isEmpty() && chance(g, 40) ? Optional.of(g.pick(types)) : Optional.empty();
            Optional<String> name = chance(g, 40) ? Optional.of("zz" + number + g.text(Gen.LETTERS, 1, 5)) : Optional.empty();
            if (direction.isEmpty() && type.isEmpty() && name.isEmpty()) {
                direction = Optional.of(g.pick(directionsFor(p.usage())));
            }
            edits.add(new ParameterEdit(built.operationId(), p.name(), direction, type, name));
            number++;
        }
        return edits;
    }

    // ------------------------------------------------------------------ the property

    @Test
    void editsAreRefusedOrExactlyWhatWasAskedFor() {
        List<Source> programs = programs();
        assertThat(programs).hasSizeGreaterThan(10);
        int[] counts = new int[3];
        Properties.check("overlay editor", g -> {
            Source source = g.pick(programs);
            Built built = build(g, source);
            OverlayText.Loaded before = OverlayText.load("api.yaml", built.text(), source.document(), FAIL);
            assertThat(before.diagnostics()).as("the generated overlay:\n" + built.text()).extracting(Diagnostic::code).isSubsetOf("OVL001");
            if (before.diagnostics().isEmpty() && before.operations().isEmpty()) {
                throw new AssertionError("nothing was built from:\n" + built.text());
            }
            List<ParameterEdit> edits = edits(g, built);
            String context = "\nthe overlay:\n" + built.text() + "\nthe edits: " + edits;

            EditResult result = OverlayEditor.apply(built.text(), "api.yaml", source.document(), edits);
            assertThat(result.diagnostics()).as("no SCF002" + context + "\nthe diagnostics: " + result.diagnostics()).extracting(Diagnostic::code).isSubsetOf("SCF001");
            Set<String> left = new HashSet<>();
            result.diagnostics().forEach(d -> left.add(d.message()));
            assertThat(result.diagnostics()).as("one warning for each edit left out" + context).hasSize(edits.size() - result.applied().size());
            assertThat(edits).containsAll(result.applied());
            String text = result.text().orElseThrow();
            if (result.applied().isEmpty()) {
                assertThat(text).as("nothing applied, nothing changed" + context).isEqualTo(built.text());
                counts[0]++;
                return;
            }
            counts[result.diagnostics().isEmpty() ? 1 : 2]++;

            assertOutsideTheEditedEntriesIsKept(built, result.applied(), text, context);
            assertTheModelShowsTheChanges(built, before, result.applied(), text, context);

            EditResult again = OverlayEditor.apply(text, "api.yaml", source.document(), result.applied());
            assertThat(again.diagnostics()).as("applying again" + context).isEmpty();
            assertThat(again.text()).as("applying the same edits again changes nothing" + context).contains(text);
            assertThat(again.applied()).isEqualTo(result.applied());
        });
        assertThat(counts[1]).as("cases in which every edit was applied").isPositive();
    }

    // (a)

    private static void assertOutsideTheEditedEntriesIsKept(Built built, List<ParameterEdit> applied, String text, String context) {
        Set<String> edited = new HashSet<>();
        applied.forEach(e -> edited.add(e.parameter()));
        Set<Integer> editedLines = new HashSet<>();
        for (Entry entry : built.entries()) {
            if (edited.contains(entry.parameter().name())) {
                for (int i = entry.first(); i < entry.end(); i++) {
                    editedLines.add(i);
                }
            }
        }
        List<String> original = new ArrayList<>();
        for (int i = 0; i < built.lines().size(); i++) {
            if (!editedLines.contains(i)) {
                original.add(built.lines().get(i));
            }
        }
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        assertThat(text.startsWith("﻿")).as("byte-order mark" + context).isEqualTo(built.text().startsWith("﻿"));
        // every line ends the way the file's lines end
        String withoutEol = body.replace(built.eol(), "");
        assertThat(withoutEol).as("line endings" + context).doesNotContain("\n").doesNotContain("\r");
        String[] now = body.split(java.util.regex.Pattern.quote(built.eol()), -1);
        int at = 0;
        List<String> unmatched = new ArrayList<>();
        for (String line : now) {
            if (at < original.size() && original.get(at).equals(line)) {
                at++;
            } else {
                unmatched.add(line);
            }
        }
        assertThat(at).as("the lines outside the edited entries, in order, are all still there (" + at + " of " + original.size()
                + ")" + context).isEqualTo(original.size());
        for (String line : unmatched) {
            String trimmed = line.strip();
            boolean mine = trimmed.isEmpty() && line.equals("") // the end of a text that has a final line break
                    || applied.stream().anyMatch(e -> trimmed.startsWith(e.parameter() + ":") || trimmed.startsWith("\"" + e.parameter() + "\":"))
                    || trimmed.startsWith("direction:") || trimmed.startsWith("type:") || trimmed.startsWith("name:")
                    || trimmed.startsWith("#") || trimmed.startsWith("description:") || trimmed.endsWith("}") || trimmed.startsWith("{");
            assertThat(mine).as("a line that is new but not part of an edited entry: '" + line + "'" + context).isTrue();
        }
    }

    // (b)

    private static void assertTheModelShowsTheChanges(Built built, OverlayText.Loaded before, List<ParameterEdit> applied,
                                                      String text, String context) {
        OverlayText.Loaded after = OverlayText.load("api.yaml", text, built.source().document(), FAIL);
        Set<String> edited = new LinkedHashSet<>();
        applied.forEach(e -> edited.add(e.parameter()));
        assertThat(after.operations()).as("operations" + context).hasSameSizeAs(before.operations());
        for (int i = 0; i < before.operations().size(); i++) {
            ApiOperation old = before.operations().get(i);
            ApiOperation now = after.operations().get(i);
            assertThat(now.id()).isEqualTo(old.id());
            assertThat(without(now, edited)).as("everything but the edited parameters is the same" + context).isEqualTo(without(old, edited));
            for (String name : edited) {
                FieldBinding was = binding(old, name);
                FieldBinding is = binding(now, name);
                Optional<ParameterEdit> edit = applied.stream().filter(e -> e.parameter().equals(name)).findFirst();
                assertThat(is.direction()).as("direction of " + name + context).isEqualTo(edit.flatMap(ParameterEdit::direction)
                        .orElse(was.direction()));
                assertThat(is.semanticType()).as("type of " + name + context).isEqualTo(edit.flatMap(ParameterEdit::type).isPresent()
                        ? edit.flatMap(ParameterEdit::type) : was.semanticType());
                assertThat(is.exposedName()).as("name of " + name + context).isEqualTo(edit.flatMap(ParameterEdit::name).orElse(was.exposedName()));
            }
        }
        // OVL001 goes away exactly for the parameters that were given a direction
        long given = applied.stream().filter(e -> e.direction().isPresent()
                && before.undirected().stream().anyMatch(u -> u.field().name().equals(e.parameter()))).count();
        assertThat(after.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).count())
                .as("errors that remain" + context).isEqualTo(before.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).count() - given);
    }

    private static FieldBinding binding(ApiOperation operation, String name) {
        return operation.bindings().stream().filter(b -> b.fieldPath().equals(name)).findFirst().orElseThrow();
    }

    private static ApiOperation without(ApiOperation op, Set<String> names) {
        List<FieldBinding> kept = op.bindings().stream().filter(b -> !names.contains(b.fieldPath())).map(OverlayEditorPropertyTest::neutral).toList();
        return new ApiOperation(op.id(), op.version(), op.summary(), op.description(), op.tags(), op.program(), op.pcmlPath(),
                op.overlayPath(), op.route(), op.method(), op.callStrategy(), kept, op.errorMapping(), op.cache(), NEUTRAL);
    }

    private static FieldBinding neutral(FieldBinding b) {
        return new FieldBinding(b.fieldPath(), b.field(), b.direction(), b.injection(), b.exposedName(), b.location(), b.semanticType(),
                b.codec(), b.required(), b.description(), b.example(), b.hidden(), b.trim(),
                b.children().stream().map(OverlayEditorPropertyTest::neutral).toList(), NEUTRAL);
    }

    @SuppressWarnings("unused")
    private static boolean isStruct(FieldDef p) {
        return p.wire() instanceof WireType.Struct;
    }
}

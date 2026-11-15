package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads one overlay file that is already in memory, against the PCML it describes, and answers the questions about
 * names that tools which write overlays need. Nothing is read from or written to disk.
 */
public final class OverlayText {

    private OverlayText() {
    }

    /**
     * What {@link #load} found.
     *
     * @param file        the overlay as authored; empty if the file as a whole cannot be used
     * @param operations  the operations that could be built, in file order. As in
     *                    {@link OverlayLoader#loadRepositoryForAnnotation}, a parameter without a direction is built as
     *                    {@code internal} and its {@code OVL001} is still reported
     * @param undirected  the parameters without a direction
     * @param diagnostics every diagnostic of the overlay and of merging it with the PCML, in the order found
     */
    public record Loaded(Optional<OverlayFile> file, List<ApiOperation> operations,
                         List<UndirectedParameter> undirected, List<Diagnostic> diagnostics) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Loaded {
            Objects.requireNonNull(file, "file");
            operations = List.copyOf(operations);
            undirected = List.copyOf(undirected);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * Parses and merges one overlay file. Duplicate ids and routes across files ({@code OVL014}) are a matter for the
     * whole repository and are not checked here.
     *
     * @param sourceName the name used in diagnostics
     * @param text       the overlay file
     * @param pcml       the PCML the overlay names in {@code pcml}
     * @param defaults   the repository defaults
     * @return what was found; never throws for a problem in the text
     */
    public static Loaded load(String sourceName, String text, PcmlDocument pcml, GatewayDefaults defaults) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(pcml, "pcml");
        Objects.requireNonNull(defaults, "defaults");
        List<Diagnostic> diagnostics = new ArrayList<>();
        Optional<OverlayParser.Parsed> parsed = new OverlayParser(new YamlReader(sourceName, diagnostics), sourceName).parse(text);
        if (parsed.isEmpty()) {
            return new Loaded(Optional.empty(), List.of(), List.of(), diagnostics);
        }
        List<UndirectedParameter> undirected = new ArrayList<>();
        List<ApiOperation> operations = OverlayLoader.buildOperations(parsed.get(), pcml, defaults,
                parsed.get().file().pcml().value(), diagnostics, undirected);
        return new Loaded(Optional.of(parsed.get().file()), operations, undirected, diagnostics);
    }

    /** The name the loader gives a parameter that has no {@code name}: {@code P0ORDN} gives {@code p0ordn}. */
    public static String exposedName(String rpgName) {
        return NameRules.exposedName(Objects.requireNonNull(rpgName, "rpgName"));
    }

    /**
     * Whether the loader accepts the text as an exposed name: it must not be blank, and (so that it can be written into a
     * file on one line) must not have leading or trailing white space or any control character. Clashes between names
     * ({@code OVL014}) depend on the other parameters and are found by loading.
     */
    public static boolean isValidExposedName(String name) {
        Objects.requireNonNull(name, "name");
        return !name.isBlank() && name.equals(name.strip()) && name.chars().noneMatch(Character::isISOControl);
    }

    /** The id the loader gives an operation: its {@code id}, or the program name in lower case. */
    public static Optional<String> operationId(OverlayFile file, OperationSettings operation) {
        return operation.id().map(Located::value)
                .or(() -> operation.program().or(file.shared()::program)
                        .map(p -> p.value().toLowerCase(java.util.Locale.ROOT)));
    }
}

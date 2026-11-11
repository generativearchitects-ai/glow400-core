package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.parser.ParseResult;
import ai.generativearchitects.glow400.pcml.parser.PcmlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a customer repository ({@code gateway.yaml}, and one overlay file with its PCML per folder under
 * {@code apis/}) and merges it into an {@link ApiRepositoryModel}.
 *
 * <p>Problems in the repository are returned as diagnostics, never thrown.
 */
public final class OverlayLoader {

    private static final List<String> OVERLAY_NAMES = List.of("api.yaml", "api.yml");

    private final Path realRoot;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, ParseResult> pcmlCache = new HashMap<>();

    private OverlayLoader(Path realRoot) {
        this.realRoot = realRoot;
    }

    /**
     * Loads a customer repository.
     *
     * @param root the repository root: the folder that holds {@code gateway.yaml} and {@code apis/}
     * @return the model, if at least {@code gateway.yaml} could be interpreted, and every diagnostic
     */
    public static RepositoryResult loadRepository(Path root) {
        Path real;
        try {
            real = root.toRealPath();
        } catch (IOException e) {
            real = null;
        }
        if (real == null || !Files.isDirectory(real)) {
            Diagnostic missing = new Diagnostic("OVL013", Severity.ERROR,
                    "Repository root not found or not a directory: " + root, new SourceLocation(".", 0, 0));
            return new RepositoryResult(Optional.empty(), List.of(missing));
        }
        return new OverlayLoader(real).load();
    }

    private RepositoryResult load() {
        Optional<GatewayDefaults> gateway = loadGateway();
        GatewayDefaults defaults = gateway.orElseGet(GatewayDefaults::standard);

        List<ApiOperation> candidates = new ArrayList<>();
        Path apis = realRoot.resolve("apis");
        if (Files.isDirectory(apis)) {
            for (Path folder : sortedChildren(apis, Files::isDirectory)) {
                candidates.addAll(loadFolder(folder, defaults));
            }
        }
        List<ApiOperation> operations = dropDuplicates(candidates);

        diagnostics.sort(Comparator
                .comparing((Diagnostic d) -> d.location().file())
                .thenComparingInt(d -> d.location().line())
                .thenComparingInt(d -> d.location().column()));
        Optional<ApiRepositoryModel> model = gateway.map(g -> new ApiRepositoryModel(g, operations));
        return new RepositoryResult(model, diagnostics);
    }

    // ------------------------------------------------------------------ gateway.yaml

    /** Empty if {@code gateway.yaml} exists but cannot be interpreted. */
    private Optional<GatewayDefaults> loadGateway() {
        Path file = realRoot.resolve("gateway.yaml");
        if (!Files.isRegularFile(file)) {
            return Optional.of(GatewayDefaults.standard());
        }
        Optional<String> text = read(file, "gateway.yaml");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return new GatewayParser(new YamlReader("gateway.yaml", diagnostics)).parse(text.get());
    }

    // ------------------------------------------------------------------ one folder under apis/

    private List<ApiOperation> loadFolder(Path folder, GatewayDefaults defaults) {
        List<Path> overlays = OVERLAY_NAMES.stream().map(folder::resolve).filter(Files::isRegularFile).toList();
        List<Path> pcmls = sortedChildren(folder, p -> Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pcml"));
        if (overlays.size() > 1) {
            report("OVL020", Severity.ERROR, "More than one overlay file in this folder: "
                    + overlays.stream().map(p -> p.getFileName().toString()).toList(), location(overlays.get(1)));
            return List.of();
        }
        if (overlays.isEmpty()) {
            if (!pcmls.isEmpty()) {
                report("OVL019", Severity.WARNING, "This folder has a PCML file but no overlay file (api.yaml)",
                        location(pcmls.get(0)));
            }
            return List.of();
        }

        Path overlay = overlays.get(0);
        String overlayPath = relative(overlay);
        Optional<String> text = read(overlay, overlayPath);
        if (text.isEmpty()) {
            return List.of();
        }
        Optional<OverlayParser.Parsed> parsed = new OverlayParser(new YamlReader(overlayPath, diagnostics), overlayPath)
                .parse(text.get());
        if (parsed.isEmpty()) {
            return List.of();
        }
        OverlayFile file = parsed.get().file();

        Optional<Path> pcmlFile = resolvePcml(overlay, file);
        if (pcmlFile.isEmpty()) {
            return List.of();
        }
        String pcmlPath = relative(pcmlFile.get());
        ParseResult pcml = pcmlCache.computeIfAbsent(pcmlPath, key -> parsePcml(pcmlFile.get(), key));
        if (pcml.hasErrors() || pcml.document().isEmpty()) {
            report("OVL013", Severity.ERROR, "The PCML file " + pcmlPath + " has errors", file.pcml().at());
            return List.of();
        }
        return build(parsed.get(), pcml.document().get(), defaults, pcmlPath);
    }

    /**
     * Merges the operations of one overlay file. An operation without errors is built in full. One that has errors in
     * the overlay is not built, but its names are still checked against its program ({@code OVL002}), so that one run
     * shows everything that is wrong. Nothing is checked for an operation whose program cannot be found ({@code OVL013}).
     */
    private List<ApiOperation> build(OverlayParser.Parsed parsed, PcmlDocument pcml, GatewayDefaults defaults,
                                     String pcmlPath) {
        OverlayFile file = parsed.file();
        ApiBuilder builder = new ApiBuilder(defaults, pcml, file.path(), pcmlPath, diagnostics);
        List<ApiOperation> operations = new ArrayList<>();
        List<String> programNames = new ArrayList<>();
        boolean allResolved = true;
        for (int i = 0; i < file.operations().size(); i++) {
            OperationSettings operation = file.operations().get(i);
            Optional<Located<String>> programName = operation.program().or(file.shared()::program);
            if (programName.isEmpty()) {
                allResolved = false; // the missing or invalid 'program' has been reported by the parser
                continue;
            }
            Optional<ProgramDef> program = pcml.programs().stream()
                    .filter(p -> p.name().equals(programName.get().value())).findFirst();
            if (program.isEmpty()) {
                report("OVL013", Severity.ERROR, "Program '" + programName.get().value() + "' is not in the PCML file "
                        + pcmlPath, programName.get().at());
                allResolved = false;
                continue;
            }
            programNames.add(program.get().name());
            if (parsed.fileValid() && parsed.operationValid().get(i)) {
                builder.build(file, operation, program.get(), programName.get()).ifPresent(operations::add);
            } else {
                builder.checkNames(file, operation, program.get());
            }
        }
        // A shared entry that no program has is reported only when every operation of the file was loaded: read without
        // errors, its program found, and built without errors. Until then the entry may be meant for an operation that
        // is not loaded.
        if (allResolved && parsed.complete() && parsed.fileValid() && builder.errorCount() == 0) {
            builder.reportUnmatchedShared(file.shared(), programNames);
        }
        return parsed.fileValid() ? operations : List.of();
    }

    /** The PCML file named by the overlay, if it exists and lies inside the repository. */
    private Optional<Path> resolvePcml(Path overlay, OverlayFile file) {
        Located<String> pcml = file.pcml();
        Path resolved;
        try {
            resolved = overlay.getParent().resolve(pcml.value()).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            report("OVL012", Severity.ERROR, "'pcml' is not a valid path: " + pcml.value(), pcml.at());
            return Optional.empty();
        }
        if (!resolved.startsWith(realRoot)) {
            report("OVL012", Severity.ERROR, "'pcml' must point to a file inside the repository: " + pcml.value(), pcml.at());
            return Optional.empty();
        }
        if (!Files.isRegularFile(resolved)) {
            report("OVL013", Severity.ERROR, "PCML file not found: " + pcml.value(), pcml.at());
            return Optional.empty();
        }
        try {
            Path real = resolved.toRealPath();
            if (!real.startsWith(realRoot)) {
                report("OVL012", Severity.ERROR, "'pcml' must point to a file inside the repository: " + pcml.value(), pcml.at());
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException e) {
            report("OVL013", Severity.ERROR, "PCML file cannot be read: " + pcml.value(), pcml.at());
            return Optional.empty();
        }
    }

    /** Parses a PCML file once; its diagnostics are reported once, however many overlays use it. */
    private ParseResult parsePcml(Path file, String name) {
        ParseResult result;
        try (InputStream in = Files.newInputStream(file)) {
            result = PcmlParser.parse(name, in);
        } catch (IOException e) {
            result = new ParseResult(Optional.empty(), List.of(new Diagnostic("PCML001", Severity.ERROR,
                    "Cannot read source: " + e.getMessage(), new SourceLocation(name, 0, 0))));
        }
        diagnostics.addAll(result.diagnostics());
        return result;
    }

    // ------------------------------------------------------------------ across the repository

    /** {@code OVL014}: operation ids, and routes with the same method, must be unique; the later one is left out. */
    private List<ApiOperation> dropDuplicates(List<ApiOperation> candidates) {
        Set<String> ids = new HashSet<>();
        Map<String, ApiOperation> routes = new HashMap<>();
        List<ApiOperation> kept = new ArrayList<>();
        for (ApiOperation operation : candidates) {
            if (!ids.add(operation.id())) {
                report("OVL014", Severity.ERROR, "Another operation already has the id '" + operation.id() + "'",
                        operation.source());
                continue;
            }
            String key = operation.method() + " " + RouteRules.shape(operation.route());
            ApiOperation earlier = routes.putIfAbsent(key, operation);
            if (earlier != null) {
                report("OVL014", Severity.ERROR, "Operation '" + operation.id() + "' has the same route and method as '"
                        + earlier.id() + "': " + operation.method() + " " + operation.route(), operation.source());
                continue;
            }
            kept.add(operation);
        }
        return kept;
    }

    // ------------------------------------------------------------------ files

    private Optional<String> read(Path file, String name) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            report("OVL010", Severity.ERROR, "Cannot read file as UTF-8 text: " + e.getMessage(), new SourceLocation(name, 0, 0));
            return Optional.empty();
        }
    }

    private static List<Path> sortedChildren(Path directory, java.util.function.Predicate<Path> filter) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(filter)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String relative(Path file) {
        return realRoot.relativize(file).toString().replace('\\', '/');
    }

    private SourceLocation location(Path file) {
        return new SourceLocation(relative(file), 1, 1);
    }

    private void report(String code, Severity severity, String message, SourceLocation at) {
        diagnostics.add(new Diagnostic(code, severity, message, at));
    }
}

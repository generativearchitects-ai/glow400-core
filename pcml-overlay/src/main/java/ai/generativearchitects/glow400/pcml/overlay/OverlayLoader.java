package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.diagnostics.IoReason;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    /** Non-null when loading for annotation: {@code OVL001} no longer leaves an operation out, and targets are collected. */
    private final List<AnnotationTarget> targets;

    private OverlayLoader(Path realRoot, List<AnnotationTarget> targets) {
        this.realRoot = realRoot;
        this.targets = targets;
    }

    /**
     * Loads a customer repository.
     *
     * @param root the repository root: the folder that holds {@code gateway.yaml} and {@code apis/}
     * @return the model, if at least {@code gateway.yaml} could be interpreted, and every diagnostic
     */
    public static RepositoryResult loadRepository(Path root) {
        return load(root, null);
    }

    /**
     * What {@link #loadRepositoryForAnnotation} returns.
     *
     * @param result  as {@link #loadRepository} would return it, except that an operation whose only errors are
     *                {@code OVL001} is built, with each undirected parameter as {@code internal}; the {@code OVL001}
     *                diagnostics are still in it
     * @param targets the overlay files that have parameters without a direction, in model order
     */
    public record Annotation(RepositoryResult result, List<AnnotationTarget> targets) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Annotation {
            Objects.requireNonNull(result, "result");
            targets = List.copyOf(targets);
        }
    }

    /**
     * Loads a customer repository for a tool that completes the overlays: the same diagnostics as
     * {@link #loadRepository}, but with {@code defaults.inputoutput: fail} an operation is not left out because a
     * parameter has no direction, so that the other errors in it, and the other operations of the file, are seen.
     *
     * @param root the repository root
     * @return the result and the files to complete
     */
    public static Annotation loadRepositoryForAnnotation(Path root) {
        List<AnnotationTarget> targets = new ArrayList<>();
        return new Annotation(load(root, targets), targets);
    }

    private static RepositoryResult load(Path root, List<AnnotationTarget> targets) {
        Path real;
        try {
            real = root.toRealPath();
        } catch (IOException e) {
            real = null;
        }
        if (real == null || !Files.isDirectory(real)) {
            Diagnostic missing = new Diagnostic("OVL013", Severity.ERROR,
                    "Repository root not found or not a directory", new SourceLocation(".", 0, 0));
            return new RepositoryResult(Optional.empty(), List.of(missing));
        }
        return new OverlayLoader(real, targets).load();
    }

    private RepositoryResult load() {
        Optional<GatewayDefaults> gateway = loadGateway();
        GatewayDefaults defaults = gateway.orElseGet(GatewayDefaults::standard);

        List<Pending> pending = new ArrayList<>();
        Path apis = realRoot.resolve("apis");
        if (Files.isDirectory(apis)) {
            for (Path folder : sortedChildren(apis, Files::isDirectory)) {
                loadFolder(folder, defaults).ifPresent(pending::add);
            }
        }
        List<ApiOperation> operations = dropDuplicates(pending.stream().flatMap(p -> p.operations().stream()).toList());
        java.util.Set<ApiOperation> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        kept.addAll(operations);
        List<ApiDefinition> definitions = new ArrayList<>();
        for (Pending api : pending) {
            List<ApiOperation> surviving = api.operations().stream().filter(kept::contains).toList();
            if (!surviving.isEmpty()) {
                Set<String> ids = new HashSet<>();
                surviving.forEach(operation -> ids.add(operation.id()));
                List<MockDefinition> mocks = api.mocks().stream().filter(m -> ids.contains(m.operationId())).toList();
                definitions.add(new ApiDefinition(api.name(), api.title(), api.version(), api.overlayPath(), surviving, mocks));
            }
        }

        diagnostics.sort(Comparator
                .comparing((Diagnostic d) -> d.location().file())
                .thenComparingInt(d -> d.location().line())
                .thenComparingInt(d -> d.location().column()));
        Optional<ApiRepositoryModel> model = gateway.map(g -> new ApiRepositoryModel(g, operations, definitions));
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

    /** The built operations of one overlay file, before duplicates across files are dropped. */
    private record Pending(String name, String title, String version, String overlayPath, List<ApiOperation> operations,
                           List<MockDefinition> mocks) {
    }

    private static final java.util.regex.Pattern API_NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private Optional<Pending> loadFolder(Path folder, GatewayDefaults defaults) {
        List<Path> overlays = OVERLAY_NAMES.stream().map(folder::resolve).filter(Files::isRegularFile).toList();
        List<Path> pcmls = sortedChildren(folder, p -> Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pcml"));
        if (overlays.size() > 1) {
            report("OVL020", Severity.ERROR, "More than one overlay file in this folder: "
                    + overlays.stream().map(p -> p.getFileName().toString()).toList(), location(overlays.get(1)));
            return Optional.empty();
        }
        if (overlays.isEmpty()) {
            if (!pcmls.isEmpty()) {
                report("OVL019", Severity.WARNING, "This folder has a PCML file but no overlay file (api.yaml)",
                        location(pcmls.get(0)));
            }
            return Optional.empty();
        }

        Path overlay = overlays.get(0);
        String overlayPath = relative(overlay);
        Optional<String> text = read(overlay, overlayPath);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Optional<OverlayParser.Parsed> parsed = new OverlayParser(new YamlReader(overlayPath, diagnostics), overlayPath)
                .parse(text.get());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        OverlayFile file = parsed.get().file();

        Optional<Path> pcmlFile = resolvePcml(overlay, file);
        if (pcmlFile.isEmpty()) {
            return Optional.empty();
        }
        String pcmlPath = relative(pcmlFile.get());
        ParseResult pcml = pcmlCache.computeIfAbsent(pcmlPath, key -> parsePcml(pcmlFile.get(), key));
        if (pcml.hasErrors() || pcml.document().isEmpty()) {
            report("OVL013", Severity.ERROR, "The PCML file " + pcmlPath + " has errors", file.pcml().at());
            return Optional.empty();
        }
        List<UndirectedParameter> undirected = targets == null ? null : new ArrayList<>();
        List<ApiOperation> operations = buildOperations(parsed.get(), pcml.document().get(), defaults, pcmlPath, diagnostics,
                undirected);
        String name = ApiRepositoryModel.apiName(overlayPath);
        if (!API_NAME.matcher(name).matches()) {
            report("OVL012", Severity.ERROR, "The API name '" + name + "' (the folder name, lower-cased) must match "
                    + "[a-z0-9][a-z0-9._-]*", location(overlay));
            return Optional.empty();
        }
        if (operations.isEmpty()) {
            return Optional.empty();
        }
        if (targets != null && !undirected.isEmpty()) {
            targets.add(new AnnotationTarget(name, overlayPath, pcmlPath, pcml.document().get(), defaults, undirected));
        }
        String title = file.title().map(Located::value).orElse(name);
        return Optional.of(new Pending(name, title, file.version().value(), overlayPath, operations, loadMocks(folder, overlay, file)));
    }

    // ------------------------------------------------------------------ mock files

    /**
     * The mock files of one API: {@code mock/<operation id>.yaml} next to the overlay. A file whose name is not the id of an
     * operation of the overlay is {@code MCK002}; one that is not valid is {@code MCK001}; neither is loaded.
     */
    private List<MockDefinition> loadMocks(Path folder, Path overlay, OverlayFile file) {
        Path directory = folder.resolve("mock");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (OperationSettings operation : file.operations()) {
            OverlayText.operationId(file, operation).ifPresent(ids::add);
        }
        List<MockDefinition> mocks = new ArrayList<>();
        List<Path> files = sortedChildren(directory, p -> Files.isRegularFile(p) && isYamlName(p.getFileName().toString()));
        for (Path mock : files) {
            String name = mock.getFileName().toString();
            String id = name.substring(0, name.lastIndexOf('.'));
            String path = relative(mock);
            if (!name.endsWith(".yaml") || !ids.contains(id)) {
                report("MCK002", Severity.ERROR, "The mock file " + name + " is not named after an operation of this API: mock files are named "
                        + "<operation id>.yaml, and the operations are " + ids, new SourceLocation(path, 1, 1));
                continue;
            }
            readMock(mock, id, path).ifPresent(mocks::add);
        }
        return mocks;
    }

    private static boolean isYamlName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private Optional<MockDefinition> readMock(Path mock, String id, String path) {
        SourceLocation at = new SourceLocation(path, 1, 1);
        String text;
        try {
            if (!mock.toRealPath().startsWith(realRoot)) {
                report("MCK001", Severity.ERROR, "The mock file must be inside the repository", at);
                return Optional.empty();
            }
            if (Files.size(mock) > MockParser.MAX_BYTES) {
                report("MCK001", Severity.ERROR, "A mock file has at most " + MockParser.MAX_BYTES + " bytes", at);
                return Optional.empty();
            }
            text = Files.readString(mock, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report("MCK001", Severity.ERROR, "Cannot read the mock file as UTF-8 text: " + IoReason.of(e), at);
            return Optional.empty();
        }
        return new MockParser(path).parse(id, text, diagnostics);
    }

    /**
     * Merges the operations of one overlay file. An operation without errors is built in full. One that has errors in
     * the overlay is not built, but its names are still checked against its program ({@code OVL002}), so that one run
     * shows everything that is wrong. Nothing is checked for an operation whose program cannot be found ({@code OVL013}).
     */
    static List<ApiOperation> buildOperations(OverlayParser.Parsed parsed, PcmlDocument pcml, GatewayDefaults defaults,
                                              String pcmlPath, List<Diagnostic> sink, List<UndirectedParameter> undirected) {
        OverlayFile file = parsed.file();
        ApiBuilder builder = new ApiBuilder(defaults, pcml, file.path(), pcmlPath, sink, undirected);
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
                sink.add(new Diagnostic("OVL013", Severity.ERROR, "Program '" + programName.get().value()
                        + "' is not in the PCML file " + pcmlPath, programName.get().at()));
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
        // A shared entry that no program has is reported whenever the program of every operation is known, whatever other
        // errors the file or its operations have. It is skipped only when a program is unresolved (OVL013) or an entry of
        // the list could not be read at all, because only then is the answer unknown.
        if (allResolved && parsed.complete()) {
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
                    "Cannot read source: " + IoReason.of(e), new SourceLocation(name, 0, 0))));
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
            report("OVL010", Severity.ERROR, "Cannot read file as UTF-8 text: " + IoReason.of(e), new SourceLocation(name, 0, 0));
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

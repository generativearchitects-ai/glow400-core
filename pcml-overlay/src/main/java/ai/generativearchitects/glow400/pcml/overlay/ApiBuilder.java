package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.CacheSettings;
import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.StructDef;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Merges one operation of an overlay file with the call model of its program and the repository defaults into an
 * {@link ApiOperation} (merge rules 1 to 8 of kickoff 02), reporting {@code OVL001}, {@code OVL002}, {@code OVL004},
 * {@code OVL012}, {@code OVL013}, {@code OVL014}, {@code OVL015}, {@code OVL016}, {@code OVL017} and {@code OVL018}.
 *
 * <p>One builder serves all operations of one overlay file, because the shared settings of the file are matched
 * against every operation's program.
 */
final class ApiBuilder {

    private final GatewayDefaults defaults;
    private final PcmlDocument pcml;
    private final String overlayPath;
    private final String pcmlPath;
    private final List<Diagnostic> sink;
    /** Positions of the overlay entries (parameters, members, inject keys) that matched something in a program. */
    private final Set<SourceLocation> matched = new HashSet<>();
    private int errors;
    /** Where the operation being built starts in the overlay file; diagnostics fall back to it. */
    private SourceLocation operationAt;

    ApiBuilder(GatewayDefaults defaults, PcmlDocument pcml, String overlayPath, String pcmlPath, List<Diagnostic> sink) {
        this.defaults = defaults;
        this.pcml = pcml;
        this.overlayPath = overlayPath;
        this.pcmlPath = pcmlPath;
        this.sink = sink;
    }

    /** Every diagnostic carries an overlay position: a position inside the PCML (a derived binding) becomes the operation's. */
    private void report(String code, Severity severity, String message, SourceLocation at) {
        if (severity == Severity.ERROR) {
            errors++;
        }
        SourceLocation where = at.file().equals(overlayPath) ? at : operationAt;
        sink.add(new Diagnostic(code, severity, message, where));
    }

    private void error(String code, String message, SourceLocation at) {
        report(code, Severity.ERROR, message, at);
    }

    // ------------------------------------------------------------------ operation

    Optional<ApiOperation> build(OverlayFile file, OperationSettings operation, ProgramDef program,
                                 Located<String> programName) {
        int errorsBefore = errors;
        operationAt = operation.source();
        OperationSettings shared = file.shared();

        String id = operation.id().map(Located::value).orElseGet(() -> program.name().toLowerCase(Locale.ROOT));
        if (operation.id().isEmpty() && !Vocabulary.ID.matcher(id).matches()) {
            error("OVL012", "The default id '" + id + "' (the program name in lower case) is not a valid id: set 'id'",
                    programName.at());
        }
        Located<String> route = operation.route().orElseThrow();
        HttpMethod method = operation.method().orElseThrow().value();
        List<String> routeNames = RouteRules.placeholders(route.value());

        markMatches(program, shared, operation);

        // Inject entries, shared first so that the operation's own win.
        Map<String, Located<String>> inject = new LinkedHashMap<>();
        for (FieldDef parameter : program.parameters()) {
            Located<String> own = operation.inject().get(parameter.name());
            Located<String> common = shared.inject().get(parameter.name());
            if (own != null || common != null) {
                inject.put(parameter.name(), own != null ? own : common);
            }
        }

        List<FieldBinding> bindings = new ArrayList<>();
        for (FieldDef parameter : program.parameters()) {
            List<FieldSettings> layers = new ArrayList<>();
            addLayer(layers, shared.parameters().get(parameter.name()));
            addLayer(layers, operation.parameters().get(parameter.name()));
            bind(parameter, parameter.name(), layers, inject.get(parameter.name()), route, routeNames, method,
                    program).ifPresent(bindings::add);
        }

        if (errors == errorsBefore) {
            // With a missing binding these checks would only repeat the real problem as a second error.
            checkRoute(route, routeNames, bindings);
            checkDuplicateNames(bindings, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        Optional<ErrorMapping> errorMapping = errorMapping(shared, operation, program);
        Optional<CacheSettings> cache = operation.cache().or(shared::cache).map(Located::value);

        reportUnmatched(operation.inject(), operation.parameters(), program.name());

        if (errors > errorsBefore) {
            return Optional.empty();
        }
        List<String> tags = operation.tags().or(shared::tags).orElse(List.of());
        return Optional.of(new ApiOperation(
                id, file.version().value(), operation.summary().map(Located::value),
                operation.description().map(Located::value), tags, program, pcmlPath, overlayPath,
                route.value(), method, file.call().value(), bindings, errorMapping, cache, operation.source()));
    }

    /**
     * Checks only that the names in the overlay exist in the program ({@code OVL002}), without building bindings. Used
     * for an operation that has other errors in the overlay, so that one run reports everything there is to fix.
     */
    void checkNames(OverlayFile file, OperationSettings operation, ProgramDef program) {
        operationAt = operation.source();
        markMatches(program, file.shared(), operation);
        reportUnmatched(operation.inject(), operation.parameters(), program.name());
    }

    /**
     * Marks the overlay entries (inject keys, parameters, struct members) that name something in the program. This walks
     * the program, not the bindings, so it does not depend on whether the bindings could be built.
     */
    private void markMatches(ProgramDef program, OperationSettings shared, OperationSettings operation) {
        for (FieldDef parameter : program.parameters()) {
            Located<String> own = operation.inject().get(parameter.name());
            Located<String> common = shared.inject().get(parameter.name());
            if (own != null) {
                matched.add(own.at());
            }
            if (common != null) {
                matched.add(common.at());
            }
            List<FieldSettings> layers = new ArrayList<>();
            addLayer(layers, shared.parameters().get(parameter.name()));
            addLayer(layers, operation.parameters().get(parameter.name()));
            markFieldMatches(parameter, layers, new ArrayDeque<>());
        }
    }

    private void markFieldMatches(FieldDef field, List<FieldSettings> layers, Deque<String> structStack) {
        layers.forEach(layer -> matched.add(layer.source()));
        if (!(field.wire() instanceof WireType.Struct struct)) {
            return;
        }
        StructDef definition = pcml.structs().get(struct.structName());
        if (definition == null || structStack.contains(struct.structName())) {
            return;
        }
        structStack.push(struct.structName());
        for (FieldDef member : definition.fields()) {
            List<FieldSettings> memberLayers = new ArrayList<>();
            for (FieldSettings layer : layers) {
                addLayer(memberLayers, layer.fields().get(member.name()));
            }
            markFieldMatches(member, memberLayers, structStack);
        }
        structStack.pop();
    }

    private static void addLayer(List<FieldSettings> layers, FieldSettings settings) {
        if (settings != null) {
            layers.add(settings);
        }
    }

    // ------------------------------------------------------------------ bindings

    /** Binds a program parameter. */
    private Optional<FieldBinding> bind(FieldDef parameter, String path, List<FieldSettings> layers,
                                        Located<String> injection, Located<String> route, List<String> routeNames,
                                        HttpMethod method, ProgramDef program) {
        boolean composite = isComposite(parameter);
        SourceLocation source = layers.isEmpty() ? parameter.source() : layers.get(layers.size() - 1).source();

        if (injection != null) {
            return injected(parameter, path, layers, injection, composite);
        }

        Optional<Located<Direction>> overlayDirection = last(layers, FieldSettings::direction);
        Optional<Direction> direction = direction(parameter, overlayDirection, source);
        if (direction.isEmpty()) {
            return Optional.empty();
        }
        String exposedName = last(layers, FieldSettings::name).map(Located::value)
                .orElseGet(() -> NameRules.exposedName(parameter.name()));

        Optional<Located<Location>> explicit = last(layers, FieldSettings::in);
        Optional<Location> location = Optional.empty();
        if (direction.get() == Direction.REQUEST || direction.get() == Direction.BOTH) {
            Location chosen = explicit.map(Located::value)
                    .orElseGet(() -> defaultLocation(exposedName, routeNames, method));
            SourceLocation at = explicit.map(Located::at).orElse(source);
            boolean noBody = method == HttpMethod.GET || method == HttpMethod.DELETE;
            if (composite && noBody) {
                error("OVL015", "Parameter '" + path + "' is a struct or an array and cannot be sent with "
                        + method + ": use POST, PUT or PATCH", at);
                return Optional.empty();
            }
            if (composite && chosen != Location.BODY) {
                error("OVL015", "Parameter '" + path + "' is a struct or an array and cannot be sent in the "
                        + chosen.name().toLowerCase(Locale.ROOT) + "; set 'in: body'", at);
                return Optional.empty();
            }
            if (noBody && chosen == Location.BODY) {
                error("OVL015", "Parameter '" + path + "' cannot be sent in the body with " + method
                        + ": use 'in: query', or POST, PUT or PATCH", at);
                return Optional.empty();
            }
            location = Optional.of(chosen);
        } else if (explicit.isPresent()) {
            error("OVL012", "'in' has no effect on parameter '" + path + "': its direction is "
                    + direction.get().name().toLowerCase(Locale.ROOT) + ", and only request fields have a location",
                    explicit.get().at());
            return Optional.empty();
        }

        if (!validTrim(parameter, layers, program)) {
            return Optional.empty();
        }
        Optional<Trim> trim = last(layers, FieldSettings::trim).map(Located::value);
        return common(parameter, path, layers, direction.get(), location, Optional.empty(), exposedName, false, trim,
                source, new ArrayDeque<>());
    }

    private Optional<FieldBinding> injected(FieldDef parameter, String path, List<FieldSettings> layers,
                                            Located<String> injection, boolean composite) {
        if (!layers.isEmpty()) {
            last(layers, FieldSettings::in).ifPresent(in -> error("OVL012", "'in' has no effect on parameter '" + path
                    + "': its direction is injected, and only request fields have a location", in.at()));
            error("OVL018", "Parameter '" + path + "' is configured by both 'inject' and 'parameters'", injection.at());
            return Optional.empty();
        }
        if (composite) {
            error("OVL012", "Parameter '" + path + "' is a struct or an array and cannot be injected", injection.at());
            return Optional.empty();
        }
        return common(parameter, path, List.of(), Direction.INJECTED, Optional.empty(), Optional.of(injection.value()),
                NameRules.exposedName(parameter.name()), false, Optional.empty(), injection.at(), new ArrayDeque<>());
    }

    /** Builds the binding once direction and location are known; shared by parameters and struct members. */
    private Optional<FieldBinding> common(FieldDef field, String path, List<FieldSettings> layers, Direction direction,
                                          Optional<Location> location, Optional<String> injection, String exposedName,
                                          boolean member, Optional<Trim> trim, SourceLocation source,
                                          Deque<String> structStack) {
        WireType wire = field.wire();
        Optional<SemanticType> semantic = NameRules.semanticType(wire);
        Optional<Located<SemanticType>> overlayType = last(layers, FieldSettings::type);
        if (overlayType.isPresent()) {
            if (wire instanceof WireType.Struct) {
                error("OVL012", "'" + path + "' is a struct: 'type' cannot be set on it", overlayType.get().at());
                return Optional.empty();
            }
            semantic = Optional.of(overlayType.get().value());
        }

        boolean text = wire instanceof WireType.Char || wire instanceof WireType.VarChar;
        CodecSettings base = text ? defaults.charCodec() : CodecSettings.none();
        boolean isBoolean = semantic.isPresent() && semantic.get() == SemanticType.BOOLEAN;
        Optional<String> trueValue = last(layers, FieldSettings::trueValue).map(Located::value);
        Optional<String> falseValue = last(layers, FieldSettings::falseValue).map(Located::value);
        if (isBoolean) {
            trueValue = Optional.of(trueValue.orElse(CodecSettings.DEFAULT_TRUE_VALUE));
            falseValue = Optional.of(falseValue.orElse(CodecSettings.DEFAULT_FALSE_VALUE));
        }
        CodecSettings codec = wire instanceof WireType.Struct
                ? new CodecSettings(Pad.NONE, ' ', "", Optional.empty(), trueValue, falseValue)
                : new CodecSettings(
                last(layers, FieldSettings::pad).map(Located::value).orElse(base.pad()),
                last(layers, FieldSettings::padChar).map(Located::value).orElse(base.padChar()),
                last(layers, FieldSettings::nullAs).map(Located::value).orElse(base.nullAs()),
                last(layers, FieldSettings::format).map(Located::value),
                trueValue, falseValue);

        boolean required = last(layers, FieldSettings::required).map(Located::value)
                .orElse(location.isPresent() && location.get() == Location.PATH);
        boolean hidden = member && last(layers, FieldSettings::hidden).map(Located::value).orElse(false);

        List<FieldBinding> children = List.of();
        if (wire instanceof WireType.Struct struct) {
            Optional<List<FieldBinding>> built = children(field, struct, path, layers, direction, location, structStack);
            if (built.isEmpty()) {
                return Optional.empty();
            }
            children = built.get();
        }

        return Optional.of(new FieldBinding(path, direction, injection, exposedName, location, semantic, codec, required,
                last(layers, FieldSettings::description).map(Located::value),
                last(layers, FieldSettings::example).map(Located::value),
                hidden, trim, children, source));
    }

    private Optional<List<FieldBinding>> children(FieldDef parent, WireType.Struct struct, String path,
                                                  List<FieldSettings> layers, Direction direction,
                                                  Optional<Location> location, Deque<String> structStack) {
        StructDef definition = pcml.structs().get(struct.structName());
        if (definition == null || structStack.contains(struct.structName())) {
            error("OVL013", "'" + path + "' uses the struct '" + struct.structName() + "', which refers to itself", parent.source());
            return Optional.empty();
        }
        structStack.push(struct.structName());
        List<FieldBinding> children = new ArrayList<>();
        boolean ok = true;
        for (FieldDef member : definition.fields()) {
            List<FieldSettings> memberLayers = new ArrayList<>();
            for (FieldSettings layer : layers) {
                addLayer(memberLayers, layer.fields().get(member.name()));
            }
            SourceLocation source = memberLayers.isEmpty() ? member.source()
                    : memberLayers.get(memberLayers.size() - 1).source();
            String name = last(memberLayers, FieldSettings::name).map(Located::value)
                    .orElseGet(() -> NameRules.exposedName(member.name()));
            Optional<FieldBinding> binding = common(member, path + "." + member.name(), memberLayers, direction, location,
                    Optional.empty(), name, true, Optional.empty(), source, structStack);
            if (binding.isPresent()) {
                children.add(binding.get());
            } else {
                ok = false;
            }
        }
        structStack.pop();
        return ok ? Optional.of(children) : Optional.empty();
    }

    /** Merge rules 1 and 2. Empty means an error has been reported. */
    private Optional<Direction> direction(FieldDef parameter, Optional<Located<Direction>> overlay, SourceLocation source) {
        return switch (parameter.usage()) {
            case INPUT -> constrained(parameter, overlay, Direction.REQUEST, "input");
            case OUTPUT -> constrained(parameter, overlay, Direction.RESPONSE, "output");
            case INPUT_OUTPUT, INHERIT -> {
                if (overlay.isPresent()) {
                    yield Optional.of(overlay.get().value());
                }
                String usage = parameter.usage() == ai.generativearchitects.glow400.pcml.model.Usage.INPUT_OUTPUT
                        ? "inputoutput" : "inherit";
                String message = "Parameter '" + parameter.name() + "' has usage " + usage
                        + " and no direction in the overlay: set 'direction' for it";
                if (defaults.inputOutput() == InputOutputPolicy.BOTH) {
                    report("OVL001", Severity.WARNING, message + "; treated as 'both'", source);
                    yield Optional.of(Direction.BOTH);
                }
                error("OVL001", message, source);
                yield Optional.empty();
            }
        };
    }

    private Optional<Direction> constrained(FieldDef parameter, Optional<Located<Direction>> overlay, Direction derived,
                                            String usage) {
        if (overlay.isEmpty()) {
            return Optional.of(derived);
        }
        Direction chosen = overlay.get().value();
        if (chosen == derived || chosen == Direction.INTERNAL) {
            return Optional.of(chosen);
        }
        error("OVL016", "Direction '" + chosen.name().toLowerCase(Locale.ROOT) + "' of parameter '" + parameter.name()
                + "' contradicts its PCML usage " + usage, overlay.get().at());
        return Optional.empty();
    }

    /** Merge rule 6. */
    private static Location defaultLocation(String exposedName, List<String> routeNames, HttpMethod method) {
        if (routeNames.contains(exposedName)) {
            return Location.PATH;
        }
        return method == HttpMethod.GET || method == HttpMethod.DELETE ? Location.QUERY : Location.BODY;
    }

    private static boolean isComposite(FieldDef field) {
        return field.wire() instanceof WireType.Struct || !(field.count() instanceof Count.None);
    }

    private static <T> Optional<Located<T>> last(List<FieldSettings> layers,
                                                 Function<FieldSettings, Optional<Located<T>>> setting) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Optional<Located<T>> value = setting.apply(layers.get(i));
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ trim

    /** Checks the {@code trim} of a parameter: only arrays can be trimmed, and a count field must be a scalar. */
    private boolean validTrim(FieldDef parameter, List<FieldSettings> layers, ProgramDef program) {
        Optional<Located<Trim>> trim = last(layers, FieldSettings::trim);
        if (trim.isEmpty()) {
            return true;
        }
        if (parameter.count() instanceof Count.None) {
            error("OVL012", "'trim' applies to arrays only, and '" + parameter.name() + "' is not an array", trim.get().at());
            return false;
        }
        if (trim.get().value() instanceof Trim.CountField countField
                && !isScalar(countField.path(), program.parameters())) {
            error("OVL017", "The count field '" + countField.path() + "' does not exist or is not a scalar",
                    trim.get().at());
            return false;
        }
        return true;
    }

    /** Whether a dotted path names a scalar, non-array field, walking through struct fields. */
    private boolean isScalar(String path, List<FieldDef> fields) {
        String[] parts = path.split("\\.");
        List<FieldDef> scope = fields;
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            FieldDef found = scope.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
            if (found == null || !(found.count() instanceof Count.None)) {
                return false;
            }
            boolean last = i == parts.length - 1;
            if (last) {
                return !(found.wire() instanceof WireType.Struct);
            }
            if (!(found.wire() instanceof WireType.Struct struct) || pcml.structs().get(struct.structName()) == null) {
                return false;
            }
            scope = pcml.structs().get(struct.structName()).fields();
        }
        return false;
    }

    // ------------------------------------------------------------------ route, names

    /** {@code OVL004}: route placeholders and PATH bindings must correspond. */
    private void checkRoute(Located<String> route, List<String> routeNames, List<FieldBinding> bindings) {
        Set<String> pathNames = new HashSet<>();
        for (FieldBinding binding : bindings) {
            if (binding.location().isPresent() && binding.location().get() == Location.PATH) {
                pathNames.add(binding.exposedName());
                if (!routeNames.contains(binding.exposedName())) {
                    error("OVL004", "'" + binding.fieldPath() + "' is bound to the path but the route has no placeholder {"
                            + binding.exposedName() + "}", binding.source());
                }
            }
        }
        for (String name : routeNames) {
            if (!pathNames.contains(name)) {
                error("OVL004", "The route placeholder {" + name + "} has no binding with 'in: path'", route.at());
            }
        }
    }

    /**
     * {@code OVL014}: within one scope, two fields that appear on the same side of the API (request or response)
     * must not share an exposed name. Hidden, internal and injected fields do not count.
     */
    private void checkDuplicateNames(List<FieldBinding> scope, Set<FieldBinding> reported) {
        for (boolean request : new boolean[] {true, false}) {
            Map<String, FieldBinding> seen = new LinkedHashMap<>();
            for (FieldBinding binding : scope) {
                if (binding.hidden() || !onSide(binding.direction(), request)) {
                    continue;
                }
                FieldBinding earlier = seen.putIfAbsent(binding.exposedName(), binding);
                if (earlier != null && reported.add(binding)) {
                    error("OVL014", "'" + binding.fieldPath() + "' and '" + earlier.fieldPath()
                            + "' share the exposed name '" + binding.exposedName() + "'", binding.source());
                }
            }
        }
        for (FieldBinding binding : scope) {
            if (!binding.children().isEmpty()) {
                checkDuplicateNames(binding.children(), reported);
            }
        }
    }

    private static boolean onSide(Direction direction, boolean request) {
        return direction == Direction.BOTH || direction == (request ? Direction.REQUEST : Direction.RESPONSE);
    }

    // ------------------------------------------------------------------ error mapping

    private Optional<ErrorMapping> errorMapping(OperationSettings shared, OperationSettings operation, ProgramDef program) {
        List<ErrorSettings> layers = new ArrayList<>();
        shared.error().ifPresent(layers::add);
        operation.error().ifPresent(layers::add);
        if (layers.isEmpty()) {
            return Optional.empty();
        }
        Optional<Located<ErrorCondition>> when = Optional.empty();
        Optional<Located<String>> code = Optional.empty();
        Optional<Located<String>> message = Optional.empty();
        Optional<Located<Integer>> status = Optional.empty();
        Map<String, Located<Integer>> byCode = new LinkedHashMap<>();
        for (ErrorSettings layer : layers) {
            when = layer.when().isPresent() ? layer.when() : when;
            code = layer.code().isPresent() ? layer.code() : code;
            message = layer.message().isPresent() ? layer.message() : message;
            status = layer.httpStatus().isPresent() ? layer.httpStatus() : status;
            byCode.putAll(layer.statusByCode());
        }
        SourceLocation errorAt = layers.get(layers.size() - 1).source();
        if (when.isEmpty()) {
            error("OVL012", "'error' needs a 'when' condition", errorAt);
            return Optional.empty();
        }
        boolean ok = true;
        ok &= requireScalar(when.get().value().field(), when.get().at(), program);
        if (code.isPresent()) {
            ok &= requireScalar(code.get().value(), code.get().at(), program);
        }
        if (message.isPresent()) {
            ok &= requireScalar(message.get().value(), message.get().at(), program);
        }
        if (!ok) {
            return Optional.empty();
        }
        Map<String, Integer> statuses = new LinkedHashMap<>();
        byCode.forEach((key, value) -> statuses.put(key, value.value()));
        return Optional.of(new ErrorMapping(when.get().value(), code.map(Located::value), message.map(Located::value),
                status.map(Located::value).orElse(ErrorMapping.DEFAULT_HTTP_STATUS), statuses));
    }

    private boolean requireScalar(String path, SourceLocation at, ProgramDef program) {
        if (isScalar(path, program.parameters())) {
            return true;
        }
        error("OVL017", "The field '" + path + "' named in 'error' does not exist or is not a scalar", at);
        return false;
    }

    // ------------------------------------------------------------------ unmatched entries (OVL002)

    /** Reports the entries of an operation's own settings that name nothing in its program. */
    private void reportUnmatched(Map<String, Located<String>> inject, Map<String, FieldSettings> parameters, String program) {
        Map<SourceLocation, String> entries = new LinkedHashMap<>();
        inject.forEach((name, value) -> entries.put(value.at(), "'inject' entry '" + name + "'"));
        collect(parameters, "", entries);
        entries.forEach((at, what) -> {
            if (!matched.contains(at)) {
                error("OVL002", what + " is not in the PCML of program " + program, at);
            }
        });
    }

    /**
     * Reports the entries of the shared settings of a file that no operation's program has. Called once, after
     * every operation was built.
     */
    void reportUnmatchedShared(OperationSettings shared, List<String> programs) {
        Map<SourceLocation, String> entries = new LinkedHashMap<>();
        shared.inject().forEach((name, value) -> entries.put(value.at(), "'inject' entry '" + name + "'"));
        collect(shared.parameters(), "", entries);
        entries.forEach((at, what) -> {
            if (!matched.contains(at)) {
                error("OVL002", what + " is not in the PCML of any program of this file ("
                        + String.join(", ", programs) + ")", at);
            }
        });
    }

    private static void collect(Map<String, FieldSettings> settings, String prefix, Map<SourceLocation, String> into) {
        settings.forEach((name, field) -> {
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            into.put(field.source(), (prefix.isEmpty() ? "Parameter '" : "Struct member '") + path + "'");
            collect(field.fields(), path, into);
        });
    }

    int errorCount() {
        return errors;
    }
}

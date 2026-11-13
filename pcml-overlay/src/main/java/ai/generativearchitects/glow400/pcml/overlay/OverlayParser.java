package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.CacheSettings;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.Node;

/**
 * Reads an overlay file ({@code api.yaml}) into an {@link OverlayFile}: checks keys, value types, enumerations,
 * ranges and the two file forms, and reports {@code OVL010}, {@code OVL011}, {@code OVL012} and {@code OVL020}.
 */
final class OverlayParser {

    /**
     * Result of parsing: whatever could be read, even if there are errors, so that the names in it can still be checked
     * against the PCML. Only a file whose root, {@code pcml} or operations cannot be read at all gives no result.
     *
     * @param file           the overlay file as far as it could be read; operations that could not be read at all are missing
     * @param operationValid for each operation of {@code file}, whether it is free of errors
     * @param fileValid      {@code false} if the header, the shared settings or the list itself has an error; no operation
     *                       of such a file is used
     * @param complete       {@code true} if every entry of the operations list could be read, with or without errors; if
     *                       one could not, its program is unknown and a shared entry that no operation uses cannot be reported
     */
    record Parsed(OverlayFile file, List<Boolean> operationValid, boolean fileValid, boolean complete) {
    }

    private enum Level { SINGLE, SHARED, OPERATION }

    private static final Set<String> HEADER_KEYS = Set.of("pcml", "version", "call", "title");
    private static final Set<String> SETTING_KEYS = Set.of("program", "tags", "inject", "parameters", "error", "cache");
    private static final Set<String> SINGLE_KEYS = union(HEADER_KEYS, SETTING_KEYS,
            Set.of("id", "route", "method", "summary", "description"));
    private static final Set<String> SHARED_KEYS = union(HEADER_KEYS, SETTING_KEYS, Set.of("operations"));
    private static final Set<String> OPERATION_KEYS = union(SETTING_KEYS,
            Set.of("id", "route", "method", "summary", "description"));
    private static final Set<String> PARAMETER_KEYS = Set.of("direction", "name", "in", "type", "format", "pad", "padChar",
            "nullAs", "required", "description", "example", "trim", "trueValue", "falseValue", "fields");
    private static final Set<String> MEMBER_KEYS = Set.of("name", "type", "format", "pad", "padChar", "nullAs",
            "required", "description", "example", "hidden", "trueValue", "falseValue", "fields");

    private final YamlReader yaml;
    private final GatewayParser scalars;
    private final String path;

    OverlayParser(YamlReader yaml, String path) {
        this.yaml = yaml;
        this.scalars = new GatewayParser(yaml);
        this.path = path;
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new java.util.HashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }

    /** Parses the text; returns empty, with diagnostics, if the file as a whole cannot be used. */
    Optional<Parsed> parse(String text) {
        int errorsBefore = yaml.errorCount();
        YamlReader.Composition composition = yaml.compose(text);
        if (!composition.valid()) {
            return Optional.empty();
        }
        if (composition.root().isEmpty() || GatewayParser.isNull(composition.root().get())) {
            yaml.error("OVL012", "The overlay file is empty", yaml.start());
            return Optional.empty();
        }
        Node root = composition.root().get();
        Optional<List<YamlReader.Entry>> top = yaml.mapping(root, "the overlay file");
        if (top.isEmpty()) {
            return Optional.empty();
        }
        List<YamlReader.Entry> entries = top.get();
        Optional<YamlReader.Entry> operationsEntry = yaml.find(entries, "operations");
        boolean list = operationsEntry.isPresent();

        Set<String> allowed = list ? union(SHARED_KEYS, Set.of("route", "method")) : SINGLE_KEYS;
        yaml.knownKeys(entries, allowed, "the overlay file");
        if (list) {
            for (String key : List.of("route", "method")) {
                if (yaml.find(entries, key).isPresent()) {
                    yaml.error("OVL020", "A file has either top-level 'route' and 'method', or 'operations', not both",
                            operationsEntry.get().keyAt());
                    break;
                }
            }
        }

        Optional<Located<String>> pcml = required(entries, "pcml", yaml.at(root))
                .flatMap(yaml::nonBlankString);
        Optional<Located<String>> version = required(entries, "version", yaml.at(root)).flatMap(this::version);
        Located<CallStrategy> call = yaml.find(entries, "call")
                .flatMap(e -> yaml.choice(e, Vocabulary.CALL))
                .orElseGet(() -> new Located<>(CallStrategy.AUTO, yaml.at(root)));

        Optional<Located<String>> title = yaml.find(entries, "title").flatMap(yaml::nonBlankString);

        OperationSettings shared;
        boolean sharedProgramKey = false;
        List<OperationSettings> operations = new ArrayList<>();
        List<Boolean> operationValid = new ArrayList<>();
        boolean complete = true;
        int fileLevelErrors;
        if (list) {
            shared = settings(entries, Level.SHARED, yaml.at(root));
            sharedProgramKey = yaml.find(entries, "program").isPresent();
            Optional<List<Node>> items = yaml.sequence(operationsEntry.get().value(), "'operations'");
            if (items.isPresent() && items.get().isEmpty()) {
                yaml.error("OVL012", "'operations' must have at least one operation", operationsEntry.get().keyAt());
            }
            // Errors so far belong to the file as a whole; from here on an error only invalidates its own operation.
            fileLevelErrors = yaml.errorCount() - errorsBefore;
            for (Node item : items.orElse(List.of())) {
                int before = yaml.errorCount();
                Optional<OperationSettings> operation = operation(item, shared, sharedProgramKey);
                boolean valid = operation.isPresent() && yaml.errorCount() == before;
                operation.ifPresent(o -> {
                    operations.add(o);
                    operationValid.add(valid);
                });
                complete &= operation.isPresent();
            }
        } else {
            shared = emptySettings(yaml.at(root));
            OperationSettings single = settings(entries, Level.SINGLE, yaml.at(root));
            requireProgram(entries, single, shared, false);
            operations.add(single);
            // The file is one operation: any error invalidates it.
            fileLevelErrors = yaml.errorCount() - errorsBefore;
            operationValid.add(fileLevelErrors == 0);
        }
        if (pcml.isEmpty() || operations.isEmpty()) {
            return Optional.empty();
        }
        // An invalid version is replaced by a placeholder: the operations of such a file are never used.
        Located<String> versionValue = version.orElseGet(() -> new Located<>("0.0.0", yaml.at(root)));
        return Optional.of(new Parsed(new OverlayFile(path, pcml.get(), versionValue, title, call, shared, operations),
                operationValid, fileLevelErrors == 0, complete));
    }

    private Optional<YamlReader.Entry> required(List<YamlReader.Entry> entries, String key, SourceLocation where) {
        Optional<YamlReader.Entry> entry = yaml.find(entries, key);
        if (entry.isEmpty()) {
            yaml.error("OVL012", "Missing required key '" + key + "'", where);
        }
        return entry;
    }

    private Optional<Located<String>> version(YamlReader.Entry entry) {
        Optional<Located<String>> text = yaml.string(entry);
        if (text.isPresent() && !Vocabulary.SEMVER.matcher(text.get().value()).matches()) {
            yaml.error("OVL012", "'version' must be a semantic version such as 1.0.0, not '" + text.get().value() + "'",
                    text.get().at());
            return Optional.empty();
        }
        return text;
    }

    private OperationSettings emptySettings(SourceLocation source) {
        return new OperationSettings(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of(), Optional.empty(),
                Optional.empty(), source);
    }

    // ------------------------------------------------------------------ operations

    private Optional<OperationSettings> operation(Node item, OperationSettings shared, boolean sharedProgramKey) {
        Optional<List<YamlReader.Entry>> entries = yaml.mapping(item, "an operation");
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        // An unknown key is reported, and the rest of the operation is still read so that its names can be checked.
        yaml.knownKeys(entries.get(), OPERATION_KEYS, "an operation");
        OperationSettings operation = settings(entries.get(), Level.OPERATION, yaml.at(item));
        requireProgram(entries.get(), operation, shared, sharedProgramKey);
        return Optional.of(operation);
    }

    /**
     * Every operation needs a program, its own or the shared one. A key that is present but invalid has been reported
     * already, so only a key that is absent everywhere is reported here.
     */
    private void requireProgram(List<YamlReader.Entry> entries, OperationSettings operation, OperationSettings shared,
                                boolean sharedProgramKey) {
        boolean own = yaml.find(entries, "program").isPresent();
        if (!own && !sharedProgramKey) {
            yaml.error("OVL012", "Missing required key 'program'", operation.source());
        }
    }

    private OperationSettings settings(List<YamlReader.Entry> entries, Level level, SourceLocation source) {
        Optional<Located<String>> id = Optional.empty();
        Optional<Located<String>> program = Optional.empty();
        Optional<Located<String>> route = Optional.empty();
        Optional<Located<ai.generativearchitects.glow400.pcml.api.HttpMethod>> method = Optional.empty();
        Optional<Located<String>> summary = Optional.empty();
        Optional<Located<String>> description = Optional.empty();
        Optional<List<String>> tags = Optional.empty();
        Map<String, Located<String>> inject = new LinkedHashMap<>();
        Map<String, FieldSettings> parameters = new LinkedHashMap<>();
        Optional<ErrorSettings> error = Optional.empty();
        Optional<Located<CacheSettings>> cache = Optional.empty();

        for (YamlReader.Entry entry : entries) {
            switch (entry.key()) {
                case "id" -> id = identifier(entry);
                case "program" -> program = yaml.nonBlankString(entry);
                case "route" -> route = route(entry);
                case "method" -> method = yaml.choice(entry, Vocabulary.METHOD);
                case "summary" -> summary = yaml.string(entry);
                case "description" -> description = yaml.string(entry);
                case "tags" -> tags = tags(entry);
                case "inject" -> inject = inject(entry);
                case "parameters" -> parameters = parameters(entry);
                case "error" -> error = error(entry);
                case "cache" -> cache = cache(entry);
                default -> { }
            }
        }
        if (level != Level.SHARED) {
            if (route.isEmpty() && yaml.find(entries, "route").isEmpty()) {
                yaml.error("OVL012", "Missing required key 'route'", source);
            }
            if (method.isEmpty() && yaml.find(entries, "method").isEmpty()) {
                yaml.error("OVL012", "Missing required key 'method'", source);
            }
        }
        if (level == Level.OPERATION && yaml.find(entries, "id").isEmpty()) {
            yaml.error("OVL012", "Missing required key 'id' (an operations list needs an id for every operation)", source);
        }
        return new OperationSettings(id, program, route, method, summary, description, tags, inject, parameters,
                error, cache, source);
    }

    private Optional<Located<String>> identifier(YamlReader.Entry entry) {
        Optional<Located<String>> text = yaml.nonBlankString(entry);
        if (text.isPresent() && !Vocabulary.ID.matcher(text.get().value()).matches()) {
            yaml.error("OVL012", "'id' may contain letters, digits, '.', '-' and '_', and must start with a letter or digit: '"
                    + text.get().value() + "'", text.get().at());
            return Optional.empty();
        }
        return text;
    }

    private Optional<Located<String>> route(YamlReader.Entry entry) {
        Optional<Located<String>> text = yaml.nonBlankString(entry);
        if (text.isPresent()) {
            Optional<String> problem = RouteRules.problem(text.get().value());
            if (problem.isPresent()) {
                yaml.error("OVL012", "'route' " + problem.get() + ": '" + text.get().value() + "'", text.get().at());
                return Optional.empty();
            }
        }
        return text;
    }

    private Optional<List<String>> tags(YamlReader.Entry entry) {
        Optional<List<Node>> items = yaml.sequence(entry.value(), "'tags'");
        if (items.isEmpty()) {
            return Optional.empty();
        }
        List<String> tags = new ArrayList<>();
        for (Node item : items.get()) {
            yaml.scalarText(item, "a tag").ifPresent(tag -> tags.add(tag.value()));
        }
        return Optional.of(tags);
    }

    private Map<String, Located<String>> inject(YamlReader.Entry entry) {
        Map<String, Located<String>> result = new LinkedHashMap<>();
        yaml.mapping(entry.value(), "'inject'").ifPresent(entries -> {
            for (YamlReader.Entry e : entries) {
                yaml.scalarText(e.value(), "the value of '" + e.key() + "'").ifPresent(value -> {
                    String text = value.value();
                    if (text.contains("${") && !Vocabulary.EXPRESSION.matcher(text).matches()) {
                        yaml.error("OVL012", "'" + text + "' is not a valid expression: use ${environment.<name>} or "
                                + "${tenant.<name>}", value.at());
                    } else {
                        result.put(e.key(), new Located<>(text, e.keyAt()));
                    }
                });
            }
        });
        return result;
    }

    // ------------------------------------------------------------------ parameters and fields

    private Map<String, FieldSettings> parameters(YamlReader.Entry entry) {
        return fieldMap(entry.value(), "'parameters'", true);
    }

    private Map<String, FieldSettings> fieldMap(Node node, String what, boolean parameters) {
        Map<String, FieldSettings> result = new LinkedHashMap<>();
        yaml.mapping(node, what).ifPresent(entries -> {
            for (YamlReader.Entry e : entries) {
                result.put(e.key(), field(e, parameters));
            }
        });
        return result;
    }

    private FieldSettings field(YamlReader.Entry entry, boolean parameter) {
        Optional<List<YamlReader.Entry>> body = yaml.mapping(entry.value(), "the settings of '" + entry.key() + "'");
        Optional<Located<ai.generativearchitects.glow400.pcml.api.Direction>> direction = Optional.empty();
        Optional<Located<ai.generativearchitects.glow400.pcml.api.Location>> in = Optional.empty();
        Optional<Located<Trim>> trim = Optional.empty();
        Optional<Located<String>> name = Optional.empty();
        Optional<Located<ai.generativearchitects.glow400.pcml.api.SemanticType>> type = Optional.empty();
        Optional<Located<String>> format = Optional.empty();
        Optional<Located<ai.generativearchitects.glow400.pcml.api.Pad>> pad = Optional.empty();
        Optional<Located<Character>> padChar = Optional.empty();
        Optional<Located<String>> nullAs = Optional.empty();
        Optional<Located<Boolean>> required = Optional.empty();
        Optional<Located<String>> description = Optional.empty();
        Optional<Located<String>> example = Optional.empty();
        Optional<Located<Boolean>> hidden = Optional.empty();
        Optional<Located<String>> trueValue = Optional.empty();
        Optional<Located<String>> falseValue = Optional.empty();
        Map<String, FieldSettings> fields = new LinkedHashMap<>();
        if (body.isPresent()) {
            yaml.knownKeys(body.get(), parameter ? PARAMETER_KEYS : MEMBER_KEYS, "the settings of '" + entry.key() + "'");
            for (YamlReader.Entry e : body.get()) {
                switch (e.key()) {
                    case "direction" -> { if (parameter) { direction = yaml.choice(e, Vocabulary.DIRECTION); } }
                    case "in" -> { if (parameter) { in = yaml.choice(e, Vocabulary.IN); } }
                    case "trim" -> { if (parameter) { trim = trim(e); } }
                    case "name" -> name = yaml.nonBlankString(e);
                    case "type" -> type = yaml.choice(e, Vocabulary.TYPE);
                    case "format" -> format = yaml.string(e);
                    case "pad" -> pad = yaml.choice(e, Vocabulary.PAD);
                    case "padChar" -> padChar = scalars.padChar(e).map(c -> new Located<>(c, yaml.at(e.value())));
                    case "nullAs" -> nullAs = yaml.string(e);
                    case "required" -> required = yaml.bool(e);
                    case "description" -> description = yaml.string(e);
                    case "example" -> example = yaml.scalarText(e.value(), "'example'");
                    case "hidden" -> { if (!parameter) { hidden = yaml.bool(e); } }
                    case "trueValue" -> trueValue = yaml.nonBlankString(e);
                    case "falseValue" -> falseValue = yaml.nonBlankString(e);
                    case "fields" -> fields = fieldMap(e.value(), "'fields'", false);
                    default -> { }
                }
            }
        }
        return new FieldSettings(direction, in, trim, name, type, format, pad, padChar, nullAs, required, description,
                example, hidden, trueValue, falseValue, fields, entry.keyAt());
    }

    private Optional<Located<Trim>> trim(YamlReader.Entry entry) {
        Optional<List<YamlReader.Entry>> body = yaml.mapping(entry.value(), "'trim'");
        if (body.isEmpty()) {
            return Optional.empty();
        }
        if (!yaml.knownKeys(body.get(), Set.of("countField", "dropBlank"), "'trim'")) {
            return Optional.empty();
        }
        Optional<YamlReader.Entry> countField = yaml.find(body.get(), "countField");
        Optional<YamlReader.Entry> dropBlank = yaml.find(body.get(), "dropBlank");
        SourceLocation at = yaml.at(entry.value());
        if (countField.isPresent() == dropBlank.isPresent()) {
            yaml.error("OVL012", "'trim' needs exactly one of 'countField' and 'dropBlank'", at);
            return Optional.empty();
        }
        if (countField.isPresent()) {
            return yaml.nonBlankString(countField.get()).map(path -> new Located<>(new Trim.CountField(path.value()), at));
        }
        Optional<Located<Boolean>> flag = yaml.bool(dropBlank.get());
        if (flag.isPresent() && !flag.get().value()) {
            yaml.error("OVL012", "'dropBlank' must be true; leave 'trim' out to keep every element", flag.get().at());
            return Optional.empty();
        }
        return flag.map(f -> new Located<>(new Trim.DropBlank(), at));
    }

    // ------------------------------------------------------------------ error and cache

    private Optional<ErrorSettings> error(YamlReader.Entry entry) {
        Optional<List<YamlReader.Entry>> body = yaml.mapping(entry.value(), "'error'");
        if (body.isEmpty()) {
            return Optional.empty();
        }
        yaml.knownKeys(body.get(), Set.of("when", "code", "message", "httpStatus", "statusByCode"), "'error'");
        Optional<Located<ErrorCondition>> when = yaml.find(body.get(), "when").flatMap(this::condition);
        Optional<Located<String>> code = yaml.find(body.get(), "code").flatMap(yaml::nonBlankString);
        Optional<Located<String>> message = yaml.find(body.get(), "message").flatMap(yaml::nonBlankString);
        Optional<Located<Integer>> status = yaml.find(body.get(), "httpStatus")
                .flatMap(e -> httpStatus(e.value(), "'httpStatus'"));
        Map<String, Located<Integer>> byCode = new LinkedHashMap<>();
        yaml.find(body.get(), "statusByCode").ifPresent(e -> yaml.mapping(e.value(), "'statusByCode'").ifPresent(entries -> {
            for (YamlReader.Entry item : entries) {
                httpStatus(item.value(), "the status of '" + item.key() + "'")
                        .ifPresent(s -> byCode.put(item.key(), s));
            }
        }));
        return Optional.of(new ErrorSettings(when, code, message, status, byCode, entry.keyAt()));
    }

    private Optional<Located<Integer>> httpStatus(Node node, String what) {
        Optional<Located<Integer>> status = yaml.integer(node, what);
        if (status.isPresent() && (status.get().value() < 400 || status.get().value() > 599)) {
            yaml.error("OVL012", what + " must be between 400 and 599, not " + status.get().value(), status.get().at());
            return Optional.empty();
        }
        return status;
    }

    private Optional<Located<ErrorCondition>> condition(YamlReader.Entry entry) {
        Optional<List<YamlReader.Entry>> body = yaml.mapping(entry.value(), "'when'");
        if (body.isEmpty()) {
            return Optional.empty();
        }
        SourceLocation at = yaml.at(entry.value());
        if (!yaml.knownKeys(body.get(), Set.of("field", "equals", "notEquals", "in"), "'when'")) {
            return Optional.empty();
        }
        Optional<YamlReader.Entry> field = yaml.find(body.get(), "field");
        if (field.isEmpty()) {
            yaml.error("OVL012", "Missing required key 'field'", at);
            return Optional.empty();
        }
        List<YamlReader.Entry> operators = body.get().stream().filter(e -> !e.key().equals("field")).toList();
        if (operators.size() != 1) {
            yaml.error("OVL012", "'when' needs exactly one of 'equals', 'notEquals' and 'in'", at);
            return Optional.empty();
        }
        Optional<Located<String>> fieldPath = yaml.nonBlankString(field.get());
        YamlReader.Entry operator = operators.get(0);
        if (fieldPath.isEmpty()) {
            return Optional.empty();
        }
        String path = fieldPath.get().value();
        return switch (operator.key()) {
            case "equals" -> yaml.scalarText(operator.value(), "'equals'")
                    .map(v -> new Located<>(new ErrorCondition.Equals(path, v.value()), at));
            case "notEquals" -> yaml.scalarText(operator.value(), "'notEquals'")
                    .map(v -> new Located<>(new ErrorCondition.NotEquals(path, v.value()), at));
            default -> yaml.sequence(operator.value(), "'in'").flatMap(items -> {
                List<String> values = new ArrayList<>();
                for (Node item : items) {
                    yaml.scalarText(item, "a value of 'in'").ifPresent(v -> values.add(v.value()));
                }
                if (values.isEmpty() || values.size() != items.size()) {
                    if (items.isEmpty()) {
                        yaml.error("OVL012", "'in' must have at least one value", yaml.at(operator.value()));
                    }
                    return Optional.empty();
                }
                return Optional.of(new Located<ErrorCondition>(new ErrorCondition.In(path, values), at));
            });
        };
    }

    private Optional<Located<CacheSettings>> cache(YamlReader.Entry entry) {
        Optional<List<YamlReader.Entry>> body = yaml.mapping(entry.value(), "'cache'");
        if (body.isEmpty() || !yaml.knownKeys(body.get(), Set.of("ttlSeconds"), "'cache'")) {
            return Optional.empty();
        }
        Optional<YamlReader.Entry> ttl = yaml.find(body.get(), "ttlSeconds");
        if (ttl.isEmpty()) {
            yaml.error("OVL012", "Missing required key 'ttlSeconds'", yaml.at(entry.value()));
            return Optional.empty();
        }
        Optional<Located<Integer>> seconds = yaml.integer(ttl.get().value(), "'ttlSeconds'");
        if (seconds.isPresent() && seconds.get().value() < 0) {
            yaml.error("OVL012", "'ttlSeconds' must not be negative", seconds.get().at());
            return Optional.empty();
        }
        return seconds.map(s -> new Located<>(new CacheSettings(s.value()), yaml.at(entry.value())));
    }
}

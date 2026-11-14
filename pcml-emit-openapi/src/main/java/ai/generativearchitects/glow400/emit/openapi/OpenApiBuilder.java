package ai.generativearchitects.glow400.emit.openapi;

import ai.generativearchitects.glow400.contract.ApiSurface;
import ai.generativearchitects.glow400.contract.FieldSurface;
import ai.generativearchitects.glow400.contract.Limits;
import ai.generativearchitects.glow400.contract.OperationSurface;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Builds the OpenAPI 3.1 document of one API as a tree for {@link YamlWriter}. Key order is the order of insertion, so
 * the same API always gives the same text.
 */
final class OpenApiBuilder {

    /** The product name appears in the vendor extension only, and only through this constant, since the name is provisional. */
    static final String VENDOR_PREFIX = "glow400";
    /** The extension on every field: the RPG field name and its wire type, for traceability. */
    static final String EXTENSION = "x-" + VENDOR_PREFIX + "-field";
    /** The schema every error response refers to. */
    static final String PROBLEM = "ProblemDetails";

    private static final String JSON = "application/json";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final List<Integer> ALWAYS = List.of(400, 404, 502, 503, 504);

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(400, "The request is not valid"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not found"), Map.entry(405, "Method not allowed"), Map.entry(409, "Conflict"), Map.entry(410, "Gone"),
            Map.entry(412, "Precondition failed"), Map.entry(422, "The program reported an error"), Map.entry(423, "Locked"),
            Map.entry(429, "Too many requests"), Map.entry(500, "Internal server error"),
            Map.entry(502, "The program or the system could not be reached or answered badly"),
            Map.entry(503, "Service unavailable"), Map.entry(504, "The program did not answer in time"));

    private OpenApiBuilder() {
    }

    static Map<String, Object> document(ApiSurface api) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.1.0");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", api.title());
        info.put("version", api.version());
        root.put("info", info);
        root.put("servers", List.of(server()));
        TreeSet<String> tags = new TreeSet<>();
        api.operations().forEach(operation -> tags.addAll(operation.tags()));
        if (!tags.isEmpty()) {
            List<Object> list = new ArrayList<>();
            tags.forEach(tag -> list.add(named(tag)));
            root.put("tags", list);
        }
        Map<String, Object> paths = new LinkedHashMap<>();
        for (OperationSurface operation : api.operations()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) paths.computeIfAbsent(operation.route(), route -> new LinkedHashMap<String, Object>());
            item.put(operation.method().name().toLowerCase(Locale.ROOT), operation(operation));
        }
        root.put("paths", paths);
        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put(PROBLEM, problemDetails());
        components.put("schemas", schemas);
        root.put("components", components);
        return root;
    }

    private static Map<String, Object> named(String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        return map;
    }

    /** Served under {@code /{tenant}/{environment}}; no host. The variables have defaults because OpenAPI requires one. */
    private static Map<String, Object> server() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("url", "/{tenant}/{environment}");
        Map<String, Object> variables = new LinkedHashMap<>();
        for (String name : List.of("tenant", "environment")) {
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("description", "The " + name + " the program is called for.");
            variable.put("default", name);
            variables.put(name, variable);
        }
        server.put("variables", variables);
        return server;
    }

    // ------------------------------------------------------------------ operations

    private static Map<String, Object> operation(OperationSurface operation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operationId", operation.id());
        operation.summary().ifPresent(summary -> out.put("summary", summary));
        operation.description().ifPresent(description -> out.put("description", description));
        if (!operation.tags().isEmpty()) {
            out.put("tags", List.copyOf(operation.tags()));
        }
        List<Object> parameters = new ArrayList<>();
        List<FieldSurface> body = new ArrayList<>();
        for (FieldSurface field : operation.request()) {
            if (field.location().get() == Location.BODY) {
                body.add(field);
            } else {
                parameters.add(parameter(field));
            }
        }
        if (!parameters.isEmpty()) {
            out.put("parameters", parameters);
        }
        if (!body.isEmpty()) {
            out.put("requestBody", requestBody(body));
        }
        out.put("responses", responses(operation));
        return out;
    }

    private static Map<String, Object> parameter(FieldSurface field) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", field.exposedName());
        out.put("in", field.location().get().name().toLowerCase(Locale.ROOT));
        out.put("required", field.required() || field.location().get() == Location.PATH);
        description(field).ifPresent(text -> out.put("description", text));
        out.put("schema", scalar(field));
        example(field).ifPresent(value -> out.put("example", value));
        out.put(EXTENSION, extension(field));
        return out;
    }

    private static Map<String, Object> requestBody(List<FieldSurface> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("required", body.stream().anyMatch(FieldSurface::required));
        out.put("content", content(JSON, object(body)));
        return out;
    }

    private static Map<String, Object> content(String type, Map<String, Object> schema) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", schema);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(type, media);
        return content;
    }

    private static Map<String, Object> responses(OperationSurface operation) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!operation.hasContent()) {
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("description", "No content");
            out.put("204", none);
        } else {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("description", "The program ran");
            ok.put("content", content(JSON, object(operation.response())));
            out.put("200", ok);
        }
        TreeSet<Integer> statuses = new TreeSet<>(ALWAYS);
        if (operation.defaultErrorStatus().isPresent()) {
            statuses.add(422);
            statuses.addAll(operation.errorStatuses());
        }
        for (int status : statuses) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("description", REASONS.getOrDefault(status, "Error"));
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("$ref", "#/components/schemas/" + PROBLEM);
            error.put("content", content(PROBLEM_JSON, ref));
            out.put(String.valueOf(status), error);
        }
        return out;
    }

    /** RFC 9457: the five standard members, and any extension member is allowed. */
    private static Map<String, Object> problemDetails() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typed("string", "uri-reference", "A URI that identifies the kind of problem."));
        properties.put("title", typed("string", null, "A short summary of the kind of problem."));
        properties.put("status", typed("integer", null, "The HTTP status code."));
        properties.put("detail", typed("string", null, "An explanation of this occurrence of the problem."));
        properties.put("instance", typed("string", "uri-reference", "A URI that identifies this occurrence of the problem."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "A problem details object (RFC 9457).");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> typed(String type, String format, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        if (format != null) {
            map.put("format", format);
        }
        map.put("description", description);
        return map;
    }

    // ------------------------------------------------------------------ schemas

    /** An object schema of the fields, in PCML order. */
    private static Map<String, Object> object(List<FieldSurface> fields) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (FieldSurface field : fields) {
            properties.put(field.exposedName(), property(field));
            if (field.required()) {
                required.add(field.exposedName());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** The schema of a field inside an object: an object, a scalar, or an array of either. */
    private static Map<String, Object> property(FieldSurface field) {
        Map<String, Object> element;
        if (field.object()) {
            element = object(field.children());
        } else {
            element = scalar(field);
            example(field).ifPresent(value -> element.put("examples", List.of(value)));
        }
        Map<String, Object> out;
        if (!field.array()) {
            out = element;
        } else {
            out = new LinkedHashMap<>();
            out.put("type", "array");
            out.put("items", element);
            field.limits().maxItems().ifPresent(items -> out.put("maxItems", items));
        }
        // Description and extension go on the outermost schema, so that an array and its elements are not described twice.
        Map<String, Object> described = new LinkedHashMap<>();
        description(field).ifPresent(text -> described.put("description", text));
        described.putAll(out);
        described.put(EXTENSION, extension(field));
        return described;
    }

    /** The schema of one value of a scalar field, by semantic type and limits. */
    private static Map<String, Object> scalar(FieldSurface field) {
        Map<String, Object> out = new LinkedHashMap<>();
        Limits limits = field.limits();
        switch (field.type().orElseThrow()) {
            case STRING -> {
                out.put("type", "string");
                limits.maxLength().ifPresent(length -> out.put("maxLength", length));
            }
            case INTEGER -> {
                out.put("type", "integer");
                limits.minimum().ifPresent(minimum -> out.put("minimum", number(minimum)));
                limits.maximum().ifPresent(maximum -> out.put("maximum", number(maximum)));
            }
            case DECIMAL -> {
                out.put("type", "string");
                out.put("format", "decimal");
                out.put("pattern", decimalPattern(limits));
            }
            case BOOLEAN -> out.put("type", "boolean");
            case DATE -> {
                out.put("type", "string");
                out.put("format", "date");
            }
            case TIME -> {
                out.put("type", "string");
                out.put("format", "local-time");
                out.put("pattern", "^\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$");
            }
            case DATETIME -> {
                out.put("type", "string");
                out.put("format", "local-date-time");
                out.put("pattern", "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$");
            }
            case BINARY -> {
                out.put("type", "string");
                out.put("contentEncoding", "base64");
                limits.maxBytes().ifPresent(bytes -> {
                    int encoded = 4 * ((bytes + 2) / 3);
                    out.put("minLength", encoded);
                    out.put("maxLength", encoded);
                });
            }
        }
        return out;
    }

    /** Small numbers are written as such; a bound beyond a long keeps all its digits. */
    private static Object number(BigInteger value) {
        return value.bitLength() < 32 ? (Object) value.intValue() : value;
    }

    /** A decimal is text: with the field's scale for packed and zoned, unsigned for text fields, any fraction for float. */
    private static String decimalPattern(Limits limits) {
        if (limits.scale().isPresent()) {
            return fixedScalePattern(limits.digits().get(), limits.scale().get());
        }
        return limits.minimum().isPresent() ? "^\\d+(\\.\\d+)?$" : "^[-+]?\\d+(\\.\\d+)?$";
    }

    private static String fixedScalePattern(int digits, int scale) {
        int integerDigits = digits - scale;
        String integer = integerDigits <= 0 ? "0" : "\\d{1," + integerDigits + "}";
        return "^-?" + integer + (scale > 0 ? "\\.\\d{" + scale + "}" : "") + "$";
    }

    /** The description of the field, with what the schema cannot say about decimals. */
    private static Optional<String> description(FieldSurface field) {
        Optional<String> note = decimalNote(field);
        if (field.description().isPresent() && note.isPresent()) {
            return Optional.of(field.description().get() + " " + note.get());
        }
        return field.description().isPresent() ? field.description() : note;
    }

    private static Optional<String> decimalNote(FieldSurface field) {
        if (field.type().orElse(null) != SemanticType.DECIMAL) {
            return Optional.empty();
        }
        return switch (field.origin().wire()) {
            case WireType.Packed p -> Optional.of(digitsNote(p.length(), p.precision()));
            case WireType.Zoned z -> Optional.of(digitsNote(z.length(), z.precision()));
            case WireType.Float f -> Optional.of("A floating-point number of " + f.lengthBytes() + " bytes.");
            case WireType.Char c -> Optional.of(textNote(c.length()));
            case WireType.VarChar v -> Optional.of(textNote(v.length()));
            default -> Optional.empty();
        };
    }

    private static String digitsNote(int digits, int scale) {
        return "A decimal number with up to " + digits + " digits, " + scale + " of them after the decimal point.";
    }

    private static String textNote(Length length) {
        return length instanceof Length.Fixed fixed
                ? "A decimal number written with at most " + fixed.value() + " characters, the decimal point included."
                : "A decimal number.";
    }

    /** The example in the form the schema describes: a whole number, a boolean or text. */
    private static Optional<Object> example(FieldSurface field) {
        if (field.example().isEmpty() || field.type().isEmpty()) {
            return Optional.empty();
        }
        String text = field.example().get();
        try {
            return Optional.of(switch (field.type().get()) {
                case INTEGER -> new BigInteger(text);
                case BOOLEAN -> Boolean.valueOf(Boolean.parseBoolean(text));
                default -> text;
            });
        } catch (NumberFormatException e) {
            return Optional.of(text);
        }
    }

    /** The RPG field and its wire type, and for a decimal its digits and scale. */
    private static Map<String, Object> extension(FieldSurface field) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", field.origin().fieldPath());
        out.put("wire", wireText(field.origin().wire()));
        if (field.type().orElse(null) == SemanticType.DECIMAL && field.limits().scale().isPresent()) {
            out.put("digits", field.limits().digits().get());
            out.put("scale", field.limits().scale().get());
        }
        return out;
    }

    static String wireText(WireType wire) {
        return switch (wire) {
            case WireType.Char c -> "char(" + length(c.length()) + ")";
            case WireType.VarChar v -> "varchar(" + length(v.length()) + ")";
            case WireType.Int i -> "int(" + i.lengthBytes() + ")";
            case WireType.Packed p -> "packed(" + p.length() + "," + p.precision() + ")";
            case WireType.Zoned z -> "zoned(" + z.length() + "," + z.precision() + ")";
            case WireType.Float f -> "float(" + f.lengthBytes() + ")";
            case WireType.Byte b -> "byte(" + length(b.length()) + ")";
            case WireType.Struct s -> "struct(" + s.structName() + ")";
            case WireType.Date d -> "date";
            case WireType.Time t -> "time";
            case WireType.Timestamp t -> "timestamp";
        };
    }

    private static String length(Length length) {
        return switch (length) {
            case Length.Fixed fixed -> String.valueOf(fixed.value());
            case Length.Ref ref -> ref.field();
        };
    }
}

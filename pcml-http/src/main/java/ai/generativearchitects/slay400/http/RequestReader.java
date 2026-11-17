package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.contract.FieldSurface;
import ai.generativearchitects.slay400.contract.OperationSurface;
import ai.generativearchitects.slay400.contract.TextForms;
import ai.generativearchitects.slay400.emit.descriptor.JsonReader;
import ai.generativearchitects.slay400.pcml.api.Location;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a request into API values, as the OpenAPI document of the operation declares it: each exposed request field from
 * its path, query, header or body; text converted by the semantic type of the field and JSON values by the same types.
 * It reports only what stops it from producing a value ({@link RequestProblemCode}); lengths, ranges and the number of array
 * elements are the codec's. It never throws for data and never puts a value into a problem.
 */
final class RequestReader {

    /** The longest whole number or decimal written as text that is read; a longer one cannot fit any field and costs time. */
    static final int MAX_NUMBER_TEXT = 1000;
    /** The most digits in front of the decimal point of a number written in JSON that is read as a whole number. */
    private static final int MAX_WHOLE_DIGITS = 40;

    private static final Pattern INTEGER = Pattern.compile(TextForms.INTEGER);
    private static final Pattern DATE = Pattern.compile(TextForms.DATE);
    private static final Pattern TIME = Pattern.compile(TextForms.TIME);
    private static final Pattern DATETIME = Pattern.compile(TextForms.DATETIME);

    /** A request field with what is needed to read it: the pattern of its decimal form, and its members. */
    private record Plan(FieldSurface field, Pattern decimal, List<Plan> children, Set<String> names) {
        static Plan of(FieldSurface field) {
            List<Plan> children = new ArrayList<>();
            Set<String> names = new HashSet<>();
            field.children().forEach(child -> {
                children.add(of(child));
                names.add(child.exposedName());
            });
            boolean decimal = field.type().isPresent() && field.type().get() == ai.generativearchitects.slay400.pcml.api.SemanticType.DECIMAL;
            return new Plan(field, decimal ? Pattern.compile(TextForms.decimal(field.limits())) : null, children, names);
        }

        String name() {
            return field.exposedName();
        }
    }

    /** Where a value is: its path in PCML names and in API names, as the codec writes them (an index after the array name). */
    private record At(String fieldPath, String exposedPath) {
        At member(FieldSurface child) {
            String own = child.origin().fieldPath();
            return new At(fieldPath + "." + own.substring(own.lastIndexOf('.') + 1), exposedPath + "." + child.exposedName());
        }

        At element(int index) {
            return new At(fieldPath + "[" + index + "]", exposedPath + "[" + index + "]");
        }

        At unknown(String name) {
            return new At(fieldPath, exposedPath.isEmpty() ? name : exposedPath + "." + name);
        }
    }

    private static final At WHOLE = new At("", "");

    private final List<Plan> plans = new ArrayList<>();
    private final boolean bodyDeclared;
    private final ReadLimits limits;
    private final JsonReader json;

    RequestReader(OperationSurface surface, ReadLimits limits) {
        surface.request().forEach(field -> plans.add(Plan.of(field)));
        this.bodyDeclared = plans.stream().anyMatch(plan -> plan.field.location().get() == Location.BODY);
        this.limits = limits;
        this.json = JsonReader.of(new JsonReader.Limits(limits.maxBodyBytes(), limits.maxDepth(), limits.maxValues(), limits.maxBodyBytes()));
    }

    RequestResult read(RequestParts parts) {
        List<ValueProblem> problems = new ArrayList<>();
        Map<?, ?> body = null;
        boolean bodyFailed = false;
        byte[] bytes = parts.bodyBytes();
        if (bytes.length > 0) {
            Object tree = readBody(parts, bytes, problems);
            if (tree instanceof Map<?, ?> map) {
                body = map;
            } else {
                bodyFailed = true;
            }
        }
        Map<String, ApiValue> values = new LinkedHashMap<>();
        for (Plan plan : plans) {
            Location location = plan.field.location().get();
            At at = new At(plan.field.origin().fieldPath(), plan.name());
            Optional<ApiValue> value;
            switch (location) {
                case PATH -> value = fromParameter(plan, singleton(parts.pathParameters().get(plan.name())), true, at, "Path parameter", problems);
                case QUERY -> value = fromParameter(plan, parts.query().get(plan.name()), plan.field.required(), at, "Query parameter", problems);
                case HEADER -> value = fromParameter(plan, parts.headers().get(plan.name()), plan.field.required(), at, "Header", problems);
                default -> {
                    if (bodyFailed) {
                        continue;
                    }
                    if (body == null || !body.containsKey(plan.name())) {
                        if (plan.field.required()) {
                            problems.add(problem(RequestProblemCode.MISSING, at, "Required body field is missing"));
                        }
                        continue;
                    }
                    value = value(plan, body.get(plan.name()), at, problems);
                }
            }
            value.ifPresent(v -> values.put(plan.name(), v));
        }
        if (body != null) {
            unknown(body, plans.stream().filter(plan -> plan.field.location().get() == Location.BODY).map(Plan::name).collect(java.util.stream.Collectors.toSet()),
                    WHOLE, problems);
        }
        return problems.isEmpty() ? new RequestResult(Optional.of(values), List.of()) : new RequestResult(Optional.empty(), problems);
    }

    // ------------------------------------------------------------------ the body

    /** The parsed body, or {@code null} if there is none to use; the reason is added to the problems. */
    private Object readBody(RequestParts parts, byte[] bytes, List<ValueProblem> problems) {
        if (!bodyDeclared) {
            problems.add(problem(RequestProblemCode.BODY_NOT_DECLARED, WHOLE, "A body was sent, but the operation takes none"));
            return null;
        }
        if (!isJson(parts.contentType())) {
            problems.add(problem(RequestProblemCode.CONTENT_TYPE, WHOLE, "The body needs the content type application/json, in UTF-8"));
            return null;
        }
        if (bytes.length > limits.maxBodyBytes()) {
            problems.add(problem(RequestProblemCode.LIMIT_EXCEEDED, WHOLE, "The body is larger than " + limits.maxBodyBytes() + " bytes"));
            return null;
        }
        Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        try {
            Object tree = json.read(reader);
            if (tree instanceof Map<?, ?>) {
                return tree;
            }
            problems.add(problem(RequestProblemCode.WRONG_TYPE, WHOLE, "The body must be a JSON object"));
        } catch (JsonReader.LimitExceededException e) {
            problems.add(problem(RequestProblemCode.LIMIT_EXCEEDED, WHOLE, "The body is more complex than the limits allow: at most "
                    + limits.maxDepth() + " levels and " + limits.maxValues() + " values, and numbers of at most 40 characters"));
        } catch (JsonReader.SyntaxException e) {
            problems.add(problem(RequestProblemCode.NOT_JSON, WHOLE, "The body is not valid JSON"
                    + (e.line() > 0 ? " (line " + e.line() + ", column " + e.column() + ")" : "")));
        } catch (IOException e) {
            problems.add(problem(RequestProblemCode.NOT_JSON, WHOLE, "The body is not valid UTF-8 text"));
        }
        return null;
    }

    /** {@code application/json}, with at most the parameter {@code charset=utf-8}, in any letter case. */
    static boolean isJson(Optional<String> contentType) {
        if (contentType.isEmpty()) {
            return false;
        }
        String[] parts = contentType.get().split(";", -1);
        if (!parts[0].trim().toLowerCase(Locale.ROOT).equals("application/json") || parts.length > 2) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        String parameter = parts[1].trim();
        int equals = parameter.indexOf('=');
        if (equals < 0 || !parameter.substring(0, equals).trim().equalsIgnoreCase("charset")) {
            return false;
        }
        String value = parameter.substring(equals + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.equalsIgnoreCase("utf-8");
    }

    private void unknown(Map<?, ?> object, Set<String> known, At at, List<ValueProblem> problems) {
        for (Object key : object.keySet()) {
            if (!known.contains(key)) {
                problems.add(problem(RequestProblemCode.UNKNOWN_PROPERTY, at.unknown((String) key), "The object has a property that the API does not declare"));
            }
        }
    }

    // ------------------------------------------------------------------ path, query and header

    private static List<String> singleton(String value) {
        return value == null ? null : List.of(value);
    }

    private Optional<ApiValue> fromParameter(Plan plan, List<String> given, boolean required, At at, String what, List<ValueProblem> problems) {
        if (given == null || given.isEmpty()) {
            if (required) {
                problems.add(problem(RequestProblemCode.MISSING, at, "Required " + what.toLowerCase(Locale.ROOT) + " is missing"));
            }
            return Optional.empty();
        }
        if (given.size() > 1) {
            problems.add(problem(RequestProblemCode.REPEATED, at, what + " takes one value and was given " + given.size() + " times"));
            return Optional.empty();
        }
        return fromText(plan, given.get(0), at, what, problems);
    }

    // ------------------------------------------------------------------ values

    /** A JSON value for a field: an array of them, an object, or a scalar. */
    private Optional<ApiValue> value(Plan plan, Object node, At at, List<ValueProblem> problems) {
        if (node == null) {
            problems.add(problem(RequestProblemCode.WRONG_TYPE, at, "A body value must not be null"));
            return Optional.empty();
        }
        if (!plan.field.array()) {
            return element(plan, node, at, problems);
        }
        if (!(node instanceof List<?> list)) {
            problems.add(problem(RequestProblemCode.WRONG_TYPE, at, "Body field must be an array"));
            return Optional.empty();
        }
        List<ApiValue> elements = new ArrayList<>();
        int before = problems.size();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            Optional<ApiValue> read = item == null ? nullValue(at.element(i), problems) : element(plan, item, at.element(i), problems);
            read.ifPresent(elements::add);
        }
        return problems.size() == before ? Optional.of(new ApiValue.Arr(elements)) : Optional.empty();
    }

    private static Optional<ApiValue> nullValue(At at, List<ValueProblem> problems) {
        problems.add(problem(RequestProblemCode.WRONG_TYPE, at, "A body value must not be null"));
        return Optional.empty();
    }

    private Optional<ApiValue> element(Plan plan, Object node, At at, List<ValueProblem> problems) {
        if (plan.field.object()) {
            if (!(node instanceof Map<?, ?> map)) {
                problems.add(problem(RequestProblemCode.WRONG_TYPE, at, "Body field must be an object"));
                return Optional.empty();
            }
            Map<String, ApiValue> members = new LinkedHashMap<>();
            int before = problems.size();
            for (Plan child : plan.children) {
                At childAt = at.member(child.field);
                if (!map.containsKey(child.name())) {
                    if (child.field.required()) {
                        problems.add(problem(RequestProblemCode.MISSING, childAt, "Required body field is missing"));
                    }
                    continue;
                }
                value(child, map.get(child.name()), childAt, problems).ifPresent(v -> members.put(child.name(), v));
            }
            unknown(map, plan.names, at, problems);
            return problems.size() == before ? Optional.of(new ApiValue.Obj(members)) : Optional.empty();
        }
        return scalar(plan, node, at, problems);
    }

    private Optional<ApiValue> scalar(Plan plan, Object node, At at, List<ValueProblem> problems) {
        switch (plan.field.type().get()) {
            case STRING:
                return node instanceof String text ? Optional.of(new ApiValue.Text(text)) : wrongType(at, "a string", problems);
            case INTEGER:
                if (node instanceof Number number) {
                    Optional<BigInteger> whole = whole(number);
                    if (whole.isPresent()) {
                        return Optional.of(new ApiValue.Integer(whole.get()));
                    }
                }
                return wrongType(at, "a whole number", problems);
            case BOOLEAN:
                return node instanceof Boolean flag ? Optional.of(new ApiValue.Bool(flag)) : wrongType(at, "true or false", problems);
            case DECIMAL:
                return node instanceof String text ? fromText(plan, text, at, "Body field", problems) : wrongType(at, "a decimal number written as text", problems);
            default:
                return node instanceof String text ? fromText(plan, text, at, "Body field", problems) : wrongType(at, "text", problems);
        }
    }

    private static Optional<ApiValue> wrongType(At at, String expected, List<ValueProblem> problems) {
        problems.add(problem(RequestProblemCode.WRONG_TYPE, at, "Body field must be " + expected));
        return Optional.empty();
    }

    /** A number written in JSON as a whole number: {@code 5}, {@code 5.0} and {@code 5e0} are the same number for JSON Schema. */
    private static Optional<BigInteger> whole(Number number) {
        if (number instanceof BigInteger big) {
            return Optional.of(big);
        }
        if (number instanceof BigDecimal decimal) {
            if (decimal.signum() == 0) {
                return Optional.of(BigInteger.ZERO);
            }
            if (decimal.precision() - decimal.scale() > MAX_WHOLE_DIGITS) {
                return Optional.empty();
            }
            BigDecimal stripped = decimal.stripTrailingZeros();
            return stripped.scale() <= 0 ? Optional.of(stripped.toBigIntegerExact()) : Optional.empty();
        }
        return number instanceof Integer || number instanceof Long ? Optional.of(BigInteger.valueOf(number.longValue())) : Optional.empty();
    }

    /** Text read as the semantic type of the field: from a path, a query or a header, or from a JSON string. */
    private Optional<ApiValue> fromText(Plan plan, String text, At at, String what, List<ValueProblem> problems) {
        String expected;
        try {
            switch (plan.field.type().get()) {
                case STRING:
                    return Optional.of(new ApiValue.Text(text));
                case INTEGER:
                    expected = "a whole number";
                    if (text.length() <= MAX_NUMBER_TEXT && INTEGER.matcher(text).matches()) {
                        return Optional.of(new ApiValue.Integer(new BigInteger(text)));
                    }
                    break;
                case DECIMAL:
                    expected = "a decimal number in the form the API declares";
                    if (text.length() <= MAX_NUMBER_TEXT && plan.decimal.matcher(text).matches()) {
                        return Optional.of(new ApiValue.Decimal(new BigDecimal(text)));
                    }
                    break;
                case BOOLEAN:
                    expected = "true or false";
                    if (text.equals("true") || text.equals("false")) {
                        return Optional.of(new ApiValue.Bool(text.equals("true")));
                    }
                    break;
                case DATE:
                    expected = "a date written yyyy-MM-dd";
                    if (DATE.matcher(text).matches()) {
                        return Optional.of(new ApiValue.Date(LocalDate.parse(text)));
                    }
                    break;
                case TIME:
                    expected = "a time written HH:mm:ss, with an optional fraction of a second";
                    if (TIME.matcher(text).matches()) {
                        return Optional.of(new ApiValue.Time(LocalTime.parse(text)));
                    }
                    break;
                case DATETIME:
                    expected = "a date and time written yyyy-MM-ddTHH:mm:ss, with an optional fraction of a second";
                    if (DATETIME.matcher(text).matches()) {
                        return Optional.of(new ApiValue.DateTime(LocalDateTime.parse(text)));
                    }
                    break;
                default:
                    expected = "Base64 text";
                    byte[] bytes = Base64.getDecoder().decode(text);
                    if (Base64.getEncoder().encodeToString(bytes).equals(text)) {
                        return Optional.of(new ApiValue.Binary(bytes));
                    }
            }
        } catch (DateTimeException | IllegalArgumentException e) {
            return reject(plan, at, what, problems);
        }
        problems.add(problem(RequestProblemCode.WRONG_FORM, at, what + " must be " + expected));
        return Optional.empty();
    }

    /** Text that has the written form but is not a value: a date that does not exist, or Base64 that does not decode. */
    private static Optional<ApiValue> reject(Plan plan, At at, String what, List<ValueProblem> problems) {
        String expected = switch (plan.field.type().get()) {
            case DATE -> "a date that exists";
            case TIME -> "a time that exists";
            case DATETIME -> "a date and time that exist";
            default -> "Base64 text";
        };
        problems.add(problem(RequestProblemCode.WRONG_FORM, at, what + " must be " + expected));
        return Optional.empty();
    }

    private static ValueProblem problem(RequestProblemCode code, At at, String message) {
        return new ValueProblem(code.code(), at.fieldPath(), at.exposedPath(), message);
    }
}

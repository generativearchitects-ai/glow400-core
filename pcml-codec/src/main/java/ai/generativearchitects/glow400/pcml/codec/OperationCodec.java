package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts between API values and the values a program call takes and returns, for one operation, following the overlay
 * settings. It is the single implementation of these rules: the gateway, the mock, the emitters and the validator all use
 * it.
 *
 * <p>Data is never altered silently: nothing is truncated, rounded or clamped, and a value that does not fit its field is
 * reported as a {@link ValueProblem}. Neither {@link #encode} nor {@link #decode} throws for bad data, and both collect
 * every problem. Messages never contain the offending value.
 *
 * <p>Whether an operation can be encoded at all is decided by {@link #check}; {@link #of} refuses an operation that does
 * not pass it.
 */
public final class OperationCodec {

    private final ApiOperation operation;
    private final List<FieldPlan> plans;

    private OperationCodec(ApiOperation operation, List<FieldPlan> plans) {
        this.operation = operation;
        this.plans = plans;
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Everything in the operation's configuration that makes it impossible to encode or decode, found by the codec's own
     * rules: a semantic type the wire type cannot hold, a padding, format, boolean text or {@code nullAs} that does not
     * fit, an example or an injected literal that cannot be encoded, arrays and lengths that depend on another field on
     * the request side, and a count field that is not a whole number.
     *
     * @param operation the operation
     * @return the issues, in field order; empty if the operation can be encoded
     */
    public static List<ConfigurationIssue> check(ApiOperation operation) {
        Objects.requireNonNull(operation, "operation");
        List<ConfigurationIssue> issues = new ArrayList<>();
        List<FieldPlan> plans = new ArrayList<>();
        for (FieldBinding binding : operation.bindings()) {
            plans.add(FieldPlan.build(binding, issues));
        }
        FieldPlan.Scope top = new FieldPlan.Scope(plans, null, null, operation.program().name());
        for (FieldPlan plan : plans) {
            checkField(plan, top, top, issues);
        }
        operation.errorMapping().ifPresent(mapping -> checkErrorMapping(mapping, top, issues));
        return issues;
    }

    /**
     * The codec of an operation.
     *
     * @throws IllegalArgumentException if {@link #check} finds an issue: the operation cannot be encoded
     */
    public static OperationCodec of(ApiOperation operation) {
        List<ConfigurationIssue> issues = check(operation);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("Operation " + operation.id() + " cannot be encoded: "
                    + issues.stream().map(i -> i.fieldPath() + ": " + i.message()).collect(Collectors.joining("; ")));
        }
        List<FieldPlan> plans = new ArrayList<>();
        for (FieldBinding binding : operation.bindings()) {
            plans.add(FieldPlan.build(binding, new ArrayList<>()));
        }
        return new OperationCodec(operation, plans);
    }

    private static void checkField(FieldPlan plan, FieldPlan.Scope scope, FieldPlan.Scope top, List<ConfigurationIssue> issues) {
        String path = plan.binding.fieldPath();
        if (plan.field.count() instanceof Count.Ref ref) {
            checkCountField(ref.field(), "the field that gives the number of elements", false, plan, scope, issues);
        }
        if (plan.binding.trim().isPresent() && plan.binding.trim().get() instanceof Trim.CountField countField) {
            checkCountField(countField.path(), "trim.countField", true, plan, top, issues);
        }
        if (plan.isStruct()) {
            if (plan.binding.example().isPresent()) {
                issues.add(new ConfigurationIssue(path, "an example cannot be given for a struct"));
            }
            FieldPlan.Scope inner = new FieldPlan.Scope(plan.children, null, top, top.program());
            for (FieldPlan child : plan.children) {
                checkField(child, inner, top, issues);
            }
            return;
        }
        checkExample(plan, issues);
        checkInjection(plan, issues);
    }

    /** A count field must exist, be a single value, and be read as a whole number. */
    private static void checkCountField(String path, String what, boolean fromTop, FieldPlan array, FieldPlan.Scope scope,
                                        List<ConfigurationIssue> issues) {
        FieldPlan.Found found = FieldPlan.resolve(path, scope, scope);
        String at = array.binding.fieldPath();
        if (found == null) {
            issues.add(new ConfigurationIssue(at, what + " cannot be found"));
        } else if (found.plan().isStruct() || found.plan().isArray()
                || found.plan().binding.semanticType().orElse(null) != SemanticType.INTEGER) {
            issues.add(new ConfigurationIssue(at, what + " is not a whole number"));
        }
    }

    private static void checkExample(FieldPlan plan, List<ConfigurationIssue> issues) {
        Optional<String> example = plan.binding.example();
        if (example.isEmpty() || plan.scalar == null) {
            return;
        }
        SemanticType semantic = plan.binding.semanticType().orElse(null);
        List<String> own = new ArrayList<>();
        ScalarCodecs.create(plan.binding, own);
        if (!own.isEmpty() || semantic == null) {
            return; // the field is already reported; its example cannot be judged
        }
        Optional<ApiValue> value = ExampleParser.parse(example.get(), semantic);
        if (value.isEmpty()) {
            issues.add(new ConfigurationIssue(plan.binding.fieldPath(), "the example is not a valid " + semantic.name().toLowerCase(java.util.Locale.ROOT)
                    + " value"));
            return;
        }
        Problems problems = new Problems();
        plan.scalar.encode(value.get(), Where.ROOT, problems);
        if (problems.any()) {
            issues.add(new ConfigurationIssue(plan.binding.fieldPath(), "the example cannot be encoded in the field (" + codes(problems) + ")"));
        }
    }

    private static void checkInjection(FieldPlan plan, List<ConfigurationIssue> issues) {
        if (plan.binding.direction() != Direction.INJECTED || plan.binding.injection().isEmpty()) {
            return;
        }
        List<String> own = new ArrayList<>();
        ScalarCodecs.create(plan.binding, own);
        if (!own.isEmpty()) {
            return;
        }
        String text = plan.binding.injection().get();
        if (!plan.scalar.supportsInjection()) {
            issues.add(new ConfigurationIssue(plan.binding.fieldPath(), "a " + ScalarCodecs.wireName(plan.field.wire()) + " field cannot be injected"));
            return;
        }
        boolean expression = text.startsWith("${") && text.endsWith("}");
        if (!expression) {
            Problems problems = new Problems();
            plan.scalar.encodeInjected(text, Where.ROOT, problems);
            if (problems.any()) {
                issues.add(new ConfigurationIssue(plan.binding.fieldPath(), "the injected value cannot be encoded in the field (" + codes(problems) + ")"));
            }
        }
    }

    /** The fields of the error mapping must be text or numbers, and what they are compared with must fit them. */
    private static void checkErrorMapping(ErrorMapping mapping, FieldPlan.Scope top, List<ConfigurationIssue> issues) {
        List<String> values = switch (mapping.when()) {
            case ErrorCondition.Equals equals -> List.of(equals.value());
            case ErrorCondition.NotEquals notEquals -> List.of(notEquals.value());
            case ErrorCondition.In in -> in.values();
        };
        FieldPlan when = mappingField(mapping.when().field(), "the field of the error condition", top, issues);
        if (when != null && values.stream().anyMatch(value -> longerThan(value, when))) {
            issues.add(new ConfigurationIssue(when.binding.fieldPath(), "a value of the error condition is longer than the field"));
        }
        mapping.messageField().ifPresent(path -> mappingField(path, "the error message field", top, issues));
        FieldPlan code = mapping.codeField().map(path -> mappingField(path, "the error code field", top, issues)).orElse(null);
        if (code != null && mapping.statusByCode().keySet().stream().anyMatch(key -> longerThan(key, code))) {
            issues.add(new ConfigurationIssue(code.binding.fieldPath(), "a statusByCode key is longer than the error code field"));
        }
    }

    /** The plan of a field the mapping reads, or {@code null} after reporting why it cannot be read as text. */
    private static FieldPlan mappingField(String path, String what, FieldPlan.Scope top, List<ConfigurationIssue> issues) {
        FieldPlan.Found found = FieldPlan.resolve(path, top, top);
        if (found == null) {
            issues.add(new ConfigurationIssue("", what + " cannot be found"));
            return null;
        }
        FieldPlan plan = found.plan();
        if (plan.isStruct() || plan.isArray() || plan.field.wire() instanceof WireType.Byte) {
            issues.add(new ConfigurationIssue(plan.binding.fieldPath(), what + " is not text or a number: a struct, an array and a byte field cannot be read as text"));
            return null;
        }
        return plan;
    }

    private static boolean longerThan(String value, FieldPlan plan) {
        Length length = switch (plan.field.wire()) {
            case WireType.Char c -> c.length();
            case WireType.VarChar v -> v.length();
            default -> null;
        };
        return length instanceof Length.Fixed fixed && value.codePointCount(0, value.length()) > fixed.value();
    }

    private static String codes(Problems problems) {
        return problems.list().stream().map(ValueProblem::code).distinct().collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ encoding

    /**
     * Encodes a request into the values of the program's parameters.
     *
     * @param request  the request fields by exposed name
     * @param injected the text of every injected parameter, by parameter name, already resolved by the caller
     * @return one value per program parameter in PCML order (output and internal parameters get their blank), or the problems
     */
    public EncodeResult encode(Map<String, ApiValue> request, Map<String, String> injected) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(injected, "injected");
        Problems problems = new Problems();
        Set<String> known = new HashSet<>();
        for (FieldPlan plan : plans) {
            if (isRequest(plan.binding.direction())) {
                known.add(plan.exposedName());
            }
        }
        for (String name : request.keySet()) {
            if (!known.contains(name)) {
                problems.add("VAL006", Where.ROOT.unknown(name), "The field is not known");
            }
        }
        List<WireValue> parameters = new ArrayList<>();
        for (FieldPlan plan : plans) {
            Where where = plan.where(Where.ROOT);
            Direction direction = plan.binding.direction();
            if (direction == Direction.INJECTED) {
                String text = injected.get(plan.field.name());
                if (text == null) {
                    problems.add("VAL001", where, "The injected value is missing");
                    parameters.add(plan.blankValue());
                } else {
                    parameters.add(plan.scalar.encodeInjected(text, where, problems).orElseGet(plan::blankValue));
                }
            } else if (isRequest(direction)) {
                parameters.add(plan.encodeRequest(request.getOrDefault(plan.exposedName(), new ApiValue.Absent()), where, problems));
            } else {
                parameters.add(plan.blankValue());
            }
        }
        return new EncodeResult(problems.any() ? Optional.empty() : Optional.of(parameters), problems.list());
    }

    private static boolean isRequest(Direction direction) {
        return direction == Direction.REQUEST || direction == Direction.BOTH;
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes what the program returned into the response fields.
     *
     * @param programOutput the program's parameters after the call, one per parameter, in PCML order
     * @return the response fields by exposed name (those with a response side that are not hidden; absent values are left
     *         out), or the problems
     */
    public DecodeResult decode(List<WireValue> programOutput) {
        Objects.requireNonNull(programOutput, "programOutput");
        Problems problems = new Problems();
        if (programOutput.size() != plans.size()) {
            problems.add("VAL101", Where.ROOT, "The program returned " + programOutput.size() + " parameters instead of " + plans.size());
            return new DecodeResult(Optional.empty(), Optional.empty(), problems.list());
        }
        FieldPlan.Scope top = new FieldPlan.Scope(plans, programOutput, null, operation.program().name());
        if (operation.errorMapping().isPresent()) {
            ErrorMapping mapping = operation.errorMapping().get();
            Optional<String> condition = mappingText(mapping.when().field(), top, problems);
            if (problems.any()) {
                return new DecodeResult(Optional.empty(), Optional.empty(), problems.list());
            }
            if (holds(mapping.when(), condition.orElseThrow())) {
                return outcome(mapping, top, problems);
            }
        }
        Map<String, ApiValue> response = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            FieldPlan plan = plans.get(i);
            Direction direction = plan.binding.direction();
            if (direction != Direction.RESPONSE && direction != Direction.BOTH || plan.binding.hidden()) {
                continue;
            }
            Optional<ApiValue> value = plan.decodeField(programOutput.get(i), plan.where(Where.ROOT), problems, top);
            if (value.isPresent() && !(value.get() instanceof ApiValue.Absent)) {
                response.put(plan.exposedName(), value.get());
            }
        }
        return new DecodeResult(problems.any() ? Optional.empty() : Optional.of(response), Optional.empty(), problems.list());
    }

    private static boolean holds(ErrorCondition condition, String text) {
        return switch (condition) {
            case ErrorCondition.Equals equals -> text.equals(equals.value());
            case ErrorCondition.NotEquals notEquals -> !text.equals(notEquals.value());
            case ErrorCondition.In in -> in.values().contains(text);
        };
    }

    /** The error the program reported: its status, and the code and message when their fields are set. */
    private DecodeResult outcome(ErrorMapping mapping, FieldPlan.Scope top, Problems problems) {
        Optional<String> code = mapping.codeField().flatMap(path -> mappingText(path, top, problems)).filter(text -> !text.isBlank());
        Optional<String> message = mapping.messageField().flatMap(path -> mappingText(path, top, problems)).filter(text -> !text.isBlank());
        if (problems.any()) {
            return new DecodeResult(Optional.empty(), Optional.empty(), problems.list());
        }
        int status = code.map(mapping.statusByCode()::get).orElse(mapping.httpStatus());
        return new DecodeResult(Optional.empty(), Optional.of(new ErrorOutcome(status, code, message)), problems.list());
    }

    /**
     * A field the error mapping reads, as text with its padding removed, whatever its direction. Empty, with a VAL101 in
     * {@code problems}, if the program returned a value of another shape: a struct, an array or bytes.
     */
    private static Optional<String> mappingText(String path, FieldPlan.Scope top, Problems problems) {
        FieldPlan.Found found = FieldPlan.resolve(path, top, top);
        Where where = new Where(path, "");
        if (found == null || found.value() == null) {
            problems.add("VAL101", where, "The program's output has no value for a field the error mapping reads");
            return Optional.empty();
        }
        String text = switch (found.value()) {
            case WireValue.Text t -> found.plan().scalar instanceof TextCodec codec ? codec.unpaddedText(t.value()) : t.value();
            case WireValue.Blank b -> "";
            case WireValue.Integer i -> i.value().toString();
            case WireValue.Decimal d -> d.value().toPlainString();
            case WireValue.Float f -> java.lang.Double.toString(f.value());
            case WireValue.Date d -> d.value().toString();
            case WireValue.Time t -> t.value().toString();
            case WireValue.Timestamp t -> t.value().toString();
            case WireValue.Bytes b -> null;
            case WireValue.Struct s -> null;
            case WireValue.Array a -> null;
        };
        if (text == null) {
            problems.add("VAL101", where, "A field the error mapping reads has the wrong shape: it must be text or a number");
        }
        return Optional.ofNullable(text);
    }
}

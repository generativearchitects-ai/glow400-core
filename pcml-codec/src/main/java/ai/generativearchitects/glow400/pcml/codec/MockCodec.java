package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.Usage;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the mock of an operation needs from the codec: whether a mock definition fits the program and its fields, whether a
 * case matches what the program receives, and the values the program returns for a case. Everything is by the RPG names
 * of the PCML, not the exposed names, so that a mock can set a parameter the API hides.
 *
 * <p>A value in a mock is written as the API would carry it, in the semantic type of the field. It is read as an
 * {@link ApiValue} and encoded by the codec of the field, so that a mock value is valid exactly when the codec can carry
 * it. {@link #check} is the one place that says so: the evaluator and the validation both use it.
 *
 * <p>The operation must be one that {@link OperationCodec#check} accepts.
 */
public final class MockCodec {

    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    private final ApiOperation operation;
    private final List<FieldPlan> plans = new ArrayList<>();
    private final Map<String, Integer> indexByName = new HashMap<>();
    /** The top-level lists whose length is read from a top-level count field, with that field. */
    private final List<Counted> counted = new ArrayList<>();

    private record Counted(FieldPlan list, FieldPlan count) {
    }

    private MockCodec(ApiOperation operation) {
        this.operation = operation;
        operation.bindings().forEach(binding -> {
            indexByName.put(binding.field().name(), plans.size());
            plans.add(FieldPlan.build(binding, new ArrayList<>()));
        });
        for (FieldPlan plan : plans) {
            if (!plan.isArray()) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            if (plan.field.count() instanceof Count.Ref ref) {
                names.add(ref.field());
            }
            plan.binding.trim().ifPresent(trim -> {
                if (trim instanceof Trim.CountField countField) {
                    names.add(countField.path());
                }
            });
            for (String name : names) {
                Integer index = indexByName.get(name); // a count field inside a struct is not followed
                if (index != null && !plans.get(index).isArray() && !plans.get(index).isStruct()) {
                    counted.add(new Counted(plan, plans.get(index)));
                }
            }
        }
    }

    /** The mock codec of an operation. */
    public static MockCodec of(ApiOperation operation) {
        return new MockCodec(Objects.requireNonNull(operation, "operation"));
    }

    // ------------------------------------------------------------------ checking a definition

    /**
     * Everything that is wrong with a definition, and every warning, in file order: a name that is not a parameter or member
     * of the program or is on the wrong side ({@code MCK003}), a value the codec cannot carry for its field ({@code MCK004},
     * whose message carries the code of the codec's problem), a case that can never match ({@code MCK005}), and a count field that is not the number of
     * elements given for its list ({@code MCK006}) or that the list cannot hold ({@code MCK004}).
     */
    public List<MockProblem> check(MockDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        List<MockProblem> problems = new ArrayList<>();
        for (int i = 0; i < definition.cases().size(); i++) {
            MockCase mockCase = definition.cases().get(i);
            String label = label(mockCase, i);
            checkWhen(mockCase, label, problems);
            checkReturns(mockCase, label, problems);
            checkCounts(mockCase, label, problems);
        }
        shadowed(definition, problems);
        problems.sort(java.util.Comparator.comparing((MockProblem p) -> p.at().file()).thenComparingInt(p -> p.at().line())
                .thenComparingInt(p -> p.at().column()));
        return problems;
    }

    private static String label(MockCase mockCase, int index) {
        return mockCase.name().map(name -> "case '" + name + "'").orElse("case " + (index + 1));
    }

    private void checkWhen(MockCase mockCase, String label, List<MockProblem> problems) {
        mockCase.when().forEach((name, value) -> {
            FieldPlan plan = plan(name);
            if (plan == null) {
                problems.add(error("MCK003", value.source(), "In " + label + ": '" + name + "' is not a parameter of the program " + operation.program().name()));
            } else if (plan.field.usage() == Usage.OUTPUT) {
                problems.add(error("MCK003", value.source(), "In " + label + ": '" + name + "' is not received by the program: it is an output parameter"));
            } else if (plan.isStruct() || plan.isArray()) {
                problems.add(error("MCK003", value.source(), "In " + label + ": '" + name + "' is a struct or an array: 'when' names scalar parameters"));
            } else {
                convert(plan, value, label, problems);
            }
        });
    }

    private void checkReturns(MockCase mockCase, String label, List<MockProblem> problems) {
        mockCase.returns().forEach((name, value) -> {
            FieldPlan plan = plan(name);
            if (plan == null) {
                problems.add(error("MCK003", value.source(), "In " + label + ": '" + name + "' is not a parameter of the program " + operation.program().name()));
            } else if (plan.field.usage() == Usage.INPUT) {
                problems.add(error("MCK003", value.source(), "In " + label + ": '" + name + "' is not returned by the program: it is an input parameter"));
            } else {
                convert(plan, value, label, problems);
            }
        });
    }

    /**
     * The count field of a list that {@code returns} gives, or whose count {@code returns} gives: the count must be one the
     * list can hold ({@code MCK004}), should be the number of elements given ({@code MCK006}), and when it is not given
     * the evaluator writes it, so the number of elements must fit it ({@code MCK004}).
     */
    private void checkCounts(MockCase mockCase, String label, List<MockProblem> problems) {
        for (Counted c : counted) {
            if (c.list.field.usage() == Usage.INPUT || c.count.field.usage() == Usage.INPUT) {
                continue;
            }
            MockValue list = mockCase.returns().get(c.list.field.name());
            MockValue count = mockCase.returns().get(c.count.field.name());
            if (list != null && !(list instanceof MockValue.Array)) {
                continue; // reported as a value the field cannot carry
            }
            int given = list == null ? 0 : ((MockValue.Array) list).elements().size();
            int capacity = c.list.field.count() instanceof Count.Fixed fixed ? fixed.value() : given;
            if (count instanceof MockValue.Whole whole) {
                if (whole.value().signum() < 0 || whole.value().compareTo(BigInteger.valueOf(capacity)) > 0) {
                    problems.add(error("MCK004", count.source(), "In " + label + ": the value for " + c.count.binding.fieldPath()
                            + " is not a count that the list " + c.list.binding.fieldPath() + " can hold: it is negative or more than the "
                            + capacity + " elements it holds"));
                } else if (!whole.value().equals(BigInteger.valueOf(given))) {
                    problems.add(new MockProblem("MCK006", Severity.WARNING, count.source(), "In " + label + ": the count "
                            + c.count.binding.fieldPath() + " is not the number of elements given for " + c.list.binding.fieldPath() + " ("
                            + given + ")"));
                }
            } else if (count == null && list != null) {
                Problems own = new Problems();
                countValue(c, given, list.source(), own);
                for (ValueProblem problem : own.list()) {
                    problems.add(error("MCK004", list.source(), "In " + label + ": the number of elements given for " + c.list.binding.fieldPath()
                            + " cannot be written to its count field " + c.count.binding.fieldPath() + " (" + problem.code() + ": " + problem.message() + ")"));
                }
            }
        }
    }

    /** The value of a count field for a number of elements; problems are added when the field cannot hold it. */
    private Optional<WireValue> countValue(Counted c, int elements, SourceLocation at, Problems problems) {
        Where where = Where.ROOT.member(c.count.field.name(), c.count.exposedName());
        Optional<WireValue> wire = c.count.scalar.encode(new ApiValue.Integer(BigInteger.valueOf(elements)), where, problems);
        return problems.any() ? Optional.empty() : wire;
    }

    /** {@code MCK005}: a case after one that has no {@code when}, or the same {@code when}, is never reached. */
    private static void shadowed(MockDefinition definition, List<MockProblem> problems) {
        List<MockCase> cases = definition.cases();
        for (int i = 1; i < cases.size(); i++) {
            for (int j = 0; j < i; j++) {
                String why = null;
                if (cases.get(j).when().isEmpty()) {
                    why = "has no 'when'";
                } else if (cases.get(j).when().equals(cases.get(i).when())) {
                    why = "has the same 'when'";
                }
                if (why != null) {
                    problems.add(new MockProblem("MCK005", Severity.WARNING, cases.get(i).source(), "The " + label(cases.get(i), i)
                            + " can never match: the " + label(cases.get(j), j) + " before it " + why));
                    break;
                }
            }
        }
    }

    private static MockProblem error(String code, SourceLocation at, String message) {
        return new MockProblem(code, Severity.ERROR, at, message);
    }

    private FieldPlan plan(String name) {
        Integer index = indexByName.get(name);
        return index == null ? null : plans.get(index);
    }

    // ------------------------------------------------------------------ converting values

    /**
     * The wire value of a mock value for a field, or empty when the field cannot carry it, with the reasons added to
     * {@code problems} ({@code MCK003} for a member the struct does not have, {@code MCK004} for the rest).
     */
    private Optional<WireValue> convert(FieldPlan plan, MockValue value, String label, List<MockProblem> problems) {
        if (plan.isArray()) {
            return convertArray(plan, value, label, problems);
        }
        return convertElement(plan, value, label, problems);
    }

    private Optional<WireValue> convertArray(FieldPlan plan, MockValue value, String label, List<MockProblem> problems) {
        if (!(value instanceof MockValue.Array array)) {
            problems.add(value(value.source(), label, plan, "VAL002", "A list was expected"));
            return Optional.empty();
        }
        if (plan.field.count() instanceof Count.Fixed fixed && array.elements().size() > fixed.value()) {
            problems.add(value(value.source(), label, plan, "VAL005", "The list has more elements than the field holds: at most " + fixed.value()));
            return Optional.empty();
        }
        List<WireValue> elements = new ArrayList<>();
        boolean ok = true;
        for (MockValue element : array.elements()) {
            Optional<WireValue> converted = convertElement(plan, element, label, problems);
            if (converted.isPresent()) {
                elements.add(converted.get());
            } else {
                ok = false;
            }
        }
        if (!ok) {
            return Optional.empty();
        }
        if (plan.field.count() instanceof Count.Fixed fixed) {
            WireValue blank = plan.blankElementValue();
            while (elements.size() < fixed.value()) {
                elements.add(blank);
            }
        }
        return Optional.of(new WireValue.Array(elements));
    }

    private Optional<WireValue> convertElement(FieldPlan plan, MockValue value, String label, List<MockProblem> problems) {
        if (plan.isStruct()) {
            return convertStruct(plan, value, label, problems);
        }
        return convertScalar(plan, value, label, problems);
    }

    private Optional<WireValue> convertStruct(FieldPlan plan, MockValue value, String label, List<MockProblem> problems) {
        if (!(value instanceof MockValue.Struct struct)) {
            problems.add(value(value.source(), label, plan, "VAL002", "An object was expected"));
            return Optional.empty();
        }
        Map<String, WireValue> given = new HashMap<>();
        boolean ok = true;
        for (Map.Entry<String, MockValue> member : struct.members().entrySet()) {
            FieldPlan child = plan.children.stream().filter(c -> c.field.name().equals(member.getKey())).findFirst().orElse(null);
            if (child == null) {
                problems.add(error("MCK003", member.getValue().source(), "In " + label + ": '" + member.getKey() + "' is not a member of "
                        + plan.binding.fieldPath()));
                ok = false;
                continue;
            }
            Optional<WireValue> converted = convert(child, member.getValue(), label, problems);
            if (converted.isPresent()) {
                given.put(member.getKey(), converted.get());
            } else {
                ok = false;
            }
        }
        if (!ok) {
            return Optional.empty();
        }
        List<WireValue> members = new ArrayList<>();
        plan.children.forEach(child -> members.add(given.getOrDefault(child.field.name(), child.blankValue())));
        return Optional.of(new WireValue.Struct(members));
    }

    private Optional<WireValue> convertScalar(FieldPlan plan, MockValue value, String label, List<MockProblem> problems) {
        Where where = Where.ROOT.member(plan.field.name(), plan.exposedName());
        Problems own = new Problems();
        Optional<ApiValue> api = toApi(value, plan.binding.semanticType().orElseThrow(), where, own);
        Optional<WireValue> wire = api.isPresent() ? plan.scalar.encode(api.get(), where, own) : Optional.empty();
        if (own.any()) {
            for (ValueProblem problem : own.list()) {
                problems.add(value(value.source(), label, plan, problem.code(), problem.message()));
            }
            return Optional.empty();
        }
        return wire;
    }

    private static MockProblem value(SourceLocation at, String label, FieldPlan plan, String code, String message) {
        return error("MCK004", at, "In " + label + ": the value for " + plan.binding.fieldPath() + " cannot be carried by the field ("
                + code + ": " + message + ")");
    }

    /** A mock value as the API value of the semantic type: the way a client would send or receive it in JSON. */
    private static Optional<ApiValue> toApi(MockValue value, SemanticType semantic, Where where, Problems problems) {
        return switch (semantic) {
            case STRING -> value instanceof MockValue.Text text ? Optional.of(new ApiValue.Text(text.value())) : wrongKind(where, problems);
            case INTEGER -> value instanceof MockValue.Whole whole ? Optional.of(new ApiValue.Integer(whole.value())) : wrongKind(where, problems);
            case BOOLEAN -> value instanceof MockValue.Bool bool ? Optional.of(new ApiValue.Bool(bool.value())) : wrongKind(where, problems);
            case DECIMAL -> value instanceof MockValue.Text text ? parse(text.value(), where, problems, "a decimal in plain digits",
                    () -> DECIMAL.matcher(text.value()).matches() ? new ApiValue.Decimal(new BigDecimal(text.value())) : null) : wrongKind(where, problems);
            case DATE -> value instanceof MockValue.Text text ? parse(text.value(), where, problems, "an ISO-8601 date",
                    () -> new ApiValue.Date(LocalDate.parse(text.value()))) : wrongKind(where, problems);
            case TIME -> value instanceof MockValue.Text text ? parse(text.value(), where, problems, "an ISO-8601 time",
                    () -> new ApiValue.Time(LocalTime.parse(text.value()))) : wrongKind(where, problems);
            case DATETIME -> value instanceof MockValue.Text text ? parse(text.value(), where, problems, "an ISO-8601 date and time",
                    () -> new ApiValue.DateTime(LocalDateTime.parse(text.value()))) : wrongKind(where, problems);
            case BINARY -> value instanceof MockValue.Text text ? parse(text.value(), where, problems, "Base64",
                    () -> new ApiValue.Binary(Base64.getDecoder().decode(text.value()))) : wrongKind(where, problems);
        };
    }

    private static Optional<ApiValue> wrongKind(Where where, Problems problems) {
        problems.add("VAL002", where, "The value is not of the kind of the field");
        return Optional.empty();
    }

    private static Optional<ApiValue> parse(String text, Where where, Problems problems, String expected, java.util.function.Supplier<ApiValue> read) {
        try {
            ApiValue value = read.get();
            if (value != null) {
                return Optional.of(value);
            }
        } catch (DateTimeException | IllegalArgumentException e) {
            // reported below
        }
        problems.add("VAL004", where, "The text is not " + expected);
        return Optional.empty();
    }

    // ------------------------------------------------------------------ evaluating a case

    /**
     * Whether a case matches what the program receives: every parameter in {@code when} has the value given. Values are
     * compared as the API would carry them, so padding and the scale of a decimal do not matter. Never throws: input that
     * cannot be read does not match.
     *
     * @param input the program's parameters as {@link OperationCodec#encode} makes them, one per parameter in PCML order
     */
    public boolean matches(MockCase mockCase, List<WireValue> input) {
        Objects.requireNonNull(mockCase, "mockCase");
        Objects.requireNonNull(input, "input");
        for (Map.Entry<String, MockValue> expected : mockCase.when().entrySet()) {
            FieldPlan plan = plan(expected.getKey());
            if (plan == null || plan.isStruct() || plan.isArray() || indexByName.get(expected.getKey()) >= input.size()) {
                return false;
            }
            Optional<WireValue> wanted = convertScalar(plan, expected.getValue(), "", new ArrayList<>());
            if (wanted.isEmpty()) {
                return false;
            }
            Optional<ApiValue> received = decoded(plan, input.get(indexByName.get(expected.getKey())));
            Optional<ApiValue> expectedValue = decoded(plan, wanted.get());
            if (received.isEmpty() || expectedValue.isEmpty() || !same(received.get(), expectedValue.get())) {
                return false;
            }
        }
        return true;
    }

    private static Optional<ApiValue> decoded(FieldPlan plan, WireValue wire) {
        if (wire instanceof WireValue.Blank) {
            return Optional.of(new ApiValue.Absent());
        }
        Problems problems = new Problems();
        Optional<ApiValue> value = plan.scalar.decode(wire, Where.ROOT, problems);
        return problems.any() ? Optional.empty() : value;
    }

    private static boolean same(ApiValue a, ApiValue b) {
        if (a instanceof ApiValue.Decimal x && b instanceof ApiValue.Decimal y) {
            return x.value().compareTo(y.value()) == 0;
        }
        return a.equals(b);
    }

    /**
     * The program's parameters after the call: what {@code returns} gives, and for every parameter it does not mention the
     * value that was received (for an input or inputoutput parameter) or the empty value of its wire type (for an output
     * parameter). A parameter that is given is complete: what is not given inside a struct or an array is empty. Never
     * throws; a value that cannot be carried is left out as if it were not given.
     *
     * @param input the program's parameters as {@link OperationCodec#encode} makes them
     * @return one value per parameter, in PCML order, as {@link OperationCodec#decode} expects them
     */
    public List<WireValue> returned(Map<String, MockValue> returns, List<WireValue> input) {
        Objects.requireNonNull(returns, "returns");
        Objects.requireNonNull(input, "input");
        List<WireValue> output = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            FieldPlan plan = plans.get(i);
            MockValue given = returns.get(plan.field.name());
            Optional<WireValue> converted = given == null || plan.field.usage() == Usage.INPUT ? Optional.empty() : convert(plan, given, "", new ArrayList<>());
            if (converted.isPresent()) {
                output.add(converted.get());
            } else if (plan.field.usage() == Usage.OUTPUT || i >= input.size()) {
                output.add(plan.blankValue());
            } else {
                output.add(input.get(i));
            }
        }
        // a list that is given, whose count field is not, has its count field set to the number of elements given
        for (Counted c : counted) {
            MockValue list = returns.get(c.list.field.name());
            if (list instanceof MockValue.Array array && c.list.field.usage() != Usage.INPUT && c.count.field.usage() != Usage.INPUT
                    && !returns.containsKey(c.count.field.name())) {
                countValue(c, array.elements().size(), list.source(), new Problems()).ifPresent(v -> output.set(indexByName.get(c.count.field.name()), v));
            }
        }
        return output;
    }
}

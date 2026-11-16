package ai.generativearchitects.slay400.mock;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.api.MockCase;
import ai.generativearchitects.slay400.pcml.api.MockDefinition;
import ai.generativearchitects.slay400.pcml.api.MockFailure;
import ai.generativearchitects.slay400.pcml.api.MockValue;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.Gen;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.Length;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Random requests, mock definitions and program output for any operation, in the manner of the codec's property tests. */
final class MockGen {

    static final SourceLocation AT = new SourceLocation("apis/A/mock/op.yaml", 1, 1);

    private MockGen() {
    }

    // ---- requests

    /** A request the codec may accept: small values of the right kind for every request-side parameter. */
    static Map<String, ApiValue> request(Gen g, ApiOperation operation) {
        Map<String, ApiValue> request = new LinkedHashMap<>();
        for (FieldBinding b : operation.bindings()) {
            if (b.direction() == Direction.REQUEST || b.direction() == Direction.BOTH) {
                request.put(b.exposedName(), api(g, b));
            }
        }
        return request;
    }

    static Map<String, String> injected(ApiOperation operation) {
        Map<String, String> injected = new LinkedHashMap<>();
        for (FieldBinding b : operation.bindings()) {
            if (b.direction() == Direction.INJECTED) {
                injected.put(b.field().name(), "1");
            }
        }
        return injected;
    }

    /** The encoded request, or empty if the codec does not accept what was generated. */
    static Optional<List<WireValue>> input(Gen g, ApiOperation operation, Map<String, ApiValue> request) {
        return OperationCodec.of(operation).encode(request, injected(operation)).parameters();
    }

    private static ApiValue api(Gen g, FieldBinding b) {
        int size = b.field().count() instanceof Count.Fixed f ? Math.min(f.value(), 3) : b.field().count() instanceof Count.Ref ? 2 : -1;
        if (size >= 0) {
            List<ApiValue> elements = new ArrayList<>();
            int n = g.intIn(0, size);
            for (int i = 0; i < n; i++) {
                elements.add(apiElement(g, b));
            }
            return new ApiValue.Arr(elements);
        }
        return apiElement(g, b);
    }

    private static ApiValue apiElement(Gen g, FieldBinding b) {
        if (b.field().wire() instanceof WireType.Struct) {
            Map<String, ApiValue> members = new LinkedHashMap<>();
            for (FieldBinding child : b.children()) {
                if (!child.hidden()) {
                    members.put(child.exposedName(), api(g, child));
                }
            }
            return new ApiValue.Obj(members);
        }
        return scalar(g, b);
    }

    private static int length(FieldBinding b) {
        return switch (b.field().wire()) {
            case WireType.Char c -> c.length() instanceof Length.Fixed f ? f.value() : 3;
            case WireType.VarChar v -> v.length() instanceof Length.Fixed f ? f.value() : 3;
            case WireType.Byte x -> x.length() instanceof Length.Fixed f ? f.value() : 3;
            case WireType.Packed p -> p.length();
            case WireType.Zoned z -> z.length();
            default -> 4;
        };
    }

    private static ApiValue scalar(Gen g, FieldBinding b) {
        SemanticType type = b.semanticType().orElseThrow();
        int n = length(b);
        return switch (type) {
            case STRING -> new ApiValue.Text(g.text(Gen.LETTERS, 1, Math.max(1, Math.min(n, 4))));
            case INTEGER -> new ApiValue.Integer(BigInteger.valueOf(g.intIn(1, 9))); // never zero: zero is blank, and a trimmed list drops blank elements
            case DECIMAL -> new ApiValue.Decimal(decimal(g, b));
            case BOOLEAN -> new ApiValue.Bool(g.bool());
            case DATE -> new ApiValue.Date(g.date());
            case TIME -> new ApiValue.Time(g.time());
            case DATETIME -> new ApiValue.DateTime(g.dateTime());
            case BINARY -> new ApiValue.Binary(g.bytes(g.intIn(1, Math.max(1, Math.min(n, 4)))));
        };
    }

    private static BigDecimal decimal(Gen g, FieldBinding b) {
        BigDecimal value = decimalOf(g, b);
        return value.signum() == 0 ? BigDecimal.ONE.movePointLeft(Math.max(0, value.scale())) : value;
    }

    private static BigDecimal decimalOf(Gen g, FieldBinding b) {
        return switch (b.field().wire()) {
            case WireType.Packed p -> g.decimal(Math.max(1, Math.min(p.length() - p.precision(), 3)) + p.precision(), p.precision());
            case WireType.Zoned z -> g.decimal(Math.max(1, Math.min(z.length() - z.precision(), 3)) + z.precision(), z.precision());
            default -> new BigDecimal(g.digits(1));
        };
    }

    // ---- values of a mock

    /** The mock value that says the same as an API value, in the way a client would write it. */
    static MockValue mock(ApiValue value) {
        return switch (value) {
            case ApiValue.Text t -> new MockValue.Text(t.value(), AT);
            case ApiValue.Integer i -> new MockValue.Whole(i.value(), AT);
            case ApiValue.Decimal d -> new MockValue.Text(d.value().toPlainString(), AT);
            case ApiValue.Bool b -> new MockValue.Bool(b.value(), AT);
            case ApiValue.Date d -> new MockValue.Text(d.value().toString(), AT);
            case ApiValue.Time t -> new MockValue.Text(t.value().toString(), AT);
            case ApiValue.DateTime t -> new MockValue.Text(t.value().toString(), AT);
            case ApiValue.Binary b -> new MockValue.Text(Base64.getEncoder().encodeToString(b.value()), AT);
            case ApiValue.Obj o -> {
                Map<String, MockValue> members = new LinkedHashMap<>();
                o.members().forEach((name, v) -> members.put(name, mock(v)));
                yield new MockValue.Struct(members, AT);
            }
            case ApiValue.Arr a -> new MockValue.Array(a.elements().stream().map(MockGen::mock).toList(), AT);
            case ApiValue.Absent x -> new MockValue.Text("", AT);
        };
    }

    /** A value for a parameter, in the RPG names of its members. Members of a struct are left out at random. */
    static MockValue value(Gen g, FieldBinding b) {
        int size = b.field().count() instanceof Count.Fixed f ? f.value() : b.field().count() instanceof Count.Ref ? 2 : -1;
        if (size >= 0) {
            List<MockValue> elements = new ArrayList<>();
            int n = g.intIn(0, Math.min(size, 3));
            for (int i = 0; i < n; i++) {
                elements.add(element(g, b));
            }
            return new MockValue.Array(elements, AT);
        }
        return element(g, b);
    }

    private static MockValue element(Gen g, FieldBinding b) {
        if (b.field().wire() instanceof WireType.Struct) {
            Map<String, MockValue> members = new LinkedHashMap<>();
            for (FieldBinding child : b.children()) {
                if (g.bool()) {
                    members.put(child.field().name(), value(g, child));
                }
            }
            if (members.isEmpty() && !b.children().isEmpty()) {
                members.put(b.children().get(0).field().name(), value(g, b.children().get(0)));
            }
            return new MockValue.Struct(members, AT);
        }
        return mock(scalar(g, b));
    }

    /** A definition of one to four cases for the operation; a case may be left unusable, which validation is asked about. */
    static MockDefinition definition(Gen g, ApiOperation operation, Map<String, ApiValue> request) {
        List<MockCase> cases = new ArrayList<>();
        int n = g.intIn(1, 4);
        for (int i = 0; i < n; i++) {
            Map<String, MockValue> when = new LinkedHashMap<>();
            Map<String, MockValue> returns = new LinkedHashMap<>();
            boolean anyCall = g.bool(); // half of the cases say nothing about what the program receives, so that they match
            for (FieldBinding b : operation.bindings()) {
                boolean composite = b.field().wire() instanceof WireType.Struct || !(b.field().count() instanceof Count.None);
                if (!anyCall && b.field().usage() != Usage.OUTPUT && !composite && g.intIn(0, 3) == 0) {
                    ApiValue incoming = request.get(b.exposedName());
                    boolean useRequest = incoming != null && g.intIn(0, 2) > 0 && !(incoming instanceof ApiValue.Arr) && !(incoming instanceof ApiValue.Obj);
                    when.put(b.field().name(), useRequest ? mock(incoming) : mock(scalar(g, b)));
                }
                if (b.field().usage() != Usage.INPUT && g.bool()) {
                    returns.put(b.field().name(), value(g, b));
                }
            }
            boolean fails = g.intIn(0, 5) == 0;
            cases.add(new MockCase(g.bool() ? Optional.of("case " + i) : Optional.empty(), when, fails ? Map.of() : returns,
                    g.bool() ? Optional.of(g.intIn(0, MockCase.MAX_DELAY_MS)) : Optional.empty(),
                    fails ? Optional.of(g.bool() ? MockFailure.TIMEOUT : MockFailure.UNAVAILABLE) : Optional.empty(), AT));
        }
        return new MockDefinition(operation.id(), cases, AT);
    }

    // ---- garbage

    /** Wire values of any shape, as input that no encode produced. */
    static List<WireValue> garbage(Gen g, int size) {
        return garbageList(g, size, 2);
    }

    private static WireValue garbageValue(Gen g, int depth) {
        return switch (g.intIn(0, depth > 0 ? 8 : 6)) {
            case 0 -> new WireValue.Blank();
            case 1 -> new WireValue.Text(g.text(Gen.LETTERS_AND_SPACES, 0, 12));
            case 2 -> new WireValue.Integer(g.signedDigits(g.intIn(1, 20)));
            case 3 -> new WireValue.Decimal(g.decimal(g.intIn(1, 9), g.intIn(0, 3)));
            case 4 -> new WireValue.Float(g.finiteDouble());
            case 5 -> new WireValue.Bytes(g.bytes(g.intIn(0, 6)));
            case 6 -> new WireValue.Date(g.date());
            case 7 -> new WireValue.Struct(garbageList(g, g.intIn(0, 3), depth - 1));
            default -> new WireValue.Array(garbageList(g, g.intIn(0, 3), depth - 1));
        };
    }

    private static List<WireValue> garbageList(Gen g, int size, int depth) {
        List<WireValue> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            values.add(garbageValue(g, depth));
        }
        return values;
    }
}

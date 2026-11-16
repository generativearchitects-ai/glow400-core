package ai.generativearchitects.glow400.mock;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.codec.ApiValue;
import ai.generativearchitects.glow400.pcml.codec.DecodeResult;
import ai.generativearchitects.glow400.pcml.codec.Gen;
import ai.generativearchitects.glow400.pcml.codec.MockCodec;
import ai.generativearchitects.glow400.pcml.codec.MockProblem;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.codec.Properties;
import ai.generativearchitects.glow400.pcml.codec.WireValue;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The mock against every operation of the fixtures, with random requests and random definitions. */
class MockPropertiesTest {

    private static final List<ApiOperation> OPERATIONS = new ArrayList<>();

    static {
        for (String name : Fixtures.VALID) {
            OPERATIONS.addAll(Fixtures.model(name).operations());
        }
    }

    /** The operations that return a list, which the properties about lists need to meet often. */
    private static final List<ApiOperation> WITH_LISTS = OPERATIONS.stream().filter(o -> o.bindings().stream().anyMatch(b ->
            (b.direction() == Direction.RESPONSE || b.direction() == Direction.BOTH) && !(b.field().count() instanceof Count.None))).toList();

    private static int accepted;
    private static int definitions;
    private static int matched;
    private static int compared;
    private static int lists;

    @Test
    void theImplicitDefaultReturnsWhatDecodeAccepts() {
        accepted = 0;
        Properties.check("implicit default", g -> {
            ApiOperation operation = g.pick(OPERATIONS);
            Map<String, ApiValue> request = MockGen.request(g, operation);
            Optional<List<WireValue>> input = MockGen.input(g, operation, request);
            if (input.isEmpty()) {
                return;
            }
            accepted++;
            MockOutcome outcome = MockProgram.of(operation).call(input.get());
            assertThat(outcome).isInstanceOf(MockOutcome.Returned.class);
            DecodeResult decoded = OperationCodec.of(operation).decode(((MockOutcome.Returned) outcome).output());
            assertThat(decoded.problems()).as(operation.id()).isEmpty();
        });
        assertThat(accepted).as("requests the codec accepted").isGreaterThan(Properties.DEFAULT_CASES / 4);
    }

    @Test
    void aDefinitionThatValidationAcceptsAlsoReturnsWhatDecodeAcceptsAndEveryGivenValueIsInTheResponse() {
        definitions = 0;
        matched = 0;
        compared = 0;
        lists = 0;
        Properties.check("accepted definitions", g -> {
            ApiOperation operation = g.bool() ? g.pick(WITH_LISTS) : g.pick(OPERATIONS);
            Map<String, ApiValue> request = MockGen.request(g, operation);
            Optional<List<WireValue>> input = MockGen.input(g, operation, request);
            if (input.isEmpty()) {
                return;
            }
            MockDefinition definition = MockGen.definition(g, operation, request);
            List<MockProblem> problems = MockCodec.of(operation).check(definition);
            if (problems.stream().anyMatch(MockProblem::isError)) {
                return;
            }
            definitions++;
            MockOutcome outcome = MockProgram.of(operation, definition).call(input.get());
            if (outcome instanceof MockOutcome.Failed failed) {
                MockCase mockCase = firstMatch(operation, definition, input.get());
                assertThat(failed.kind()).isEqualTo(mockCase.fails().orElseThrow());
                assertThat(failed.delay().toMillis()).isEqualTo((long) mockCase.delayMs().orElse(0));
                assertThat(failed.caseName()).isEqualTo(mockCase.name());
                return;
            }
            MockOutcome.Returned returned = (MockOutcome.Returned) outcome;
            DecodeResult decoded = OperationCodec.of(operation).decode(returned.output());
            assertThat(decoded.problems()).as(operation.id() + " " + definition).isEmpty();
            MockCase mockCase = firstMatchOrNull(operation, definition, input.get());
            assertThat(returned.delay().toMillis()).isEqualTo(mockCase == null ? 0L : (long) mockCase.delayMs().orElse(0));
            assertThat(returned.caseName()).isEqualTo(mockCase == null ? Optional.empty() : mockCase.name());
            if (mockCase == null || decoded.response().isEmpty()) {
                return;
            }
            matched++;
            boolean deliberate = problems.stream().anyMatch(pr -> pr.code().equals("MCK006"));
            for (FieldBinding b : operation.bindings()) {
                if (!deliberate) {
                    listsFound(b, mockCase, decoded);
                }
                MockValue given = mockCase.returns().get(b.field().name());
                boolean response = b.direction() == Direction.RESPONSE || b.direction() == Direction.BOTH;
                boolean scalar = !(b.field().wire() instanceof WireType.Struct) && b.field().count() instanceof Count.None;
                if (given != null && response && scalar && b.field().usage() != ai.generativearchitects.glow400.pcml.model.Usage.INPUT) {
                    compared++;
                    ApiValue found = decoded.response().orElseThrow().get(b.exposedName());
                    assertThat(found).as(operation.id() + "." + b.exposedName()).isNotNull();
                    assertThat(same(found, given)).as(operation.id() + "." + b.exposedName() + " " + found + " vs " + given).isTrue();
                }
            }
        });
        assertThat(definitions).as("definitions that validation accepted").isGreaterThan(Properties.DEFAULT_CASES / 4);
        assertThat(matched).as("calls that matched a case and decoded to a response").isGreaterThan(20);
        assertThat(compared).as("given values that were looked for in a response").isGreaterThan(20);
        assertThat(lists).as("given lists that were looked for in a response").isGreaterThan(10);
    }

    /** Every element given in {@code returns} for an exposed list is in the decoded response, with the members that were given. */
    private static void listsFound(FieldBinding b, MockCase mockCase, DecodeResult decoded) {
        MockValue given = mockCase.returns().get(b.field().name());
        boolean response = b.direction() == Direction.RESPONSE || b.direction() == Direction.BOTH;
        if (!(given instanceof MockValue.Array array) || !response || b.field().count() instanceof Count.None) {
            return;
        }
        lists++;
        ApiValue found = decoded.response().orElseThrow().get(b.exposedName());
        assertThat(found).as("the list " + b.exposedName()).isInstanceOf(ApiValue.Arr.class);
        List<ApiValue> elements = ((ApiValue.Arr) found).elements();
        assertThat(elements.size()).as("elements of " + b.exposedName()).isGreaterThanOrEqualTo(array.elements().size());
        for (int i = 0; i < array.elements().size(); i++) {
            MockValue element = array.elements().get(i);
            if (element instanceof MockValue.Struct struct) {
                assertThat(elements.get(i)).isInstanceOf(ApiValue.Obj.class);
                Map<String, ApiValue> members = ((ApiValue.Obj) elements.get(i)).members();
                for (Map.Entry<String, MockValue> member : struct.members().entrySet()) {
                    FieldBinding child = b.children().stream().filter(c -> c.field().name().equals(member.getKey())).findFirst().orElseThrow();
                    if (!child.hidden() && !(child.field().wire() instanceof WireType.Struct) && child.field().count() instanceof Count.None) {
                        assertThat(members.get(child.exposedName())).as(b.exposedName() + "[" + i + "]." + child.exposedName()).isNotNull();
                        assertThat(same(members.get(child.exposedName()), member.getValue())).as(b.exposedName() + "[" + i + "]." + child.exposedName()).isTrue();
                    }
                }
            } else if (!(element instanceof MockValue.Array)) {
                assertThat(same(elements.get(i), element)).as(b.exposedName() + "[" + i + "]").isTrue();
            }
        }
    }

    private static MockCase firstMatchOrNull(ApiOperation operation, MockDefinition definition, List<WireValue> input) {
        MockCodec codec = MockCodec.of(operation);
        return definition.cases().stream().filter(c -> codec.matches(c, input)).findFirst().orElse(null);
    }

    private static MockCase firstMatch(ApiOperation operation, MockDefinition definition, List<WireValue> input) {
        return firstMatchOrNull(operation, definition, input);
    }

    /** Whether a decoded API value is the one a mock value stands for. */
    private static boolean same(ApiValue found, MockValue given) {
        return switch (given) {
            case MockValue.Text t -> switch (found) {
                case ApiValue.Text x -> x.value().equals(t.value());
                case ApiValue.Decimal d -> d.value().compareTo(new BigDecimal(t.value())) == 0;
                case ApiValue.Date d -> d.value().toString().equals(t.value());
                case ApiValue.Time x -> x.value().equals(java.time.LocalTime.parse(t.value()));
                case ApiValue.DateTime x -> x.value().equals(java.time.LocalDateTime.parse(t.value()));
                case ApiValue.Binary x -> java.util.Base64.getEncoder().encodeToString(x.value()).equals(t.value());
                default -> false;
            };
            case MockValue.Whole w -> found instanceof ApiValue.Integer i && i.value().equals(w.value());
            case MockValue.Bool b -> found instanceof ApiValue.Bool x && x.value() == b.value();
            default -> false;
        };
    }

    @Test
    void callNeverThrowsWhateverItIsGiven() {
        Properties.check("call never throws", g -> {
            ApiOperation operation = g.pick(OPERATIONS);
            MockDefinition definition = MockGen.definition(g, operation, Map.of());
            if (MockCodec.of(operation).check(definition).stream().anyMatch(MockProblem::isError)) {
                definition = MockDefinition.empty(operation.id());
            }
            MockProgram mock = MockProgram.of(operation, definition);
            for (int i = 0; i < 3; i++) {
                MockOutcome outcome = mock.call(MockGen.garbage(g, g.intIn(0, operation.bindings().size() + 2)));
                assertThat(outcome).isNotNull();
                assertThat(outcome.delay().isNegative()).isFalse();
            }
        });
    }

    @Test
    void theFixturesHaveMocksToLookAtAndTheyAreAccepted() {
        for (String name : Fixtures.VALID) {
            Fixtures.model(name).apis().forEach(api -> api.mocks().forEach(mock -> {
                ApiOperation operation = api.operations().stream().filter(o -> o.id().equals(mock.operationId())).findFirst().orElseThrow();
                assertThat(MockCodec.of(operation).check(mock)).isEmpty();
            }));
        }
        assertThat(OPERATIONS.size()).isGreaterThan(10);
    }
}

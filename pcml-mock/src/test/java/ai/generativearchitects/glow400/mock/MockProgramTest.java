package ai.generativearchitects.glow400.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.codec.ApiValue;
import ai.generativearchitects.glow400.pcml.codec.DecodeResult;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.codec.WireValue;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The evaluator, between encode and decode, on the fixture with the mock of the brief. */
class MockProgramTest {

    private static final SourceLocation AT = new SourceLocation("apis/ORDERS/mock/ordstatus.yaml", 1, 1);
    private static final ApiRepositoryModel MODEL = Fixtures.model("mocked");
    private static final ApiOperation STATUS = Fixtures.operation(MODEL, "ordstatus");
    private static final ApiOperation UPDATE = Fixtures.operation(MODEL, "update-quantity");
    private static final MockDefinition DEFINITION = MODEL.apis().get(0).mocks().get(0);
    private static final MockProgram MOCK = MockProgram.of(STATUS, DEFINITION);

    private static List<WireValue> encode(ApiOperation operation, Map<String, ApiValue> request) {
        return OperationCodec.of(operation).encode(request, Map.of()).parameters().orElseThrow();
    }

    private static List<WireValue> order(long number) {
        return encode(STATUS, Map.of("orderNumber", new ApiValue.Integer(BigInteger.valueOf(number))));
    }

    private static DecodeResult decode(ApiOperation operation, MockOutcome outcome) {
        return OperationCodec.of(operation).decode(((MockOutcome.Returned) outcome).output());
    }

    @Test
    void theFirstCaseThatMatchesWinsAndItsValuesAreReturnedAsTheAPIWouldCarryThem() {
        MockOutcome outcome = MOCK.call(order(1001));
        assertThat(outcome).isInstanceOf(MockOutcome.Returned.class);
        assertThat(outcome.caseName()).contains("shipped order");
        assertThat(outcome.delay()).isEqualTo(Duration.ZERO);
        DecodeResult decoded = decode(STATUS, outcome);
        assertThat(decoded.problems()).isEmpty();
        assertThat(decoded.response().orElseThrow()).containsEntry("status", new ApiValue.Text("S"));
        ApiValue.Arr lines = (ApiValue.Arr) decoded.response().orElseThrow().get("lines");
        assertThat(lines.elements()).hasSize(1);
        assertThat(((ApiValue.Obj) lines.elements().get(0)).members()).containsEntry("sku", new ApiValue.Text("A1"));
        assertThat(((ApiValue.Decimal) ((ApiValue.Obj) lines.elements().get(0)).members().get("quantity")).value()).isEqualByComparingTo("2.00");
    }

    @Test
    void aMockCanSetAParameterTheAPIHidesAndTheErrorMappingStaysReal() {
        MockOutcome outcome = MOCK.call(order(9999));
        assertThat(outcome.caseName()).contains("unknown order");
        DecodeResult decoded = decode(STATUS, outcome);
        assertThat(decoded.response()).isEmpty();
        assertThat(decoded.error().orElseThrow().status()).isEqualTo(404);
        assertThat(decoded.error().orElseThrow().code()).contains("404");
    }

    @Test
    void aCaseCanDelayItsAnswer() {
        MockOutcome outcome = MOCK.call(order(5));
        assertThat(outcome).isInstanceOf(MockOutcome.Returned.class);
        assertThat(outcome.delay()).isEqualTo(Duration.ofMillis(1500));
        assertThat(outcome.caseName()).contains("slow");
        assertThat(decode(STATUS, outcome).problems()).isEmpty();
    }

    @Test
    void aCaseCanFail() {
        MockOutcome outcome = MOCK.call(order(6));
        assertThat(outcome).isEqualTo(new MockOutcome.Failed(MockFailure.UNAVAILABLE, Duration.ZERO, Optional.of("host down")));
    }

    @Test
    void aFailureCanBeDelayedToo() {
        MockCase down = new MockCase(Optional.empty(), Map.of(), Map.of(), Optional.of(2000), Optional.of(MockFailure.TIMEOUT), AT);
        MockOutcome outcome = MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(down), AT)).call(order(1));
        assertThat(outcome).isEqualTo(new MockOutcome.Failed(MockFailure.TIMEOUT, Duration.ofSeconds(2), Optional.empty()));
    }

    @Test
    void whenNothingMatchesTheImplicitDefaultApplies() {
        MockOutcome outcome = MOCK.call(order(77));
        assertThat(outcome.caseName()).isEmpty();
        assertThat(outcome.delay()).isEqualTo(Duration.ZERO);
        DecodeResult decoded = decode(STATUS, outcome);
        assertThat(decoded.problems()).isEmpty();
        assertThat(decoded.response().orElseThrow()).containsEntry("status", new ApiValue.Text(""));
        assertThat(decoded.response().orElseThrow().get("lines")).isEqualTo(new ApiValue.Arr(List.of()));
    }

    @Test
    void anOperationWithoutAMockFileCanBeMocked() {
        MockProgram mock = MockProgram.of(UPDATE);
        Map<String, ApiValue> request = new LinkedHashMap<>();
        request.put("itemId", new ApiValue.Text("X1"));
        request.put("quantity", new ApiValue.Integer(BigInteger.valueOf(7)));
        request.put("note", new ApiValue.Text("hello"));
        List<WireValue> input = encode(UPDATE, request);
        MockOutcome outcome = mock.call(input);
        assertThat(outcome.caseName()).isEmpty();
        assertThat(outcome.delay()).isEqualTo(Duration.ZERO);
        assertThat(((MockOutcome.Returned) outcome).output()).isEqualTo(input);
        DecodeResult decoded = decode(UPDATE, outcome);
        assertThat(decoded.response().orElseThrow()).containsEntry("quantity", new ApiValue.Integer(BigInteger.valueOf(7))).containsEntry("note", new ApiValue.Text("hello"));
    }

    @Test
    void aDefinitionWithNoCaseIsTheSameAsNoDefinition() {
        List<WireValue> input = order(1001);
        assertThat(MockProgram.of(STATUS, MockDefinition.empty("ordstatus")).call(input)).isEqualTo(MockProgram.of(STATUS).call(input));
    }

    @Test
    void theFirstOfSeveralMatchingCasesWins() {
        MockCase first = new MockCase(Optional.of("first"), Map.of(), Map.of("P0STAT", new MockValue.Text("F", AT)), Optional.empty(), Optional.empty(), AT);
        MockCase second = new MockCase(Optional.of("second"), Map.of("P0ORDN", new MockValue.Whole(BigInteger.ONE, AT)), Map.of("P0STAT", new MockValue.Text("S", AT)),
                Optional.empty(), Optional.empty(), AT);
        MockOutcome outcome = MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(first, second), AT)).call(order(1));
        assertThat(outcome.caseName()).contains("first");
        assertThat(decode(STATUS, outcome).response().orElseThrow()).containsEntry("status", new ApiValue.Text("F"));
    }

    @Test
    void aCaseWithoutANameHasNoCaseName() {
        MockCase unnamed = new MockCase(Optional.empty(), Map.of(), Map.of(), Optional.of(10), Optional.empty(), AT);
        MockOutcome outcome = MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(unnamed), AT)).call(order(1));
        assertThat(outcome.caseName()).isEmpty();
        assertThat(outcome.delay()).isEqualTo(Duration.ofMillis(10));
    }

    @Test
    void aDefinitionThatValidationWouldRefuseIsRefusedWithoutTheValue() {
        MockCase bad = new MockCase(Optional.of("bad"), Map.of(), Map.of("P0STAT", new MockValue.Text("SECRET VALUE", AT)), Optional.empty(), Optional.empty(), AT);
        assertThatThrownBy(() -> MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(bad), AT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The mock of the operation ordstatus is not valid: MCK004 apis/ORDERS/mock/ordstatus.yaml:1:1: In case 'bad': the value for P0STAT "
                        + "cannot be carried by the field (VAL003: The value is too long: at most 1 characters)");
        MockCase unknown = new MockCase(Optional.of("u"), Map.of("NOPE", new MockValue.Text("x", AT)), Map.of(), Optional.empty(), Optional.empty(), AT);
        assertThatThrownBy(() -> MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(unknown), AT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MCK003").hasMessageContaining("'NOPE' is not a parameter");
        assertThatThrownBy(() -> MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(unknown, bad), AT))).hasMessageContaining("; MCK004");
    }

    @Test
    void aWarningDoesNotRefuseADefinition() {
        MockCase all = new MockCase(Optional.of("all"), Map.of(), Map.of(), Optional.empty(), Optional.empty(), AT);
        MockCase never = new MockCase(Optional.of("never"), Map.of("P0ORDN", new MockValue.Whole(BigInteger.ONE, AT)), Map.of(), Optional.empty(), Optional.empty(), AT);
        assertThat(MockProgram.of(STATUS, new MockDefinition("ordstatus", List.of(all, never), AT)).call(order(1)).caseName()).contains("all");
    }

    @Test
    void aDefinitionOfAnotherOperationIsRefused() {
        assertThatThrownBy(() -> MockProgram.of(UPDATE, DEFINITION)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The definition is the mock of the operation ordstatus, not of update-quantity");
    }

    @Test
    void anOperationTheCodecCannotUseCannotBeMocked() {
        ApiOperation broken = new ApiOperation(STATUS.id(), STATUS.version(), STATUS.summary(), STATUS.description(), STATUS.tags(), STATUS.program(),
                STATUS.pcmlPath(), STATUS.overlayPath(), STATUS.route(), STATUS.method(), STATUS.callStrategy(),
                STATUS.bindings().stream().map(b -> b.fieldPath().equals("P0STAT") ? withType(b) : b).toList(), STATUS.errorMapping(), STATUS.cache(), STATUS.source());
        assertThatThrownBy(() -> MockProgram.of(broken)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The operation ordstatus cannot be encoded, so it cannot be mocked");
    }

    private static ai.generativearchitects.glow400.pcml.api.FieldBinding withType(ai.generativearchitects.glow400.pcml.api.FieldBinding b) {
        return new ai.generativearchitects.glow400.pcml.api.FieldBinding(b.fieldPath(), b.field(), b.direction(), b.injection(), b.exposedName(), b.location(),
                Optional.of(ai.generativearchitects.glow400.pcml.api.SemanticType.BINARY), b.codec(), b.required(), b.description(), b.example(), b.hidden(),
                b.trim(), b.children(), b.source());
    }

    @Test
    void theOutcomesRefuseNullsAndKeepTheirOutputAsACopy() {
        List<WireValue> output = new java.util.ArrayList<>(List.of(new WireValue.Blank()));
        MockOutcome.Returned returned = new MockOutcome.Returned(output, Duration.ZERO, Optional.empty());
        output.add(new WireValue.Blank());
        assertThat(returned.output()).hasSize(1);
        assertThatThrownBy(() -> new MockOutcome.Returned(List.of(), null, Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("delay");
        assertThatThrownBy(() -> new MockOutcome.Returned(List.of(), Duration.ZERO, null)).isInstanceOf(NullPointerException.class).hasMessage("caseName");
        assertThatThrownBy(() -> new MockOutcome.Returned(null, Duration.ZERO, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockOutcome.Failed(null, Duration.ZERO, Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("kind");
        assertThatThrownBy(() -> new MockOutcome.Failed(MockFailure.TIMEOUT, null, Optional.empty())).isInstanceOf(NullPointerException.class).hasMessage("delay");
        assertThatThrownBy(() -> new MockOutcome.Failed(MockFailure.TIMEOUT, Duration.ZERO, null)).isInstanceOf(NullPointerException.class).hasMessage("caseName");
        assertThatThrownBy(() -> MOCK.call(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MockProgram.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MockProgram.of(STATUS, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MockProgram.of(null, DEFINITION)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void callingNeverThrowsForInputThatIsNotWhatEncodeMakes() {
        assertThat(MOCK.call(List.of())).isInstanceOf(MockOutcome.Returned.class);
        assertThat(MOCK.call(List.of(new WireValue.Blank()))).isInstanceOf(MockOutcome.Returned.class);
    }

    // ---- a list trimmed by a count field (the review of PR 12)

    private static final ApiRepositoryModel STRUCTS = Fixtures.model("structs");
    private static final ApiOperation LISTLINES = Fixtures.operation(STRUCTS, "listlines");

    private static MockDefinition listlines(Map<String, MockValue> returns) {
        MockCase only = new MockCase(Optional.of("c"), Map.of(), returns, Optional.empty(), Optional.empty(), AT);
        return new MockDefinition("listlines", List.of(only), AT);
    }

    private static MockValue.Array twoLines() {
        return new MockValue.Array(List.of(
                new MockValue.Struct(Map.of("ITEMCD", new MockValue.Text("A", AT)), AT), new MockValue.Struct(Map.of("ITEMCD", new MockValue.Text("B", AT)), AT)), AT);
    }

    private static ApiValue.Arr decodedLines(MockOutcome outcome) {
        List<WireValue> input = OperationCodec.of(LISTLINES).encode(Map.of("orderNumber", new ApiValue.Text("1")), Map.of()).parameters().orElseThrow();
        assertThat(input).hasSize(3);
        DecodeResult decoded = OperationCodec.of(LISTLINES).decode(((MockOutcome.Returned) outcome).output());
        assertThat(decoded.problems()).isEmpty();
        return (ApiValue.Arr) decoded.response().orElseThrow().get("lines");
    }

    private static MockOutcome callListlines(Map<String, MockValue> returns) {
        List<WireValue> input = OperationCodec.of(LISTLINES).encode(Map.of("orderNumber", new ApiValue.Text("1")), Map.of()).parameters().orElseThrow();
        return MockProgram.of(LISTLINES, listlines(returns)).call(input);
    }

    @Test
    void aTrimmedListGivenWithoutItsCountIsNotLost() {
        ApiValue.Arr lines = decodedLines(callListlines(Map.of("P0ROWS", twoLines())));
        assertThat(lines.elements()).hasSize(2);
        assertThat(((ApiValue.Obj) lines.elements().get(1)).members()).containsEntry("itemCode", new ApiValue.Text("B"));
    }

    @Test
    void aCountThatIsGivenAndEqualToTheElementsGivesTheSameResult() {
        assertThat(decodedLines(callListlines(Map.of("P0ROWS", twoLines(), "P0NROW", new MockValue.Whole(BigInteger.TWO, AT)))).elements()).hasSize(2);
    }

    @Test
    void aCountThatDiffersIsUsedAsGivenAndItsDefinitionIsOnlyWarnedAbout() {
        ApiValue.Arr lines = decodedLines(callListlines(Map.of("P0ROWS", twoLines(), "P0NROW", new MockValue.Whole(BigInteger.valueOf(7), AT))));
        assertThat(lines.elements()).hasSize(7);
    }

    @Test
    void aCountThatTheListCannotHoldRefusesTheDefinition() {
        assertThatThrownBy(() -> MockProgram.of(LISTLINES, listlines(Map.of("P0ROWS", twoLines(), "P0NROW", new MockValue.Whole(BigInteger.valueOf(814), AT)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MCK004").hasMessageContaining("is not a count that the list P0ROWS can hold");
        assertThatThrownBy(() -> MockProgram.of(LISTLINES, listlines(Map.of("P0NROW", new MockValue.Whole(BigInteger.valueOf(-1), AT)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MCK004");
    }

    @Test
    void theImplicitDefaultOfAListOperationHasNoLines() {
        assertThat(decodedLines(callListlines(Map.of())).elements()).isEmpty();
    }
}

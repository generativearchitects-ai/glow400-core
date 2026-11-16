package ai.generativearchitects.slay400.pcml.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.Length;
import ai.generativearchitects.slay400.pcml.model.ProgramDef;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The records of a mock file: what they hold, what they refuse, and that a position is not part of what they are. */
class MockModelTest {

    private static final SourceLocation A = new SourceLocation("a.yaml", 1, 1);
    private static final SourceLocation B = new SourceLocation("b.json", 9, 9);

    @Test
    void valuesAreEqualWhateverTheirPosition() {
        assertThat(new MockValue.Text("x", A)).isEqualTo(new MockValue.Text("x", B)).hasSameHashCodeAs(new MockValue.Text("x", B));
        assertThat(new MockValue.Whole(BigInteger.ONE, A)).isEqualTo(new MockValue.Whole(BigInteger.ONE, B)).hasSameHashCodeAs(new MockValue.Whole(BigInteger.ONE, B));
        assertThat(new MockValue.Real("1.5", A)).isEqualTo(new MockValue.Real("1.5", B)).hasSameHashCodeAs(new MockValue.Real("1.5", B));
        assertThat(new MockValue.Bool(true, A)).isEqualTo(new MockValue.Bool(true, B)).hasSameHashCodeAs(new MockValue.Bool(true, B));
        MockValue.Struct struct = new MockValue.Struct(Map.of("P", new MockValue.Text("x", A)), A);
        assertThat(struct).isEqualTo(new MockValue.Struct(Map.of("P", new MockValue.Text("x", B)), B)).hasSameHashCodeAs(new MockValue.Struct(Map.of("P", new MockValue.Text("x", B)), B));
        MockValue.Array array = new MockValue.Array(List.of(new MockValue.Bool(false, A)), A);
        assertThat(array).isEqualTo(new MockValue.Array(List.of(new MockValue.Bool(false, B)), B)).hasSameHashCodeAs(new MockValue.Array(List.of(new MockValue.Bool(false, B)), B));
    }

    @Test
    void valuesOfAnotherKindOrContentAreNotEqual() {
        assertThat(new MockValue.Text("x", A)).isNotEqualTo(new MockValue.Text("y", A)).isNotEqualTo(new MockValue.Real("x", A)).isNotEqualTo("x");
        assertThat(new MockValue.Whole(BigInteger.ONE, A)).isNotEqualTo(new MockValue.Whole(BigInteger.TWO, A)).isNotEqualTo(new MockValue.Text("1", A));
        assertThat(new MockValue.Real("1.5", A)).isNotEqualTo(new MockValue.Real("1.50", A)).isNotEqualTo(new MockValue.Text("1.5", A));
        assertThat(new MockValue.Bool(true, A)).isNotEqualTo(new MockValue.Bool(false, A)).isNotEqualTo(new MockValue.Text("true", A));
        assertThat(new MockValue.Struct(Map.of(), A)).isNotEqualTo(new MockValue.Struct(Map.of("P", new MockValue.Bool(true, A)), A)).isNotEqualTo(new MockValue.Array(List.of(), A));
        assertThat(new MockValue.Array(List.of(), A)).isNotEqualTo(new MockValue.Array(List.of(new MockValue.Bool(true, A)), A)).isNotEqualTo(new MockValue.Struct(Map.of(), A));
        assertThat(new MockValue.Text("1", A).hashCode()).isNotEqualTo(new MockValue.Real("1", A).hashCode());
    }

    @Test
    void aValueKeepsItsPositionAndItsMembersInTheOrderWritten() {
        assertThat(new MockValue.Text("x", B).source()).isEqualTo(B);
        Map<String, MockValue> members = new LinkedHashMap<>();
        members.put("Z", new MockValue.Bool(true, A));
        members.put("A", new MockValue.Bool(false, A));
        assertThat(new MockValue.Struct(members, A).members().keySet()).containsExactly("Z", "A");
        members.put("M", new MockValue.Bool(true, A));
        assertThat(new MockValue.Struct(members, A).members()).hasSize(3);
        assertThatThrownBy(() -> new MockValue.Struct(Map.of(), A).members().put("X", new MockValue.Bool(true, A))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MockValue.Array(List.of(), A).elements().add(new MockValue.Bool(true, A))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void valuesRefuseNulls() {
        assertThatThrownBy(() -> new MockValue.Text(null, A)).isInstanceOf(NullPointerException.class).hasMessage("value");
        assertThatThrownBy(() -> new MockValue.Text("x", null)).isInstanceOf(NullPointerException.class).hasMessage("source");
        assertThatThrownBy(() -> new MockValue.Whole(null, A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockValue.Whole(BigInteger.ONE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockValue.Real(null, A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockValue.Real("1.5", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockValue.Bool(true, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockValue.Struct(Map.of(), null)).isInstanceOf(NullPointerException.class);
        Map<String, MockValue> nullMember = new LinkedHashMap<>();
        nullMember.put("P", null);
        assertThatThrownBy(() -> new MockValue.Struct(nullMember, A)).isInstanceOf(NullPointerException.class).hasMessage("value");
        Map<String, MockValue> nullName = new LinkedHashMap<>();
        nullName.put(null, new MockValue.Bool(true, A));
        assertThatThrownBy(() -> new MockValue.Struct(nullName, A)).isInstanceOf(NullPointerException.class).hasMessage("name");
        assertThatThrownBy(() -> new MockValue.Array(List.of(), null)).isInstanceOf(NullPointerException.class);
    }

    private static MockCase mockCase(String name, Map<String, MockValue> when, Map<String, MockValue> returns, Integer delay, MockFailure fails, SourceLocation at) {
        return new MockCase(Optional.ofNullable(name), when, returns, Optional.ofNullable(delay), Optional.ofNullable(fails), at);
    }

    @Test
    void casesAreEqualWhateverTheirPositionAndDifferInEveryOtherPart() {
        Map<String, MockValue> when = Map.of("P", new MockValue.Text("x", A));
        Map<String, MockValue> returns = Map.of("Q", new MockValue.Text("y", A));
        MockCase base = mockCase("n", when, returns, 5, null, A);
        assertThat(base).isEqualTo(mockCase("n", when, returns, 5, null, B)).hasSameHashCodeAs(mockCase("n", when, returns, 5, null, B));
        assertThat(base).isNotEqualTo(mockCase("m", when, returns, 5, null, A)).isNotEqualTo(mockCase(null, when, returns, 5, null, A))
                .isNotEqualTo(mockCase("n", Map.of(), returns, 5, null, A)).isNotEqualTo(mockCase("n", when, Map.of(), 5, null, A))
                .isNotEqualTo(mockCase("n", when, returns, 6, null, A)).isNotEqualTo(mockCase("n", when, returns, null, null, A))
                .isNotEqualTo(mockCase("n", when, Map.of(), 5, MockFailure.TIMEOUT, A)).isNotEqualTo("n");
        assertThat(mockCase("n", when, Map.of(), 5, MockFailure.TIMEOUT, A)).isNotEqualTo(mockCase("n", when, Map.of(), 5, MockFailure.UNAVAILABLE, A));
        assertThat(base.hashCode()).isNotEqualTo(mockCase("n", when, returns, 6, null, A).hashCode());
    }

    @Test
    void aCaseKeepsOrderAndRefusesNulls() {
        Map<String, MockValue> when = new LinkedHashMap<>();
        when.put("Z", new MockValue.Bool(true, A));
        when.put("A", new MockValue.Bool(true, A));
        assertThat(mockCase("n", when, Map.of(), null, null, A).when().keySet()).containsExactly("Z", "A");
        assertThat(MockCase.MAX_DELAY_MS).isEqualTo(60_000);
        assertThatThrownBy(() -> new MockCase(null, Map.of(), Map.of(), Optional.empty(), Optional.empty(), A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockCase(Optional.empty(), Map.of(), Map.of(), null, Optional.empty(), A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockCase(Optional.empty(), Map.of(), Map.of(), Optional.empty(), null, A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockCase(Optional.empty(), Map.of(), Map.of(), Optional.empty(), Optional.empty(), null)).isInstanceOf(NullPointerException.class);
        Map<String, MockValue> nullMember = new LinkedHashMap<>();
        nullMember.put("P", null);
        assertThatThrownBy(() -> mockCase("n", nullMember, Map.of(), null, null, A)).isInstanceOf(NullPointerException.class).hasMessage("value");
        assertThatThrownBy(() -> mockCase("n", Map.of(), nullMember, null, null, A)).isInstanceOf(NullPointerException.class).hasMessage("value");
        Map<String, MockValue> nullName = new LinkedHashMap<>();
        nullName.put(null, new MockValue.Bool(true, A));
        assertThatThrownBy(() -> mockCase("n", nullName, Map.of(), null, null, A)).isInstanceOf(NullPointerException.class).hasMessage("name");
    }

    @Test
    void definitionsAreEqualWhateverTheirPositionAndDifferInTheirIdAndCases() {
        MockCase c = mockCase("n", Map.of(), Map.of(), null, null, A);
        MockDefinition base = new MockDefinition("op", List.of(c), A);
        assertThat(base).isEqualTo(new MockDefinition("op", List.of(mockCase("n", Map.of(), Map.of(), null, null, B)), B)).hasSameHashCodeAs(new MockDefinition("op", List.of(c), B));
        assertThat(base).isNotEqualTo(new MockDefinition("other", List.of(c), A)).isNotEqualTo(new MockDefinition("op", List.of(), A)).isNotEqualTo("op");
        assertThat(base.hashCode()).isNotEqualTo(new MockDefinition("op", List.of(), A).hashCode());
    }

    @Test
    void aDefinitionRefusesWhatCannotBeOne() {
        assertThat(MockDefinition.MAX_CASES).isEqualTo(1000);
        assertThatThrownBy(() -> new MockDefinition(null, List.of(), A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockDefinition(" ", List.of(), A)).isInstanceOf(IllegalArgumentException.class).hasMessage("operationId must not be blank");
        assertThatThrownBy(() -> new MockDefinition("op", null, A)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockDefinition("op", List.of(), null)).isInstanceOf(NullPointerException.class);
        MockDefinition empty = MockDefinition.empty("op");
        assertThat(empty.operationId()).isEqualTo("op");
        assertThat(empty.cases()).isEmpty();
        assertThat(empty).isEqualTo(new MockDefinition("op", List.of(), A));
    }

    // ---- an API with mocks

    private static final FieldDef FIELD = new FieldDef("P", new WireType.Char(new Length.Fixed(1), Optional.empty()), Usage.INPUT, new Count.None(), Map.of(), A);
    private static final ProgramDef PROGRAM = new ProgramDef("P", Optional.empty(), Optional.empty(), Optional.empty(), List.of(FIELD), Map.of(), A);

    private static ApiOperation operation(String id) {
        return new ApiOperation(id, "1.0.0", Optional.empty(), Optional.empty(), List.of(), PROGRAM, "a.pcml", "api.yaml", "/" + id, HttpMethod.GET,
                CallStrategy.AUTO, List.of(), Optional.empty(), Optional.empty(), A);
    }

    @Test
    void anApiCarriesAtMostOneMockForEachOfItsOperations() {
        ApiDefinition without = new ApiDefinition("x", "X", "1.0.0", "p", List.of(operation("a")));
        assertThat(without.mocks()).isEmpty();
        MockDefinition mock = MockDefinition.empty("a");
        ApiDefinition with = new ApiDefinition("x", "X", "1.0.0", "p", List.of(operation("a"), operation("b")), List.of(mock));
        assertThat(with.mocks()).containsExactly(mock);
        assertThatThrownBy(() -> new ApiDefinition("x", "X", "1.0.0", "p", List.of(operation("a")), List.of(MockDefinition.empty("zzz"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("mocks must be at most one for each operation of the API");
        assertThatThrownBy(() -> new ApiDefinition("x", "X", "1.0.0", "p", List.of(operation("a")), List.of(mock, mock)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiDefinition("x", "X", "1.0.0", "p", List.of(operation("a")), null)).isInstanceOf(NullPointerException.class);
    }
}

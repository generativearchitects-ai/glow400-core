package ai.generativearchitects.glow400.pcml.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The value records, results and problems. */
class ValuesTest {

    @Test
    void apiValuesRejectNull() {
        assertThatThrownBy(() -> new ApiValue.Text(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Integer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Decimal(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Date(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Time(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.DateTime(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Binary(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Obj(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiValue.Arr(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void wireValuesRejectNull() {
        assertThatThrownBy(() -> new WireValue.Text(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Integer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Decimal(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Bytes(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Date(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Time(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Timestamp(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Struct(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WireValue.Array(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void valuesHaveValueEquality() {
        assertThat(new ApiValue.Text("a")).isEqualTo(new ApiValue.Text("a")).isNotEqualTo(new ApiValue.Text("b"));
        assertThat(new ApiValue.Integer(BigInteger.ONE)).isEqualTo(new ApiValue.Integer(BigInteger.ONE));
        assertThat(new ApiValue.Decimal(new BigDecimal("1.0"))).isNotEqualTo(new ApiValue.Decimal(new BigDecimal("1.00")));
        assertThat(new ApiValue.Bool(true)).isEqualTo(new ApiValue.Bool(true)).isNotEqualTo(new ApiValue.Bool(false));
        assertThat(new ApiValue.Date(LocalDate.EPOCH)).isEqualTo(new ApiValue.Date(LocalDate.EPOCH));
        assertThat(new ApiValue.Time(LocalTime.NOON)).isEqualTo(new ApiValue.Time(LocalTime.NOON));
        assertThat(new ApiValue.DateTime(LocalDateTime.MIN)).isEqualTo(new ApiValue.DateTime(LocalDateTime.MIN));
        assertThat(new ApiValue.Absent()).isEqualTo(new ApiValue.Absent());
        assertThat(new WireValue.Blank()).isEqualTo(new WireValue.Blank());
        assertThat(new WireValue.Float(1.5)).isEqualTo(new WireValue.Float(1.5));
    }

    @Test
    void binaryValuesAreCopiedAndCompareByContent() {
        byte[] source = {1, 2, 3};
        ApiValue.Binary api = new ApiValue.Binary(source);
        source[0] = 9;
        assertThat(api.value()).containsExactly(1, 2, 3);
        api.value()[1] = 9;
        assertThat(api.value()).containsExactly(1, 2, 3);
        assertThat(api).isEqualTo(new ApiValue.Binary(new byte[] {1, 2, 3})).isNotEqualTo(new ApiValue.Binary(new byte[] {1, 2}));
        assertThat(api.hashCode()).isEqualTo(new ApiValue.Binary(new byte[] {1, 2, 3}).hashCode());
        assertThat(api).isNotEqualTo("x");
        assertThat(api.toString()).isEqualTo("Binary[3 bytes]").doesNotContain("1");

        byte[] wireSource = {4, 5};
        WireValue.Bytes wire = new WireValue.Bytes(wireSource);
        wireSource[0] = 9;
        assertThat(wire.value()).containsExactly(4, 5);
        wire.value()[0] = 9;
        assertThat(wire.value()).containsExactly(4, 5);
        assertThat(wire).isEqualTo(new WireValue.Bytes(new byte[] {4, 5})).isNotEqualTo(new WireValue.Bytes(new byte[] {4}));
        assertThat(wire.hashCode()).isEqualTo(new WireValue.Bytes(new byte[] {4, 5}).hashCode());
        assertThat(wire).isNotEqualTo("x");
        assertThat(wire.toString()).isEqualTo("Bytes[2 bytes]");
    }

    @Test
    void objectsKeepTheirOrderAndCopyTheirMembers() {
        Map<String, ApiValue> members = new LinkedHashMap<>();
        for (String name : List.of("z", "a", "m", "b")) {
            members.put(name, new ApiValue.Text(name));
        }
        ApiValue.Obj obj = new ApiValue.Obj(members);
        members.put("extra", new ApiValue.Absent());
        assertThat(obj.members().keySet()).containsExactly("z", "a", "m", "b");
        assertThatThrownBy(() -> obj.members().put("x", new ApiValue.Absent())).isInstanceOf(UnsupportedOperationException.class);
        Map<String, ApiValue> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        assertThatThrownBy(() -> new ApiValue.Obj(withNull)).isInstanceOf(NullPointerException.class);
        Map<String, ApiValue> nullKey = new LinkedHashMap<>();
        nullKey.put(null, new ApiValue.Absent());
        assertThatThrownBy(() -> new ApiValue.Obj(nullKey)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void listsAreCopied() {
        List<ApiValue> elements = new ArrayList<>(List.of(new ApiValue.Absent()));
        ApiValue.Arr arr = new ApiValue.Arr(elements);
        elements.add(new ApiValue.Absent());
        assertThat(arr.elements()).hasSize(1);
        List<WireValue> wires = new ArrayList<>(List.of(new WireValue.Blank()));
        WireValue.Struct struct = new WireValue.Struct(wires);
        WireValue.Array array = new WireValue.Array(wires);
        wires.add(new WireValue.Blank());
        assertThat(struct.members()).hasSize(1);
        assertThat(array.elements()).hasSize(1);
        assertThatThrownBy(() -> arr.elements().add(new ApiValue.Absent())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aProblemNeedsACodeAndShowsItsPlace() {
        ValueProblem problem = new ValueProblem("VAL003", "A.B", "a.b", "too long");
        assertThat(problem.toString()).isEqualTo("VAL003 a.b: too long");
        assertThat(new ValueProblem("VAL001", "", "", "x").toString()).isEqualTo("VAL001 (request): x");
        assertThatThrownBy(() -> new ValueProblem(" ", "", "", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValueProblem(null, "", "", "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValueProblem("V", null, "", "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValueProblem("V", "", null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValueProblem("V", "", "", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void resultsCopyTheirContentAndRejectNull() {
        List<ValueProblem> problems = new ArrayList<>(List.of(new ValueProblem("V", "", "", "x")));
        EncodeResult encoded = new EncodeResult(Optional.empty(), problems);
        DecodeResult decoded = new DecodeResult(Optional.empty(), problems);
        problems.clear();
        assertThat(encoded.problems()).hasSize(1);
        assertThat(decoded.problems()).hasSize(1);
        assertThatThrownBy(() -> new EncodeResult(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DecodeResult(null, List.of())).isInstanceOf(NullPointerException.class);
        Map<String, ApiValue> response = new LinkedHashMap<>(Map.of("a", new ApiValue.Absent()));
        DecodeResult ok = new DecodeResult(Optional.of(response), List.of());
        response.put("b", new ApiValue.Absent());
        assertThat(ok.response().orElseThrow()).containsOnlyKeys("a");
        assertThatThrownBy(() -> ok.response().orElseThrow().put("c", new ApiValue.Absent())).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new EncodeResult(Optional.of(List.of(new WireValue.Blank())), List.of()).parameters()).isPresent();
    }

    @Test
    void aConfigurationIssueRejectsNull() {
        assertThat(new ConfigurationIssue("A", "m").fieldPath()).isEqualTo("A");
        assertThatThrownBy(() -> new ConfigurationIssue(null, "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConfigurationIssue("A", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theCodecRejectsNullArguments() {
        OperationCodec codec = Ops.codec(Ops.field("A", Ops.chr(1)));
        assertThatThrownBy(() -> codec.encode(null, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.encode(Map.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OperationCodec.check(null)).isInstanceOf(NullPointerException.class);
    }
}

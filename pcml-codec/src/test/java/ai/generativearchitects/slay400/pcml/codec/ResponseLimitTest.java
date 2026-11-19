package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.chr;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static ai.generativearchitects.slay400.pcml.codec.Ops.op;
import static ai.generativearchitects.slay400.pcml.codec.Ops.struct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** Kickoff 14, Part C: {@code defaults.maxResponseValues}, enforced while decoding. */
class ResponseLimitTest {

    private static WireValue.Text t(String s) {
        return new WireValue.Text(s);
    }

    private static WireValue.Array of(int n) {
        List<WireValue> elements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            elements.add(t("x"));
        }
        return new WireValue.Array(elements);
    }

    private static List<Tuple> problems(DecodeResult result) {
        return result.problems().stream().map(p -> Tuple.tuple(p.code(), p.fieldPath())).toList();
    }

    @Test
    void ofRejectsALimitBelow1() {
        assertThatThrownBy(() -> OperationCodec.of(op(field("A", chr(1)).response()), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationCodec.of(op(field("A", chr(1)).response()), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactlyAtTheLimitHasNoProblem() {
        var f = field("A", chr(1)).count(5).response();
        DecodeResult r = OperationCodec.of(op(f), 5).decode(List.of(of(5)));
        assertThat(r.problems()).isEmpty();
        assertThat(((ApiValue.Arr) r.response().orElseThrow().get("a")).elements()).hasSize(5);
    }

    @Test
    void oneOverTheLimitIsVal104AndStopsBuildingTheRest() {
        var f = field("A", chr(1)).count(6).response();
        DecodeResult r = OperationCodec.of(op(f), 5).decode(List.of(of(6)));
        assertThat(problems(r)).containsExactly(Tuple.tuple("VAL104", "A[5]"));
        assertThat(r.response()).isEmpty();
    }

    @Test
    void farOverTheLimitIsStillOneVal104NotOnePerExcessValue() {
        var f = field("A", chr(1)).count(10_000).response();
        DecodeResult r = OperationCodec.of(op(f), 100).decode(List.of(of(10_000)));
        assertThat(problems(r)).containsExactly(Tuple.tuple("VAL104", "A[100]"));
    }

    @Test
    void theLimitAppliesAcrossEveryResponseFieldOfTheOperationTogether() {
        var a = field("A", chr(1)).count(3).response();
        var b = field("B", chr(1)).count(3).response();
        DecodeResult r = OperationCodec.of(op(a, b), 4).decode(List.of(of(3), of(3)));
        assertThat(problems(r)).hasSize(1).extracting(Tuple::toString).allMatch(s -> s.contains("VAL104"));
    }

    @Test
    void theLimitAppliesToValuesInsideNestedStructsToo() {
        var cell = struct("CELL", field("X", chr(1)), field("Y", chr(1))).count(10).response();
        WireValue cellValue = new WireValue.Struct(List.of(t("x"), t("y")));
        List<WireValue> elements = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            elements.add(cellValue);
        }
        // 10 cells of 2 scalars each = 20 values; a limit of 7 is passed partway through the 4th cell.
        DecodeResult r = OperationCodec.of(op(cell), 7).decode(List.of(new WireValue.Array(elements)));
        assertThat(problems(r)).hasSize(1).extracting(Tuple::toString).allMatch(s -> s.contains("VAL104"));
        assertThat(r.response()).isEmpty();
    }

    @Test
    void aTrimmedArrayThatFitsOnceTrimmedNeverReachesTheLimit() {
        var f = field("A", chr(1)).count(10_000).response().trimBlank();
        List<WireValue> elements = new ArrayList<>();
        elements.add(t("x"));
        for (int i = 1; i < 10_000; i++) {
            elements.add(t(" "));
        }
        DecodeResult r = OperationCodec.of(op(f), 1).decode(List.of(new WireValue.Array(elements)));
        assertThat(r.problems()).isEmpty();
        assertThat(((ApiValue.Arr) r.response().orElseThrow().get("a")).elements()).hasSize(1);
    }
}

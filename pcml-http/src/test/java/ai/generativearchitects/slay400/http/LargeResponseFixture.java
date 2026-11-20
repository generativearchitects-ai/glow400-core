package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.Ops;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import ai.generativearchitects.slay400.pcml.model.Length;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The synthetic shape of kickoff 14's "What real programs showed": a response array of structs, each a {@code char(4)},
 * a {@code char(4)} and a {@code packed(9,2)}, all declared at a fixed count, filled at the front the way a real RPG
 * array is (decision 109's corpus finding). Shared by {@link LargeResponseBenchmark} and the guard test.
 */
final class LargeResponseFixture {

    private LargeResponseFixture() {
    }

    /** An operation whose single response field is {@code n} of these cells, with a {@code dropBlank} trim if {@code trim}. */
    static ApiOperation operation(int n, boolean trim) {
        Ops.F cell = Ops.struct("CELL",
                Ops.field("A", new WireType.Char(new Length.Fixed(4), Optional.empty())),
                Ops.field("B", new WireType.Char(new Length.Fixed(4), Optional.empty())),
                Ops.field("AMT", new WireType.Packed(9, 2)))
                .response().count(n);
        if (trim) {
            cell.trimBlank();
        }
        return Ops.op(cell);
    }

    /** {@code n} elements, the first {@code filled} of them real values, the rest entirely blank. */
    static WireValue cells(int n, int filled) {
        List<WireValue> elements = new ArrayList<>(n);
        WireValue real = new WireValue.Struct(List.of(new WireValue.Text("AAAA"), new WireValue.Text("BBBB"),
                new WireValue.Decimal(new BigDecimal("123.45"))));
        WireValue blank = new WireValue.Struct(List.of(new WireValue.Text("    "), new WireValue.Text("    "),
                new WireValue.Decimal(BigDecimal.ZERO)));
        for (int i = 0; i < n; i++) {
            elements.add(i < filled ? real : blank);
        }
        return new WireValue.Array(elements);
    }
}

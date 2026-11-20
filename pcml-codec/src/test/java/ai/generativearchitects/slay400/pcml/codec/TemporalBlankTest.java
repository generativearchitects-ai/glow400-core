package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.codec;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.model.WireType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** kickoff 15, Part C: the lowest date, time or timestamp counts as blank for {@code dropBlank} (decision 126). */
class TemporalBlankTest {

    private static WireValue.Array a(WireValue... elements) {
        return new WireValue.Array(List.of(elements));
    }

    @Test
    void theLowestDateCountsAsBlankButAnyOtherDateDoesNot() {
        Ops.F f = field("D", new WireType.Date()).count(3).response().trimBlank();
        DecodeResult cleared = codec(f).decode(List.of(a(new WireValue.Date(LocalDate.of(2026, 1, 2)),
                new WireValue.Date(LocalDate.of(1, 1, 1)), new WireValue.Date(LocalDate.of(1, 1, 1)))));
        assertThat(cleared.problems()).isEmpty();
        assertThat(((ApiValue.Arr) cleared.response().orElseThrow().get("d")).elements()).hasSize(1);

        DecodeResult epoch = codec(f).decode(List.of(a(new WireValue.Date(LocalDate.of(2026, 1, 2)),
                new WireValue.Date(LocalDate.EPOCH), new WireValue.Date(LocalDate.EPOCH))));
        assertThat(((ApiValue.Arr) epoch.response().orElseThrow().get("d")).elements()).hasSize(3);
    }

    @Test
    void aTwoDigitYearFormatAlsoTreats1940AsBlankButAFourDigitFormatDoesNot() {
        for (String format : List.of("MDY", "DMY", "YMD", "JUL")) {
            Ops.F f = field("D", new WireType.Date()).attr("dateformat", format).count(2).response().trimBlank();
            assertThat(((ApiValue.Arr) codec(f).decode(List.of(a(new WireValue.Date(LocalDate.of(2026, 1, 2)),
                    new WireValue.Date(LocalDate.of(1940, 1, 1))))).response().orElseThrow().get("d")).elements())
                    .as(format).hasSize(1);
        }
        Ops.F iso = field("D", new WireType.Date()).attr("dateformat", "ISO").count(2).response().trimBlank();
        assertThat(((ApiValue.Arr) codec(iso).decode(List.of(a(new WireValue.Date(LocalDate.of(2026, 1, 2)),
                new WireValue.Date(LocalDate.of(1940, 1, 1))))).response().orElseThrow().get("d")).elements()).hasSize(2);
        // 0001-01-01 is still blank regardless of the format.
        assertThat(((ApiValue.Arr) codec(iso).decode(List.of(a(new WireValue.Date(LocalDate.of(2026, 1, 2)),
                new WireValue.Date(LocalDate.of(1, 1, 1))))).response().orElseThrow().get("d")).elements()).hasSize(1);
    }

    @Test
    void midnightTimeAndTheLowestTimestampCountAsBlank() {
        Ops.F time = field("T", new WireType.Time()).count(2).response().trimBlank();
        assertThat(((ApiValue.Arr) codec(time).decode(List.of(a(new WireValue.Time(LocalTime.of(9, 0)),
                new WireValue.Time(LocalTime.MIDNIGHT)))).response().orElseThrow().get("t")).elements()).hasSize(1);

        Ops.F ts = field("S", new WireType.Timestamp()).count(2).response().trimBlank();
        assertThat(((ApiValue.Arr) codec(ts).decode(List.of(a(new WireValue.Timestamp(LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
                new WireValue.Timestamp(LocalDateTime.of(1, 1, 1, 0, 0, 0))))).response().orElseThrow().get("s")).elements()).hasSize(1);
    }

    @Test
    void aKeptElementKeepsItsRealValue() {
        Ops.F f = field("D", new WireType.Date()).count(2).response().trimBlank();
        DecodeResult r = codec(f).decode(List.of(a(new WireValue.Date(LocalDate.of(2026, 1, 2)), new WireValue.Date(LocalDate.of(1, 1, 1)))));
        assertThat(r.response().orElseThrow().get("d")).isEqualTo(new ApiValue.Arr(List.of(new ApiValue.Date(LocalDate.of(2026, 1, 2)))));
    }
}

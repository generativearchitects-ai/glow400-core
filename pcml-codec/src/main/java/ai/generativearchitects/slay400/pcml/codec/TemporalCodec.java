package ai.generativearchitects.slay400.pcml.codec;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/** A {@code date}, {@code time} or {@code timestamp} field: the call layer formats it, so the value passes through. */
final class TemporalCodec implements ScalarCodec {

    enum Kind { DATE, TIME, TIMESTAMP }

    /** The lowest date, time or timestamp an RPG program's cleared or initialised field holds (decision 126). */
    private static final LocalDate LOWEST_DATE = LocalDate.of(1, 1, 1);
    /** The lowest date of a two-digit-year format (MDY, DMY, YMD, JUL), also blank for {@code dropBlank}. */
    private static final LocalDate LOWEST_TWO_DIGIT_YEAR_DATE = LocalDate.of(1940, 1, 1);
    private static final LocalDateTime LOWEST_TIMESTAMP = LocalDateTime.of(1, 1, 1, 0, 0, 0);

    private final Kind kind;
    /** Whether a DATE field's format has a two-digit year, so 1940-01-01 is also blank, next to 0001-01-01. */
    private final boolean twoDigitYear;

    TemporalCodec(Kind kind, boolean twoDigitYear) {
        this.kind = kind;
        this.twoDigitYear = twoDigitYear;
    }

    @Override
    public Optional<WireValue> absent() {
        return Optional.empty(); // these types have no blank
    }

    @Override
    public WireValue blank() {
        return new WireValue.Blank();
    }

    @Override
    public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
        return switch (kind) {
            case DATE -> value instanceof ApiValue.Date d ? Optional.of(new WireValue.Date(d.value())) : wrong(where, problems);
            case TIME -> value instanceof ApiValue.Time t ? Optional.of(new WireValue.Time(t.value())) : wrong(where, problems);
            case TIMESTAMP -> value instanceof ApiValue.DateTime t ? Optional.of(new WireValue.Timestamp(t.value())) : wrong(where, problems);
        };
    }

    private Optional<WireValue> wrong(Where where, Problems problems) {
        problems.add("VAL002", where, "The value is not a " + (kind == Kind.TIMESTAMP ? "datetime" : kind.name().toLowerCase(java.util.Locale.ROOT)));
        return Optional.empty();
    }

    @Override
    public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
        throw new UnsupportedOperationException("A date or time field cannot be injected: see supportsInjection");
    }

    @Override
    public boolean supportsInjection() {
        return false;
    }

    @Override
    public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
        return switch (kind) {
            case DATE -> value instanceof WireValue.Date d ? Optional.of(new ApiValue.Date(d.value())) : unexpected(where, problems);
            case TIME -> value instanceof WireValue.Time t ? Optional.of(new ApiValue.Time(t.value())) : unexpected(where, problems);
            case TIMESTAMP -> value instanceof WireValue.Timestamp t ? Optional.of(new ApiValue.DateTime(t.value())) : unexpected(where, problems);
        };
    }

    private Optional<ApiValue> unexpected(Where where, Problems problems) {
        problems.add("VAL101", where, "A " + (kind == Kind.TIMESTAMP ? "timestamp" : kind.name().toLowerCase(java.util.Locale.ROOT)) + " was expected");
        return Optional.empty();
    }

    /**
     * For {@code dropBlank} only (kickoff 15, Part C, decision 126): the lowest value of a date, time or timestamp
     * counts as blank, as zero does for a number, because a cleared or initialised RPG structure holds it rather
     * than leaving the field entirely absent. Everything else (including a kept element's real value) is unchanged:
     * {@link WireValue.Blank} stands for "no value" where the type has none and is recognised by the caller first.
     */
    @Override
    public boolean isBlank(WireValue value) {
        return switch (kind) {
            case DATE -> value instanceof WireValue.Date d
                    && (d.value().equals(LOWEST_DATE) || twoDigitYear && d.value().equals(LOWEST_TWO_DIGIT_YEAR_DATE));
            case TIME -> value instanceof WireValue.Time t && t.value().equals(LocalTime.MIDNIGHT);
            case TIMESTAMP -> value instanceof WireValue.Timestamp t && t.value().equals(LOWEST_TIMESTAMP);
        };
    }
}

package ai.generativearchitects.glow400.pcml.codec;

import java.util.Optional;

/** A {@code date}, {@code time} or {@code timestamp} field: the call layer formats it, so the value passes through. */
final class TemporalCodec implements ScalarCodec {

    enum Kind { DATE, TIME, TIMESTAMP }

    private final Kind kind;

    TemporalCodec(Kind kind) {
        this.kind = kind;
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

    @Override
    public boolean isBlank(WireValue value) {
        // A date, time or timestamp has no blank of its own: only WireValue.Blank stands for it, which the caller recognises first
        return false;
    }
}

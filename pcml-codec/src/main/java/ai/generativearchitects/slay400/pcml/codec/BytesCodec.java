package ai.generativearchitects.slay400.pcml.codec;

import java.util.Optional;

/**
 * A {@code byte} field, or a {@code char} or {@code varchar} field with CCSID 65535 read as bytes (kickoff 15,
 * Part A): an exact number of bytes for {@code byte} and {@code char}, at most the declared length for
 * {@code varchar}, whose value has its current length rather than the declared one, the same as text varchar.
 */
final class BytesCodec implements ScalarCodec {

    /** The field length in bytes (exact, or the maximum for varchar), or -1 if it is given by another field. */
    private final int length;
    /** Whether {@link #length} is a maximum (varchar) rather than an exact length (byte, char). */
    private final boolean varchar;

    BytesCodec(int length, boolean varchar) {
        this.length = length;
        this.varchar = varchar;
    }

    @Override
    public Optional<WireValue> absent() {
        return Optional.of(blank());
    }

    @Override
    public WireValue blank() {
        return new WireValue.Bytes(varchar ? new byte[0] : new byte[Math.max(length, 0)]);
    }

    @Override
    public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
        if (!(value instanceof ApiValue.Binary b)) {
            problems.add("VAL002", where, "The value is not binary");
            return Optional.empty();
        }
        byte[] bytes = b.value();
        if (length >= 0 && (varchar ? bytes.length > length : bytes.length != length)) {
            problems.add("VAL003", where, varchar ? "The value is too long: at most " + length + " bytes"
                    : "The value must be exactly " + length + " bytes");
            return Optional.empty();
        }
        return Optional.of(new WireValue.Bytes(bytes));
    }

    @Override
    public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
        throw new UnsupportedOperationException("A binary field cannot be injected: see supportsInjection");
    }

    @Override
    public boolean supportsInjection() {
        return false;
    }

    @Override
    public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
        if (!(value instanceof WireValue.Bytes b)) {
            problems.add("VAL101", where, "Bytes were expected");
            return Optional.empty();
        }
        byte[] bytes = b.value();
        if (length >= 0 && (varchar ? bytes.length > length : bytes.length != length)) {
            problems.add("VAL101", where, varchar ? "The bytes are longer than the field's declared length"
                    : "The bytes do not have the length of the field");
            return Optional.empty();
        }
        return Optional.of(new ApiValue.Binary(bytes));
    }

    @Override
    public boolean isBlank(WireValue value) {
        // WireValue.Blank is recognised by the caller before it asks the codec
        if (!(value instanceof WireValue.Bytes b)) {
            return false;
        }
        for (byte x : b.value()) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }
}

package ai.generativearchitects.glow400.pcml.codec;

import java.util.Optional;

/** A {@code byte} field: an exact number of bytes. */
final class BytesCodec implements ScalarCodec {

    /** The field length in bytes, or -1 if it is given by another field. */
    private final int length;

    BytesCodec(int length) {
        this.length = length;
    }

    @Override
    public Optional<WireValue> absent() {
        return Optional.of(blank());
    }

    @Override
    public WireValue blank() {
        return new WireValue.Bytes(new byte[Math.max(length, 0)]);
    }

    @Override
    public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
        if (!(value instanceof ApiValue.Binary b)) {
            problems.add("VAL002", where, "The value is not binary");
            return Optional.empty();
        }
        byte[] bytes = b.value();
        if (length >= 0 && bytes.length != length) {
            problems.add("VAL003", where, "The value must be exactly " + length + " bytes");
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
        if (length >= 0 && bytes.length != length) {
            problems.add("VAL101", where, "The bytes do not have the length of the field");
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

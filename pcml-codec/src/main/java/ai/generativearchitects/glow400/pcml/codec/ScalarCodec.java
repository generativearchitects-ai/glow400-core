package ai.generativearchitects.glow400.pcml.codec;

import java.util.Optional;

/** Encodes and decodes the values of one scalar field, by its wire type and semantic type. */
interface ScalarCodec {

    /** The value that stands for "no value" in a request; empty if the type has none (date, time, timestamp). */
    Optional<WireValue> absent();

    /** A value to send where there is nothing to say: output, internal and hidden fields and filler elements. */
    WireValue blank();

    /** Encodes a value that is not absent and not a struct or array; reports a problem and returns empty if it does not fit. */
    Optional<WireValue> encode(ApiValue value, Where where, Problems problems);

    /** Encodes the already resolved text of an injected parameter; empty and a problem if it does not fit. */
    Optional<WireValue> encodeInjected(String text, Where where, Problems problems);

    /** Whether an injected parameter can have this wire type. */
    boolean supportsInjection();

    /** Decodes a returned value; empty, after reporting a problem, if it cannot be read. {@link ApiValue.Absent} is a value. */
    Optional<ApiValue> decode(WireValue value, Where where, Problems problems);

    /** Whether a returned value is blank: blank text, zero, or zero bytes. */
    boolean isBlank(WireValue value);
}

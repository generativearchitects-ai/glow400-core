package ai.generativearchitects.glow400.pcml.codec;

import java.util.Objects;

/**
 * Something wrong with a value, found while encoding or decoding. The message never contains the value itself, only
 * where it is and what is wrong, so it is safe to log.
 *
 * <p>Codes: {@code VAL001} required field missing; {@code VAL002} wrong kind of value for the field's type;
 * {@code VAL003} too long, too many digits or decimals, or out of range; {@code VAL004} text does not have the expected
 * form; {@code VAL005} too many array elements; {@code VAL006} unknown field. While decoding: {@code VAL101} program
 * output does not have the expected shape or wire type; {@code VAL102} wire value cannot be read as the semantic type;
 * {@code VAL103} the count field of an array is negative, not a number, or larger than the array.
 *
 * @param code        stable code, for example {@code VAL003}
 * @param fieldPath   path in the PCML, with {@code [i]} after an array element: {@code P0ROWS[2].QTY}; empty if the
 *                    field is unknown
 * @param exposedPath the same path in API names: {@code lines[2].quantity}
 * @param message     what is wrong, without the value
 */
public record ValueProblem(String code, String fieldPath, String exposedPath, String message) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public ValueProblem {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        Objects.requireNonNull(fieldPath, "fieldPath");
        Objects.requireNonNull(exposedPath, "exposedPath");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return code + " " + (exposedPath.isEmpty() ? "(request)" : exposedPath) + ": " + message;
    }
}

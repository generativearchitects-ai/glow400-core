package ai.generativearchitects.glow400.contract;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * What a consumer can rely on about the values of a field. Every limit is optional: a limit that depends on another field
 * of the program, such as a length given by a field, is not a limit a consumer can see.
 *
 * @param maxLength the most characters of a text
 * @param minimum   the smallest whole number; for a decimal held as text, 0 means it cannot be negative
 * @param maximum   the largest whole number
 * @param digits    the digits of a decimal: the most for packed and zoned, the characters of the text for a decimal held as text
 * @param scale     the digits after the decimal point of a packed or zoned decimal, which is exactly how many are written
 * @param minBytes  the fewest bytes of a binary value
 * @param maxBytes  the most bytes of a binary value
 * @param maxItems  the most elements of an array
 */
public record Limits(Optional<Integer> maxLength, Optional<BigInteger> minimum, Optional<BigInteger> maximum, Optional<Integer> digits,
                     Optional<Integer> scale, Optional<Integer> minBytes, Optional<Integer> maxBytes, Optional<Integer> maxItems) {

    /** @throws NullPointerException if an argument is {@code null} */
    public Limits {
        Objects.requireNonNull(maxLength, "maxLength");
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        Objects.requireNonNull(digits, "digits");
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(minBytes, "minBytes");
        Objects.requireNonNull(maxBytes, "maxBytes");
        Objects.requireNonNull(maxItems, "maxItems");
    }

    /** No limit at all. */
    public static Limits none() {
        return new Limits(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /** These limits with a maximum number of array elements. */
    public Limits withMaxItems(int items) {
        return new Limits(maxLength, minimum, maximum, digits, scale, minBytes, maxBytes, Optional.of(items));
    }
}

package ai.generativearchitects.glow400.pcml.api;

import java.util.Objects;
import java.util.Optional;

/**
 * How a value is turned into wire text and back: the {@code pad}, {@code padChar}, {@code nullAs} and
 * {@code format} settings of the overlay, with defaults already resolved.
 *
 * <p>{@code trueValue} and {@code falseValue} are the texts that stand for true and false. For a binding whose
 * semantic type is BOOLEAN they are always present (the overlay's value, or the defaults {@code "1"} and {@code "0"});
 * for any other type they are present only if the overlay set them, which the validator reports.
 *
 * @param pad        side to pad on, or {@link Pad#NONE}
 * @param padChar    character used for padding; meaningful only if {@code pad} is not {@link Pad#NONE}
 * @param nullAs     wire text that stands for "no value"
 * @param format     date or time pattern, verbatim, if any
 * @param trueValue  text for true, see above
 * @param falseValue text for false, see above
 */
public record CodecSettings(
        Pad pad,
        char padChar,
        String nullAs,
        Optional<String> format,
        Optional<String> trueValue,
        Optional<String> falseValue) {

    /** Text for true when a BOOLEAN field has no {@code trueValue}. */
    public static final String DEFAULT_TRUE_VALUE = "1";
    /** Text for false when a BOOLEAN field has no {@code falseValue}. */
    public static final String DEFAULT_FALSE_VALUE = "0";

    /** @throws NullPointerException if an argument is {@code null} */
    public CodecSettings {
        Objects.requireNonNull(pad, "pad");
        Objects.requireNonNull(nullAs, "nullAs");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(trueValue, "trueValue");
        Objects.requireNonNull(falseValue, "falseValue");
    }

    /** Settings without boolean texts. */
    public CodecSettings(Pad pad, char padChar, String nullAs, Optional<String> format) {
        this(pad, padChar, nullAs, format, Optional.empty(), Optional.empty());
    }

    /** Settings for a value that is neither padded nor given a null text. */
    public static CodecSettings none() {
        return new CodecSettings(Pad.NONE, ' ', "", Optional.empty());
    }
}

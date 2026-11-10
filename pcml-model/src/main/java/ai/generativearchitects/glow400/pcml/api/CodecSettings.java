package ai.generativearchitects.glow400.pcml.api;

import java.util.Objects;
import java.util.Optional;

/**
 * How a value is turned into wire text and back: the {@code pad}, {@code padChar}, {@code nullAs} and
 * {@code format} settings of the overlay, with defaults already resolved.
 *
 * @param pad     side to pad on, or {@link Pad#NONE}
 * @param padChar character used for padding; meaningful only if {@code pad} is not {@link Pad#NONE}
 * @param nullAs  wire text that stands for "no value"
 * @param format  date or time pattern, verbatim, if any
 */
public record CodecSettings(Pad pad, char padChar, String nullAs, Optional<String> format) {

    /** @throws NullPointerException if an argument is {@code null} */
    public CodecSettings {
        Objects.requireNonNull(pad, "pad");
        Objects.requireNonNull(nullAs, "nullAs");
        Objects.requireNonNull(format, "format");
    }

    /** Settings for a value that is neither padded nor given a null text. */
    public static CodecSettings none() {
        return new CodecSettings(Pad.NONE, ' ', "", Optional.empty());
    }
}

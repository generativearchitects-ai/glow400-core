package ai.generativearchitects.glow400.pcml.api;

import java.util.List;
import java.util.Objects;

/**
 * Repository-wide defaults: the content of {@code gateway.yaml}, with absent keys replaced by their defaults.
 *
 * @param charCodec   codec settings of char and varchar fields ({@code defaults.char})
 * @param inputOutput policy for {@code inputoutput} parameters without a direction ({@code defaults.inputoutput})
 * @param emitters    names of the emitters to run, verbatim
 * @param largeArrayThreshold a fixed array with at least this many elements is "large" ({@code defaults.largeArrayThreshold})
 */
public record GatewayDefaults(
        CodecSettings charCodec,
        InputOutputPolicy inputOutput,
        List<String> emitters,
        int largeArrayThreshold) {

    /** The threshold when {@code gateway.yaml} gives none. */
    public static final int DEFAULT_LARGE_ARRAY_THRESHOLD = 100;

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code largeArrayThreshold} is less than 1
     */
    public GatewayDefaults {
        Objects.requireNonNull(charCodec, "charCodec");
        Objects.requireNonNull(inputOutput, "inputOutput");
        emitters = List.copyOf(emitters);
        if (largeArrayThreshold < 1) {
            throw new IllegalArgumentException("largeArrayThreshold must be at least 1");
        }
    }

    /** Defaults with the standard large-array threshold. */
    public GatewayDefaults(CodecSettings charCodec, InputOutputPolicy inputOutput, List<String> emitters) {
        this(charCodec, inputOutput, emitters, DEFAULT_LARGE_ARRAY_THRESHOLD);
    }

    /** The values that apply when there is no {@code gateway.yaml}. */
    public static GatewayDefaults standard() {
        return new GatewayDefaults(
                new CodecSettings(Pad.RIGHT, ' ', "", java.util.Optional.empty()),
                InputOutputPolicy.BOTH,
                List.of("descriptor", "openapi", "mock"),
                DEFAULT_LARGE_ARRAY_THRESHOLD);
    }
}

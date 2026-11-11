package ai.generativearchitects.glow400.pcml.api;

import java.util.List;
import java.util.Objects;

/**
 * Repository-wide defaults: the content of {@code gateway.yaml}, with absent keys replaced by their defaults.
 *
 * @param charCodec   codec settings of char and varchar fields ({@code defaults.char})
 * @param inputOutput policy for {@code inputoutput} parameters without a direction ({@code defaults.inputoutput})
 * @param emitters    names of the emitters to run, verbatim
 */
public record GatewayDefaults(CodecSettings charCodec, InputOutputPolicy inputOutput, List<String> emitters) {

    /** @throws NullPointerException if an argument is {@code null} */
    public GatewayDefaults {
        Objects.requireNonNull(charCodec, "charCodec");
        Objects.requireNonNull(inputOutput, "inputOutput");
        emitters = List.copyOf(emitters);
    }

    /** The values that apply when there is no {@code gateway.yaml}. */
    public static GatewayDefaults standard() {
        return new GatewayDefaults(
                new CodecSettings(Pad.RIGHT, ' ', "", java.util.Optional.empty()),
                InputOutputPolicy.BOTH,
                List.of("descriptor", "openapi", "mock"));
    }
}

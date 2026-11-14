package ai.generativearchitects.glow400.pcml.codec;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of decoding what a program returned.
 *
 * <p>At most one of {@code response} and {@code error} is present, and neither when there are problems. When the
 * operation's error condition is true the result is the error and no response is built, since a failing program may
 * leave its output fields unset.
 *
 * @param response the response fields by exposed name; present only if there are no problems and no error
 * @param error    the error the program reported, if the error condition of the operation is true
 * @param problems everything that is wrong with the output, not only the first problem
 */
public record DecodeResult(Optional<Map<String, ApiValue>> response, Optional<ErrorOutcome> error, java.util.List<ValueProblem> problems) {

    /** @throws NullPointerException if an argument is {@code null} */
    public DecodeResult {
        response = Objects.requireNonNull(response, "response").map(m -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(m)));
        Objects.requireNonNull(error, "error");
        problems = java.util.List.copyOf(problems);
    }
}

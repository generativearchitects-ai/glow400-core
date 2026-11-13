package ai.generativearchitects.glow400.pcml.codec;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of decoding what a program returned.
 *
 * @param response the response fields by exposed name; present only if there are no problems
 * @param problems everything that is wrong with the output, not only the first problem
 */
public record DecodeResult(Optional<Map<String, ApiValue>> response, java.util.List<ValueProblem> problems) {

    /** @throws NullPointerException if an argument is {@code null} */
    public DecodeResult {
        response = Objects.requireNonNull(response, "response").map(m -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(m)));
        problems = java.util.List.copyOf(problems);
    }
}

package ai.generativearchitects.glow400.pcml.codec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of encoding a request.
 *
 * @param parameters one value per program parameter, in PCML order; present only if there are no problems
 * @param problems   everything that is wrong with the request, not only the first problem
 */
public record EncodeResult(Optional<List<WireValue>> parameters, List<ValueProblem> problems) {

    /** @throws NullPointerException if an argument is {@code null} */
    public EncodeResult {
        Objects.requireNonNull(parameters, "parameters");
        problems = List.copyOf(problems);
    }
}

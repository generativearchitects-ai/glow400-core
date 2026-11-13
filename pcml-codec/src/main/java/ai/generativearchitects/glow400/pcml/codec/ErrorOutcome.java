package ai.generativearchitects.glow400.pcml.codec;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of an operation whose error condition is true: the program reported a business error.
 *
 * <p>{@code message} is program text meant for the caller. It is the one place where program data appears in a result
 * outside the response, and it never appears in a {@link ValueProblem}.
 *
 * @param status  the HTTP status: the one configured for the code, or the default status of the mapping
 * @param code    the program's error code, absent when its field is blank or not configured
 * @param message the program's error message, absent when its field is blank or not configured
 */
public record ErrorOutcome(int status, Optional<String> code, Optional<String> message) {

    /** @throws NullPointerException if an optional is {@code null} */
    public ErrorOutcome {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}

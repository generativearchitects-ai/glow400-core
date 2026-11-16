package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.Objects;

/**
 * Something wrong with a mock definition, found by {@link MockCodec#check}: a name that is not what the program has
 * ({@code MCK003}), a value the codec cannot carry for its field ({@code MCK004}), a case that can never match
 * ({@code MCK005}), a count field that is not the number of elements given ({@code MCK006}). The message never contains a value, only where it is and what is wrong.
 *
 * @param code     {@code MCK003}, {@code MCK004}, {@code MCK005} or {@code MCK006}
 * @param severity {@link Severity#WARNING} for {@code MCK005} and {@code MCK006}, otherwise {@link Severity#ERROR}
 * @param at       the position in the mock file
 * @param message  what is wrong
 */
public record MockProblem(String code, Severity severity, SourceLocation at, String message) {

    /** @throws NullPointerException if an argument is {@code null} */
    public MockProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(message, "message");
    }

    /** Whether this problem makes the definition unusable. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }
}

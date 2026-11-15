package ai.generativearchitects.glow400.pcml.api;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One case of a mock: when the program receives these values, it returns these values, after this delay, or fails. Names are
 * the RPG names of the PCML. The position is not part of equality.
 *
 * @param name    the name of the case, if it has one
 * @param when    the top-level scalar parameters the program receives and the values they must have, in the order written;
 *                empty if the case matches every call
 * @param returns the parameters the program returns and their values; empty if the case says nothing about them, and always
 *                empty when the case fails
 * @param delayMs the delay of the answer in milliseconds, 0 to 60000, if given
 * @param fails   the failure to simulate, if any
 * @param source  where the case starts in the mock file
 */
public record MockCase(Optional<String> name, Map<String, MockValue> when, Map<String, MockValue> returns, Optional<Integer> delayMs,
                       Optional<MockFailure> fails, SourceLocation source) {

    /** The most milliseconds a case may delay its answer. */
    public static final int MAX_DELAY_MS = 60_000;

    /** @throws NullPointerException if an argument, a name or a value is {@code null} */
    public MockCase {
        Objects.requireNonNull(name, "name");
        when = copy(when);
        returns = copy(returns);
        Objects.requireNonNull(delayMs, "delayMs");
        Objects.requireNonNull(fails, "fails");
        Objects.requireNonNull(source, "source");
    }

    private static Map<String, MockValue> copy(Map<String, MockValue> values) {
        Map<String, MockValue> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MockCase that && name.equals(that.name) && when.equals(that.when) && returns.equals(that.returns)
                && delayMs.equals(that.delayMs) && fails.equals(that.fails);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, when, returns, delayMs, fails);
    }
}

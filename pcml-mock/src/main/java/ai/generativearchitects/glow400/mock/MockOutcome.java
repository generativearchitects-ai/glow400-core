package ai.generativearchitects.glow400.mock;

import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.codec.WireValue;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a mocked program call came to: the values the program returned, or a failure. The delay is data for the caller:
 * the evaluator never sleeps.
 */
public sealed interface MockOutcome {

    /** How long the caller should wait before it uses the outcome. */
    Duration delay();

    /** The name of the case that matched; empty when no case matched, or the case has no name. */
    Optional<String> caseName();

    /**
     * The program returned.
     *
     * @param output   the program's parameters after the call, one per parameter in PCML order, as
     *                 {@code OperationCodec.decode} expects them
     * @param delay    the delay of the answer
     * @param caseName the case that matched
     */
    record Returned(List<WireValue> output, Duration delay, Optional<String> caseName) implements MockOutcome {
        /** @throws NullPointerException if an argument is {@code null} */
        public Returned {
            output = List.copyOf(output);
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(caseName, "caseName");
        }
    }

    /**
     * The call failed.
     *
     * @param kind     how it failed
     * @param delay    the delay before the failure shows
     * @param caseName the case that matched
     */
    record Failed(MockFailure kind, Duration delay, Optional<String> caseName) implements MockOutcome {
        /** @throws NullPointerException if an argument is {@code null} */
        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(caseName, "caseName");
        }
    }
}

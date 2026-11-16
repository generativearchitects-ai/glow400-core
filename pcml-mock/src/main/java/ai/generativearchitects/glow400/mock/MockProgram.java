package ai.generativearchitects.glow400.mock;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.codec.MockCodec;
import ai.generativearchitects.glow400.pcml.codec.MockProblem;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.codec.WireValue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Stands in for the call of one program: it receives the values the program would receive and returns the values it would
 * return. Everything above the call, the codec, the error mapping and HTTP, stays real, so a mock is used between
 * {@code OperationCodec.encode} and {@code OperationCodec.decode}.
 *
 * <p>The cases of the definition are tried in file order and the first that matches wins. With no case, or none that
 * matches, there is no delay and no failure, an {@code input} or {@code inputoutput} parameter comes back as received and an
 * {@code output} parameter comes back empty: every operation can be mocked with no file at all.
 *
 * <p>The program never sleeps and never throws for the data it is given.
 */
public final class MockProgram {

    private final MockCodec codec;
    private final List<MockCase> cases;

    private MockProgram(MockCodec codec, List<MockCase> cases) {
        this.codec = codec;
        this.cases = cases;
    }

    /** The mock of an operation that has no mock file. */
    public static MockProgram of(ApiOperation operation) {
        return of(operation, MockDefinition.empty(Objects.requireNonNull(operation, "operation").id()));
    }

    /**
     * The mock of an operation with its definition, which may have no case.
     *
     * @throws IllegalArgumentException if the definition is not the operation's, or if validation would refuse it (an error
     *                                  {@code MCK003} or {@code MCK004}); the message names the problems and never a value
     */
    public static MockProgram of(ApiOperation operation, MockDefinition definition) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(definition, "definition");
        if (!definition.operationId().equals(operation.id())) {
            throw new IllegalArgumentException("The definition is the mock of the operation " + definition.operationId() + ", not of " + operation.id());
        }
        if (!OperationCodec.check(operation).isEmpty()) {
            throw new IllegalArgumentException("The operation " + operation.id() + " cannot be encoded, so it cannot be mocked");
        }
        MockCodec codec = MockCodec.of(operation);
        List<MockProblem> errors = codec.check(definition).stream().filter(MockProblem::isError).toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("The mock of the operation " + operation.id() + " is not valid: "
                    + errors.stream().map(p -> p.code() + " " + p.at() + ": " + p.message()).collect(Collectors.joining("; ")));
        }
        return new MockProgram(codec, definition.cases());
    }

    /**
     * Calls the mocked program.
     *
     * @param input what {@code OperationCodec.encode} produced: one value per parameter, in PCML order
     * @return what the call came to
     */
    public MockOutcome call(List<WireValue> input) {
        Objects.requireNonNull(input, "input");
        for (MockCase mockCase : cases) {
            if (codec.matches(mockCase, input)) {
                Duration delay = Duration.ofMillis(mockCase.delayMs().orElse(0));
                if (mockCase.fails().isPresent()) {
                    return new MockOutcome.Failed(mockCase.fails().get(), delay, mockCase.name());
                }
                return new MockOutcome.Returned(codec.returned(mockCase.returns(), input), delay, mockCase.name());
            }
        }
        return new MockOutcome.Returned(codec.returned(Map.of(), input), Duration.ZERO, Optional.empty());
    }
}

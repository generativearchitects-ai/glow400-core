package ai.generativearchitects.slay400.pcml.api;

import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.util.List;
import java.util.Objects;

/**
 * The mock of one operation: its cases in the order written, tried in that order, the first that matches winning. An
 * operation without a mock file has no definition, and behaves as a definition without cases: no delay, no failure, nothing
 * returned but what the program echoes. The position is not part of equality.
 *
 * @param operationId the id of the operation
 * @param cases       the cases in file order; may be empty
 * @param source      the mock file, relative to the repository root with {@code /} separators, at line 1 and column 1
 */
public record MockDefinition(String operationId, List<MockCase> cases, SourceLocation source) {

    /** The most cases of one mock file. */
    public static final int MAX_CASES = 1000;

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code operationId} is blank
     */
    public MockDefinition {
        Objects.requireNonNull(operationId, "operationId");
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        cases = List.copyOf(cases);
        Objects.requireNonNull(source, "source");
    }

    /** A definition with no case, for an operation that has no mock file. */
    public static MockDefinition empty(String operationId) {
        return new MockDefinition(operationId, List.of(), new SourceLocation(".", 0, 0));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MockDefinition that && operationId.equals(that.operationId) && cases.equals(that.cases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationId, cases);
    }
}

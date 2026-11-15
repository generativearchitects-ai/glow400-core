package ai.generativearchitects.glow400.mock;

import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import java.util.List;
import java.util.Objects;

/**
 * What {@code mock/<api>.json} holds: the mock definitions of the operations of one API that have a mock file, in operation
 * order.
 *
 * @param api        the name of the API
 * @param operations the definitions; at most one per operation id
 */
public record MockFile(String api, List<MockDefinition> operations) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code api} is blank
     */
    public MockFile {
        Objects.requireNonNull(api, "api");
        if (api.isBlank()) {
            throw new IllegalArgumentException("api must not be blank");
        }
        operations = List.copyOf(operations);
    }
}

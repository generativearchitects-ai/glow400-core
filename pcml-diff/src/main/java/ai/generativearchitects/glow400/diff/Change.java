package ai.generativearchitects.glow400.diff;

import java.util.Objects;

/**
 * One change between two versions of an API. The description names the operation and the exposed field names, and never a
 * value.
 *
 * @param impact      what the change means for consumers
 * @param operationId the operation, or empty for a change of the API itself
 * @param path        where in the operation: {@code request.name}, {@code response.rows.price}, {@code route}, and so on; empty for the operation
 * @param description what changed
 */
public record Change(Impact impact, String operationId, String path, String description) {

    /** @throws NullPointerException if an argument is {@code null} */
    public Change {
        Objects.requireNonNull(impact, "impact");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(description, "description");
    }
}

package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.model.FieldDef;
import java.util.Objects;

/**
 * A top-level parameter whose PCML usage is {@code inputoutput} (or {@code inherit}) and for which the overlay, merged
 * with the shared settings, gives no direction: the parameter that {@code OVL001} is about.
 *
 * @param operationId the id of the operation, as the loader derives it
 * @param field       the parameter in the PCML
 */
public record UndirectedParameter(String operationId, FieldDef field) {

    /** @throws NullPointerException if an argument is {@code null} */
    public UndirectedParameter {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(field, "field");
    }
}

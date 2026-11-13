package ai.generativearchitects.glow400.contract;

import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.Objects;

/**
 * Where a field comes from in the program. It is not part of the contract: a consumer cannot see it, and the comparison
 * of releases ignores it. Documents use it for traceability.
 *
 * @param fieldPath the RPG field, with its parents, such as {@code P0ROWS.PRICE.AMT}
 * @param wire      the wire type of the field
 */
public record Origin(String fieldPath, WireType wire) {

    /** @throws NullPointerException if an argument is {@code null} */
    public Origin {
        Objects.requireNonNull(fieldPath, "fieldPath");
        Objects.requireNonNull(wire, "wire");
    }
}

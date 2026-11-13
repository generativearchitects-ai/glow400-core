package ai.generativearchitects.glow400.contract;

import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A field as a consumer sees it, on the request side or the response side.
 *
 * <p>A field is a scalar, an object (a struct) or an array of either; {@code object} and {@code array} tell which. For an
 * array, {@code type}, {@code limits} and {@code children} describe its elements, and {@code limits.maxItems} its size.
 *
 * @param exposedName the name in the API
 * @param location    where a request field travels; empty on the response side
 * @param object      whether the field, or the elements of an array, are objects
 * @param array       whether the field is an array
 * @param type        the semantic type of a scalar or of the elements of an array of scalars; empty for objects
 * @param required    whether the field must be given
 * @param limits      the limits of the values
 * @param children    the members of an object, not hidden, in PCML order; empty otherwise
 * @param description the description, which is documentation
 * @param example     the example, which is documentation
 * @param origin      where the field comes from; not part of the contract
 */
public record FieldSurface(String exposedName, Optional<Location> location, boolean object, boolean array, Optional<SemanticType> type,
                           boolean required, Limits limits, List<FieldSurface> children, Optional<String> description,
                           Optional<String> example, Origin origin) {

    /** @throws NullPointerException if an argument is {@code null} */
    public FieldSurface {
        Objects.requireNonNull(exposedName, "exposedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(limits, "limits");
        children = List.copyOf(children);
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(example, "example");
        Objects.requireNonNull(origin, "origin");
    }
}

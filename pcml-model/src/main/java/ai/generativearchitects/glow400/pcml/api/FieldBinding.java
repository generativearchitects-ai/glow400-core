package ai.generativearchitects.glow400.pcml.api;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the API does with one program parameter or one struct member: the PCML field, merged with its overlay
 * settings and the defaults.
 *
 * @param fieldPath    path in the PCML: {@code P0ORDN}, {@code P0ROWS}, {@code P0ROWS.QTY}
 * @param direction    how the field takes part in the API
 * @param injection    for {@link Direction#INJECTED}: the literal or {@code ${...}} expression, verbatim
 * @param exposedName  name in the API
 * @param location     where a request field travels; empty for fields that are not part of the request
 * @param semanticType the API type; empty for struct fields, which have {@code children}
 * @param codec        wire encoding settings, defaults resolved
 * @param required     whether the caller must supply the field
 * @param description  documentation text, if any
 * @param example      example value, as written in the overlay, if any
 * @param hidden       {@code true} for a struct member that is not exposed
 * @param trim         how an output array is shortened, if at all
 * @param children     the members of a struct field, in PCML order; empty for scalars
 * @param source       the overlay position, or the PCML position when the binding is derived entirely from the PCML
 */
public record FieldBinding(
        String fieldPath,
        Direction direction,
        Optional<String> injection,
        String exposedName,
        Optional<Location> location,
        Optional<SemanticType> semanticType,
        CodecSettings codec,
        boolean required,
        Optional<String> description,
        Optional<String> example,
        boolean hidden,
        Optional<Trim> trim,
        List<FieldBinding> children,
        SourceLocation source) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code fieldPath} or {@code exposedName} is blank
     */
    public FieldBinding {
        Objects.requireNonNull(fieldPath, "fieldPath");
        if (fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath must not be blank");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(injection, "injection");
        Objects.requireNonNull(exposedName, "exposedName");
        if (exposedName.isBlank()) {
            throw new IllegalArgumentException("exposedName must not be blank");
        }
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(semanticType, "semanticType");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(example, "example");
        Objects.requireNonNull(trim, "trim");
        children = List.copyOf(children);
        Objects.requireNonNull(source, "source");
    }
}

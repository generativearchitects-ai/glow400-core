package ai.generativearchitects.glow400.pcml.model;

import java.util.Map;
import java.util.Objects;

/**
 * A PCML {@code data} element, or a nested {@code struct} element, seen as a field of a program
 * parameter list or of a struct.
 *
 * @param name       the {@code name} attribute; never blank
 * @param wire       the {@code type} and its layout attributes
 * @param usage      the {@code usage} attribute; {@link Usage#INHERIT} when absent
 * @param count      the {@code count} attribute
 * @param attributes every attribute not modelled by another component, verbatim and in document order
 * @param source     where the element starts
 */
public record FieldDef(
        String name,
        WireType wire,
        Usage usage,
        Count count,
        Map<String, String> attributes,
        SourceLocation source) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public FieldDef {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(wire, "wire");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(count, "count");
        attributes = Immutables.orderedCopy(attributes, "attributes");
        Objects.requireNonNull(source, "source");
    }
}

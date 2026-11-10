package ai.generativearchitects.glow400.pcml.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A PCML {@code struct} element: a named group of fields.
 *
 * @param name       the {@code name} attribute; for a {@code struct} nested in a program or in another
 *                   struct, the qualified name {@code parent.name}
 * @param fields     the fields in declaration order
 * @param attributes every attribute not modelled by another component, verbatim and in document order
 * @param source     where the element starts
 */
public record StructDef(
        String name,
        List<FieldDef> fields,
        Map<String, String> attributes,
        SourceLocation source) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public StructDef {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        fields = List.copyOf(fields);
        attributes = Immutables.orderedCopy(attributes, "attributes");
        Objects.requireNonNull(source, "source");
    }
}

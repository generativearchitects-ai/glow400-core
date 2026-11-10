package ai.generativearchitects.glow400.pcml.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A PCML {@code program} element: one callable program or service program procedure.
 *
 * @param name         the {@code name} attribute; never blank
 * @param path         the {@code path} attribute, verbatim (it may contain placeholders)
 * @param entryPoint   the {@code entrypoint} attribute: the service program procedure, if any
 * @param returnValue  the {@code returnvalue} attribute, if any
 * @param parameters   the parameters in declaration order; the order is significant
 * @param attributes   every attribute not modelled by another component, verbatim and in document order
 * @param source       where the element starts
 */
public record ProgramDef(
        String name,
        Optional<String> path,
        Optional<String> entryPoint,
        Optional<String> returnValue,
        List<FieldDef> parameters,
        Map<String, String> attributes,
        SourceLocation source) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public ProgramDef {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(entryPoint, "entryPoint");
        Objects.requireNonNull(returnValue, "returnValue");
        parameters = List.copyOf(parameters);
        attributes = Immutables.orderedCopy(attributes, "attributes");
        Objects.requireNonNull(source, "source");
    }
}

package ai.generativearchitects.glow400.pcml.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The call model of one PCML file: its structs and its programs.
 *
 * <p>The model is lossless with respect to attributes: every attribute of the source that is not
 * modelled by a dedicated component is kept in an {@code attributes} map.
 *
 * @param version    the {@code version} attribute of the {@code pcml} element; empty if it was missing
 * @param structs    structs by name, in document order. A {@code struct} nested in a program or in another
 *                   struct is registered under the qualified name {@code parent.name}, and the field that
 *                   stands for it has {@link WireType.Struct} of that name
 * @param programs   programs in document order; zero or more per file
 * @param attributes every other attribute of the {@code pcml} element, verbatim and in document order
 */
public record PcmlDocument(
        String version,
        Map<String, StructDef> structs,
        List<ProgramDef> programs,
        Map<String, String> attributes) {

    /** @throws NullPointerException if an argument is {@code null} */
    public PcmlDocument {
        Objects.requireNonNull(version, "version");
        structs = Immutables.orderedCopy(structs, "structs");
        programs = List.copyOf(programs);
        attributes = Immutables.orderedCopy(attributes, "attributes");
    }
}

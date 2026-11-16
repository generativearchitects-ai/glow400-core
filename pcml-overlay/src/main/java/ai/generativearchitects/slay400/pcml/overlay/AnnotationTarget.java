package ai.generativearchitects.slay400.pcml.overlay;

import ai.generativearchitects.slay400.pcml.api.GatewayDefaults;
import ai.generativearchitects.slay400.pcml.model.PcmlDocument;
import java.util.List;
import java.util.Objects;

/**
 * One overlay file of a repository, with what a tool needs to complete it: the PCML it describes and the parameters
 * that have no direction yet. See {@link OverlayLoader#loadRepositoryForAnnotation}.
 *
 * @param apiName     the API name (the folder name, lower-cased)
 * @param overlayPath the overlay file, relative to the repository root, with {@code /} separators
 * @param pcmlPath    the PCML file, relative to the repository root, with {@code /} separators
 * @param pcml        the parsed PCML
 * @param defaults    the repository defaults
 * @param undirected  the parameters without a direction, in operation order and then PCML order
 */
public record AnnotationTarget(String apiName, String overlayPath, String pcmlPath, PcmlDocument pcml,
                               GatewayDefaults defaults, List<UndirectedParameter> undirected) {

    /** @throws NullPointerException if an argument is {@code null} */
    public AnnotationTarget {
        Objects.requireNonNull(apiName, "apiName");
        Objects.requireNonNull(overlayPath, "overlayPath");
        Objects.requireNonNull(pcmlPath, "pcmlPath");
        Objects.requireNonNull(pcml, "pcml");
        Objects.requireNonNull(defaults, "defaults");
        undirected = List.copyOf(undirected);
    }
}

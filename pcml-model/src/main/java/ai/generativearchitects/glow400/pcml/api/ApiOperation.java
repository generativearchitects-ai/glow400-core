package ai.generativearchitects.glow400.pcml.api;

import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One API operation: a PCML program, its route and method, and how each parameter is exposed.
 *
 * @param id           unique identifier within the repository
 * @param version      semantic version of the operation
 * @param summary      one-line description, if any
 * @param description  longer description, if any
 * @param tags         grouping tags
 * @param program      the program from the call model, unchanged
 * @param pcmlPath     the PCML file, relative to the repository root, with {@code /} separators
 * @param overlayPath  the overlay file, relative to the repository root, with {@code /} separators
 * @param route        route template, for example {@code /orders/{orderNumber}/status}
 * @param method       HTTP method
 * @param callStrategy how the runtime calls the program
 * @param bindings     one binding per program parameter, in PCML order
 * @param errorMapping the error convention, if declared
 * @param cache        cache settings, if declared
 * @param source       where the operation is defined in the overlay file
 */
public record ApiOperation(
        String id,
        String version,
        Optional<String> summary,
        Optional<String> description,
        List<String> tags,
        ProgramDef program,
        String pcmlPath,
        String overlayPath,
        String route,
        HttpMethod method,
        CallStrategy callStrategy,
        List<FieldBinding> bindings,
        Optional<ErrorMapping> errorMapping,
        Optional<CacheSettings> cache,
        SourceLocation source) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code id}, {@code version} or {@code route} is blank
     */
    public ApiOperation {
        requireNotBlank(id, "id");
        requireNotBlank(version, "version");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(description, "description");
        tags = List.copyOf(tags);
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(pcmlPath, "pcmlPath");
        Objects.requireNonNull(overlayPath, "overlayPath");
        requireNotBlank(route, "route");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(callStrategy, "callStrategy");
        bindings = List.copyOf(bindings);
        Objects.requireNonNull(errorMapping, "errorMapping");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(source, "source");
    }

    private static void requireNotBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.CacheSettings;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The settings of one operation, or the shared settings of an overlay file, as authored.
 *
 * @param id          {@code id}
 * @param program     {@code program}
 * @param route       {@code route}
 * @param method      {@code method}
 * @param summary     {@code summary}
 * @param description {@code description}
 * @param tags        {@code tags}
 * @param inject      {@code inject}: parameter name to literal or expression; the position is that of the key
 * @param parameters  {@code parameters}: settings by parameter name, in file order
 * @param error       {@code error}
 * @param cache       {@code cache}
 * @param source      position of the operation (the file root, or the list entry)
 */
public record OperationSettings(
        Optional<Located<String>> id,
        Optional<Located<String>> program,
        Optional<Located<String>> route,
        Optional<Located<HttpMethod>> method,
        Optional<Located<String>> summary,
        Optional<Located<String>> description,
        Optional<List<String>> tags,
        Map<String, Located<String>> inject,
        Map<String, FieldSettings> parameters,
        Optional<ErrorSettings> error,
        Optional<Located<CacheSettings>> cache,
        SourceLocation source) {

    /** @throws NullPointerException if an argument is {@code null} */
    public OperationSettings {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(description, "description");
        tags = tags.map(List::copyOf);
        inject = Collections.unmodifiableMap(new LinkedHashMap<>(inject));
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(source, "source");
    }
}

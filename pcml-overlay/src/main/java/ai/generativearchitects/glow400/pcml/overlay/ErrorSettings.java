package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code error} block of an overlay, as authored.
 *
 * @param when         {@code when}; may be absent in an operation that inherits it from the shared block
 * @param code         {@code code}: the field that carries the error code
 * @param message      {@code message}: the field that carries the error message
 * @param httpStatus   {@code httpStatus}
 * @param statusByCode {@code statusByCode}
 * @param source       position of the {@code error} key
 */
public record ErrorSettings(
        Optional<Located<ErrorCondition>> when,
        Optional<Located<String>> code,
        Optional<Located<String>> message,
        Optional<Located<Integer>> httpStatus,
        Map<String, Located<Integer>> statusByCode,
        SourceLocation source) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ErrorSettings {
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(httpStatus, "httpStatus");
        statusByCode = Collections.unmodifiableMap(new LinkedHashMap<>(statusByCode));
        Objects.requireNonNull(source, "source");
    }
}

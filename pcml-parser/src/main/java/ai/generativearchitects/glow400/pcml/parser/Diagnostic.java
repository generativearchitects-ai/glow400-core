package ai.generativearchitects.glow400.pcml.parser;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.Objects;

/**
 * A finding about a PCML source: a stable code, a severity, a message and a position.
 *
 * <p>Codes {@code PCML001} to {@code PCML007} are defined by the parser; see {@link PcmlParser}.
 *
 * @param code     stable identifier, for example {@code PCML004}; never blank
 * @param severity how serious the finding is
 * @param message  human-readable explanation
 * @param location where in the source the finding applies
 */
public record Diagnostic(String code, Severity severity, String message, SourceLocation location) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public Diagnostic {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(location, "location");
    }

    @Override
    public String toString() {
        return location + ": " + severity.name().toLowerCase(java.util.Locale.ROOT) + " " + code + ": " + message;
    }
}

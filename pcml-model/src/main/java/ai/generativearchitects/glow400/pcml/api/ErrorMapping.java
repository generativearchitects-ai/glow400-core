package ai.generativearchitects.glow400.pcml.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The error convention of an operation: the {@code error} block of the overlay.
 *
 * @param when         condition that signals a business error
 * @param codeField    path of the field that carries the error code, if any
 * @param messageField path of the field that carries the error message, if any
 * @param httpStatus   status for a business error, 400 to 599; 422 when the overlay gives none
 * @param statusByCode status per error code; takes precedence over {@code httpStatus}
 */
public record ErrorMapping(
        ErrorCondition when,
        Optional<String> codeField,
        Optional<String> messageField,
        int httpStatus,
        Map<String, Integer> statusByCode) {

    /** Status used for a business error when the overlay does not set one. */
    public static final int DEFAULT_HTTP_STATUS = 422;

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if a status is outside 400 to 599
     */
    public ErrorMapping {
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(codeField, "codeField");
        Objects.requireNonNull(messageField, "messageField");
        requireHttpError(httpStatus);
        Map<String, Integer> copy = new LinkedHashMap<>();
        statusByCode.forEach((code, status) -> {
            Objects.requireNonNull(code, "code");
            requireHttpError(Objects.requireNonNull(status, "status"));
            copy.put(code, status);
        });
        statusByCode = Collections.unmodifiableMap(copy);
    }

    private static void requireHttpError(int status) {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be between 400 and 599: " + status);
        }
    }
}

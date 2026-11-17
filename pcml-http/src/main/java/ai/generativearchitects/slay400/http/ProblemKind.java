package ai.generativearchitects.slay400.http;

import java.util.Optional;

/**
 * The situations of section 5 of the gateway design, with the status, the {@code type} and the {@code title} that every front
 * end uses for them, so that they are worded the same way everywhere.
 */
public enum ProblemKind {

    /** The request fails validation. */
    REQUEST_INVALID(400, "request-invalid", "The request is not valid"),
    /** Authentication is missing or invalid (an Enterprise extension point). */
    UNAUTHORIZED(401, "authentication-required", "Authentication is required"),
    /** The caller is not authorised (an Enterprise extension point). */
    FORBIDDEN(403, "forbidden", "Forbidden"),
    /** An unknown tenant, environment or route. */
    NOT_FOUND(404, "not-found", "Not found"),
    /** The route exists, but not for this method. */
    METHOD_NOT_ALLOWED(405, "method-not-allowed", "Method not allowed"),
    /** The program reported an error through the declared convention; the status is the one the error mapping gives. */
    PROGRAM_ERROR(422, "program-error", "The program reported an error"),
    /** Something failed inside the gateway. */
    INTERNAL_ERROR(500, "internal-error", "Internal server error"),
    /** The program call failed, or IBM i returned an escape message. */
    PROGRAM_CALL_FAILED(502, "program-call-failed", "The program call failed"),
    /** The connection pool is exhausted or IBM i is not reachable. */
    UNAVAILABLE(503, "service-unavailable", "Service unavailable"),
    /** The program call timed out. */
    TIMEOUT(504, "program-timeout", "The program did not answer in time");

    private final int status;
    private final String slug;
    private final String title;

    ProblemKind(int status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The {@code type} URI of RFC 9457: the base of {@link ProblemDetails#TYPE_BASE} and a fixed name. */
    public String type() {
        return ProblemDetails.TYPE_BASE + slug;
    }

    /** The {@code title}: a short summary of the kind of problem, the same for every occurrence. */
    public String title() {
        return title;
    }

    /** The kind that has this status, if the table has one. */
    public static Optional<ProblemKind> forStatus(int status) {
        for (ProblemKind kind : values()) {
            if (kind.status == status) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}

package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.emit.descriptor.Json;
import ai.generativearchitects.slay400.pcml.codec.ErrorOutcome;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A problem-details body (RFC 9457), as every front end writes it: {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance}, and the extensions {@code code} (the program's error code), {@code errors} (one entry for each problem of
 * a request: the exposed path of the field, the code and the message), {@code tenant}, {@code environment} and
 * {@code correlationId}. The {@code ProblemDetails} schema of the OpenAPI document describes exactly this body.
 *
 * <p>No value sent by the caller and no value returned by the program is echoed, apart from the error code and message that
 * the error mapping names. A problem details object is immutable.
 */
public final class ProblemDetails {

    /** The media type of the body. */
    public static final String CONTENT_TYPE = "application/problem+json";

    /** The start of every {@code type} that the table of {@link ProblemKind} gives; the product name is only here and in {@link #INSTANCE_PREFIX}. */
    public static final String TYPE_BASE = "https://slay400.dev/problems/";

    /** The start of the {@code instance} of a problem that has a correlation id. */
    public static final String INSTANCE_PREFIX = "urn:slay400:request:";

    private final int status;
    private final String type;
    private final String title;
    private final Optional<String> detail;
    private final Optional<String> code;
    private final List<ValueProblem> errors;
    private final String tenant;
    private final String environment;
    private final String correlationId;

    private ProblemDetails(int status, String type, String title, Optional<String> detail, Optional<String> code, List<ValueProblem> errors,
                           String tenant, String environment, String correlationId) {
        this.status = status;
        this.type = type;
        this.title = title;
        this.detail = detail;
        this.code = code;
        this.errors = errors;
        this.tenant = tenant;
        this.environment = environment;
        this.correlationId = correlationId;
    }

    /**
     * The problem of a request that cannot be read or encoded: status 400, with an entry in {@code errors} for each problem.
     *
     * @param problems what {@code HttpBinding.readRequest} or {@code OperationCodec.encode} found
     * @throws NullPointerException if {@code problems} is {@code null}
     */
    public static ProblemDetails forRequestProblems(List<ValueProblem> problems) {
        List<ValueProblem> errors = List.copyOf(problems);
        String detail = errors.isEmpty() ? "The request is not valid."
                : "The request has " + errors.size() + (errors.size() == 1 ? " problem" : " problems") + "; see errors.";
        return new ProblemDetails(400, ProblemKind.REQUEST_INVALID.type(), ProblemKind.REQUEST_INVALID.title(), Optional.of(detail),
                Optional.empty(), errors, "", "", "");
    }

    /**
     * The problem of a program that reported an error through the declared convention: the status the error mapping gives, the
     * program's error code as {@code code} and its message as {@code detail}.
     *
     * @throws NullPointerException if {@code outcome} is {@code null}
     */
    public static ProblemDetails forBusinessError(ErrorOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        String detail = outcome.message().filter(text -> !text.isBlank()).orElse(ProblemKind.PROGRAM_ERROR.title() + ".");
        return new ProblemDetails(outcome.status(), ProblemKind.PROGRAM_ERROR.type(), ProblemKind.PROGRAM_ERROR.title(), Optional.of(detail),
                outcome.code().filter(text -> !text.isBlank()), List.of(), "", "", "");
    }

    /**
     * Any other problem. The {@code type} is the one of the table for the status, or {@code about:blank} for a status that the
     * table does not have.
     *
     * @param status the HTTP status, 400 to 599
     * @param title  a short summary of the kind of problem
     * @param detail an explanation of this occurrence; left out if blank
     * @throws NullPointerException     if {@code title} or {@code detail} is {@code null}
     * @throws IllegalArgumentException if {@code status} is outside 400 to 599
     */
    public static ProblemDetails of(int status, String title, String detail) {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be between 400 and 599: " + status);
        }
        return new ProblemDetails(status, ProblemKind.forStatus(status).map(ProblemKind::type).orElse("about:blank"),
                Objects.requireNonNull(title, "title"), Optional.of(Objects.requireNonNull(detail, "detail")).filter(text -> !text.isBlank()),
                Optional.empty(), List.of(), "", "", "");
    }

    /**
     * A problem of the table, with its status, type and title.
     *
     * @param kind   the situation
     * @param detail an explanation of this occurrence; left out if blank
     * @throws NullPointerException if an argument is {@code null}
     */
    public static ProblemDetails of(ProblemKind kind, String detail) {
        Objects.requireNonNull(kind, "kind");
        return new ProblemDetails(kind.status(), kind.type(), kind.title(), Optional.of(Objects.requireNonNull(detail, "detail")).filter(text -> !text.isBlank()),
                Optional.empty(), List.of(), "", "", "");
    }

    /**
     * The same problem for a tenant and an environment, with the id that identifies the request. An empty text leaves its
     * member out. A correlation id also gives the {@code instance}: {@link #INSTANCE_PREFIX} and the id, percent-encoded.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public ProblemDetails withContext(String tenant, String environment, String correlationId) {
        return new ProblemDetails(status, type, title, detail, code, errors, Objects.requireNonNull(tenant, "tenant"),
                Objects.requireNonNull(environment, "environment"), Objects.requireNonNull(correlationId, "correlationId"));
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The {@code type}, a URI. */
    public String type() {
        return type;
    }

    /** The {@code title}. */
    public String title() {
        return title;
    }

    /** The {@code detail}. */
    public Optional<String> detail() {
        return detail;
    }

    /** The {@code instance}: derived from the correlation id; empty without one. */
    public Optional<String> instance() {
        return correlationId.isEmpty() ? Optional.empty() : Optional.of(INSTANCE_PREFIX + percentEncoded(correlationId));
    }

    /** The program's error code. */
    public Optional<String> code() {
        return code;
    }

    /** The problems of a request, in order. */
    public List<ValueProblem> errors() {
        return errors;
    }

    /** The tenant; empty if not given. */
    public Optional<String> tenant() {
        return tenant.isEmpty() ? Optional.empty() : Optional.of(tenant);
    }

    /** The environment; empty if not given. */
    public Optional<String> environment() {
        return environment.isEmpty() ? Optional.empty() : Optional.of(environment);
    }

    /** The correlation id; empty if not given. */
    public Optional<String> correlationId() {
        return correlationId.isEmpty() ? Optional.empty() : Optional.of(correlationId);
    }

    /** The body: deterministic UTF-8 JSON without white space, members in the order of the schema, a member without a value left out. */
    public byte[] toJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status);
        detail.ifPresent(text -> body.put("detail", text));
        instance().ifPresent(text -> body.put("instance", text));
        code.ifPresent(text -> body.put("code", text));
        if (!errors.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (ValueProblem problem : errors) {
                Map<String, Object> entry = new LinkedHashMap<>();
                if (!problem.exposedPath().isEmpty()) {
                    entry.put("field", problem.exposedPath());
                }
                entry.put("code", problem.code());
                entry.put("message", problem.message());
                list.add(entry);
            }
            body.put("errors", list);
        }
        tenant().ifPresent(text -> body.put("tenant", text));
        environment().ifPresent(text -> body.put("environment", text));
        correlationId().ifPresent(text -> body.put("correlationId", text));
        return Json.writeCompact(body).getBytes(StandardCharsets.UTF_8);
    }

    /** Only the status, the type and the title: a problem can carry text that must not end up in a log. */
    @Override
    public String toString() {
        return "ProblemDetails[" + status + " " + type + " " + title + "]";
    }

    private static String percentEncoded(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return out.toString();
    }
}

package ai.generativearchitects.glow400.contract;

import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An operation as a consumer sees it.
 *
 * @param id                 the operation id
 * @param route              the route, with its placeholders
 * @param method             the HTTP method
 * @param summary            the summary, which is documentation
 * @param description        the description, which is documentation
 * @param tags               the tags, which are documentation
 * @param request            the request fields, in PCML order
 * @param response           the response fields, in PCML order; empty when the operation returns no content
 * @param defaultErrorStatus the status of an error the program reports without a code of its own; empty without an error mapping
 * @param errorStatuses      every status the error mapping can produce, sorted; empty without an error mapping
 */
public record OperationSurface(String id, String route, HttpMethod method, Optional<String> summary, Optional<String> description,
                               List<String> tags, List<FieldSurface> request, List<FieldSurface> response,
                               Optional<Integer> defaultErrorStatus, List<Integer> errorStatuses) {

    /** @throws NullPointerException if an argument is {@code null} */
    public OperationSurface {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(description, "description");
        tags = List.copyOf(tags);
        request = List.copyOf(request);
        response = List.copyOf(response);
        Objects.requireNonNull(defaultErrorStatus, "defaultErrorStatus");
        errorStatuses = List.copyOf(errorStatuses);
    }

    /** Whether the operation returns content: it has at least one response field. */
    public boolean hasContent() {
        return !response.isEmpty();
    }
}

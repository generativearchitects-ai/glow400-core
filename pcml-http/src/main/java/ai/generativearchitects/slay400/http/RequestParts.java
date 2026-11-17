package ai.generativearchitects.slay400.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A request as the HTTP binding reads it, without any web framework: plain values that an adapter takes from whatever server
 * it runs on.
 *
 * <p>Everything is already decoded: path parameter values come from {@link RouteMatch.Found}, query values are what remains
 * after the query string was split and percent-decoded, and the body is the bytes as they came. A query parameter or a header
 * that was given more than once has several values. Header names are compared without regard to case; query names are not.
 *
 * @param pathParameters the values of the placeholders of the route, by placeholder name
 * @param query          the query parameters by name, each with its values in the order given
 * @param headers        the headers by name, each with its values in the order given, except the content type
 * @param contentType    the value of the {@code Content-Type} header, if there is one
 * @param body           the body; empty if the request has none
 */
public record RequestParts(Map<String, String> pathParameters, Map<String, List<String>> query, Map<String, List<String>> headers,
                           Optional<String> contentType, byte[] body) {

    /**
     * @throws NullPointerException if an argument, a name, a value or a list is {@code null}
     */
    public RequestParts {
        Map<String, String> path = new LinkedHashMap<>();
        pathParameters.forEach((name, value) -> path.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
        pathParameters = Collections.unmodifiableMap(path);
        query = copy(query, new LinkedHashMap<>());
        headers = copy(headers, new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        Objects.requireNonNull(contentType, "contentType");
        body = Objects.requireNonNull(body, "body").clone();
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> source, Map<String, List<String>> into) {
        source.forEach((name, values) -> {
            List<String> list = new ArrayList<>();
            Objects.requireNonNull(values, "values").forEach(value -> list.add(Objects.requireNonNull(value, "value")));
            into.put(Objects.requireNonNull(name, "name"), Collections.unmodifiableList(list));
        });
        return Collections.unmodifiableMap(into);
    }

    /** A copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** The body itself, for the reader that does not change it. */
    byte[] bodyBytes() {
        return body;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RequestParts that && pathParameters.equals(that.pathParameters) && query.equals(that.query)
                && headers.equals(that.headers) && contentType.equals(that.contentType) && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        // header names are equal without regard to case, so they are hashed in lower case
        Map<String, List<String>> lowerCased = new java.util.HashMap<>();
        headers.forEach((name, values) -> lowerCased.put(name.toLowerCase(java.util.Locale.ROOT), values));
        return Objects.hash(pathParameters, query, lowerCased, contentType, Arrays.hashCode(body));
    }

    /** Only the sizes: a request carries values that must not end up in a log. */
    @Override
    public String toString() {
        return "RequestParts[" + pathParameters.size() + " path parameters, " + query.size() + " query parameters, " + headers.size()
                + " headers, " + body.length + " body bytes]";
    }
}

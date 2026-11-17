package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** What a method and a path come to, in {@link RouteTable#match}: an operation, a path that has other methods, or nothing. */
public sealed interface RouteMatch {

    /**
     * The operation that the request is for.
     *
     * @param api            the API that has the operation
     * @param operation      the operation
     * @param pathParameters the values of the placeholders of the route, by placeholder name, in route order and percent-decoded
     */
    record Found(ApiDefinition api, ApiOperation operation, Map<String, String> pathParameters) implements RouteMatch {

        /** @throws NullPointerException if an argument or a value is {@code null} */
        public Found {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(operation, "operation");
            Map<String, String> copy = new LinkedHashMap<>();
            pathParameters.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
            pathParameters = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * The path is a route of the API, but not for this method.
     *
     * @param allowed the methods that the route has; not empty
     */
    record MethodNotAllowed(Set<HttpMethod> allowed) implements RouteMatch {

        /**
         * @throws NullPointerException     if {@code allowed} is {@code null}
         * @throws IllegalArgumentException if {@code allowed} is empty
         */
        public MethodNotAllowed {
            Objects.requireNonNull(allowed, "allowed");
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("allowed must not be empty");
            }
            allowed = Collections.unmodifiableSet(EnumSet.copyOf(allowed));
        }
    }

    /** No route of the repository has this path. */
    record NotFound() implements RouteMatch {
    }
}

package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of reading a request: the values of the exposed request fields, by exposed name, in the order of the program's
 * parameters, ready for {@code OperationCodec.encode}; or everything that is wrong with the request, not only the first
 * problem. A field that is optional and was not given is not in the map.
 *
 * @param values   the values; present only if there are no problems
 * @param problems the problems, in the order of the request: the body first, then the fields in the order of the program
 */
public record RequestResult(Optional<Map<String, ApiValue>> values, List<ValueProblem> problems) {

    /** @throws NullPointerException if an argument, a value or a problem is {@code null} */
    public RequestResult {
        values = Objects.requireNonNull(values, "values").map(map -> {
            Map<String, ApiValue> copy = new LinkedHashMap<>();
            map.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
            return Collections.unmodifiableMap(copy);
        });
        problems = List.copyOf(problems);
    }
}

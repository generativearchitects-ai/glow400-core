package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.contract.FieldSurface;
import ai.generativearchitects.slay400.contract.OperationSurface;
import ai.generativearchitects.slay400.emit.descriptor.Json;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The HTTP binding of one operation, without a web framework: it turns the parts of a request into the values that
 * {@code OperationCodec.encode} takes, and what {@code OperationCodec.decode} returns into the JSON of the response. What it
 * accepts and what it writes is what the OpenAPI document of the operation declares, and the tests hold the two together.
 *
 * <p>A binding is immutable and can be shared by threads. No method throws for data from a request.
 */
public final class HttpBinding {

    /** The media type of a success response with a body. */
    public static final String JSON = "application/json";

    /**
     * The status and the content type of a success, as the OpenAPI document declares them.
     *
     * @param status      200 with a body, 204 without
     * @param contentType {@code application/json} with a body; empty without
     */
    public record Success(int status, Optional<String> contentType) {
        /** @throws NullPointerException if {@code contentType} is {@code null} */
        public Success {
            Objects.requireNonNull(contentType, "contentType");
        }
    }

    private final OperationSurface surface;
    private final RequestReader reader;

    private HttpBinding(OperationSurface surface, ReadLimits limits) {
        this.surface = surface;
        this.reader = new RequestReader(surface, limits);
    }

    /**
     * The binding of an operation, with {@link ReadLimits#defaults()}.
     *
     * @throws NullPointerException if {@code operation} is {@code null}
     */
    public static HttpBinding of(ApiOperation operation) {
        return of(operation, ReadLimits.defaults());
    }

    /**
     * The binding of an operation, with limits for the body of a request.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpBinding of(ApiOperation operation, ReadLimits limits) {
        return new HttpBinding(OperationSurface.of(operation), Objects.requireNonNull(limits, "limits"));
    }

    /**
     * Reads a request. The values are by exposed name and feed {@code OperationCodec.encode}; they are present only if there is
     * no problem.
     *
     * @throws NullPointerException if {@code parts} is {@code null}
     */
    public RequestResult readRequest(RequestParts parts) {
        return reader.read(Objects.requireNonNull(parts, "parts"));
    }

    /** The status and content type that go with {@link #writeResponse}: 200 and JSON, or 204 for an operation with no response fields. */
    public Success success() {
        return surface.hasContent() ? new Success(200, Optional.of(JSON)) : new Success(204, Optional.empty());
    }

    /**
     * The body of a success response: deterministic UTF-8 JSON without white space, in the shape of the response schema of the
     * OpenAPI document. Members come in the order of the schema, a decimal is text (with the scale of its field, if it has
     * one), a date, a time and a date-time are ISO-8601, binary is Base64, and a member that is absent is left out. The body is
     * empty for an operation with no response fields.
     *
     * @param response what {@code OperationCodec.decode} returned, by exposed name
     * @throws NullPointerException     if {@code response} is {@code null}
     * @throws IllegalArgumentException if a value is not of the type of its field, which a decode result never is
     */
    public byte[] writeResponse(Map<String, ApiValue> response) {
        Objects.requireNonNull(response, "response");
        if (!surface.hasContent()) {
            return new byte[0];
        }
        return Json.writeCompact(members(surface.response(), response)).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> members(List<FieldSurface> fields, Map<String, ApiValue> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldSurface field : fields) {
            ApiValue value = values.get(field.exposedName());
            if (value != null && !(value instanceof ApiValue.Absent)) {
                out.put(field.exposedName(), json(field, value));
            }
        }
        return out;
    }

    private static Object json(FieldSurface field, ApiValue value) {
        if (!field.array()) {
            return element(field, value);
        }
        if (!(value instanceof ApiValue.Arr array)) {
            throw new IllegalArgumentException("The field " + field.exposedName() + " is an array");
        }
        List<Object> out = new ArrayList<>();
        for (ApiValue item : array.elements()) {
            out.add(element(field, item));
        }
        return out;
    }

    private static Object element(FieldSurface field, ApiValue value) {
        if (field.object()) {
            if (!(value instanceof ApiValue.Obj object)) {
                throw new IllegalArgumentException("The field " + field.exposedName() + " is an object");
            }
            return members(field.children(), object.members());
        }
        return switch (value) {
            case ApiValue.Text text -> text.value();
            case ApiValue.Integer whole -> whole.value();
            case ApiValue.Decimal decimal -> decimal(field, decimal.value());
            case ApiValue.Bool flag -> flag.value();
            case ApiValue.Date date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date.value());
            case ApiValue.Time time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time.value());
            case ApiValue.DateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime.value());
            case ApiValue.Binary binary -> Base64.getEncoder().encodeToString(binary.value());
            default -> throw new IllegalArgumentException("The field " + field.exposedName() + " has a value that is not a scalar");
        };
    }

    /** Plain notation, never an exponent, and at least the scale of the field when it has one. */
    private static String decimal(FieldSurface field, BigDecimal value) {
        BigDecimal scaled = field.limits().scale().isPresent() && value.scale() < field.limits().scale().get()
                ? value.setScale(field.limits().scale().get()) : value;
        return scaled.toPlainString();
    }
}

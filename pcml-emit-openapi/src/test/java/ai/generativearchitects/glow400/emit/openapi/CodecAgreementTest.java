package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.codec.ApiValue;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.codec.DecodeResult;
import ai.generativearchitects.glow400.pcml.codec.EncodeResult;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The document and the codec agree: for every valid fixture, a request that the document's schemas accept is accepted by
 * {@code OperationCodec.encode}, and what {@code decode} returns is valid against the {@code 200} schema.
 */
class CodecAgreementTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void aSampleRequestAcceptedByTheDocumentIsAcceptedByTheCodecAndItsResponseIsValid() {
        int checked = 0;
        for (String repository : Repositories.VALID) {
            ApiRepositoryModel model = Repositories.model(repository);
            for (ApiDefinition api : model.apis()) {
                JsonNode document = OpenApiSchema.yaml(new OpenApiEmitter().emit(model).files().stream()
                        .filter(f -> f.path().equals("openapi/" + api.name() + ".yaml")).findFirst().orElseThrow().content());
                for (ApiOperation operation : api.operations()) {
                    check(repository, document, operation);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(10);
    }

    private static void check(String repository, JsonNode document, ApiOperation operation) {
        String where = repository + " " + operation.id();
        JsonNode item = document.at("/paths").get(operation.route()).get(operation.method().name().toLowerCase(Locale.ROOT));
        Map<String, ApiValue> request = new LinkedHashMap<>();
        ObjectNode body = JSON.objectNode();
        Map<String, String> injected = new LinkedHashMap<>();
        for (FieldBinding binding : operation.bindings()) {
            if (binding.direction() == Direction.INJECTED) {
                injected.put(binding.fieldPath(), binding.injection().orElseThrow().startsWith("${") ? "1" : binding.injection().get());
                continue;
            }
            if ((binding.direction() != Direction.REQUEST && binding.direction() != Direction.BOTH) || binding.location().isEmpty()) {
                continue;
            }
            ApiValue value = sample(binding);
            request.put(binding.exposedName(), value);
            if (binding.location().get() == Location.BODY) {
                body.set(binding.exposedName(), json(value, binding));
            } else {
                JsonNode parameter = parameter(item, binding.exposedName());
                assertThat(OpenApiSchema.schemaOf(document, parameter.get("schema")).validate(json(value, binding))).as(where + " " + binding.exposedName()).isEmpty();
            }
        }
        JsonNode bodySchema = item.at("/requestBody/content/application~1json/schema");
        if (!bodySchema.isMissingNode()) {
            assertThat(OpenApiSchema.schemaOf(document, bodySchema).validate(body)).as(where + " body").isEmpty();
        }
        OperationCodec codec = OperationCodec.of(operation);
        EncodeResult encoded = codec.encode(request, injected);
        assertThat(encoded.problems()).as(where).isEmpty();
        DecodeResult decoded = codec.decode(encoded.parameters().orElseThrow());
        assertThat(decoded.problems()).as(where).isEmpty();
        ObjectNode response = JSON.objectNode();
        for (FieldBinding binding : operation.bindings()) {
            ApiValue value = decoded.response().orElseThrow().get(binding.exposedName());
            if (value != null && (binding.direction() == Direction.RESPONSE || binding.direction() == Direction.BOTH)) {
                response.set(binding.exposedName(), json(value, binding));
            }
        }
        JsonNode okSchema = item.at("/responses/200/content/application~1json/schema");
        if (okSchema.isMissingNode()) {
            assertThat(item.at("/responses").has("204")).as(where).isTrue();
            assertThat(response).as(where).isEmpty();
        } else {
            assertThat(OpenApiSchema.schemaOf(document, okSchema).validate(response)).as(where + " response").isEmpty();
        }
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.get("parameters")) {
            if (parameter.get("name").asText().equals(name)) {
                return parameter;
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    // ---- sample values

    private static ApiValue sample(FieldBinding binding) {
        ApiValue element = binding.field().wire() instanceof WireType.Struct ? struct(binding) : scalar(binding);
        Count count = binding.field().count();
        return count instanceof Count.Fixed fixed && fixed.value() > 0 ? new ApiValue.Arr(List.of(element)) : element;
    }

    private static ApiValue struct(FieldBinding binding) {
        Map<String, ApiValue> members = new LinkedHashMap<>();
        for (FieldBinding child : binding.children()) {
            if (!child.hidden()) {
                members.put(child.exposedName(), sample(child));
            }
        }
        return new ApiValue.Obj(members);
    }

    private static ApiValue scalar(FieldBinding binding) {
        WireType wire = binding.field().wire();
        return switch (binding.semanticType().orElseThrow()) {
            case STRING -> new ApiValue.Text(fixedLength(wire) == 1 ? "a" : "ab");
            case INTEGER -> new ApiValue.Integer(BigInteger.valueOf(7));
            case DECIMAL -> new ApiValue.Decimal(new BigDecimal(BigInteger.ONE, 0).setScale(scaleOf(wire)));
            case BOOLEAN -> new ApiValue.Bool(true);
            case DATE -> new ApiValue.Date(LocalDate.of(2026, 1, 31));
            case TIME -> new ApiValue.Time(LocalTime.of(10, 20, 30));
            case DATETIME -> new ApiValue.DateTime(LocalDateTime.of(2026, 1, 31, 10, 20, 30));
            case BINARY -> new ApiValue.Binary(new byte[fixedLength(wire)]);
        };
    }

    private static int scaleOf(WireType wire) {
        return switch (wire) {
            case WireType.Packed p -> p.precision();
            case WireType.Zoned z -> z.precision();
            default -> 0;
        };
    }

    private static int fixedLength(WireType wire) {
        Length length = switch (wire) {
            case WireType.Char c -> c.length();
            case WireType.VarChar v -> v.length();
            case WireType.Byte b -> b.length();
            default -> new Length.Fixed(2);
        };
        return length instanceof Length.Fixed fixed ? fixed.value() : 2;
    }

    /** A decimal is written in plain notation, with the scale of the field for packed and zoned (decision 56). */
    private static String plain(BigDecimal value, WireType wire) {
        boolean fixedScale = wire instanceof WireType.Packed || wire instanceof WireType.Zoned;
        return (fixedScale ? value.setScale(scaleOf(wire)) : value).toPlainString();
    }

    private static JsonNode json(ApiValue value, FieldBinding binding) {
        return switch (value) {
            case ApiValue.Text t -> JSON.textNode(t.value());
            case ApiValue.Integer i -> JSON.numberNode(i.value());
            case ApiValue.Decimal d -> JSON.textNode(plain(d.value(), binding.field().wire()));
            case ApiValue.Bool b -> JSON.booleanNode(b.value());
            case ApiValue.Date d -> JSON.textNode(d.value().toString());
            case ApiValue.Time t -> JSON.textNode(t.value().toString());
            case ApiValue.DateTime t -> JSON.textNode(t.value().toString());
            case ApiValue.Binary b -> JSON.textNode(Base64.getEncoder().encodeToString(b.value()));
            case ApiValue.Obj o -> {
                ObjectNode node = JSON.objectNode();
                for (FieldBinding child : binding.children()) {
                    if (o.members().containsKey(child.exposedName())) {
                        node.set(child.exposedName(), json(o.members().get(child.exposedName()), child));
                    }
                }
                yield node;
            }
            case ApiValue.Arr a -> {
                ArrayNode node = JSON.arrayNode();
                a.elements().forEach(element -> node.add(json(element, binding)));
                yield node;
            }
            case ApiValue.Absent a -> JSON.nullNode();
        };
    }

    @Test
    void theSchemasRefuseWhatTheCodecRefuses() {
        ApiRepositoryModel model = Repositories.model("kitchen-sink");
        JsonNode document = OpenApiSchema.yaml(new OpenApiEmitter().emit(model).files().get(0).content());
        String body = "/paths/~1items~1{code}~1search/post/requestBody/content/application~1json/schema";
        for (String[] bad : new String[][] {{"quantity", "99999999999"}, {"quantity", "\"x\""}, {"active", "\"yes\""}, {"level", "100000"},
                {"token", "\"AAAA\""}, {"unknown", "1"}, {"echo", "\"toolong1\""}}) {
            JsonNode value = OpenApiSchema.json("{\"active\": true, \"" + bad[0] + "\": " + bad[1] + "}");
            assertThat(OpenApiSchema.schemaOf(document, document.at(body)).validate(value)).as(bad[0] + "=" + bad[1]).isNotEmpty();
        }
        OperationCodec codec = OperationCodec.of(model.operations().get(0));
        assertThat(codec.encode(Map.of("quantity", new ApiValue.Integer(BigInteger.valueOf(99999999999L))), Map.of("P0SRC", "web")).problems()).isNotEmpty();
        assertThat(codec.encode(Map.of("level", new ApiValue.Integer(BigInteger.valueOf(100000))), Map.of("P0SRC", "web")).problems()).isNotEmpty();
        assertThat(codec.encode(Map.of("unknown", new ApiValue.Decimal(BigDecimal.ONE)), Map.of("P0SRC", "web")).problems()).isNotEmpty();
    }
}

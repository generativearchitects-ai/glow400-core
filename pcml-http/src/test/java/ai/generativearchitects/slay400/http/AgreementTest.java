package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.contract.FieldSurface;
import ai.generativearchitects.slay400.contract.OperationSurface;
import ai.generativearchitects.slay400.emit.openapi.OpenApiEmitter;
import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.api.Location;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.EncodeResult;
import ai.generativearchitects.slay400.pcml.codec.Gen;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The binding and the OpenAPI document of the same operation agree, over the fixtures, with the in-house generator and the
 * schema validator: what the binding reads without a problem and the codec then accepts is valid against the document; what is
 * valid against the document is read without a problem; what the binding writes is valid against the response schema; and every
 * problem body is valid against the {@code ProblemDetails} schema.
 */
class AgreementTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String[] ODD = {"", " ", "abc", "1.5", "-1", "+1", "1e2", "007", "0.0", "-0", "2026-02-30", "2026-1-5", "10:20", "25:00:00", "TRUE", "true", "false",
            "AAEC", "AAECAwQ", "!!!!", "2026-11-17T10:20", "2026-11-17T10:20:30", "10:20:30.5", "99999999999999999999", "1.", ".5", "é", "0"};

    private static JsonSchema schema(JsonNode document, JsonNode fragment) {
        ObjectNode root = JSON.objectNode();
        root.setAll((ObjectNode) fragment.deepCopy());
        root.set("components", document.get("components"));
        // formats are asserted, so that a date that does not exist is invalid, as a strict client would find it
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root,
                com.networknt.schema.SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    private static JsonNode document(ApiRepositoryModel model, ApiDefinition api) throws Exception {
        byte[] bytes = new OpenApiEmitter().emit(model).files().stream().filter(f -> f.path().equals("openapi/" + api.name() + ".yaml")).findFirst().orElseThrow().content();
        return YAML.readTree(new String(bytes, StandardCharsets.UTF_8));
    }

    // ---- candidate requests

    /** A text for a scalar field: usually of its form, often not. */
    private static String text(FieldSurface field, Gen g) {
        if (g.intIn(0, 3) == 0) {
            return g.pick(List.of(ODD));
        }
        return switch (field.type().orElseThrow()) {
            case STRING -> g.text(Gen.LETTERS_AND_SPACES, 0, field.limits().maxLength().orElse(6) + 1);
            case INTEGER -> g.signedDigits(g.intIn(1, 6)).toString();
            case DECIMAL -> g.decimal(g.intIn(1, 6), g.intIn(0, 3)).toPlainString();
            case BOOLEAN -> String.valueOf(g.bool());
            case DATE -> g.date().toString();
            case TIME -> g.time().toString();
            case DATETIME -> g.dateTime().toString();
            case BINARY -> Base64.getEncoder().encodeToString(g.bytes(field.limits().maxBytes().orElse(4) + g.intIn(-1, 1)));
        };
    }

    private static JsonNode scalarJson(FieldSurface field, Gen g) {
        if (g.intIn(0, 9) == 0) {
            return g.pick(List.of(JSON.numberNode(5), JSON.textNode("x"), JSON.booleanNode(true), JSON.nullNode(), JSON.arrayNode(), JSON.objectNode(), JSON.numberNode(1.5)));
        }
        String text = text(field, g);
        return switch (field.type().orElseThrow()) {
            case INTEGER -> text.matches("-?\\d{1,15}") ? JSON.numberNode(Long.parseLong(text)) : JSON.textNode(text);
            case BOOLEAN -> text.equals("true") || text.equals("false") ? JSON.booleanNode(text.equals("true")) : JSON.textNode(text);
            default -> JSON.textNode(text);
        };
    }

    private static JsonNode bodyJson(FieldSurface field, Gen g) {
        if (field.array()) {
            if (g.intIn(0, 9) == 0) {
                return JSON.textNode("not an array");
            }
            ArrayNode array = JSON.arrayNode();
            for (int i = g.intIn(0, 3); i > 0; i--) {
                array.add(element(field, g));
            }
            return array;
        }
        return element(field, g);
    }

    private static JsonNode element(FieldSurface field, Gen g) {
        if (!field.object()) {
            return scalarJson(field, g);
        }
        ObjectNode object = JSON.objectNode();
        for (FieldSurface child : field.children()) {
            if (child.required() || g.intIn(0, 3) > 0) {
                object.set(child.exposedName(), bodyJson(child, g));
            }
        }
        if (g.intIn(0, 14) == 0) {
            object.set("unknownMember", JSON.textNode("x"));
        }
        return object;
    }

    /** A JSON value for a parameter text, typed as an OpenAPI validator would type it from the schema. */
    private static JsonNode coerce(FieldSurface field, String text) {
        return switch (field.type().orElseThrow()) {
            case INTEGER -> text.matches("-?\\d{1,18}") ? JSON.numberNode(Long.parseLong(text)) : JSON.textNode(text);
            case BOOLEAN -> text.equals("true") || text.equals("false") ? JSON.booleanNode(text.equals("true")) : JSON.textNode(text);
            default -> JSON.textNode(text);
        };
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.get("parameters")) {
            if (parameter.get("name").asText().equals(name)) {
                return parameter;
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    // ---- the properties

    @Test
    void whatTheBindingReadsAndTheCodecAcceptsIsValidAgainstTheDocumentAndWhatIsValidAgainstTheDocumentIsRead() throws Exception {
        int cases = 0, readOk = 0, accepted = 0, schemaOk = 0, rejected = 0;
        Map<String, int[]> perOperation = new LinkedHashMap<>();
        for (String name : Repos.VALID) {
            ApiRepositoryModel model = Repos.model(name);
            for (ApiDefinition api : model.apis()) {
                JsonNode document = document(model, api);
                for (ApiOperation operation : api.operations()) {
                    JsonNode item = document.at("/paths").get(operation.route()).get(operation.method().name().toLowerCase(Locale.ROOT));
                    HttpBinding binding = HttpBinding.of(operation);
                    OperationCodec codec = OperationCodec.of(operation);
                    OperationSurface surface = OperationSurface.of(operation);
                    int[] counts = perOperation.computeIfAbsent(name + " " + operation.id(), k -> new int[3]);
                    for (int i = 0; i < 300; i++) {
                        Gen g = new Gen(20261117L + name.hashCode(), i);
                        Map<String, String> path = new LinkedHashMap<>();
                        Map<String, List<String>> query = new LinkedHashMap<>();
                        Map<String, List<String>> headers = new LinkedHashMap<>();
                        ObjectNode body = JSON.objectNode();
                        boolean valid = true;
                        for (FieldSurface field : surface.request()) {
                            Location location = field.location().orElseThrow();
                            boolean present = field.required() || location == Location.PATH || g.intIn(0, 3) > 0;
                            if (!present) {
                                continue;
                            }
                            if (location == Location.BODY) {
                                body.set(field.exposedName(), bodyJson(field, g));
                                continue;
                            }
                            String text = text(field, g);
                            if (location == Location.PATH) {
                                path.put(field.exposedName(), text);
                            } else if (location == Location.QUERY) {
                                query.put(field.exposedName(), List.of(text));
                            } else {
                                headers.put(field.exposedName(), List.of(text));
                            }
                            valid &= schema(document, parameter(item, field.exposedName()).get("schema")).validate(coerce(field, text)).isEmpty();
                        }
                        boolean hasBody = surface.request().stream().anyMatch(f -> f.location().get() == Location.BODY);
                        if (hasBody) {
                            valid &= schema(document, item.at("/requestBody/content/application~1json/schema")).validate(body).isEmpty();
                        }
                        RequestParts parts = new RequestParts(path, query, headers, hasBody ? Optional.of("application/json") : Optional.empty(),
                                hasBody ? body.toString().getBytes(StandardCharsets.UTF_8) : new byte[0]);
                        RequestResult result = binding.readRequest(parts);
                        boolean read = result.problems().isEmpty();
                        cases++;
                        counts[0]++;
                        if (valid) {
                            schemaOk++;
                            assertThat(read).as(name + " " + operation.id() + " is valid against the document but was refused: " + result.problems() + " " + parts + " " + body).isTrue();
                        }
                        if (read) {
                            readOk++;
                            counts[1]++;
                            EncodeResult encoded = codec.encode(result.values().orElseThrow(), injected(operation));
                            if (encoded.problems().isEmpty()) {
                                accepted++;
                                counts[2]++;
                                assertThat(valid).as(name + " " + operation.id() + " was read and accepted but is not valid against the document: " + parts + " " + body).isTrue();
                            } else {
                                rejected++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(cases).isGreaterThan(3000);
        assertThat(readOk).isPositive();
        assertThat(accepted).isGreaterThan(100);
        assertThat(schemaOk).isGreaterThan(100);
        assertThat(rejected).isPositive();
        assertThat(cases - readOk).isGreaterThan(100);
        assertThat(perOperation.values()).allSatisfy(c -> assertThat(c[1]).isPositive());
    }

    @Test
    void whatTheBindingWritesIsValidAgainstTheResponseSchemaAndEveryProblemBodyIsValidAgainstTheProblemDetailsSchema() throws Exception {
        int responses = 0, noContent = 0, problems = 0;
        for (String name : Repos.VALID) {
            ApiRepositoryModel model = Repos.model(name);
            for (ApiDefinition api : model.apis()) {
                JsonNode document = document(model, api);
                JsonSchema problemSchema = schema(document, document.at("/components/schemas/ProblemDetails"));
                for (ApiOperation operation : api.operations()) {
                    JsonNode item = document.at("/paths").get(operation.route()).get(operation.method().name().toLowerCase(Locale.ROOT));
                    HttpBinding binding = HttpBinding.of(operation);
                    OperationCodec codec = OperationCodec.of(operation);
                    OperationSurface surface = OperationSurface.of(operation);
                    for (int i = 0; i < 200; i++) {
                        Gen g = new Gen(4711L + name.hashCode(), i);
                        Map<String, ApiValue> request = new LinkedHashMap<>();
                        for (FieldSurface field : surface.request()) {
                            ApiValue value = value(field, g);
                            request.put(field.exposedName(), value);
                        }
                        EncodeResult encoded = codec.encode(request, injected(operation));
                        if (encoded.parameters().isEmpty()) {
                            problems += checkProblem(problemSchema, ProblemDetails.forRequestProblems(encoded.problems()).withContext("acme", "test", "req-" + i));
                            continue;
                        }
                        DecodeResult decoded = codec.decode(encoded.parameters().get());
                        if (decoded.error().isPresent()) {
                            problems += checkProblem(problemSchema, ProblemDetails.forBusinessError(decoded.error().get()).withContext("acme", "test", "req-" + i));
                            continue;
                        }
                        if (decoded.response().isEmpty()) {
                            continue;
                        }
                        byte[] bytes = binding.writeResponse(decoded.response().get());
                        if (!surface.hasContent()) {
                            noContent++;
                            assertThat(bytes).isEmpty();
                            assertThat(item.at("/responses").has(String.valueOf(binding.success().status()))).isTrue();
                            continue;
                        }
                        responses++;
                        JsonNode written = YAML.readTree(new String(bytes, StandardCharsets.UTF_8));
                        assertThat(schema(document, item.at("/responses/200/content/application~1json/schema")).validate(written)).as(name + " " + operation.id() + " " + written).isEmpty();
                        assertThat(item.at("/responses").has(String.valueOf(binding.success().status()))).isTrue();
                    }
                    for (ProblemKind kind : ProblemKind.values()) {
                        problems += checkProblem(problemSchema, ProblemDetails.of(kind, "A detail").withContext("t", "e", "c"));
                    }
                    problems += checkProblem(problemSchema, ProblemDetails.of(418, "Teapot", "short and stout"));
                }
            }
        }
        assertThat(responses).isGreaterThan(100);
        assertThat(noContent).isPositive();
        assertThat(problems).isGreaterThan(100);
    }

    private static int checkProblem(JsonSchema schema, ProblemDetails problem) throws Exception {
        JsonNode body = YAML.readTree(new String(problem.toJson(), StandardCharsets.UTF_8));
        assertThat(schema.validate(body)).as(body.toString()).isEmpty();
        assertThat(body.get("status").asInt()).isEqualTo(problem.status());
        return 1;
    }

    private static Map<String, String> injected(ApiOperation operation) {
        Map<String, String> injected = new LinkedHashMap<>();
        for (FieldBinding binding : operation.bindings()) {
            if (binding.direction() == Direction.INJECTED) {
                injected.put(binding.fieldPath(), binding.injection().orElseThrow().startsWith("${") ? "1" : binding.injection().get());
            }
        }
        return injected;
    }

    /** A value that the binding or the codec may or may not accept, for the encode of a response sample. */
    private static ApiValue value(FieldSurface field, Gen g) {
        if (field.array()) {
            List<ApiValue> elements = new ArrayList<>();
            for (int i = g.intIn(0, 2); i > 0; i--) {
                elements.add(elementValue(field, g));
            }
            return new ApiValue.Arr(elements);
        }
        return elementValue(field, g);
    }

    private static ApiValue elementValue(FieldSurface field, Gen g) {
        if (field.object()) {
            Map<String, ApiValue> members = new LinkedHashMap<>();
            for (FieldSurface child : field.children()) {
                members.put(child.exposedName(), value(child, g));
            }
            return new ApiValue.Obj(members);
        }
        return switch (field.type().orElseThrow()) {
            case STRING -> new ApiValue.Text(g.text(Gen.LETTERS, 0, Math.max(1, field.limits().maxLength().orElse(3))));
            case INTEGER -> new ApiValue.Integer(g.digits(g.intIn(1, 3)));
            case DECIMAL -> new ApiValue.Decimal(g.decimal(g.intIn(1, 4), g.intIn(0, 2)));
            case BOOLEAN -> new ApiValue.Bool(g.bool());
            case DATE -> new ApiValue.Date(g.date());
            case TIME -> new ApiValue.Time(g.time());
            case DATETIME -> new ApiValue.DateTime(g.dateTime());
            case BINARY -> new ApiValue.Binary(g.bytes(field.limits().maxBytes().orElse(4)));
        };
    }
}

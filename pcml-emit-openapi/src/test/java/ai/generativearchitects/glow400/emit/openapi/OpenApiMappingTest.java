package ai.generativearchitects.glow400.emit.openapi;

import static ai.generativearchitects.glow400.emit.openapi.Apis.data;
import static ai.generativearchitects.glow400.emit.openapi.Apis.header;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One test per row of the mapping tables of kickoff 04: document, operation, schemas by semantic type. */
class OpenApiMappingTest {

    private static final String POST = "/paths/~1x/post";
    private static final String BODY = POST + "/requestBody/content/application~1json/schema";
    private static final String OK = POST + "/responses/200/content/application~1json/schema";

    private static JsonNode doc(Path dir, String pcmlData, String overlayTail) {
        return Apis.document(dir, pcmlData, header("/x", "POST", "") + overlayTail);
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---- the document

    @Test
    void theDocumentHasTheVersionTheTitleTheServerAndSortedTags(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"),
                "title: Orders and more\ntags: [zeta, alpha]\nparameters:\n  A: { name: a }\n");
        assertThat(d.at("/openapi").asText()).isEqualTo("3.1.0");
        assertThat(d.at("/info/title").asText()).isEqualTo("Orders and more");
        assertThat(d.at("/info/version").asText()).isEqualTo("1.0.0");
        assertThat(d.at("/servers")).hasSize(1);
        assertThat(d.at("/servers/0/url").asText()).isEqualTo("/{tenant}/{environment}");
        assertThat(names(d.at("/servers/0/variables"))).containsExactly("tenant", "environment");
        assertThat(d.at("/servers/0/variables/tenant/default").asText()).isEqualTo("tenant");
        assertThat(d.at("/servers/0/variables/environment/default").asText()).isEqualTo("environment");
        assertThat(d.at("/servers/0").has("host")).isFalse();
        assertThat(d.at("/tags/0/name").asText()).isEqualTo("alpha");
        assertThat(d.at("/tags/1/name").asText()).isEqualTo("zeta");
        assertThat(d.at("/tags")).hasSize(2);
    }

    @Test
    void theTitleDefaultsToTheApiNameAndThereAreNoTagsWithoutOperationTags(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"), "parameters:\n  A: { name: a }\n");
        assertThat(d.at("/info/title").asText()).isEqualTo("t");
        assertThat(d.has("tags")).isFalse();
        assertThat(d.at(POST).has("tags")).isFalse();
    }

    @Test
    void oneDocumentPerApiNamedAfterItsFolder(@TempDir Path dir) {
        ApiRepositoryModel model = Apis.model(dir, "Orders", data("A", "type=\"char\" length=\"4\"", "input"),
                header("/x", "POST", "") + "parameters:\n  A: { name: a }\n");
        assertThat(new OpenApiEmitter().emit(model).files()).extracting(f -> f.path()).containsExactly("openapi/orders.yaml");
    }

    // ---- the operation

    @Test
    void theOperationHasItsIdSummaryDescriptionAndTags(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"),
                "summary: Does it\ndescription: Does it well\ntags: [one, two]\nparameters:\n  A: { name: a }\n");
        assertThat(d.at(POST + "/operationId").asText()).isEqualTo("t");
        assertThat(d.at(POST + "/summary").asText()).isEqualTo("Does it");
        assertThat(d.at(POST + "/description").asText()).isEqualTo("Does it well");
        assertThat(texts(d.at(POST + "/tags"))).containsExactly("one", "two");
    }

    @Test
    void summaryAndDescriptionAreLeftOutWhenThereAreNone(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"), "parameters:\n  A: { name: a }\n");
        assertThat(d.at(POST).has("summary")).isFalse();
        assertThat(d.at(POST).has("description")).isFalse();
    }

    @Test
    void twoMethodsOnOneRouteShareThePathItem(@TempDir Path dir) {
        String overlay = "pcml: T.pcml\nversion: 1.0.0\nprogram: T\noperations:\n"
                + "  - { id: read-it, route: /x, method: GET, parameters: { A: { name: a, in: query } } }\n"
                + "  - { id: write-it, route: /x, method: POST, parameters: { A: { name: a } } }\n";
        JsonNode d = Apis.document(dir, data("A", "type=\"char\" length=\"4\"", "input"), overlay);
        assertThat(names(d.at("/paths"))).containsExactly("/x");
        assertThat(names(d.at("/paths/~1x"))).containsExactly("get", "post");
        assertThat(d.at("/paths/~1x/get/operationId").asText()).isEqualTo("read-it");
        assertThat(d.at("/paths/~1x/post/operationId").asText()).isEqualTo("write-it");
    }

    // ---- parameters and the request body

    @Test
    void pathQueryAndHeaderFieldsAreParametersWithTheirExtension(@TempDir Path dir) {
        String pcml = data("ID", "type=\"char\" length=\"6\"", "input") + data("Q", "type=\"char\" length=\"4\"", "input")
                + data("H", "type=\"char\" length=\"3\"", "input");
        String overlay = header("/x/{id}", "GET", "") + "parameters:\n  ID: { name: id, in: path, example: ABC }\n"
                + "  Q: { name: q, in: query, description: \"A query\" }\n  H: { name: x-h, in: header, required: true }\n";
        JsonNode d = Apis.document(dir, pcml, overlay);
        JsonNode params = d.at("/paths/~1x~1{id}/get/parameters");
        assertThat(params).hasSize(3);
        JsonNode id = params.get(0);
        assertThat(id.get("name").asText()).isEqualTo("id");
        assertThat(id.get("in").asText()).isEqualTo("path");
        assertThat(id.get("required").asBoolean()).isTrue();
        assertThat(id.get("example").asText()).isEqualTo("ABC");
        assertThat(id.at("/x-glow400-field/name").asText()).isEqualTo("ID");
        assertThat(id.at("/x-glow400-field/wire").asText()).isEqualTo("char(6)");
        assertThat(id.at("/schema/maxLength").asInt()).isEqualTo(6);
        JsonNode q = params.get(1);
        assertThat(q.get("in").asText()).isEqualTo("query");
        assertThat(q.get("required").asBoolean()).isFalse();
        assertThat(q.get("description").asText()).isEqualTo("A query");
        assertThat(q.has("example")).isFalse();
        JsonNode h = params.get(2);
        assertThat(h.get("in").asText()).isEqualTo("header");
        assertThat(h.get("name").asText()).isEqualTo("x-h");
        assertThat(h.get("required").asBoolean()).isTrue();
        assertThat(d.at("/paths/~1x~1{id}/get").has("requestBody")).isFalse();
    }

    @Test
    void bodyFieldsBecomeOneRequestBodyRequiredWhenAnyFieldIs(@TempDir Path dir) {
        String pcml = data("A", "type=\"char\" length=\"4\"", "input") + data("B", "type=\"char\" length=\"4\"", "input");
        JsonNode optional = doc(dir.resolve("o"), pcml, "parameters:\n  A: { name: a }\n  B: { name: b }\n");
        assertThat(optional.at(POST + "/requestBody/required").asBoolean()).isFalse();
        assertThat(optional.at(POST + "/parameters").isMissingNode()).isTrue();
        assertThat(names(optional.at(BODY + "/properties"))).containsExactly("a", "b");
        assertThat(optional.at(BODY + "/type").asText()).isEqualTo("object");
        assertThat(optional.at(BODY + "/additionalProperties").asBoolean(true)).isFalse();
        assertThat(optional.at(BODY).has("required")).isFalse();
        JsonNode required = doc(dir.resolve("r"), pcml, "parameters:\n  A: { name: a }\n  B: { name: b, required: true }\n");
        assertThat(required.at(POST + "/requestBody/required").asBoolean()).isTrue();
        assertThat(texts(required.at(BODY + "/required"))).containsExactly("b");
    }

    @Test
    void anOperationWithoutBodyFieldsHasNoRequestBody(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "output"), "parameters:\n  A: { name: a }\n");
        assertThat(d.at(POST).has("requestBody")).isFalse();
        assertThat(d.at(POST).has("parameters")).isFalse();
    }

    // ---- responses

    @Test
    void responseFieldsBecomeTheOkResponseAndBothFieldsAppearOnBothSides(@TempDir Path dir) {
        String pcml = data("IN", "type=\"char\" length=\"4\"", "input") + data("OUT", "type=\"char\" length=\"4\"", "output")
                + data("IO", "type=\"char\" length=\"4\"", "inputoutput");
        JsonNode d = doc(dir, pcml, "parameters:\n  IN: { name: inField }\n  OUT: { name: outField }\n  IO: { name: both, direction: both }\n");
        assertThat(names(d.at(BODY + "/properties"))).containsExactly("inField", "both");
        assertThat(names(d.at(OK + "/properties"))).containsExactly("outField", "both");
        assertThat(d.at(POST + "/responses/200/description").asText()).isEqualTo("The program ran");
        assertThat(d.at(POST + "/responses").has("204")).isFalse();
    }

    @Test
    void anOperationWithNoResponseFieldReturns204WithNoContent(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"), "parameters:\n  A: { name: a }\n");
        assertThat(d.at(POST + "/responses/204/description").asText()).isEqualTo("No content");
        assertThat(d.at(POST + "/responses/204").has("content")).isFalse();
        assertThat(d.at(POST + "/responses").has("200")).isFalse();
    }

    @Test
    void internalInjectedAndHiddenFieldsNeverAppear(@TempDir Path dir) {
        String pcml = data("A", "type=\"char\" length=\"4\"", "input") + data("I", "type=\"char\" length=\"4\"", "output")
                + data("J", "type=\"char\" length=\"4\"", "input") + data("R", "type=\"struct\" struct=\"ROW\"", "output");
        String overlay = "inject:\n  J: fix\nparameters:\n  A: { name: a }\n  I: { direction: internal }\n"
                + "  R:\n    name: row\n    direction: response\n    fields:\n      X: { name: x }\n      Y: { name: y }\n      H: { hidden: true }\n";
        JsonNode d = doc(dir, pcml, overlay);
        assertThat(names(d.at(BODY + "/properties"))).containsExactly("a");
        assertThat(names(d.at(OK + "/properties"))).containsExactly("row");
        assertThat(names(d.at(OK + "/properties/row/properties"))).containsExactly("x", "y");
        String text = d.toString();
        assertThat(text).doesNotContain("\"I\"").doesNotContain("fix").doesNotContain("\"J\"").doesNotContain("\"H\"");
    }

    @Test
    void errorResponsesWithoutAnErrorMappingAreTheFiveAlwaysThere(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"), "parameters:\n  A: { name: a }\n");
        assertThat(names(d.at(POST + "/responses"))).containsExactly("204", "400", "404", "502", "503", "504");
        for (String status : List.of("400", "404", "502", "503", "504")) {
            JsonNode response = d.at(POST + "/responses/" + status);
            assertThat(response.at("/content/application~1problem+json/schema/$ref").asText()).isEqualTo("#/components/schemas/ProblemDetails");
            assertThat(response.get("description").asText()).isNotBlank();
        }
        assertThat(d.at(POST + "/responses/400/description").asText()).isEqualTo("The request is not valid");
        assertThat(d.at(POST + "/responses/404/description").asText()).isEqualTo("Not found");
        assertThat(d.at(POST + "/responses/503/description").asText()).isEqualTo("Service unavailable");
    }

    @Test
    void anErrorMappingAdds422ItsDefaultStatusAndEveryStatusByCode(@TempDir Path dir) {
        String pcml = data("A", "type=\"char\" length=\"4\"", "input") + data("R", "type=\"char\" length=\"1\"", "output")
                + data("C", "type=\"char\" length=\"4\"", "output");
        String overlay = "parameters:\n  A: { name: a }\n  R: { direction: internal }\n  C: { direction: internal }\n"
                + "error:\n  when: { field: R, equals: \"1\" }\n  code: C\n  httpStatus: 409\n  statusByCode: { GONE: 410, NONE: 404, ODD: 599 }\n";
        JsonNode d = doc(dir, pcml, overlay);
        assertThat(names(d.at(POST + "/responses"))).containsExactly("204", "400", "404", "409", "410", "422", "502", "503", "504", "599");
        assertThat(d.at(POST + "/responses/409/description").asText()).isEqualTo("Conflict");
        assertThat(d.at(POST + "/responses/410/description").asText()).isEqualTo("Gone");
        assertThat(d.at(POST + "/responses/422/description").asText()).isEqualTo("The program reported an error");
        assertThat(d.at(POST + "/responses/599/description").asText()).isEqualTo("Error");
        assertThat(d.at(POST + "/responses/599/content/application~1problem+json/schema/$ref").asText()).isEqualTo("#/components/schemas/ProblemDetails");
    }

    @Test
    void theProblemDetailsSchemaIsInComponentsOnce(@TempDir Path dir) {
        JsonNode d = doc(dir, data("A", "type=\"char\" length=\"4\"", "input"), "parameters:\n  A: { name: a }\n");
        assertThat(names(d.at("/components/schemas"))).containsExactly("ProblemDetails");
        JsonNode problem = d.at("/components/schemas/ProblemDetails");
        assertThat(names(problem.get("properties"))).containsExactly("type", "title", "status", "detail", "instance");
        assertThat(problem.at("/properties/type/format").asText()).isEqualTo("uri-reference");
        assertThat(problem.at("/properties/status/type").asText()).isEqualTo("integer");
        assertThat(problem.has("additionalProperties")).isFalse();
    }

    // ---- schemas by semantic type

    private JsonNode field(Path dir, String attributes, String overlayField) {
        JsonNode d = doc(dir, data("A", attributes, "input"), "parameters:\n  A: { name: a" + overlayField + " }\n");
        return d.at(BODY + "/properties/a");
    }

    @Test
    void stringIsATextWithTheFieldLengthAsMaximum(@TempDir Path dir) {
        JsonNode s = field(dir, "type=\"char\" length=\"12\"", "");
        assertThat(s.get("type").asText()).isEqualTo("string");
        assertThat(s.get("maxLength").asInt()).isEqualTo(12);
        assertThat(s.has("format")).isFalse();
        assertThat(field(dir.resolve("v"), "type=\"varchar\" length=\"30\"", "").get("maxLength").asInt()).isEqualTo(30);
    }

    @Test
    void integerBoundsComeFromTheWireType(@TempDir Path dir) {
        JsonNode text = field(dir.resolve("a"), "type=\"char\" length=\"3\"", ", type: integer");
        assertThat(text.get("type").asText()).isEqualTo("integer");
        assertThat(text.get("minimum").asInt()).isZero();
        assertThat(text.get("maximum").asInt()).isEqualTo(999);
        assertThat(field(dir.resolve("b"), "type=\"int\" length=\"2\"", "").get("minimum").asInt()).isEqualTo(-32768);
        assertThat(field(dir.resolve("b2"), "type=\"int\" length=\"2\"", "").get("maximum").asInt()).isEqualTo(32767);
        assertThat(field(dir.resolve("c"), "type=\"int\" length=\"4\" precision=\"32\"", "").get("minimum").asInt()).isZero();
        assertThat(field(dir.resolve("c2"), "type=\"int\" length=\"4\" precision=\"32\"", "").get("maximum").asLong()).isEqualTo(4294967295L);
        assertThat(field(dir.resolve("d"), "type=\"int\" length=\"8\"", "").get("minimum").bigIntegerValue())
                .isEqualTo(java.math.BigInteger.valueOf(Long.MIN_VALUE));
        assertThat(field(dir.resolve("d2"), "type=\"int\" length=\"8\"", "").get("maximum").bigIntegerValue())
                .isEqualTo(java.math.BigInteger.valueOf(Long.MAX_VALUE));
        assertThat(field(dir.resolve("d3"), "type=\"int\" length=\"8\" precision=\"64\"", "").get("maximum").bigIntegerValue())
                .isEqualTo(java.math.BigInteger.TWO.pow(64).subtract(java.math.BigInteger.ONE));
        assertThat(field(dir.resolve("d4"), "type=\"int\" length=\"4\" precision=\"31\"", "").get("maximum").asLong()).isEqualTo(2147483647L);
        JsonNode packed = field(dir.resolve("e"), "type=\"packed\" length=\"5\" precision=\"0\"", ", type: integer");
        assertThat(packed.get("minimum").asInt()).isEqualTo(-99999);
        assertThat(packed.get("maximum").asInt()).isEqualTo(99999);
        JsonNode zoned = field(dir.resolve("f"), "type=\"zoned\" length=\"7\" precision=\"0\"", ", type: integer");
        assertThat(zoned.get("minimum").asInt()).isEqualTo(-9999999);
        assertThat(zoned.get("maximum").asInt()).isEqualTo(9999999);
    }

    @Test
    void decimalIsTextWithAPatternAndItsDigitsAndScaleStated(@TempDir Path dir) {
        JsonNode packed = field(dir.resolve("a"), "type=\"packed\" length=\"9\" precision=\"2\"", "");
        assertThat(packed.get("type").asText()).isEqualTo("string");
        assertThat(packed.get("format").asText()).isEqualTo("decimal");
        assertThat(packed.get("pattern").asText()).isEqualTo("^-?\\d{1,7}\\.\\d{2}$");
        assertThat(packed.has("minimum")).isFalse();
        assertThat(packed.has("maximum")).isFalse();
        assertThat(packed.get("description").asText()).isEqualTo("A decimal number with up to 9 digits, 2 of them after the decimal point.");
        assertThat(packed.at("/x-glow400-field/digits").asInt()).isEqualTo(9);
        assertThat(packed.at("/x-glow400-field/scale").asInt()).isEqualTo(2);
        JsonNode zoned = field(dir.resolve("b"), "type=\"zoned\" length=\"6\" precision=\"3\"", "");
        assertThat(zoned.get("pattern").asText()).isEqualTo("^-?\\d{1,3}\\.\\d{3}$");
        assertThat(zoned.at("/x-glow400-field/digits").asInt()).isEqualTo(6);
        assertThat(zoned.at("/x-glow400-field/scale").asInt()).isEqualTo(3);
        assertThat(zoned.at("/x-glow400-field/wire").asText()).isEqualTo("zoned(6,3)");
        assertThat(field(dir.resolve("b1"), "type=\"packed\" length=\"5\" precision=\"5\"", "").get("pattern").asText()).isEqualTo("^-?0\\.\\d{5}$");
        assertThat(field(dir.resolve("b2"), "type=\"packed\" length=\"5\" precision=\"0\"", "").get("pattern").asText()).isEqualTo("^-?\\d{1,5}$");
        JsonNode text = field(dir.resolve("c"), "type=\"char\" length=\"8\"", ", type: decimal");
        assertThat(text.get("type").asText()).isEqualTo("string");
        assertThat(text.get("format").asText()).isEqualTo("decimal");
        assertThat(text.get("pattern").asText()).isEqualTo("^\\d+(\\.\\d+)?$");
        assertThat(text.has("minimum")).isFalse();
        assertThat(text.get("description").asText()).isEqualTo("A decimal number written with at most 8 characters, the decimal point included.");
        assertThat(text.at("/x-glow400-field").has("digits")).isFalse();
        JsonNode floating = field(dir.resolve("d"), "type=\"float\" length=\"4\"", "");
        assertThat(floating.get("pattern").asText()).isEqualTo("^[-+]?\\d+(\\.\\d+)?$");
        assertThat(floating.get("description").asText()).isEqualTo("A floating-point number of 4 bytes.");
        JsonNode both = field(dir.resolve("e"), "type=\"packed\" length=\"5\" precision=\"1\"", ", description: \"The price.\"");
        assertThat(both.get("description").asText()).isEqualTo("The price. A decimal number with up to 5 digits, 1 of them after the decimal point.");
    }

    @Test
    void thePatternsAcceptWhatTheCodecWritesAndRefuseTheRest(@TempDir Path dir) {
        String packed = field(dir.resolve("a"), "type=\"packed\" length=\"9\" precision=\"2\"", "").get("pattern").asText();
        for (String ok : List.of("0.00", "-1.50", "1234567.99", "-1234567.99")) {
            assertThat(java.util.regex.Pattern.matches(packed, ok)).as(ok).isTrue();
        }
        for (String bad : List.of("1", "1.5", "1.500", "12345678.00", ".50", "+1.00", "1e2", "")) {
            assertThat(java.util.regex.Pattern.matches(packed, bad)).as(bad).isFalse();
        }
        String text = field(dir.resolve("b"), "type=\"char\" length=\"8\"", ", type: decimal").get("pattern").asText();
        assertThat(java.util.regex.Pattern.matches(text, "12")).isTrue();
        assertThat(java.util.regex.Pattern.matches(text, "1.25")).isTrue();
        assertThat(java.util.regex.Pattern.matches(text, "-1")).isFalse();
        assertThat(java.util.regex.Pattern.matches(text, "1.")).isFalse();
        String floating = field(dir.resolve("c"), "type=\"float\" length=\"8\"", "").get("pattern").asText();
        assertThat(java.util.regex.Pattern.matches(floating, "-1.5")).isTrue();
        assertThat(java.util.regex.Pattern.matches(floating, "+2")).isTrue();
        assertThat(java.util.regex.Pattern.matches(floating, "1e5")).isFalse();
    }

    @Test
    void booleanDateTimeDateTimeAndBinary(@TempDir Path dir) {
        assertThat(field(dir.resolve("a"), "type=\"char\" length=\"1\"", ", type: boolean").get("type").asText()).isEqualTo("boolean");
        JsonNode date = field(dir.resolve("b"), "type=\"date\"", "");
        assertThat(date.get("type").asText()).isEqualTo("string");
        assertThat(date.get("format").asText()).isEqualTo("date");
        JsonNode time = field(dir.resolve("c"), "type=\"time\"", "");
        assertThat(time.get("type").asText()).isEqualTo("string");
        assertThat(time.get("format").asText()).isEqualTo("local-time");
        assertThat(time.get("pattern").asText()).isEqualTo("^\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$");
        for (String ok : List.of("10:20:30", "00:00:00", "23:59:59.1", "10:20:30.123456789")) {
            assertThat(java.util.regex.Pattern.matches(time.get("pattern").asText(), ok)).as(ok).isTrue();
        }
        for (String bad : List.of("10:20", "10:20:30Z", "10:20:30+01:00", "102030", "10:20:30.", "10:20:30.1234567890", "1:20:30")) {
            assertThat(java.util.regex.Pattern.matches(time.get("pattern").asText(), bad)).as(bad).isFalse();
        }
        JsonNode stamp = field(dir.resolve("d"), "type=\"timestamp\"", "");
        assertThat(stamp.get("format").asText()).isEqualTo("local-date-time");
        assertThat(stamp.get("pattern").asText()).isEqualTo("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$");
        assertThat(java.util.regex.Pattern.matches(stamp.get("pattern").asText(), "2026-01-31T10:20:30")).isTrue();
        assertThat(java.util.regex.Pattern.matches(stamp.get("pattern").asText(), "2026-01-31T10:20:30.123456789")).isTrue();
        assertThat(java.util.regex.Pattern.matches(stamp.get("pattern").asText(), "2026-01-31T10:20:30Z")).isFalse();
        for (int bytes : new int[] {1, 2, 3, 4, 10}) {
            JsonNode binary = field(dir.resolve("e" + bytes), "type=\"byte\" length=\"" + bytes + "\"", "");
            assertThat(binary.get("type").asText()).isEqualTo("string");
            assertThat(binary.get("contentEncoding").asText()).isEqualTo("base64");
            int encoded = java.util.Base64.getEncoder().encodeToString(new byte[bytes]).length();
            assertThat(binary.get("minLength").asInt()).isEqualTo(encoded);
            assertThat(binary.get("maxLength").asInt()).isEqualTo(encoded);
        }
    }

    @Test
    void aLengthThatNamesAnotherFieldGivesNoLimit(@TempDir Path dir) {
        String pcml = data("N", "type=\"int\" length=\"4\"", "output") + data("S", "type=\"char\" length=\"N\"", "output")
                + data("D", "type=\"char\" length=\"N\"", "output") + data("B", "type=\"byte\" length=\"N\"", "output");
        String overlay = "parameters:\n  N: { direction: internal }\n  S: { name: s }\n  D: { name: d, type: integer }\n  B: { name: b }\n";
        JsonNode d = doc(dir, pcml, overlay);
        assertThat(d.at(OK + "/properties/s").has("maxLength")).isFalse();
        assertThat(d.at(OK + "/properties/d").has("maximum")).isFalse();
        assertThat(d.at(OK + "/properties/d/minimum").asInt()).isZero();
        assertThat(d.at(OK + "/properties/b").has("maxLength")).isFalse();
        assertThat(d.at(OK + "/properties/b").has("minLength")).isFalse();
        assertThat(d.at(OK + "/properties/s/x-glow400-field/wire").asText()).isEqualTo("char(N)");
        assertThat(d.at(OK + "/properties/b/x-glow400-field/wire").asText()).isEqualTo("byte(N)");
    }

    // ---- structs, arrays, descriptions and examples

    @Test
    void aStructIsAnInlineObjectInPcmlOrderThatAllowsNoOtherProperty(@TempDir Path dir) {
        JsonNode d = doc(dir, data("R", "type=\"struct\" struct=\"ROW\"", "input"),
                "parameters:\n  R:\n    name: row\n    required: true\n    description: \"A row\"\n    fields:\n      X: { name: x, required: true }\n      Y: { name: y }\n      H: { hidden: true }\n");
        JsonNode row = d.at(BODY + "/properties/row");
        assertThat(row.get("type").asText()).isEqualTo("object");
        assertThat(row.get("description").asText()).isEqualTo("A row");
        assertThat(names(row.get("properties"))).containsExactly("x", "y");
        assertThat(texts(row.get("required"))).containsExactly("x");
        assertThat(row.get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(row.at("/x-glow400-field/name").asText()).isEqualTo("R");
        assertThat(row.at("/x-glow400-field/wire").asText()).isEqualTo("struct(ROW)");
        assertThat(row.at("/properties/y/x-glow400-field/name").asText()).isEqualTo("R.Y");
        assertThat(texts(d.at(BODY + "/required"))).containsExactly("row");
    }

    @Test
    void anArrayHasItemsAndTheFixedCountAsMaximum(@TempDir Path dir) {
        String pcml = data("T", "type=\"char\" length=\"4\" count=\"5\"", "input") + data("R", "type=\"struct\" struct=\"ROW\" count=\"3\"", "input");
        JsonNode d = doc(dir, pcml, "parameters:\n  T: { name: tags, description: \"Tags\", example: abc }\n  R: { name: rows }\n");
        JsonNode tags = d.at(BODY + "/properties/tags");
        assertThat(tags.get("type").asText()).isEqualTo("array");
        assertThat(tags.get("maxItems").asInt()).isEqualTo(5);
        assertThat(tags.at("/items/type").asText()).isEqualTo("string");
        assertThat(tags.at("/items/maxLength").asInt()).isEqualTo(4);
        assertThat(tags.at("/items/examples/0").asText()).isEqualTo("abc");
        assertThat(tags.get("description").asText()).isEqualTo("Tags");
        assertThat(tags.at("/items").has("description")).isFalse();
        assertThat(tags.at("/x-glow400-field/name").asText()).isEqualTo("T");
        assertThat(tags.at("/items").has("x-glow400-field")).isFalse();
        JsonNode rows = d.at(BODY + "/properties/rows");
        assertThat(rows.get("maxItems").asInt()).isEqualTo(3);
        assertThat(rows.at("/items/type").asText()).isEqualTo("object");
        assertThat(names(rows.at("/items/properties"))).containsExactly("x", "y", "h");
    }

    @Test
    void anArrayCountedByAnotherFieldHasNoMaximum(@TempDir Path dir) {
        String pcml = data("N", "type=\"int\" length=\"4\"", "output") + data("T", "type=\"char\" length=\"4\" count=\"N\"", "output");
        JsonNode d = doc(dir, pcml, "parameters:\n  N: { direction: internal }\n  T: { name: tags }\n");
        JsonNode tags = d.at(OK + "/properties/tags");
        assertThat(tags.get("type").asText()).isEqualTo("array");
        assertThat(tags.has("maxItems")).isFalse();
        assertThat(tags.at("/items/maxLength").asInt()).isEqualTo(4);
    }

    @Test
    void examplesAreWrittenInTheFormTheSchemaDescribes(@TempDir Path dir) {
        String pcml = data("I", "type=\"char\" length=\"4\"", "input") + data("D", "type=\"packed\" length=\"7\" precision=\"2\"", "input")
                + data("B", "type=\"char\" length=\"1\"", "input") + data("S", "type=\"char\" length=\"4\"", "input")
                + data("T", "type=\"date\"", "input");
        String overlay = "parameters:\n  I: { name: i, type: integer, example: \"42\" }\n  D: { name: d, example: \"10.50\" }\n"
                + "  B: { name: b, type: boolean, example: \"true\" }\n  S: { name: s, example: \"1042\" }\n  T: { name: t, example: \"2026-01-31\" }\n";
        JsonNode props = doc(dir, pcml, overlay).at(BODY + "/properties");
        assertThat(props.at("/i/examples/0").isIntegralNumber()).isTrue();
        assertThat(props.at("/i/examples/0").asInt()).isEqualTo(42);
        assertThat(props.at("/d/examples/0").isTextual()).isTrue();
        assertThat(props.at("/d/examples/0").asText()).isEqualTo("10.50");
        assertThat(props.at("/b/examples/0").isBoolean()).isTrue();
        assertThat(props.at("/b/examples/0").asBoolean()).isTrue();
        assertThat(props.at("/s/examples/0").isTextual()).isTrue();
        assertThat(props.at("/s/examples/0").asText()).isEqualTo("1042");
        assertThat(props.at("/t/examples/0").asText()).isEqualTo("2026-01-31");
    }

    @Test
    void aFalseBooleanExampleIsFalseAndABooleanParameterHasTheTypedExample(@TempDir Path dir) {
        JsonNode d = Apis.document(dir, data("B", "type=\"char\" length=\"1\"", "input"),
                header("/x", "GET", "") + "parameters:\n  B: { name: b, in: query, type: boolean, example: \"false\" }\n");
        assertThat(d.at("/paths/~1x/get/parameters/0/example").isBoolean()).isTrue();
        assertThat(d.at("/paths/~1x/get/parameters/0/example").asBoolean(true)).isFalse();
    }

    @Test
    void theVendorExtensionNameComesFromOneConstant() {
        assertThat(OpenApiBuilder.EXTENSION).isEqualTo("x-" + OpenApiBuilder.VENDOR_PREFIX + "-field");
        assertThat(OpenApiBuilder.wireText(new ai.generativearchitects.glow400.pcml.model.WireType.Date())).isEqualTo("date");
        assertThat(OpenApiBuilder.wireText(new ai.generativearchitects.glow400.pcml.model.WireType.Time())).isEqualTo("time");
        assertThat(OpenApiBuilder.wireText(new ai.generativearchitects.glow400.pcml.model.WireType.Timestamp())).isEqualTo("timestamp");
    }
}

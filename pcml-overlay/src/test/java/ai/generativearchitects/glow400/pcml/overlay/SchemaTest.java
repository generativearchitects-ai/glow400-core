package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The published JSON Schemas against the fixtures: every valid file passes, every file that breaks a rule a schema can
 * express fails, and files that break only rules that need the PCML (for example a parameter that does not exist) pass.
 */
class SchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final String BASE = "/ai/generativearchitects/glow400/pcml/overlay/";

    private static JsonSchema schema(String name) {
        try (InputStream in = SchemaTest.class.getResourceAsStream(BASE + name)) {
            assertThat(in).as("schema resource %s", name).isNotNull();
            return FACTORY.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final JsonSchema API = schema("api.schema.json");
    private static final JsonSchema GATEWAY = schema("gateway.schema.json");

    private static JsonNode yaml(String text) {
        Object value = new Load(LoadSettings.builder().build()).loadFromString(text);
        return JSON.valueToTree(value);
    }

    private static Set<ValidationMessage> validateApi(String yaml) {
        return API.validate(yaml(yaml));
    }

    private static Stream<Path> overlayFiles(String below) throws IOException {
        Path root = Repos.fixture(below);
        return Files.walk(root).filter(p -> p.getFileName().toString().matches("api\\.ya?ml"));
    }

    // ---- fixtures

    static List<Path> validOverlays() throws IOException {
        List<Path> all = new java.util.ArrayList<>();
        for (String repository : List.of("minimal", "inputoutput", "defaults-both", "defaults-fail", "structs", "body-struct",
                "operations-list", "action-parameter", "injected", "dclcase")) {
            try (Stream<Path> files = overlayFiles(repository)) {
                files.forEach(all::add);
            }
        }
        return all;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validOverlays")
    void everyValidOverlayPasses(Path file) throws IOException {
        assertThat(validateApi(Files.readString(file))).as(file.toString()).isEmpty();
    }

    @Test
    void validGatewayFilesPass() throws IOException {
        for (String repository : List.of("defaults-both", "defaults-fail")) {
            Path file = Repos.fixture(repository).resolve("gateway.yaml");
            assertThat(GATEWAY.validate(yaml(Files.readString(file)))).as(file.toString()).isEmpty();
        }
    }

    static Stream<String> schemaLevelFixtures() {
        return Stream.of("ovl011/apis/TOPLEVEL", "ovl011/apis/PARAMETER", "ovl012/apis/METHOD", "ovl012/apis/NOVERSION",
                "ovl012/apis/VERSION", "ovl012/apis/STATUS", "ovl012/apis/EXPRESSION", "ovl020/apis/BOTHFORMS",
                "multiple/apis/VERSIONANDNAME", "multiple/apis/ROUTEANDNAME", "multiple/apis/KEYANDNAME",
                "multiple/apis/SHAREDANDVERSION");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaLevelFixtures")
    void invalidFixturesForSchemaLevelRulesFail(String folder) throws IOException {
        Path file = Repos.fixture("invalid/" + folder.substring(0, folder.indexOf('/'))).resolve(folder.substring(folder.indexOf('/') + 1)).resolve("api.yaml");
        assertThat(validateApi(Files.readString(file))).as(folder).isNotEmpty();
    }

    static Stream<String> semanticOnlyFixtures() {
        return Stream.of("ovl001/apis/GETBAL", "ovl002/apis/NOPARAM", "ovl002/apis/NOMEMBER", "ovl002/apis/INJECT",
                "ovl004/apis/PLACEHOLDER", "ovl004/apis/BINDING", "ovl013/apis/NOPCML", "ovl013/apis/NOPROGRAM",
                "ovl014/apis/NAMES", "ovl014/apis/ID1", "ovl014/apis/ROUTE1", "ovl015/apis/GETSTRUCT",
                "ovl015/apis/ARRAYHEADER", "ovl016/apis/INPUT", "ovl016/apis/OUTPUT", "ovl017/apis/COUNTFIELD",
                "ovl017/apis/ERRORFIELD", "ovl018/apis/BOTH",
                // 'in' where it has no effect, and a body on GET or DELETE, need the direction and the method together:
                // the schema leaves them to the loader.
                "ovl012/apis/INRESPONSE", "ovl015/apis/GETBODY", "ovl015/apis/DELETEBODY",
                "multiple/apis/DIRECTIONANDNAME", "multiple/apis/TYPEONSTRUCT", "multiple/apis/NOPROGRAM");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semanticOnlyFixtures")
    void fixturesThatBreakOnlyRulesThatNeedThePcmlPassTheSchema(String folder) throws IOException {
        Path file = Repos.fixture("invalid/" + folder.substring(0, folder.indexOf('/'))).resolve(folder.substring(folder.indexOf('/') + 1)).resolve("api.yaml");
        assertThat(validateApi(Files.readString(file))).as(folder).isEmpty();
    }

    // ---- rules the schema expresses, one by one

    private static final String HEAD = "pcml: A.pcml\nversion: 1.0.0\nprogram: A\nroute: /a\nmethod: GET\n";

    static Stream<String> invalidSnippets() {
        return Stream.of(
                HEAD + "extra: 1\n",
                HEAD + "parameters:\n  P: { direcion: request }\n",
                HEAD + "parameters:\n  P: { direction: sideways }\n",
                HEAD + "parameters:\n  P: { in: cookie }\n",
                HEAD + "parameters:\n  P: { type: number }\n",
                HEAD + "parameters:\n  P: { pad: center }\n",
                HEAD + "parameters:\n  P: { padChar: \"ab\" }\n",
                HEAD + "parameters:\n  P: { padChar: \"\" }\n",
                HEAD + "parameters:\n  P: { required: yes_please }\n",
                HEAD + "parameters:\n  P: { name: \"\" }\n",
                HEAD + "parameters:\n  P: { trim: { dropBlank: false } }\n",
                HEAD + "parameters:\n  P: { trim: { countField: N, dropBlank: true } }\n",
                HEAD + "parameters:\n  P: { trim: {} }\n",
                HEAD + "parameters:\n  P: { fields: { M: { direction: request } } }\n",
                HEAD + "parameters:\n  P: { fields: { M: { in: body } } }\n",
                HEAD + "parameters:\n  P: { hidden: true }\n",
                HEAD + "parameters:\n  P: { trueValue: \"\" }\n",
                HEAD + "parameters:\n  P: { falseValue: \" \" }\n",
                HEAD + "parameters:\n  P: { trueValue: 1 }\n",
                HEAD + "parameters:\n  P: { fields: { M: { trueValue: true } } }\n",
                HEAD + "inject:\n  P: ${user.name}\n",
                HEAD + "inject:\n  P: x${tenant.id}\n",
                HEAD + "error:\n  when: { field: E }\n",
                HEAD + "error:\n  when: { field: E, equals: a, notEquals: b }\n",
                HEAD + "error:\n  when: { field: E, in: [] }\n",
                HEAD + "error:\n  when: { field: E, equals: a }\n  httpStatus: 399\n",
                HEAD + "error:\n  when: { field: E, equals: a }\n  statusByCode: { X: 600 }\n",
                HEAD + "cache: {}\n",
                HEAD + "cache: { ttlSeconds: -1 }\n",
                HEAD + "tags: orders\n",
                HEAD.replace("/a", "a"),
                HEAD.replace("/a", "/{a"),
                HEAD.replace("/a", "/{}"),
                HEAD.replace("GET", "get"),
                HEAD.replace("1.0.0", "1.0"),
                HEAD.replace("1.0.0", "01.0.0"),
                HEAD + "call: fast\n",
                "version: 1.0.0\nprogram: A\nroute: /a\nmethod: GET\n",
                "pcml: A.pcml\nprogram: A\nroute: /a\nmethod: GET\n",
                "pcml: A.pcml\nversion: 1.0.0\nroute: /a\nmethod: GET\n",
                "pcml: A.pcml\nversion: 1.0.0\nprogram: A\nmethod: GET\n",
                "pcml: A.pcml\nversion: 1.0.0\nprogram: A\nroute: /a\n",
                "pcml: A.pcml\nversion: 1.0.0\noperations: []\n",
                "pcml: A.pcml\nversion: 1.0.0\noperations:\n  - { id: x, route: /x, method: GET }\n",
                "pcml: A.pcml\nversion: 1.0.0\nid: top\noperations:\n  - { id: x, program: A, route: /x, method: GET }\n",
                "pcml: A.pcml\nversion: 1.0.0\nsummary: top\noperations:\n  - { id: x, program: A, route: /x, method: GET }\n",
                "pcml: A.pcml\nversion: 1.0.0\noperations:\n  - { program: A, route: /x, method: GET }\n",
                "pcml: A.pcml\nversion: 1.0.0\noperations:\n  - { id: x, program: A, route: /x, method: GET, version: 1.0.0 }\n",
                "pcml: A.pcml\nversion: 1.0.0\noperations:\n  - { id: \"bad id\", program: A, route: /x, method: GET }\n");
    }

    @ParameterizedTest
    @MethodSource("invalidSnippets")
    void schemaRejects(String yaml) {
        assertThat(validateApi(yaml)).as(yaml).isNotEmpty();
    }

    static Stream<String> validSnippets() {
        return Stream.of(
                HEAD,
                HEAD + "call: raw\ntags: [a, b]\nsummary: s\ndescription: d\nid: my.id_1-x\n",
                HEAD + "inject:\n  P: 7\n  Q: true\n  R: DONE\n  S: ${environment.code}\n  T: ${tenant.region.code}\n",
                HEAD + "parameters:\n  P:\n    direction: both\n    in: header\n    type: datetime\n    pad: right\n    padChar: \"-\"\n"
                        + "    nullAs: \"\"\n    required: false\n    example: 1.5\n    trim: { dropBlank: true }\n"
                        + "    fields:\n      M: { hidden: true, fields: { N: { name: n } } }\n",
                HEAD + "error:\n  when: { field: E, in: [a, 2, true] }\n  code: C\n  message: M\n  httpStatus: 599\n  statusByCode: { X: 400 }\n",
                HEAD + "error:\n  when: { field: E, notEquals: OK }\n",
                HEAD + "parameters:\n  P: { type: boolean, trueValue: \"Y\", falseValue: \"N\", fields: { M: { trueValue: \"+\", falseValue: \"-\" } } }\n",
                HEAD + "cache: { ttlSeconds: 60 }\n",
                HEAD.replace("1.0.0", "1.2.3-rc.1+build.5"),
                HEAD.replace("/a", "/a/{b}/c/{d}"),
                "pcml: A.pcml\nversion: 1.0.0\nprogram: A\noperations:\n  - { id: x, route: /x, method: GET }\n",
                "pcml: A.pcml\nversion: 1.0.0\noperations:\n  - { id: x, program: A, route: /x, method: GET }\n  - { id: y, program: B, route: /y, method: POST, tags: [t] }\n");
    }

    @ParameterizedTest
    @MethodSource("validSnippets")
    void schemaAccepts(String yaml) {
        assertThat(validateApi(yaml)).as(yaml).isEmpty();
    }

    @Test
    void gatewaySchemaRejectsWhatTheLoaderRejects() {
        for (String text : List.of("extra: 1\n", "defaults: { char: { pad: middle } }\n", "defaults: { inputoutput: maybe }\n",
                "defaults: { char: { padChar: \"ab\" } }\n", "defaults: { other: 1 }\n", "emitters: openapi\n",
                "defaults: { largeArrayThreshold: 0 }\n", "defaults: { largeArrayThreshold: x }\n", "defaults: { largeArrayThreshold: 1.5 }\n")) {
            assertThat(GATEWAY.validate(yaml(text))).as(text).isNotEmpty();
        }
        for (String text : List.of("{}\n", "defaults: { char: { pad: left, padChar: \"0\", nullAs: \"\" }, inputoutput: fail }\n",
                "emitters: [descriptor, openapi, mock, java]\n", "defaults: { largeArrayThreshold: 1 }\n")) {
            assertThat(GATEWAY.validate(yaml(text))).as(text).isEmpty();
        }
    }
}

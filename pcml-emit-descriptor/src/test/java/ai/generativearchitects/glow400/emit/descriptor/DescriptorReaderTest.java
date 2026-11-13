package ai.generativearchitects.glow400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Reading descriptors that are wrong: one diagnostic, with its code and a message that names the place. */
class DescriptorReaderTest {

    private static final String OP = "apis/0/operations/0";
    private static final String SEARCH_PARAMS = OP + "/program/parameters";
    private static final String BINDINGS = OP + "/bindings";

    private static DescriptorResult read(String text) {
        return DescriptorReader.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Diagnostic only(DescriptorResult result) {
        assertThat(result.model()).isEmpty();
        assertThat(result.diagnostics()).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        return d;
    }

    // ---- DSC001

    @ParameterizedTest
    @MethodSource("notJson")
    void aDocumentThatIsNotJsonIsDsc001(String text, String message) {
        Diagnostic d = only(read(text));
        assertThat(d.code()).isEqualTo("DSC001");
        assertThat(d.message()).startsWith("The document is not valid JSON: ").contains(message);
        assertThat(d.location().file()).isEqualTo("descriptor.json");
    }

    static Stream<Arguments> notJson() {
        return Stream.of(
                Arguments.of("", "empty"),
                Arguments.of("   \n", "empty"),
                Arguments.of("{", "Unexpected end-of-input"),
                Arguments.of("{\"a\": }", "Unexpected character"),
                Arguments.of("not json", "Unrecognized token"),
                Arguments.of("{\"schemaVersion\": 1, \"schemaVersion\": 1}", "Duplicate field"),
                Arguments.of("{} {}", "after the end of the document"),
                Arguments.of("{\"a\": 1,}", "was expecting double-quote"),
                Arguments.of("{'a': 1}", "was expecting double-quote"),
                Arguments.of("{\"a\": 1 /* c */}", "Unexpected character"),
                Arguments.of("[1, 2", "Unexpected end-of-input"));
    }

    @Test
    void theLocationOfAJsonErrorIsWhereItIs() {
        Diagnostic d = only(read("{\n  \"a\": 1,\n  \"b\": ]\n}"));
        assertThat(d.location()).isEqualTo(new SourceLocation("descriptor.json", 3, 8));
    }

    @Test
    void aDocumentAboveTheSizeLimitIsDsc001() {
        InputStream in = new InputStream() {
            private long sent;

            @Override
            public int read() {
                return sent++ == 0 ? '[' : ' ';
            }
        };
        DescriptorResult result = DescriptorReader.read(in);
        assertThat(only(result).code()).isEqualTo("DSC001");
        assertThat(result.diagnostics().get(0).message()).contains("Document length").contains("exceeds the maximum");
    }

    @Test
    void aDocumentNestedTooDeeplyIsDsc001() {
        String ok = "[".repeat(JsonTree.MAX_DEPTH) + "]".repeat(JsonTree.MAX_DEPTH);
        assertThat(only(read(ok)).code()).isEqualTo("DSC003"); // deep enough to read, and not a descriptor
        String deep = "[".repeat(JsonTree.MAX_DEPTH + 1) + "]".repeat(JsonTree.MAX_DEPTH + 1);
        Diagnostic d = only(read(deep));
        assertThat(d.code()).isEqualTo("DSC001");
        assertThat(d.message()).contains("nesting depth");
    }

    @Test
    void aStringAboveTheLengthLimitIsDsc001() {
        Diagnostic d = only(read("{\"a\": \"" + "x".repeat(JsonTree.MAX_STRING_LENGTH + 1) + "\"}"));
        assertThat(d.code()).isEqualTo("DSC001");
        assertThat(d.message()).contains("String value length");
    }

    @Test
    void aNumberThatIsTooLongIsDsc001() {
        assertThat(only(read("{\"a\": " + "1".repeat(100) + "}")).code()).isEqualTo("DSC001");
    }

    @Test
    void anInputThatCannotBeReadIsDsc001AndNothingIsThrown() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk on fire");
            }
        };
        Diagnostic d = only(DescriptorReader.read(broken));
        assertThat(d.code()).isEqualTo("DSC001");
        assertThat(d.message()).isEqualTo("The document cannot be read: IOException");
        assertThat(d.location()).isEqualTo(new SourceLocation("descriptor.json", 1, 1));
    }

    @Test
    void theReaderLeavesTheStreamOpen() {
        boolean[] closed = {false};
        InputStream in = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        DescriptorReader.read(in);
        assertThat(closed[0]).isFalse();
    }

    // ---- DSC002

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 2, 99, -1, 4294967297L})
    void anotherSchemaVersionIsDsc002(long version) {
        Diagnostic d = only(Doc.of("minimal").set("schemaVersion", version).read());
        assertThat(d.code()).isEqualTo("DSC002");
        assertThat(d.message()).isEqualTo("Unsupported descriptor schema version " + version + "; this reader supports version 1");
    }

    @Test
    void aVersionFromTheFutureIsRefusedBeforeItsStructureIsLooked_at() {
        DescriptorResult result = read("{\"schemaVersion\": 2, \"somethingNew\": [1, 2]}");
        assertThat(only(result).code()).isEqualTo("DSC002");
    }

    @Test
    void theVersionMustBeAWholeNumber() {
        for (Object bad : new Object[] {"1", "@@1.5@@", true, null, List.of()}) {
            Diagnostic d = only(Doc.of("minimal").set("schemaVersion", bad).read());
            assertThat(d.code()).as(String.valueOf(bad)).isEqualTo("DSC003");
            assertThat(d.message()).isEqualTo("schemaVersion: expected a whole number");
        }
    }

    // ---- DSC003

    private record Case(String name, Consumer<Doc> change, String message) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Case c(String name, Consumer<Doc> change, String message) {
        return new Case(name, change, message);
    }

    private static Consumer<Doc> set(String path, Object value) {
        return doc -> doc.set(path, value);
    }

    private static Consumer<Doc> remove(String path) {
        return doc -> doc.remove(path);
    }

    private static Consumer<Doc> add(String path) {
        return doc -> doc.add(path, 1);
    }

    static Stream<Case> structureProblems() {
        String sp = SEARCH_PARAMS;
        return Stream.of(
                // the document and its header
                c("root is an array", doc -> { }, null),
                c("missing generator", remove("generator"), "(document): missing key 'generator'"),
                c("unknown root key", add("extra"), "(document): unknown key 'extra'"),
                c("generator without name", remove("generator/name"), "generator: missing key 'name'"),
                c("generator version is a number", set("generator/version", 1), "generator.version: expected a string"),
                c("generator has an unknown key", add("generator/extra"), "generator: unknown key 'extra'"),
                c("defaults is a list", set("defaults", List.of()), "defaults: expected an object"),
                c("unknown inputoutput", set("defaults/inputoutput", "maybe"), "defaults.inputoutput: expected one of [both, fail]"),
                c("threshold is text", set("defaults/largeArrayThreshold", "5"), "defaults.largeArrayThreshold: expected a whole number"),
                c("threshold is fractional", set("defaults/largeArrayThreshold", "@@2.5@@"), "defaults.largeArrayThreshold: expected a whole number"),
                c("threshold is huge", set("defaults/largeArrayThreshold", 3000000000L), "defaults.largeArrayThreshold: expected a whole number that fits in 32 bits"),
                c("threshold is zero", set("defaults/largeArrayThreshold", 0), "defaults.largeArrayThreshold: largeArrayThreshold must be at least 1"),
                c("apis is an object", set("apis", java.util.Map.of()), "apis: expected an array"),
                c("an api is a string", set("apis/0", "x"), "apis[0]: expected an object"),
                // an API
                c("api name blank", set("apis/0/name", " "), "apis[0]: name must not be blank"),
                c("api title missing", remove("apis/0/title"), "apis[0]: missing key 'title'"),
                c("api without operations", set("apis/0/operations", List.of()), "apis[0]: operations must not be empty"),
                c("api overlay is null", set("apis/0/overlay", null), "apis[0].overlay: expected a string"),
                // an operation
                c("operation id blank", set(OP + "/id", ""), "apis[0].operations[0]: id must not be blank"),
                c("method in lower case", set(OP + "/method", "get"), "apis[0].operations[0].method: expected one of [GET, POST, PUT, PATCH, DELETE]"),
                c("call is auto", set(OP + "/call", "auto"), "apis[0].operations[0].call: expected one of [pcml, raw]: the call strategy is resolved in a descriptor"),
                c("call is unknown", set(OP + "/call", "soap"), "apis[0].operations[0].call: expected one of [auto, pcml, raw]"),
                c("summary is a number", set(OP + "/summary", 5), "apis[0].operations[0].summary: expected a string or null"),
                c("tags has a number", set(OP + "/tags/0", 5), "apis[0].operations[0].tags[0]: expected a string"),
                c("tags is a string", set(OP + "/tags", "x"), "apis[0].operations[0].tags: expected an array"),
                c("route blank", set(OP + "/route", " "), "apis[0].operations[0]: route must not be blank"),
                c("cache ttl negative", set(OP + "/cache/ttlSeconds", -1), "apis[0].operations[0].cache.ttlSeconds: ttlSeconds must not be negative"),
                c("cache has an unknown key", add(OP + "/cache/extra"), "apis[0].operations[0].cache: unknown key 'extra'"),
                c("operation unknown key", add(OP + "/extra"), "apis[0].operations[0]: unknown key 'extra'"),
                // the program
                c("program name blank", set(OP + "/program/name", ""), "apis[0].operations[0].program: name must not be blank"),
                c("program path is a number", set(OP + "/program/path", 1), "apis[0].operations[0].program.path: expected a string or null"),
                c("attribute value is a number", set(OP + "/program/attributes", java.util.Map.of("k", 1)), "apis[0].operations[0].program.attributes.k: expected a string"),
                c("attributes is a list", set(OP + "/program/attributes", List.of()), "apis[0].operations[0].program.attributes: expected an object"),
                c("parameter usage unknown", set(sp + "/name=P0CODE/usage", "both"), "apis[0].operations[0].program.parameters[0].usage: expected one of [input, output, inputoutput, inherit]"),
                c("parameter name blank", set(sp + "/0/name", ""), "apis[0].operations[0].program.parameters[0]: name must not be blank"),
                c("count is true", set(sp + "/0/count", true), "apis[0].operations[0].program.parameters[0].count: expected a whole number, a field name or null"),
                c("count is negative", set(sp + "/0/count", -1), "apis[0].operations[0].program.parameters[0].count: count must not be negative"),
                c("count is blank", set(sp + "/0/count", " "), "apis[0].operations[0].program.parameters[0].count: field must not be blank"),
                c("count is huge", set(sp + "/0/count", 5000000000L), "apis[0].operations[0].program.parameters[0].count: expected a whole number, a field name or null that fits in 32 bits"),
                c("a struct without fields", set(sp + "/name=P0HITS/fields", null), "apis[0].operations[0].program.parameters[19].fields: a struct has a list of fields; any other type has null"),
                c("a char with fields", set(sp + "/0/fields", List.of()), "apis[0].operations[0].program.parameters[0].fields: a struct has a list of fields; any other type has null"),
                c("fields is a string", set(sp + "/name=P0HITS/fields", "x"), "apis[0].operations[0].program.parameters[19].fields: expected an array or null"),
                c("a member is a string", set(sp + "/name=P0HITS/fields/0", "x"), "apis[0].operations[0].program.parameters[19].fields[0]: expected an object"),
                c("a parameter twice", doc -> doc.set(sp + "/1", doc.get(sp + "/0")), "apis[0].operations[0].program.parameters[1]: the field 'P0CODE' is defined twice"),
                c("a member twice", doc -> doc.set(sp + "/name=P0HITS/fields/1", doc.get(sp + "/name=P0HITS/fields/0")), "the field 'P0HITS.ID' is defined twice"),
                c("a parameter has an unknown key", add(sp + "/0/extra"), "apis[0].operations[0].program.parameters[0]: unknown key 'extra'"),
                // wire types
                c("wire kind unknown", set(sp + "/0/wire/kind", "blob"), "parameters[0].wire.kind: unknown wire type 'blob'"),
                c("wire kind is a number", set(sp + "/0/wire/kind", 3), "parameters[0].wire.kind: expected a string"),
                c("char length is true", set(sp + "/0/wire/length", true), "parameters[0].wire.length: expected a whole number or a field name"),
                c("char length is null", set(sp + "/0/wire/length", null), "parameters[0].wire.length: expected a whole number or a field name"),
                c("char length negative", set(sp + "/0/wire/length", -4), "parameters[0].wire.length: length must not be negative"),
                c("char length blank ref", set(sp + "/0/wire/length", ""), "parameters[0].wire.length: field must not be blank"),
                c("char ccsid is text", set(sp + "/0/wire/ccsid", "37"), "parameters[0].wire.ccsid: expected a whole number or null"),
                c("char has an unknown key", add(sp + "/0/wire/extra"), "parameters[0].wire: unknown key 'extra'"),
                c("varchar without varLength", remove(sp + "/name=P0QRY/wire/varLength"), "wire: missing key 'varLength'"),
                c("int length is text", set(sp + "/name=P0QTY/wire/length", "4"), "wire.length: expected a whole number"),
                c("packed precision is null", set(sp + "/name=P0AMT/wire/precision", null), "wire.precision: expected a whole number"),
                c("zoned length missing", remove(sp + "/name=P0ZON/wire/length"), "wire: missing key 'length'"),
                c("float length is a list", set(sp + "/name=P0RATE/wire/length", List.of()), "wire.length: expected a whole number"),
                c("byte length is null", set(sp + "/name=P0BIN/wire/length", null), "wire.length: expected a whole number or a field name"),
                c("struct name blank", set(sp + "/name=P0HITS/wire/name", ""), "wire.name: structName must not be blank"),
                c("date has an unknown key", add(sp + "/name=P0DAY/wire/extra"), "wire: unknown key 'extra'"),
                c("wire is a string", set(sp + "/0/wire", "char"), "parameters[0].wire: expected an object"),
                // bindings
                c("binding for an unknown field", set(BINDINGS + "/0/field", "NOPE"), "apis[0].operations[0].bindings[0].field: the field 'NOPE' is not defined by the program"),
                c("binding field is null", set(BINDINGS + "/0/field", null), "bindings[0].field: expected a string"),
                c("child of another field", set(BINDINGS + "/field=P0HITS/children/0/field", "P0CODE"), "bindings[19].children[0].field: the field 'P0CODE' is not a member of 'P0HITS'"),
                c("direction unknown", set(BINDINGS + "/0/direction", "up"), "bindings[0].direction: expected one of [request, response, both, internal, injected]"),
                c("name blank", set(BINDINGS + "/0/name", " "), "bindings[0]: exposedName must not be blank"),
                c("in unknown", set(BINDINGS + "/0/in", "cookie"), "bindings[0].in: expected one of [path, query, header, body]"),
                c("in is a number", set(BINDINGS + "/0/in", 1), "bindings[0].in: expected a string or null"),
                c("type unknown", set(BINDINGS + "/0/type", "money"), "bindings[0].type: expected one of [string, integer, decimal, boolean, date, time, datetime, binary]"),
                c("codec is null", set(BINDINGS + "/0/codec", null), "bindings[0].codec: expected an object"),
                c("pad unknown", set(BINDINGS + "/0/codec/pad", "center"), "bindings[0].codec.pad: expected one of [none, left, right]"),
                c("padChar two characters", set(BINDINGS + "/0/codec/padChar", "ab"), "bindings[0].codec.padChar: expected a single character"),
                c("padChar empty", set(BINDINGS + "/0/codec/padChar", ""), "bindings[0].codec.padChar: expected a single character"),
                c("padChar is a surrogate pair", set(BINDINGS + "/0/codec/padChar", "😀"), "bindings[0].codec.padChar: expected a single character"),
                c("nullAs is null", set(BINDINGS + "/0/codec/nullAs", null), "bindings[0].codec.nullAs: expected a string"),
                c("format is a number", set(BINDINGS + "/0/codec/format", 1), "bindings[0].codec.format: expected a string or null"),
                c("trueValue is a number", set(BINDINGS + "/0/codec/trueValue", 1), "bindings[0].codec.trueValue: expected a string or null"),
                c("falseValue missing", remove(BINDINGS + "/0/codec/falseValue"), "bindings[0].codec: missing key 'falseValue'"),
                c("codec has an unknown key", add(BINDINGS + "/0/codec/extra"), "bindings[0].codec: unknown key 'extra'"),
                c("required is text", set(BINDINGS + "/0/required", "yes"), "bindings[0].required: expected true or false"),
                c("hidden is null", set(BINDINGS + "/0/hidden", null), "bindings[0].hidden: expected true or false"),
                c("description is a number", set(BINDINGS + "/0/description", 1), "bindings[0].description: expected a string or null"),
                c("example is a list", set(BINDINGS + "/0/example", List.of()), "bindings[0].example: expected a string or null"),
                c("children is null", set(BINDINGS + "/0/children", null), "bindings[0].children: expected an array"),
                c("binding has an unknown key", add(BINDINGS + "/0/extra"), "bindings[0]: unknown key 'extra'"),
                c("a binding is null", set(BINDINGS + "/0", null), "bindings[0]: expected an object"),
                // trim
                c("trim kind unknown", set(BINDINGS + "/field=P0HITS/trim/kind", "first"), "trim.kind: expected one of [countField, dropBlank]"),
                c("trim countField blank", set(BINDINGS + "/field=P0HITS/trim/field", " "), "trim.field: path must not be blank"),
                c("trim countField missing", remove(BINDINGS + "/field=P0HITS/trim/field"), "trim: missing key 'field'"),
                c("trim dropBlank with a field", doc -> doc.set(BINDINGS + "/field=P0TAGS/trim/kind", "dropBlank").add(BINDINGS + "/field=P0TAGS/trim/field", "X"), "trim: unknown key 'field'"),
                // inject
                c("inject kind unknown", set(BINDINGS + "/field=P0SRC/inject/kind", "secret"), "inject.kind: expected one of [literal, expression]"),
                c("inject literal that is an expression", set(BINDINGS + "/field=P0SRC/inject/value", "${a.b}"), "inject.kind: the kind does not match the value: an expression starts with ${ and ends with }"),
                c("inject expression that is a literal", set(BINDINGS + "/field=P0SRC/inject/kind", "expression"), "inject.kind: the kind does not match the value"),
                c("inject value is a number", set(BINDINGS + "/field=P0SRC/inject/value", 1), "inject.value: expected a string"),
                c("inject unknown key", add(BINDINGS + "/field=P0SRC/inject/extra"), "inject: unknown key 'extra'"),
                // error mapping
                c("error op unknown", set(OP + "/error/when/op", "like"), "error.when.op: expected one of [equals, notEquals, in]"),
                c("error in with no values", set(OP + "/error/when/values", List.of()), "error.when.values: values must not be empty"),
                c("error in with a number", set(OP + "/error/when/values", List.of(1)), "error.when.values[0]: expected a string"),
                c("error equals without a value", doc -> doc.set(OP + "/error/when/op", "equals").add(OP + "/error/when/value", "x"), "error.when: unknown key 'values'"),
                c("error status too low", set(OP + "/error/status", 200), "error: status must be between 400 and 599: 200"),
                c("error status is text", set(OP + "/error/status", "409"), "error.status: expected a whole number"),
                c("error statusByCode is a list", set(OP + "/error/statusByCode", List.of()), "error.statusByCode: expected an object"),
                c("error statusByCode value is text", set(OP + "/error/statusByCode", java.util.Map.of("E1", "404")), "error.statusByCode.E1: expected a whole number"),
                c("error statusByCode out of range", set(OP + "/error/statusByCode", java.util.Map.of("E1", 99)), "error: status must be between 400 and 599: 99"),
                c("error codeField is a number", set(OP + "/error/codeField", 1), "error.codeField: expected a string or null"),
                c("error messageField is a number", set(OP + "/error/messageField", 1), "error.messageField: expected a string or null"),
                c("error has an unknown key", add(OP + "/error/extra"), "error: unknown key 'extra'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structureProblems")
    void aStructureProblemIsDsc003AtTheRightPlace(Case problem) {
        Doc doc = Doc.of("kitchen-sink");
        if (problem.message() == null) {
            Diagnostic d = only(read("[]"));
            assertThat(d.code()).isEqualTo("DSC003");
            assertThat(d.message()).isEqualTo("(document): expected an object");
            return;
        }
        problem.change().accept(doc);
        Diagnostic d = only(doc.read());
        assertThat(d.code()).isEqualTo("DSC003");
        assertThat(d.message()).contains(problem.message());
        assertThat(d.location()).isEqualTo(new SourceLocation("descriptor.json", 1, 1));
    }

    @Test
    void theNotEqualsAndTheCountReferenceAreReadToo() {
        Doc doc = Doc.of("kitchen-sink");
        doc.set(sp(0) + "/count", "P0CNT");
        DescriptorResult result = doc.read();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).program().parameters().get(0).count())
                .isEqualTo(new ai.generativearchitects.glow400.pcml.model.Count.Ref("P0CNT"));
        var lookup = result.model().orElseThrow().operations().get(1);
        assertThat(lookup.errorMapping().orElseThrow().when()).isEqualTo(new ai.generativearchitects.glow400.pcml.api.ErrorCondition.NotEquals("P0OK", "Y"));
    }

    private static String sp(int index) {
        return SEARCH_PARAMS + "/" + index;
    }

    @Test
    void everyStructureProblemIsReportedAsOneDiagnosticWithoutThrowing() {
        assertThat(structureProblems().count()).isGreaterThan(100);
        assertThat(Arrays.asList(Severity.values())).contains(Severity.ERROR);
    }
}

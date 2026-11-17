package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a request: each location, each semantic type, each problem code, each limit; never a value in a problem. */
class RequestTest {

    private static final String SENTINEL = "SENTINEL";

    private static HttpBinding query;
    private static HttpBinding body;
    private static ai.generativearchitects.slay400.pcml.api.ApiOperation bodyOperation;
    private static HttpBinding mixed;

    @BeforeAll
    static void bindings(@TempDir Path dir) throws IOException {
        String queryPcml = Repos.data("P", "type=\"char\" length=\"7\"", "input") + Repos.data("R", "type=\"char\" length=\"7\"", "input")
                + Repos.data("Q", "type=\"char\" length=\"4\"", "input") + Repos.data("D", "type=\"packed\" length=\"7\" precision=\"2\"", "input")
                + Repos.data("B", "type=\"char\" length=\"1\"", "input") + Repos.data("DT", "type=\"char\" length=\"8\"", "input")
                + Repos.data("TM", "type=\"char\" length=\"6\"", "input") + Repos.data("TS", "type=\"char\" length=\"14\"", "input")
                + Repos.data("BIN", "type=\"byte\" length=\"6\"", "input") + Repos.data("H", "type=\"char\" length=\"4\"", "input")
                + Repos.data("F", "type=\"float\" length=\"8\"", "input");
        String queryOverlay = Repos.header("/t/{p}", "GET") + "parameters:\n  P: { name: p, in: path, type: integer }\n"
                + "  R: { name: r, in: query, type: integer, required: true }\n  Q: { name: q, in: query }\n  D: { name: d, in: query }\n"
                + "  B: { name: b, in: query, type: boolean }\n  DT: { name: dt, in: query, type: date, format: yyyyMMdd }\n"
                + "  TM: { name: tm, in: query, type: time, format: HHmmss }\n  TS: { name: ts, in: query, type: datetime, format: yyyyMMddHHmmss }\n"
                + "  BIN: { name: bin, in: query }\n  H: { name: trace, in: header }\n  F: { name: f, in: query }\n";
        query = HttpBinding.of(Repos.operation(Files.createDirectories(dir.resolve("query")), queryPcml, queryOverlay),
                new ReadLimits(200, 4, 12));
        String bodyPcml = Repos.data("S", "type=\"char\" length=\"10\"", "input") + Repos.data("N", "type=\"int\" length=\"4\"", "input")
                + Repos.data("D", "type=\"packed\" length=\"7\" precision=\"2\"", "input") + Repos.data("B", "type=\"char\" length=\"1\"", "input")
                + Repos.data("DT", "type=\"char\" length=\"8\"", "input") + Repos.data("TM", "type=\"char\" length=\"6\"", "input")
                + Repos.data("TS", "type=\"char\" length=\"14\"", "input") + Repos.data("BIN", "type=\"byte\" length=\"6\"", "input")
                + "    <data name=\"ROWS\" type=\"struct\" struct=\"ROW\" count=\"3\" usage=\"input\"/>\n";
        String bodyOverlay = Repos.header("/t", "POST") + "parameters:\n  S: { name: s, required: true }\n  N: { name: n }\n  D: { name: d }\n"
                + "  B: { name: b, type: boolean }\n  DT: { name: dt, type: date, format: yyyyMMdd }\n  TM: { name: tm, type: time, format: HHmmss }\n"
                + "  TS: { name: ts, type: datetime, format: yyyyMMddHHmmss }\n  BIN: { name: bin }\n"
                + "  ROWS: { name: rows, fields: { X: { name: x, required: true }, Y: { name: y }, H: { name: h } } }\n";
        bodyOperation = Repos.operation(Files.createDirectories(dir.resolve("body")), bodyPcml, bodyOverlay);
        body = HttpBinding.of(bodyOperation);
        mixed = HttpBinding.of(Repos.operation(Files.createDirectories(dir.resolve("mixed")), Repos.data("I", "type=\"char\" length=\"7\"", "input")
                + Repos.data("S", "type=\"char\" length=\"10\"", "input"), Repos.header("/m/{id}", "POST")
                + "parameters:\n  I: { name: id, in: path, type: integer }\n  S: { name: s, in: body }\n"));
    }

    // ---- helpers

    private static Map<String, List<String>> map(String... pairs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.computeIfAbsent(pairs[i], k -> new ArrayList<>()).add(pairs[i + 1]);
        }
        return out;
    }

    private static RequestResult read(Map<String, String> path, Map<String, List<String>> query, Map<String, List<String>> headers) {
        return RequestTest.query.readRequest(new RequestParts(path, query, headers, Optional.empty(), new byte[0]));
    }

    private static RequestResult json(String text) {
        return body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> problems(RequestResult result) {
        return result.problems().stream().map(p -> p.code() + " " + p.exposedPath()).collect(Collectors.toList());
    }

    private static Map<String, ApiValue> values(RequestResult result) {
        assertThat(result.problems()).isEmpty();
        return result.values().orElseThrow();
    }

    private static void assertNoValueEchoed(RequestResult result) {
        for (ValueProblem problem : result.problems()) {
            // the name of an unknown property is a name, not a value, and is reported at its path as the codec reports VAL006
            String path = problem.code().equals(RequestProblemCode.UNKNOWN_PROPERTY.code()) ? "" : problem.exposedPath();
            assertThat(problem.message() + problem.fieldPath() + path).doesNotContain(SENTINEL);
        }
    }

    // ---- path, query and header

    @Test
    void everySemanticTypeIsReadFromTheQueryAndThePathAndAHeaderIsRead() {
        RequestResult result = read(Map.of("p", "1042"), map("r", "-7", "q", "ab", "d", "12.50", "b", "true", "dt", "2026-11-17", "tm", "10:20:30.5",
                "ts", "2026-11-17T10:20:30", "bin", "AAECAwQF", "f", "-1.5"), map("TRACE", "xyz"));
        Map<String, ApiValue> values = values(result);
        assertThat(values.keySet()).containsExactly("p", "r", "q", "d", "b", "dt", "tm", "ts", "bin", "trace", "f");
        assertThat(values.get("p")).isEqualTo(new ApiValue.Integer(BigInteger.valueOf(1042)));
        assertThat(values.get("r")).isEqualTo(new ApiValue.Integer(BigInteger.valueOf(-7)));
        assertThat(values.get("q")).isEqualTo(new ApiValue.Text("ab"));
        assertThat(values.get("d")).isEqualTo(new ApiValue.Decimal(new BigDecimal("12.50")));
        assertThat(values.get("b")).isEqualTo(new ApiValue.Bool(true));
        assertThat(values.get("dt")).isEqualTo(new ApiValue.Date(LocalDate.of(2026, 11, 17)));
        assertThat(values.get("tm")).isEqualTo(new ApiValue.Time(LocalTime.of(10, 20, 30, 500_000_000)));
        assertThat(values.get("ts")).isEqualTo(new ApiValue.DateTime(LocalDateTime.of(2026, 11, 17, 10, 20, 30)));
        assertThat(values.get("bin")).isEqualTo(new ApiValue.Binary(new byte[] {0, 1, 2, 3, 4, 5}));
        assertThat(values.get("trace")).isEqualTo(new ApiValue.Text("xyz"));
        assertThat(values.get("f")).isEqualTo(new ApiValue.Decimal(new BigDecimal("-1.5")));
    }

    @Test
    void anOptionalFieldThatWasNotGivenIsAbsentAndARequiredOneIsAProblemWhateverItsLocation() {
        assertThat(values(read(Map.of("p", "1"), map("r", "1"), Map.of())).keySet()).containsExactly("p", "r");
        assertThat(values(read(Map.of("p", "1"), map("r", "1", "q", ""), Map.of())).get("q")).isEqualTo(new ApiValue.Text(""));
        assertThat(problems(read(Map.of(), Map.of(), Map.of()))).containsExactly("REQ005 p", "REQ005 r");
        assertThat(read(Map.of(), Map.of(), Map.of()).values()).isEmpty();
        assertThat(problems(read(Map.of("p", "1"), Map.of("r", List.of()), Map.of()))).containsExactly("REQ005 r");
        assertThat(read(Map.of(), map("r", "1"), Map.of()).problems().get(0).message()).isEqualTo("Required path parameter is missing");
        assertThat(read(Map.of("p", "1"), Map.of(), Map.of()).problems().get(0).message()).isEqualTo("Required query parameter is missing");
    }

    @Test
    void aSingleValueParameterGivenTwiceIsAProblemAndHeaderNamesIgnoreCaseWhileQueryNamesDoNot() {
        assertThat(problems(read(Map.of("p", "1"), map("r", "1", "q", "a", "q", "b"), Map.of()))).containsExactly("REQ009 q");
        assertThat(problems(read(Map.of("p", "1"), map("r", "1"), map("trace", "a", "trace", "b")))).containsExactly("REQ009 trace");
        assertThat(read(Map.of("p", "1"), map("r", "1", "q", "a", "q", "b"), Map.of()).problems().get(0).message()).isEqualTo("Query parameter takes one value and was given 2 times");
        assertThat(values(read(Map.of("p", "1"), map("r", "1"), map("tRaCe", "x"))).get("trace")).isEqualTo(new ApiValue.Text("x"));
        assertThat(problems(read(Map.of("p", "1"), map("R", "1"), Map.of()))).containsExactly("REQ005 r");
        assertThat(values(read(Map.of("p", "1", "extra", "x"), map("r", "1", "other", "x"), map("X-Other", "x"))).keySet()).containsExactly("p", "r");
    }

    @Test
    void textThatDoesNotHaveTheFormOfItsTypeIsAProblemForEachType() {
        String[][] cases = {{"r", "12a"}, {"r", ""}, {"r", "+1"}, {"r", "1.0"}, {"r", " 1"}, {"d", "1.555"}, {"d", "1e2"}, {"d", "+1"}, {"d", "12345678"}, {"d", "."},
                {"b", "TRUE"}, {"b", "1"}, {"b", ""}, {"dt", "2026-02-30"}, {"dt", "2026-1-5"}, {"dt", "20261117"}, {"tm", "10:20"}, {"tm", "25:00:00"},
                {"tm", "10:20:30Z"}, {"ts", "2026-11-17T10:20"}, {"ts", "2026-11-17 10:20:30"}, {"ts", "2026-11-17T10:20:30+01:00"}, {"bin", "QQ"}, {"bin", "!!!!"},
                {"bin", "AAECAwQ=="}, {"f", "1."}, {"f", "abc"}};
        for (String[] c : cases) {
            Map<String, List<String>> query = map("r", "1");
            query.put(c[0], List.of(c[1]));
            RequestResult result = read(Map.of("p", "1"), query, Map.of());
            assertThat(problems(result)).as(c[0] + "=" + c[1]).containsExactly("REQ008 " + c[0]);
            assertThat(result.problems().get(0).message()).startsWith("Query parameter must be ");
        }
        assertThat(problems(read(Map.of("p", "x"), map("r", "1"), Map.of()))).containsExactly("REQ008 p");
        assertThat(read(Map.of("p", "x"), map("r", "1"), Map.of()).problems().get(0).message()).isEqualTo("Path parameter must be a whole number");
        assertThat(problems(read(Map.of("p", "1"), map("r", "1"), map("trace", "a", "other", "b")))).isEmpty();
        assertThat(read(Map.of("p", "1"), map("r", "1", "dt", "2026-02-30"), Map.of()).problems().get(0).message()).isEqualTo("Query parameter must be a date that exists");
        assertThat(read(Map.of("p", "1"), map("r", "1", "bin", "!!!!"), Map.of()).problems().get(0).message()).isEqualTo("Query parameter must be Base64 text");
    }

    @Test
    void theFormsThatAreAcceptedAreExactlyThoseOfTheDocument() {
        for (String[] c : new String[][] {{"r", "0"}, {"r", "-0"}, {"r", "007"}, {"d", "0"}, {"d", "-0"}, {"d", "12345.5"}, {"d", "1.500"}, {"d", "007.5"}, {"b", "false"},
                {"tm", "00:00:00"}, {"tm", "23:59:59.123456789"}, {"ts", "2026-01-31T23:59:59.9"}, {"dt", "2026-02-28"}, {"bin", ""}, {"f", "+1.5"}, {"f", "0.5"}}) {
            Map<String, List<String>> query = map("r", "1");
            query.put(c[0], List.of(c[1]));
            assertThat(read(Map.of("p", "1"), query, Map.of()).problems()).as(c[0] + "=" + c[1]).isEmpty();
        }
        assertThat(values(read(Map.of("p", "1"), map("r", "007", "d", "-0"), Map.of())).get("r")).isEqualTo(new ApiValue.Integer(BigInteger.valueOf(7)));
        assertThat(read(Map.of("p", "1"), map("r", "1", "bin", "AAECAwQ="), Map.of()).problems()).isEmpty();
    }

    @Test
    void aNumberWrittenAsTextHasAnUpperBoundOfOneThousandCharacters() {
        assertThat(read(Map.of("p", "1"), map("r", "9".repeat(1000)), Map.of()).problems()).isEmpty();
        assertThat(problems(read(Map.of("p", "1"), map("r", "9".repeat(1001)), Map.of()))).containsExactly("REQ008 r");
        assertThat(read(Map.of("p", "1"), map("r", "1", "f", "9".repeat(1000)), Map.of()).problems()).isEmpty();
        assertThat(problems(read(Map.of("p", "1"), map("r", "1", "f", "9".repeat(1001)), Map.of()))).containsExactly("REQ008 f");
    }

    @Test
    void aProblemOfAParameterNeverCarriesItsValueAndAllProblemsAreReportedInTheOrderOfTheProgram() {
        RequestResult result = read(Map.of("p", SENTINEL), map("r", SENTINEL, "d", SENTINEL, "dt", SENTINEL, "q", SENTINEL, "q", SENTINEL), map("trace", SENTINEL, "trace", SENTINEL));
        assertThat(problems(result)).containsExactly("REQ008 p", "REQ008 r", "REQ009 q", "REQ008 d", "REQ008 dt", "REQ009 trace");
        assertNoValueEchoed(result);
        assertThat(result.problems()).allSatisfy(p -> assertThat(p.fieldPath()).isNotEmpty());
        assertThat(result.problems().get(0).fieldPath()).isEqualTo("P");
    }

    // ---- the body

    private static final String ROWS = "\"rows\":[{\"x\":\"ab\",\"y\":5,\"h\":\"H\"},{\"x\":\"cd\"}]";

    @Test
    void aBodyIsReadInTheOrderOfTheProgramWithTheTypesOfItsFieldsAndStructsAndArrays() {
        Map<String, ApiValue> values = values(json("{" + ROWS + ",\"bin\":\"AAECAwQF\",\"ts\":\"2026-11-17T10:20:30\",\"tm\":\"10:20:30\",\"dt\":\"2026-11-17\",\"b\":true,"
                + "\"d\":\"12.50\",\"n\":-5,\"s\":\"hello\"}"));
        assertThat(values.keySet()).containsExactly("s", "n", "d", "b", "dt", "tm", "ts", "bin", "rows");
        assertThat(values.get("s")).isEqualTo(new ApiValue.Text("hello"));
        assertThat(values.get("n")).isEqualTo(new ApiValue.Integer(BigInteger.valueOf(-5)));
        assertThat(values.get("d")).isEqualTo(new ApiValue.Decimal(new BigDecimal("12.50")));
        assertThat(values.get("b")).isEqualTo(new ApiValue.Bool(true));
        assertThat(values.get("rows")).isEqualTo(new ApiValue.Arr(List.of(
                new ApiValue.Obj(Map.of("x", new ApiValue.Text("ab"), "y", new ApiValue.Integer(BigInteger.valueOf(5)), "h", new ApiValue.Text("H"))),
                new ApiValue.Obj(Map.of("x", new ApiValue.Text("cd"))))));
        assertThat(((ApiValue.Obj) ((ApiValue.Arr) values.get("rows")).elements().get(0)).members().keySet()).containsExactly("x", "y", "h");
        assertThat(values(json("{\"s\":\"a\",\"rows\":[]}")).get("rows")).isEqualTo(new ApiValue.Arr(List.of()));
        assertThat(values(json("{\"s\":\"a\"}")).keySet()).containsExactly("s");
        assertThat(values(json("{\"s\":\"\\ud800\"}")).get("s")).isEqualTo(new ApiValue.Text("\ud800"));
    }

    @Test
    void aWholeNumberMayBeWrittenWithAFractionOfZeroOrAnExponentLikeInJsonSchemaButNeverBeyondFortyDigits() {
        for (String number : new String[] {"5", "5.0", "5.00", "5e0", "0.5e1", "50e-1", "-0", "0.0", "0e5", "1.5e1"}) {
            assertThat(json("{\"s\":\"a\",\"n\":" + number + "}").problems()).as(number).isEmpty();
        }
        assertThat(values(json("{\"s\":\"a\",\"n\":1E2}")).get("n")).isEqualTo(new ApiValue.Integer(BigInteger.valueOf(100)));
        assertThat(values(json("{\"s\":\"a\",\"n\":-0}")).get("n")).isEqualTo(new ApiValue.Integer(BigInteger.ZERO));
        assertThat(values(json("{\"s\":\"a\",\"n\":12345678901234567890}")).get("n")).isEqualTo(new ApiValue.Integer(new BigInteger("12345678901234567890")));
        for (String number : new String[] {"5.5", "0.1", "1e-1", "1e40", "1e999999999", "1.5e-1"}) {
            assertThat(problems(json("{\"s\":\"a\",\"n\":" + number + "}"))).as(number).containsExactly("REQ007 n");
        }
        assertThat(json("{\"s\":\"a\",\"n\":1e39}").problems()).isEmpty();
        assertThat(problems(json("{\"s\":\"a\",\"n\":" + "1".repeat(41) + "}"))).containsExactly("REQ004 ");
    }

    @Test
    void theContentTypeIsApplicationJsonWithOrWithoutACharsetOfUtf8() {
        for (String type : new String[] {"application/json", "APPLICATION/JSON", "Application/Json", "application/json; charset=utf-8", "application/json;charset=UTF-8",
                "application/json; charset=\"utf-8\"", " application/json ; charset = Utf-8 ", "application/json;CHARSET=utf-8"}) {
            assertThat(body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of(type), "{\"s\":\"a\"}".getBytes(StandardCharsets.UTF_8))).problems()).as(type).isEmpty();
        }
        for (String type : new String[] {"text/plain", "application/json; charset=utf-16", "application/json; charset=latin1", "application/json; foo=bar", "application/json; charset=utf-8; x=y",
                "application/jsonx", "application/json;", "application/json; charset", "application/xml", "", " ", "json", "application/json,", "application/json; charset=\"utf-8"}) {
            RequestResult result = body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of(type), "{\"s\":\"a\"}".getBytes(StandardCharsets.UTF_8)));
            assertThat(problems(result)).as("[" + type + "]").containsExactly("REQ001 ");
        }
        RequestResult none = body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.empty(), "{\"s\":\"a\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(problems(none)).containsExactly("REQ001 ");
        assertThat(none.problems().get(0).message()).isEqualTo("The body needs the content type application/json, in UTF-8");
        assertThat(none.problems().get(0).fieldPath()).isEmpty();
    }

    @Test
    void aBodyWithoutDeclaredFieldsIsAProblemAndAMissingBodyIsNoneUnlessAFieldIsRequired() {
        RequestResult sent = query.readRequest(new RequestParts(Map.of("p", "1"), map("r", "1"), Map.of(), Optional.of("application/json"), "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(problems(sent)).containsExactly("REQ002 ");
        assertThat(sent.problems().get(0).message()).isEqualTo("A body was sent, but the operation takes none");
        assertThat(problems(query.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.empty(), new byte[] {' '})))).containsExactly("REQ002 ", "REQ005 p", "REQ005 r");
        assertThat(problems(json(""))).containsExactly("REQ005 s");
        assertThat(problems(body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.empty(), new byte[0])))).containsExactly("REQ005 s");
    }

    @Test
    void whatIsNotJsonIsOneProblemWithTheLineAndTheColumnAndNeverTheInput() {
        for (String text : new String[] {"{\"s\": " + SENTINEL + "}", "  ", "{\"s\":\"a\"} " + SENTINEL, "{\"s\":\"a\",\"s\":\"b\"}", "{\"s\":\"a\",}", "{\"" + SENTINEL + "\":1,\"" + SENTINEL + "\":2}",
                "\uFEFF{\"s\":\"a\"}", "{\"s\":\"a\"", "{'s':'a'}"}) {
            RequestResult result = json(text);
            assertThat(problems(result)).as(text).containsExactly("REQ003 ");
            assertNoValueEchoed(result);
        }
        assertThat(json("{\"s\":\n  " + SENTINEL + "}").problems().get(0).message()).startsWith("The body is not valid JSON (line 2, column ");
        byte[] bad = {'{', '"', 's', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        RequestResult encoding = body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), bad));
        assertThat(problems(encoding)).containsExactly("REQ003 ");
        assertThat(encoding.problems().get(0).message()).isEqualTo("The body is not valid UTF-8 text");
        byte[] utf16 = "{\"s\":\"a\"}".getBytes(StandardCharsets.UTF_16LE);
        assertThat(problems(body.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), utf16)))).containsExactly("REQ003 ");
    }

    @Test
    void theSizeTheDepthTheNumberOfValuesAndTheLengthOfAStringAndOfANumberAreLimitedAndTheLimitsAreParameters() {
        assertThat(problems(json("{\"s\":\"" + "x".repeat(ReadLimits.defaults().maxBodyBytes()) + "\"}"))).containsExactly("REQ004 ");
        HttpBinding small = HttpBinding.of(bodyOperation, new ReadLimits(40, 3, 6));
        RequestResult tooBig = small.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), ("{\"s\":\"" + "x".repeat(40) + "\"}").getBytes(StandardCharsets.UTF_8)));
        assertThat(problems(tooBig)).containsExactly("REQ004 ");
        assertThat(tooBig.problems().get(0).message()).isEqualTo("The body is larger than 40 bytes");
        assertThat(small.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), "{\"s\":\"a\"}".getBytes(StandardCharsets.UTF_8))).problems()).isEmpty();
        byte[] exactly = ("{\"s\":\"" + "x".repeat(32) + "\"}").getBytes(StandardCharsets.UTF_8); // 40 bytes: the limit itself is allowed
        assertThat(exactly).hasSize(40);
        assertThat(small.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), exactly)).problems()).isEmpty();
        // a property that is named like a path or query field of the operation is still not a property of the body
        assertThat(problems(mixed.readRequest(new RequestParts(Map.of("id", "1"), Map.of(), Map.of(), Optional.of("application/json"), "{\"s\":\"a\",\"id\":5}".getBytes(StandardCharsets.UTF_8)))))
                .containsExactly("REQ006 id");
        assertThat(mixed.readRequest(new RequestParts(Map.of("id", "1"), Map.of(), Map.of(), Optional.of("application/json"), "{\"s\":\"a\"}".getBytes(StandardCharsets.UTF_8))).problems()).isEmpty();
        RequestResult deep = small.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), "{\"s\":[[[1]]]}".getBytes(StandardCharsets.UTF_8)));
        assertThat(problems(deep)).containsExactly("REQ004 ");
        assertThat(deep.problems().get(0).message()).isEqualTo("The body is more complex than the limits allow: at most 3 levels and 6 values, and numbers of at most 40 characters");
        RequestResult many = small.readRequest(new RequestParts(Map.of(), Map.of(), Map.of(), Optional.of("application/json"), "{\"s\":[1,2,3,4,5]}".getBytes(StandardCharsets.UTF_8)));
        assertThat(problems(many)).containsExactly("REQ004 ");
        assertThat(problems(json("{\"s\":\"a\",\"n\":" + "9".repeat(41) + "}"))).containsExactly("REQ004 ");
        assertThat(problems(json("{\"s\":\"a\",\"d\":\"1\",\"n\":1." + "1".repeat(40) + "}"))).containsExactly("REQ004 ");
    }

    @Test
    void aBodyThatIsNotAnObjectAndAFieldThatIsMissingOrUnknownAreProblemsAtTheirPath() {
        for (String text : new String[] {"[]", "\"s\"", "5", "true", "null"}) {
            assertThat(problems(json(text))).as(text).containsExactly("REQ007 ");
        }
        assertThat(json("[]").problems().get(0).message()).isEqualTo("The body must be a JSON object");
        RequestResult missing = json("{\"rows\":[{\"y\":1}]}");
        assertThat(problems(missing)).containsExactly("REQ005 s", "REQ005 rows[0].x");
        assertThat(missing.problems().get(1).fieldPath()).isEqualTo("ROWS[0].X");
        assertThat(missing.problems().get(0).message()).isEqualTo("Required body field is missing");
        RequestResult unknown = json("{\"s\":\"a\",\"zzz\":1,\"rows\":[{\"x\":\"a\",\"yyy\":2}]}");
        assertThat(problems(unknown)).containsExactly("REQ006 rows[0].yyy", "REQ006 zzz");
        assertThat(unknown.problems().get(0).fieldPath()).isEqualTo("ROWS[0]");
        assertThat(unknown.problems().get(1).fieldPath()).isEmpty();
        assertThat(unknown.problems().get(1).message()).isEqualTo("The object has a property that the API does not declare");
    }

    @Test
    void aValueOfTheWrongJsonTypeIsAProblemAtItsPathForEveryTypeAndNullIsNeverAccepted() {
        String[][] cases = {{"s", "5"}, {"s", "true"}, {"s", "null"}, {"s", "[]"}, {"s", "{}"}, {"n", "\"5\""}, {"n", "true"}, {"n", "null"}, {"d", "1.5"}, {"d", "1"}, {"d", "true"},
                {"b", "\"true\""}, {"b", "1"}, {"dt", "20261117"}, {"tm", "[]"}, {"ts", "{}"}, {"bin", "5"}, {"rows", "{}"}, {"rows", "\"x\""}, {"rows", "null"}};
        for (String[] c : cases) {
            RequestResult result = json(c[0].equals("s") ? "{\"s\":" + c[1] + "}" : "{\"s\":\"a\",\"" + c[0] + "\":" + c[1] + "}");
            assertThat(problems(result)).as(c[0] + "=" + c[1]).containsExactly("REQ007 " + c[0]);
        }
        assertThat(json("{\"s\":\"a\",\"d\":1.5}").problems().get(0).message()).isEqualTo("Body field must be a decimal number written as text");
        assertThat(json("{\"s\":5}").problems().get(0).message()).isEqualTo("Body field must be a string");
        assertThat(json("{\"s\":\"a\",\"n\":\"5\"}").problems().get(0).message()).isEqualTo("Body field must be a whole number");
        assertThat(json("{\"s\":\"a\",\"b\":1}").problems().get(0).message()).isEqualTo("Body field must be true or false");
        assertThat(json("{\"s\":\"a\",\"rows\":{}}").problems().get(0).message()).isEqualTo("Body field must be an array");
        assertThat(json("{\"s\":\"a\",\"rows\":[1]}").problems().get(0).message()).isEqualTo("Body field must be an object");
        assertThat(json("{\"s\":\"a\",\"bin\":5}").problems().get(0).message()).isEqualTo("Body field must be text");
        assertThat(json("{\"s\":null}").problems().get(0).message()).isEqualTo("A body value must not be null");
        assertThat(problems(json("{\"s\":\"a\",\"rows\":[null,{\"x\":\"a\",\"y\":\"7\"}]}"))).containsExactly("REQ007 rows[0]", "REQ007 rows[1].y");
        assertThat(json("{\"s\":\"a\",\"rows\":[null,{\"x\":\"a\",\"y\":\"7\"}]}").problems().get(1).fieldPath()).isEqualTo("ROWS[1].Y");
    }

    @Test
    void textInTheBodyThatDoesNotHaveTheFormOfItsTypeIsAProblem() {
        for (String[] c : new String[][] {{"d", "1.555"}, {"d", "abc"}, {"d", "1e2"}, {"dt", "2026-02-30"}, {"dt", "x"}, {"tm", "10:20"}, {"ts", "2026-11-17"}, {"bin", "QQ"}, {"bin", "!!!!"}}) {
            RequestResult result = json("{\"s\":\"a\",\"" + c[0] + "\":\"" + c[1] + "\"}");
            assertThat(problems(result)).as(c[0] + "=" + c[1]).containsExactly("REQ008 " + c[0]);
            assertThat(result.problems().get(0).message()).startsWith("Body field must be ");
        }
        assertThat(json("{\"s\":\"a\",\"dt\":\"2026-02-30\"}").problems().get(0).message()).isEqualTo("Body field must be a date that exists");
    }

    @Test
    void everyProblemOfABodyIsReportedAndNoValueIsEverEchoed() {
        RequestResult result = json("{\"" + SENTINEL + "\":1,\"s\":5,\"n\":\"" + SENTINEL + "\",\"d\":1.5,\"b\":\"" + SENTINEL + "\",\"dt\":\"" + SENTINEL + "\",\"bin\":\"" + SENTINEL
                + "\",\"rows\":[{\"x\":" + SENTINEL.length() + ",\"y\":\"" + SENTINEL + "\"}]}");
        assertThat(problems(result)).containsExactly("REQ007 s", "REQ007 n", "REQ007 d", "REQ007 b", "REQ008 dt", "REQ007 rows[0].x", "REQ007 rows[0].y", "REQ006 " + SENTINEL);
        assertNoValueEchoed(result);
        assertThat(result.values()).isEmpty();
    }

    // ---- the types themselves

    @Test
    void theRequestTypesRefuseWhatIsNotValidAndNeverShowTheirValues() {
        assertThat(ReadLimits.defaults()).isEqualTo(new ReadLimits(1_048_576, 32, 100_000));
        assertThat(new ReadLimits(1, 1, 1).maxValues()).isEqualTo(1);
        assertThat(new ReadLimits(1, 2, 3)).isEqualTo(new ReadLimits(1, 2, 3));
        for (int[] bad : new int[][] {{0, 1, 1}, {1, 0, 1}, {1, 1, 0}, {-1, 1, 1}}) {
            assertThatThrownBy(() -> new ReadLimits(bad[0], bad[1], bad[2])).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> HttpBinding.of(bodyOperation, null)).isInstanceOf(NullPointerException.class).hasMessage("limits");
        assertThatThrownBy(() -> HttpBinding.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> body.readRequest(null)).isInstanceOf(NullPointerException.class).hasMessage("parts");
        byte[] bytes = {1, 2};
        RequestParts parts = new RequestParts(Map.of("p", "1"), map("q", "a"), map("H", "x"), Optional.of("text/plain"), bytes);
        bytes[0] = 9;
        parts.body()[1] = 9;
        assertThat(parts.body()).containsExactly(1, 2);
        assertThat(parts.headers().get("h")).containsExactly("x");
        assertThat(parts.query().get("Q")).isNull();
        assertThat(parts).isEqualTo(new RequestParts(Map.of("p", "1"), map("q", "a"), map("h", "x"), Optional.of("text/plain"), new byte[] {1, 2}))
                .hasSameHashCodeAs(new RequestParts(Map.of("p", "1"), map("q", "a"), map("h", "x"), Optional.of("text/plain"), new byte[] {1, 2}));
        assertThat(parts).isNotEqualTo(new RequestParts(Map.of("p", "1"), map("q", "a"), map("h", "x"), Optional.of("text/plain"), new byte[] {1, 3}))
                .isNotEqualTo(new RequestParts(Map.of("p", "2"), map("q", "a"), map("h", "x"), Optional.of("text/plain"), new byte[] {1, 2}))
                .isNotEqualTo(new RequestParts(Map.of("p", "1"), map("q", "b"), map("h", "x"), Optional.of("text/plain"), new byte[] {1, 2}))
                .isNotEqualTo(new RequestParts(Map.of("p", "1"), map("q", "a"), map("h", "y"), Optional.of("text/plain"), new byte[] {1, 2}))
                .isNotEqualTo(new RequestParts(Map.of("p", "1"), map("q", "a"), map("h", "x"), Optional.empty(), new byte[] {1, 2})).isNotEqualTo("x");
        assertThat(new RequestParts(Map.of("secret", "SENTINEL"), map("q", "SENTINEL"), map("h", "SENTINEL"), Optional.empty(), "SENTINEL".getBytes(StandardCharsets.UTF_8)).toString())
                .isEqualTo("RequestParts[1 path parameters, 1 query parameters, 1 headers, 8 body bytes]");
        assertThatThrownBy(() -> parts.pathParameters().put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parts.query().get("q").add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RequestParts(null, Map.of(), Map.of(), Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestParts(Map.of(), null, Map.of(), Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestParts(Map.of(), Map.of(), null, Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestParts(Map.of(), Map.of(), Map.of(), null, bytes)).isInstanceOf(NullPointerException.class).hasMessage("contentType");
        assertThatThrownBy(() -> new RequestParts(Map.of(), Map.of(), Map.of(), Optional.empty(), null)).isInstanceOf(NullPointerException.class).hasMessage("body");
        assertThatThrownBy(() -> new RequestParts(Map.of(), java.util.Collections.singletonMap("a", null), Map.of(), Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestParts(Map.of(), map("a", "b"), java.util.Collections.singletonMap(null, List.of()), Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestParts(Map.of(), Map.of("a", java.util.Arrays.asList("x", null)), Map.of(), Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestParts(java.util.Collections.singletonMap("a", null), Map.of(), Map.of(), Optional.empty(), bytes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestResult(null, List.of())).isInstanceOf(NullPointerException.class).hasMessage("values");
        assertThatThrownBy(() -> new RequestResult(Optional.of(java.util.Collections.singletonMap("a", null)), List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestResult(Optional.of(java.util.Collections.singletonMap(null, new ApiValue.Bool(true))), List.of())).isInstanceOf(NullPointerException.class);
        assertThat(java.util.Arrays.stream(RequestProblemCode.values()).map(RequestProblemCode::code)).containsExactly("REQ001", "REQ002", "REQ003", "REQ004", "REQ005", "REQ006", "REQ007", "REQ008", "REQ009");
        assertThat(java.util.Arrays.stream(RequestProblemCode.values()).map(RequestProblemCode::summary)).doesNotHaveDuplicates().allSatisfy(s -> assertThat(s).isNotBlank());
    }
}

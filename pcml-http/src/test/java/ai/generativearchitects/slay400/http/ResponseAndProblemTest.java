package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.ErrorOutcome;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Writing a response in the shape of the response schema, and the problem-details body. */
class ResponseAndProblemTest {

    private static HttpBinding response;
    private static HttpBinding noContent;

    @BeforeAll
    static void bindings(@TempDir Path dir) throws Exception {
        String pcml = Repos.data("S", "type=\"char\" length=\"10\"", "output") + Repos.data("N", "type=\"int\" length=\"4\"", "output")
                + Repos.data("D", "type=\"packed\" length=\"7\" precision=\"2\"", "output") + Repos.data("B", "type=\"char\" length=\"1\"", "output")
                + Repos.data("DT", "type=\"char\" length=\"8\"", "output") + Repos.data("TM", "type=\"char\" length=\"6\"", "output")
                + Repos.data("TS", "type=\"char\" length=\"14\"", "output") + Repos.data("BIN", "type=\"byte\" length=\"6\"", "output")
                + Repos.data("T", "type=\"char\" length=\"8\"", "output")
                + "    <data name=\"ROWS\" type=\"struct\" struct=\"ROW\" count=\"3\" usage=\"output\"/>\n";
        String overlay = Repos.header("/t", "POST") + "parameters:\n  S: { name: s }\n  N: { name: n }\n  D: { name: d }\n  B: { name: b, type: boolean }\n"
                + "  DT: { name: dt, type: date, format: yyyyMMdd }\n  TM: { name: tm, type: time, format: HHmmss }\n"
                + "  TS: { name: ts, type: datetime, format: yyyyMMddHHmmss }\n  BIN: { name: bin }\n  T: { name: t, type: decimal }\n"
                + "  ROWS: { name: rows, fields: { X: { name: x }, Y: { name: y }, H: { name: h } } }\n";
        response = HttpBinding.of(Repos.operation(Files.createDirectories(dir.resolve("a")), pcml, overlay));
        noContent = HttpBinding.of(Repos.operation(Files.createDirectories(dir.resolve("b")), Repos.data("A", "type=\"char\" length=\"4\"", "input"),
                Repos.header("/t", "POST") + "parameters:\n  A: { name: a }\n"));
    }

    private static String json(HttpBinding binding, Map<String, ApiValue> values) {
        return new String(binding.writeResponse(values), StandardCharsets.UTF_8);
    }

    private static Map<String, ApiValue> map(Object... pairs) {
        Map<String, ApiValue> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (ApiValue) pairs[i + 1]);
        }
        return out;
    }

    private static ApiValue.Obj row(String x, Integer y) {
        Map<String, ApiValue> members = new LinkedHashMap<>();
        members.put("x", new ApiValue.Text(x));
        if (y != null) {
            members.put("y", new ApiValue.Integer(BigInteger.valueOf(y)));
        }
        return new ApiValue.Obj(members);
    }

    // ---- the response

    @Test
    void aResponseIsCompactUtf8JsonInTheOrderOfTheSchemaWithEveryTypeWrittenAsTheDocumentDeclares() {
        Map<String, ApiValue> values = map("rows", new ApiValue.Arr(List.of(row("ab", 5), row("cd", null))), "bin", new ApiValue.Binary(new byte[] {0, 1, 2, 3, 4, 5}),
                "ts", new ApiValue.DateTime(LocalDateTime.of(2026, 11, 17, 10, 20, 30, 500_000_000)), "tm", new ApiValue.Time(LocalTime.of(10, 20)),
                "dt", new ApiValue.Date(LocalDate.of(2026, 11, 17)), "b", new ApiValue.Bool(true), "d", new ApiValue.Decimal(new BigDecimal("5")),
                "n", new ApiValue.Integer(BigInteger.valueOf(-5)), "s", new ApiValue.Text("héllo"));
        assertThat(json(response, values)).isEqualTo("{\"s\":\"héllo\",\"n\":-5,\"d\":\"5.00\",\"b\":true,\"dt\":\"2026-11-17\",\"tm\":\"10:20:00\","
                + "\"ts\":\"2026-11-17T10:20:30.5\",\"bin\":\"AAECAwQF\",\"rows\":[{\"x\":\"ab\",\"y\":5},{\"x\":\"cd\"}]}");
        assertThat(response.writeResponse(values)).isEqualTo(json(response, values).getBytes(StandardCharsets.UTF_8));
        assertThat(response.writeResponse(map("s", new ApiValue.Text("é")))).isEqualTo("{\"s\":\"é\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aDecimalIsPlainTextWithAtLeastTheScaleOfItsFieldAndNeverRoundedOrInScientificNotation() {
        for (String[] c : new String[][] {{"5", "5.00"}, {"5.1", "5.10"}, {"5.12", "5.12"}, {"5.123", "5.123"}, {"1E+2", "100.00"}, {"-0.5", "-0.50"}, {"0.00", "0.00"}, {"12345.67", "12345.67"}}) {
            assertThat(json(response, map("d", new ApiValue.Decimal(new BigDecimal(c[0]))))).as(c[0]).isEqualTo("{\"d\":\"" + c[1] + "\"}");
        }
        // a decimal held as text keeps the scale it was decoded with
        assertThat(json(response, map("t", new ApiValue.Decimal(new BigDecimal("5"))))).isEqualTo("{\"t\":\"5\"}");
        assertThat(json(response, map("t", new ApiValue.Decimal(new BigDecimal("5.500"))))).isEqualTo("{\"t\":\"5.500\"}");
        assertThat(json(response, map("t", new ApiValue.Decimal(new BigDecimal("1E+3"))))).isEqualTo("{\"t\":\"1000\"}");
    }

    @Test
    void timesAndDateTimesAlwaysHaveSecondsAndOnlyTheFractionTheyNeed() {
        assertThat(json(response, map("tm", new ApiValue.Time(LocalTime.of(0, 0))))).isEqualTo("{\"tm\":\"00:00:00\"}");
        assertThat(json(response, map("tm", new ApiValue.Time(LocalTime.of(1, 2, 3, 120_000_000))))).isEqualTo("{\"tm\":\"01:02:03.12\"}");
        assertThat(json(response, map("tm", new ApiValue.Time(LocalTime.of(1, 2, 3, 1))))).isEqualTo("{\"tm\":\"01:02:03.000000001\"}");
        assertThat(json(response, map("ts", new ApiValue.DateTime(LocalDateTime.of(2026, 1, 2, 3, 4))))).isEqualTo("{\"ts\":\"2026-01-02T03:04:00\"}");
        assertThat(json(response, map("dt", new ApiValue.Date(LocalDate.of(2026, 1, 2))))).isEqualTo("{\"dt\":\"2026-01-02\"}");
        assertThat(json(response, map("bin", new ApiValue.Binary(new byte[] {1})))).isEqualTo("{\"bin\":\"AQ==\"}");
        assertThat(json(response, map("bin", new ApiValue.Binary(new byte[0])))).isEqualTo("{\"bin\":\"\"}");
    }

    @Test
    void aMemberThatIsAbsentIsLeftOutAndAnEmptyResponseIsAnEmptyObject() {
        assertThat(json(response, Map.of())).isEqualTo("{}");
        assertThat(json(response, map("s", new ApiValue.Absent(), "n", new ApiValue.Integer(BigInteger.ONE)))).isEqualTo("{\"n\":1}");
        assertThat(json(response, map("rows", new ApiValue.Arr(List.of())))).isEqualTo("{\"rows\":[]}");
        assertThat(json(response, map("other", new ApiValue.Text("x"), "s", new ApiValue.Text("a")))).isEqualTo("{\"s\":\"a\"}");
        assertThat(json(response, map("rows", new ApiValue.Arr(List.of(new ApiValue.Obj(Map.of("x", new ApiValue.Absent()))))))).isEqualTo("{\"rows\":[{}]}");
        assertThat(json(response, map("s", new ApiValue.Text("\ud800\"\\\n")))).isEqualTo("{\"s\":\"\\ud800\\\"\\\\\\n\"}");
    }

    @Test
    void theSuccessIs200WithJsonOrNoContentWhenTheOperationReturnsNothing() {
        assertThat(response.success()).isEqualTo(new HttpBinding.Success(200, Optional.of("application/json")));
        assertThat(HttpBinding.JSON).isEqualTo("application/json");
        assertThat(noContent.success()).isEqualTo(new HttpBinding.Success(204, Optional.empty()));
        assertThat(noContent.writeResponse(Map.of())).isEmpty();
        assertThat(noContent.writeResponse(map("a", new ApiValue.Text("x")))).isEmpty();
        assertThatThrownBy(() -> new HttpBinding.Success(200, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aValueThatIsNotOfTheTypeOfItsFieldIsAProgrammingErrorAndNullsAreRefused() {
        assertThatThrownBy(() -> response.writeResponse(map("rows", new ApiValue.Text("x")))).isInstanceOf(IllegalArgumentException.class).hasMessage("The field rows is an array");
        assertThatThrownBy(() -> response.writeResponse(map("rows", new ApiValue.Arr(List.of(new ApiValue.Text("x"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The field rows is an object");
        assertThatThrownBy(() -> response.writeResponse(map("s", new ApiValue.Arr(List.of())))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The field s has a value that is not a scalar");
        assertThatThrownBy(() -> response.writeResponse(map("s", new ApiValue.Obj(Map.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response.writeResponse(null)).isInstanceOf(NullPointerException.class).hasMessage("response");
    }

    // ---- problem details

    private static String text(ProblemDetails problem) {
        return new String(problem.toJson(), StandardCharsets.UTF_8);
    }

    @Test
    void theProblemsOfARequestAreOneEntryEachWithTheExposedPathTheCodeAndTheMessage() {
        ProblemDetails problem = ProblemDetails.forRequestProblems(List.of(new ValueProblem("REQ005", "P0ORDN", "orderNumber", "Required query parameter is missing"),
                new ValueProblem("REQ003", "", "", "The body is not valid JSON"), new ValueProblem("VAL003", "P0ROWS[2].QTY", "lines[2].quantity", "Too many digits")));
        assertThat(problem.status()).isEqualTo(400);
        assertThat(text(problem)).isEqualTo("{\"type\":\"https://slay400.dev/problems/request-invalid\",\"title\":\"The request is not valid\",\"status\":400,"
                + "\"detail\":\"The request has 3 problems; see errors.\",\"errors\":[{\"field\":\"orderNumber\",\"code\":\"REQ005\",\"message\":\"Required query parameter is missing\"},"
                + "{\"code\":\"REQ003\",\"message\":\"The body is not valid JSON\"},{\"field\":\"lines[2].quantity\",\"code\":\"VAL003\",\"message\":\"Too many digits\"}]}");
        assertThat(problem.errors()).hasSize(3);
        assertThat(text(ProblemDetails.forRequestProblems(List.of(new ValueProblem("REQ005", "", "a", "m"))))).contains("\"detail\":\"The request has 1 problem; see errors.\"");
        assertThat(text(ProblemDetails.forRequestProblems(List.of()))).isEqualTo("{\"type\":\"https://slay400.dev/problems/request-invalid\",\"title\":\"The request is not valid\","
                + "\"status\":400,\"detail\":\"The request is not valid.\"}");
        assertThatThrownBy(() -> ProblemDetails.forRequestProblems(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBusinessErrorHasTheStatusOfTheMappingTheProgramsCodeAndMessageAndNothingElseOfTheProgram() {
        ProblemDetails problem = ProblemDetails.forBusinessError(new ErrorOutcome(409, Optional.of("E42"), Optional.of("The order is on hold")));
        assertThat(text(problem)).isEqualTo("{\"type\":\"https://slay400.dev/problems/program-error\",\"title\":\"The program reported an error\",\"status\":409,"
                + "\"detail\":\"The order is on hold\",\"code\":\"E42\"}");
        assertThat(problem.code()).contains("E42");
        assertThat(text(ProblemDetails.forBusinessError(new ErrorOutcome(422, Optional.empty(), Optional.empty()))))
                .isEqualTo("{\"type\":\"https://slay400.dev/problems/program-error\",\"title\":\"The program reported an error\",\"status\":422,\"detail\":\"The program reported an error.\"}");
        assertThat(text(ProblemDetails.forBusinessError(new ErrorOutcome(500, Optional.of(" "), Optional.of(" "))))).doesNotContain("\"code\"").contains("\"detail\":\"The program reported an error.\"");
        assertThatThrownBy(() -> ProblemDetails.forBusinessError(null)).isInstanceOf(NullPointerException.class).hasMessage("outcome");
    }

    @Test
    void anyOtherProblemHasTheTypeOfTheTableForItsStatusOrAboutBlankAndTheTableIsOneFixedList() {
        assertThat(text(ProblemDetails.of(404, "Not found", "No such route"))).isEqualTo("{\"type\":\"https://slay400.dev/problems/not-found\",\"title\":\"Not found\",\"status\":404,\"detail\":\"No such route\"}");
        assertThat(text(ProblemDetails.of(418, "Teapot", ""))).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Teapot\",\"status\":418}");
        assertThat(text(ProblemDetails.of(ProblemKind.TIMEOUT, "Slow"))).isEqualTo("{\"type\":\"https://slay400.dev/problems/program-timeout\",\"title\":\"The program did not answer in time\",\"status\":504,\"detail\":\"Slow\"}");
        assertThat(text(ProblemDetails.of(ProblemKind.NOT_FOUND, " "))).doesNotContain("detail");
        for (int status : new int[] {399, 600, 0, -1}) {
            assertThatThrownBy(() -> ProblemDetails.of(status, "t", "d")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ProblemDetails.of(400, "t", "d").status()).isEqualTo(400);
        assertThat(ProblemDetails.of(599, "t", "d").status()).isEqualTo(599);
        assertThatThrownBy(() -> ProblemDetails.of(400, null, "d")).isInstanceOf(NullPointerException.class).hasMessage("title");
        assertThatThrownBy(() -> ProblemDetails.of(400, "t", null)).isInstanceOf(NullPointerException.class).hasMessage("detail");
        assertThatThrownBy(() -> ProblemDetails.of(null, "d")).isInstanceOf(NullPointerException.class).hasMessage("kind");
        assertThat(java.util.Arrays.stream(ProblemKind.values()).map(ProblemKind::status)).containsExactly(400, 401, 403, 404, 405, 422, 500, 502, 503, 504);
        assertThat(java.util.Arrays.stream(ProblemKind.values()).map(ProblemKind::type)).doesNotHaveDuplicates().allSatisfy(t -> assertThat(t).startsWith(ProblemDetails.TYPE_BASE));
        assertThat(java.util.Arrays.stream(ProblemKind.values()).map(ProblemKind::title)).doesNotHaveDuplicates().allSatisfy(t -> assertThat(t).isNotBlank());
        for (ProblemKind kind : ProblemKind.values()) {
            assertThat(ProblemKind.forStatus(kind.status())).contains(kind);
        }
        assertThat(ProblemKind.forStatus(418)).isEmpty();
        assertThat(ProblemDetails.CONTENT_TYPE).isEqualTo("application/problem+json");
    }

    @Test
    void theContextAddsTheTenantTheEnvironmentTheCorrelationIdAndAnInstanceAndAnEmptyTextLeavesAMemberOut() {
        ProblemDetails base = ProblemDetails.of(ProblemKind.NOT_FOUND, "No such route");
        ProblemDetails context = base.withContext("acme", "test", "req 42/é~");
        assertThat(text(context)).isEqualTo("{\"type\":\"https://slay400.dev/problems/not-found\",\"title\":\"Not found\",\"status\":404,\"detail\":\"No such route\","
                + "\"instance\":\"urn:slay400:request:req%2042%2F%C3%A9~\",\"tenant\":\"acme\",\"environment\":\"test\",\"correlationId\":\"req 42/é~\"}");
        assertThat(context.tenant()).contains("acme");
        assertThat(context.environment()).contains("test");
        assertThat(context.correlationId()).contains("req 42/é~");
        assertThat(context.instance()).contains("urn:slay400:request:req%2042%2F%C3%A9~");
        assertThat(text(base.withContext("", "", ""))).isEqualTo(text(base));
        assertThat(base.tenant()).isEmpty();
        assertThat(base.environment()).isEmpty();
        assertThat(base.correlationId()).isEmpty();
        assertThat(base.instance()).isEmpty();
        assertThat(text(base.withContext("acme", "", ""))).contains("\"tenant\":\"acme\"").doesNotContain("environment").doesNotContain("instance");
        assertThat(text(base.withContext("", "test", "abc-DEF_1.2~"))).contains("\"instance\":\"urn:slay400:request:abc-DEF_1.2~\"").contains("\"environment\":\"test\"").doesNotContain("tenant");
        assertThat(base.withContext("a", "b", "c").status()).isEqualTo(404);
        assertThat(base.withContext("a", "b", "c").type()).isEqualTo(ProblemKind.NOT_FOUND.type());
        assertThat(base.withContext("a", "b", "c").title()).isEqualTo("Not found");
        assertThat(base.withContext("a", "b", "c").detail()).contains("No such route");
        assertThat(base.withContext("a", "b", "c").code()).isEmpty();
        assertThat(base.withContext("a", "b", "c").errors()).isEmpty();
        assertThatThrownBy(() -> base.withContext(null, "b", "c")).isInstanceOf(NullPointerException.class).hasMessage("tenant");
        assertThatThrownBy(() -> base.withContext("a", null, "c")).isInstanceOf(NullPointerException.class).hasMessage("environment");
        assertThatThrownBy(() -> base.withContext("a", "b", null)).isInstanceOf(NullPointerException.class).hasMessage("correlationId");
        ProblemDetails withErrors = ProblemDetails.forRequestProblems(List.of(new ValueProblem("REQ005", "A", "a", "m"))).withContext("t", "e", "c");
        assertThat(withErrors.errors()).hasSize(1);
        assertThat(withErrors.toString()).isEqualTo("ProblemDetails[400 https://slay400.dev/problems/request-invalid The request is not valid]");
        assertThat(ProblemDetails.forBusinessError(new ErrorOutcome(409, Optional.of("E1"), Optional.empty())).withContext("t", "e", "c").code()).contains("E1");
    }

    @Test
    void theInstanceKeepsTheUnreservedCharactersAndEncodesTheOnesRightNextToThem() {
        ProblemDetails base = ProblemDetails.of(ProblemKind.NOT_FOUND, "d");
        assertThat(base.withContext("t", "e", "@AZ[`az{/09:").instance()).contains("urn:slay400:request:%40AZ%5B%60az%7B%2F09%3A");
        assertThat(base.withContext("t", "e", "-._~").instance()).contains("urn:slay400:request:-._~");
        assertThat(base.withContext("t", "e", " !\"+,").instance()).contains("urn:slay400:request:%20%21%22%2B%2C");
        assertThat(base.withContext("t", "e", "\u007f\u0080\u00ff").instance()).contains("urn:slay400:request:%7F%C2%80%C3%BF");
    }

    @Test
    void aProblemNeverCarriesWhatTheCallerSentOrTheProgramReturnedBesidesItsOwnCodeAndMessage() {
        String secret = "SENTINEL-4711";
        List<ValueProblem> problems = List.of(new ValueProblem("REQ007", "P0A", "a", "Body field must be a string"));
        assertThat(text(ProblemDetails.forRequestProblems(problems))).doesNotContain(secret);
        assertThat(text(ProblemDetails.forBusinessError(new ErrorOutcome(422, Optional.of("E1"), Optional.of("not found"))))).doesNotContain(secret);
    }
}

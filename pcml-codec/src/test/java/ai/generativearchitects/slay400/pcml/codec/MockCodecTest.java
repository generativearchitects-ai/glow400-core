package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.chr;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static ai.generativearchitects.slay400.pcml.codec.Ops.int4;
import static ai.generativearchitects.slay400.pcml.codec.Ops.struct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.MockCase;
import ai.generativearchitects.slay400.pcml.api.MockDefinition;
import ai.generativearchitects.slay400.pcml.api.MockFailure;
import ai.generativearchitects.slay400.pcml.api.MockValue;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the mock of an operation takes from the codec: checking a definition, matching a case, the values returned. */
class MockCodecTest {

    private static final SourceLocation AT = new SourceLocation("apis/A/mock/op.yaml", 1, 1);
    private static int line;

    private static SourceLocation next() {
        return new SourceLocation("apis/A/mock/op.yaml", ++line, 7);
    }

    static MockValue.Text t(String v) { return new MockValue.Text(v, next()); }
    static MockValue.Whole w(long v) { return new MockValue.Whole(BigInteger.valueOf(v), next()); }
    static MockValue.Bool b(boolean v) { return new MockValue.Bool(v, next()); }
    static MockValue.Real r(String v) { return new MockValue.Real(v, next()); }

    static MockValue.Struct s(Object... pairs) {
        Map<String, MockValue> members = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            members.put((String) pairs[i], (MockValue) pairs[i + 1]);
        }
        return new MockValue.Struct(members, next());
    }

    static MockValue.Array a(MockValue... elements) {
        return new MockValue.Array(List.of(elements), next());
    }

    private static Map<String, MockValue> map(Object... pairs) {
        Map<String, MockValue> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (MockValue) pairs[i + 1]);
        }
        return map;
    }

    static MockCase c(String name, Map<String, MockValue> when, Map<String, MockValue> returns) {
        return new MockCase(Optional.ofNullable(name), when, returns, Optional.empty(), Optional.empty(), next());
    }

    static MockDefinition def(MockCase... cases) {
        return new MockDefinition("op", List.of(cases), AT);
    }

    /** ID input; ECHO inputoutput; STAT output; N inputoutput integer; AMT output decimal; ROWS output array of structs; NOTE inputoutput struct. */
    private static ApiOperation operation() {
        return Ops.op(
                field("ID", chr(10)).dir(ai.generativearchitects.slay400.pcml.api.Direction.REQUEST).usage(Usage.INPUT),
                field("ECHO", chr(4)).both().usage(Usage.INPUT_OUTPUT),
                field("STAT", chr(1)).response().usage(Usage.OUTPUT),
                field("N", int4()).both().usage(Usage.INPUT_OUTPUT),
                field("AMT", new WireType.Packed(7, 2)).response().usage(Usage.OUTPUT),
                struct("ROWS", field("SKU", chr(4)), field("QTY", new WireType.Packed(7, 2))).count(3).response().usage(Usage.OUTPUT),
                struct("NOTE", field("A", chr(2)), field("B", chr(2))).both().usage(Usage.INPUT_OUTPUT),
                field("TAGS", chr(3)).count(2).response().usage(Usage.OUTPUT),
                field("WHEN", new WireType.Date()).type(SemanticType.DATE).both().usage(Usage.INPUT_OUTPUT),
                field("OK", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N").both().usage(Usage.INPUT_OUTPUT),
                field("RAW", new WireType.Byte(new ai.generativearchitects.slay400.pcml.model.Length.Fixed(4))).response().usage(Usage.OUTPUT),
                field("INH", chr(2)).both().usage(Usage.INHERIT),
                field("LIST", chr(2)).count(2).both().usage(Usage.INPUT_OUTPUT));
    }

    private static final MockCodec CODEC = MockCodec.of(operation());

    private static List<MockProblem> check(MockCase... cases) {
        return CODEC.check(def(cases));
    }

    private static List<String> codes(List<MockProblem> problems) {
        return problems.stream().map(MockProblem::code).toList();
    }

    // ---- MCK003: names and sides

    @Test
    void aNameThatIsNotAParameterIsMck003() {
        MockValue first = t("x");
        MockValue second = t("y");
        List<MockProblem> problems = check(c("one", map("NOPE", first), Map.of()), c(null, Map.of(), map("ALSO", second)));
        assertThat(problems).extracting(MockProblem::code, MockProblem::severity, MockProblem::at, MockProblem::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple("MCK003", Severity.ERROR, first.source(), "In case 'one': 'NOPE' is not a parameter of the program PGM"),
                org.assertj.core.groups.Tuple.tuple("MCK003", Severity.ERROR, second.source(), "In case 2: 'ALSO' is not a parameter of the program PGM"));
    }

    @Test
    void whenNamesOnlyWhatTheProgramReceivesAndOnlyScalars() {
        assertThat(check(c("c", map("STAT", t("S")), Map.of())).get(0).message()).isEqualTo("In case 'c': 'STAT' is not received by the program: it is an output parameter");
        assertThat(check(c("c", map("LIST", a()), Map.of())).get(0).message()).isEqualTo("In case 'c': 'LIST' is a struct or an array: 'when' names scalar parameters");
        assertThat(check(c("c", map("NOTE", s()), Map.of())).get(0).message()).isEqualTo("In case 'c': 'NOTE' is a struct or an array: 'when' names scalar parameters");
        assertThat(check(c("c", map("ID", t("1")), Map.of()))).isEmpty();
        assertThat(check(c("c", map("ECHO", t("1")), Map.of()))).isEmpty();
        assertThat(check(c("c", map("INH", t("1")), Map.of()))).isEmpty();
    }

    @Test
    void returnsNamesOnlyWhatTheProgramReturns() {
        assertThat(check(c("c", Map.of(), map("ID", t("1")))).get(0).message()).isEqualTo("In case 'c': 'ID' is not returned by the program: it is an input parameter");
        assertThat(check(c("c", Map.of(), map("STAT", t("S"))))).isEmpty();
        assertThat(check(c("c", Map.of(), map("ECHO", t("1"))))).isEmpty();
        assertThat(check(c("c", Map.of(), map("INH", t("1"))))).isEmpty();
    }

    @Test
    void aMemberTheStructDoesNotHaveIsMck003AtAnyDepth() {
        MockValue bad = t("x");
        List<MockProblem> problems = check(c("c", Map.of(), map("ROWS", a(s("SKU", t("A"), "NOPE", bad)))));
        assertThat(problems).extracting(MockProblem::code, MockProblem::at, MockProblem::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("MCK003", bad.source(), "In case 'c': 'NOPE' is not a member of ROWS"));
    }

    // ---- MCK004: values

    @Test
    void aValueThatTheFieldCanCarryIsAccepted() {
        assertThat(check(c("ok", map("ID", t("1001")), map("STAT", t("S"), "AMT", t("12.50"), "N", w(5), "WHEN", t("2026-01-31"), "OK", b(true),
                "RAW", t("AAECAw=="), "ECHO", t("ab"), "ROWS", a(s("SKU", t("A1"), "QTY", t("2.00")), s("SKU", t("B2"))),
                "NOTE", s("A", t("x")), "TAGS", a(t("a"), t("b")))))).isEmpty();
    }

    @Test
    void aValueOfAnotherKindIsMck004WithTheCodecsCodeAndNeverTheValue() {
        MockValue value = w(5);
        List<MockProblem> problems = check(c("c", Map.of(), map("STAT", value)));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).code()).isEqualTo("MCK004");
        assertThat(problems.get(0).at()).isEqualTo(value.source());
        assertThat(problems.get(0).message()).isEqualTo("In case 'c': the value for STAT cannot be carried by the field (VAL002: The value is not of the kind of the field)");
        assertThat(check(c("c", Map.of(), map("N", t("5")))).get(0).message()).contains("N cannot be carried").contains("VAL002");
        assertThat(check(c("c", Map.of(), map("OK", t("true")))).get(0).message()).contains("VAL002");
        assertThat(check(c("c", Map.of(), map("N", r("5.5")))).get(0).message()).contains("VAL002");
        assertThat(check(c("c", Map.of(), map("STAT", b(true)))).get(0).message()).contains("VAL002");
    }

    @Test
    void aDecimalIsTextAndANumberIsVal002() {
        assertThat(check(c("c", Map.of(), map("AMT", w(5)))).get(0).message()).contains("VAL002");
        assertThat(check(c("c", Map.of(), map("AMT", r("5.5")))).get(0).message()).contains("VAL002");
        assertThat(check(c("c", Map.of(), map("AMT", t("5.5"))))).isEmpty();
        assertThat(check(c("c", Map.of(), map("AMT", t("-5"))))).isEmpty();
    }

    @Test
    void textThatIsNotInTheFormOfItsTypeIsVal004() {
        for (Object[] bad : new Object[][] {{"AMT", "five"}, {"AMT", "5."}, {"AMT", " 5"}, {"WHEN", "31/01/2026"}, {"WHEN", "2026-02-30"}, {"RAW", "not base64!"}}) {
            List<MockProblem> problems = check(c("c", Map.of(), map((String) bad[0], t((String) bad[1]))));
            assertThat(problems).as(String.valueOf(bad[1])).hasSize(1);
            assertThat(problems.get(0).message()).contains("(VAL004: The text is not ").doesNotContain((String) bad[1]);
        }
    }

    @Test
    void theOtherTemporalAndBinaryTypesAreChecked() {
        ApiOperation op = Ops.op(field("T", new WireType.Time()).type(SemanticType.TIME).response().usage(Usage.OUTPUT),
                field("TS", new WireType.Timestamp()).type(SemanticType.DATETIME).response().usage(Usage.OUTPUT),
                field("D", chr(8)).type(SemanticType.DATE).format("yyyyMMdd").response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        assertThat(codec.check(def(c("ok", Map.of(), map("T", t("10:15:30"), "TS", t("2026-01-31T10:15:30"), "D", t("2026-01-31")))))).isEmpty();
        assertThat(codec.check(def(c("bad", Map.of(), map("T", t("25:00"))))).get(0).message()).contains("VAL004");
        assertThat(codec.check(def(c("bad", Map.of(), map("TS", t("2026-01-31"))))).get(0).message()).contains("VAL004");
        assertThat(codec.check(def(c("bad", Map.of(), map("T", w(1))))).get(0).message()).contains("VAL002");
        assertThat(codec.check(def(c("bad", Map.of(), map("TS", b(true))))).get(0).message()).contains("VAL002");
        assertThat(codec.check(def(c("bad", Map.of(), map("D", w(1))))).get(0).message()).contains("VAL002");
    }

    @Test
    void aValueTooLongForItsFieldIsVal003() {
        assertThat(check(c("c", Map.of(), map("STAT", t("TOO LONG")))).get(0).message()).contains("(VAL003: ");
        assertThat(check(c("c", Map.of(), map("N", w(5_000_000_000L)))).get(0).message()).contains("VAL003");
        assertThat(check(c("c", Map.of(), map("AMT", t("1234567.123")))).get(0).message()).contains("VAL003");
    }

    @Test
    void aStructAndAnArrayNeedTheirShape() {
        assertThat(check(c("c", Map.of(), map("ROWS", t("x")))).get(0).message()).contains("ROWS").contains("VAL002: A list was expected");
        assertThat(check(c("c", Map.of(), map("ROWS", a(t("x"))))).get(0).message()).contains("ROWS").contains("VAL002: An object was expected");
        assertThat(check(c("c", Map.of(), map("NOTE", t("x")))).get(0).message()).contains("VAL002: An object was expected");
        assertThat(check(c("c", Map.of(), map("STAT", s()))).get(0).message()).contains("VAL002");
        assertThat(check(c("c", Map.of(), map("STAT", a()))).get(0).message()).contains("VAL002");
        assertThat(check(c("c", Map.of(), map("TAGS", a(a()))))).hasSize(1);
        assertThat(check(c("c", Map.of(), map("TAGS", t("x")))).get(0).message()).contains("VAL002: A list was expected");
    }

    @Test
    void anArrayHasNoMoreElementsThanItsCount() {
        assertThat(check(c("c", Map.of(), map("ROWS", a(s(), s(), s()))))).isEmpty();
        List<MockProblem> problems = check(c("c", Map.of(), map("ROWS", a(s(), s(), s(), s()))));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("VAL005").contains("at most 3");
        assertThat(check(c("c", Map.of(), map("TAGS", a(t("a"), t("b"), t("c"))))).get(0).message()).contains("at most 2");
    }

    @Test
    void anArrayWhoseCountIsAnotherFieldHasNoLimit() {
        ApiOperation op = Ops.op(field("CNT", int4()).response().usage(Usage.OUTPUT), field("DYN", chr(2)).countRef("CNT").response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        assertThat(codec.check(def(c("c", Map.of(), map("DYN", a(t("a"), t("b"), t("c"), t("d")), "CNT", w(4)))))).isEmpty();
        List<WireValue> out = codec.returned(map("DYN", a(t("a"), t("b")), "CNT", w(2)), List.of(new WireValue.Blank(), new WireValue.Blank()));
        assertThat(((WireValue.Array) out.get(1)).elements()).hasSize(2);
    }

    @Test
    void everyProblemOfACaseIsFoundAndTheyComeInFileOrder() {
        List<MockProblem> problems = check(c("c", map("NOPE", t("x"), "ID", t("ok")), map("STAT", w(1), "ROWS", a(s("SKU", t("TOOLONG"))))));
        assertThat(codes(problems)).containsExactly("MCK003", "MCK004", "MCK004");
        assertThat(problems).extracting(p -> p.at().line()).isSorted();
    }

    // ---- MCK005

    @Test
    void aCaseAfterOneWithNoWhenOrTheSameWhenCanNeverMatch() {
        MockCase all = c("all", Map.of(), Map.of());
        MockCase later = c("later", map("ID", t("1")), Map.of());
        List<MockProblem> problems = check(all, later);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).isEqualTo(new MockProblem("MCK005", Severity.WARNING, later.source(),
                "The case 'later' can never match: the case 'all' before it has no 'when'"));
        assertThat(problems.get(0).isError()).isFalse();
        MockCase first = c("first", map("ID", t("1"), "N", w(2)), Map.of());
        MockCase twin = c(null, map("N", w(2), "ID", t("1")), map("STAT", t("S")));
        assertThat(check(first, twin).get(0).message()).isEqualTo("The case 2 can never match: the case 'first' before it has the same 'when'");
        assertThat(check(first, c("other", map("ID", t("2"), "N", w(2)), Map.of()))).isEmpty();
        assertThat(check(first, c("other", map("ID", t("1")), Map.of()))).isEmpty();
        assertThat(check(c("only", Map.of(), Map.of()))).isEmpty();
        assertThat(check(first, c("x", map("ID", t("9")), Map.of()), c("y", Map.of(), Map.of()), c("z", Map.of(), Map.of())).stream().map(MockProblem::message).toList())
                .containsExactly("The case 'z' can never match: the case 'y' before it has no 'when'");
    }

    @Test
    void eachShadowedCaseIsReportedOnceAgainstTheFirstCaseThatShadowsIt() {
        MockCase a1 = c("a", Map.of(), Map.of());
        MockCase b1 = c("b", Map.of(), Map.of());
        MockCase c1 = c("c", Map.of(), Map.of());
        assertThat(check(a1, b1, c1).stream().map(MockProblem::message)).containsExactly(
                "The case 'b' can never match: the case 'a' before it has no 'when'", "The case 'c' can never match: the case 'a' before it has no 'when'");
    }

    @Test
    void aProblemThatIsNotAnErrorDoesNotMakeTheDefinitionUnusable() {
        assertThat(new MockProblem("MCK003", Severity.ERROR, AT, "m").isError()).isTrue();
    }

    @Test
    void theProblemRefusesNulls() {
        assertThatThrownBy(() -> new MockProblem(null, Severity.ERROR, AT, "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockProblem("c", null, AT, "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockProblem("c", Severity.ERROR, null, "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockProblem("c", Severity.ERROR, AT, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MockCodec.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CODEC.check(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CODEC.matches(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CODEC.matches(c("c", Map.of(), Map.of()), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CODEC.returned(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CODEC.returned(Map.of(), null)).isInstanceOf(NullPointerException.class);
    }

    // ---- matching

    private static List<WireValue> input(String id, String echo, long n) {
        OperationCodec codec = OperationCodec.of(operation());
        Map<String, ApiValue> request = new LinkedHashMap<>();
        request.put("id", Ops.text(id));
        request.put("echo", Ops.text(echo));
        request.put("n", Ops.integer(n));
        request.put("when", new ApiValue.Date(java.time.LocalDate.of(2026, 1, 31)));
        request.put("ok", new ApiValue.Bool(true));
        request.put("note", Ops.obj("a", Ops.text("x"), "b", Ops.text("y")));
        request.put("inh", Ops.text("ih"));
        return codec.encode(request, Map.of()).parameters().orElseThrow();
    }

    @Test
    void aCaseWithNoWhenMatchesEverything() {
        assertThat(CODEC.matches(c("c", Map.of(), Map.of()), input("1", "ab", 3))).isTrue();
    }

    @Test
    void everyValueOfWhenMustEqualTheIncomingOne() {
        List<WireValue> in = input("1001", "ab", 3);
        assertThat(CODEC.matches(c("c", map("ID", t("1001")), Map.of()), in)).isTrue();
        assertThat(CODEC.matches(c("c", map("ID", t("1001"), "N", w(3)), Map.of()), in)).isTrue();
        assertThat(CODEC.matches(c("c", map("ID", t("1001"), "N", w(4)), Map.of()), in)).isFalse();
        assertThat(CODEC.matches(c("c", map("ID", t("1002"), "N", w(3)), Map.of()), in)).isFalse();
        assertThat(CODEC.matches(c("c", map("ID", t("100")), Map.of()), in)).isFalse();
    }

    @Test
    void paddingDoesNotMatter() {
        List<WireValue> in = input("1001", "ab", 3);
        assertThat(in.get(0)).isEqualTo(new WireValue.Text("1001      "));
        assertThat(CODEC.matches(c("c", map("ID", t("1001")), Map.of()), in)).isTrue();
        assertThat(CODEC.matches(c("c", map("ECHO", t("ab")), Map.of()), in)).isTrue();
    }

    @Test
    void theScaleOfADecimalDoesNotMatter() {
        ApiOperation op = Ops.op(field("AMT", new WireType.Packed(7, 2)).usage(Usage.INPUT));
        MockCodec codec = MockCodec.of(op);
        List<WireValue> in = List.of(new WireValue.Decimal(new BigDecimal("2.00")));
        assertThat(codec.matches(c("c", map("AMT", t("2")), Map.of()), in)).isTrue();
        assertThat(codec.matches(c("c", map("AMT", t("2.0")), Map.of()), in)).isTrue();
        assertThat(codec.matches(c("c", map("AMT", t("2.01")), Map.of()), in)).isFalse();
    }

    @Test
    void otherTypesAreComparedAsTheAPIWouldCarryThem() {
        ApiOperation op = Ops.op(field("D", new WireType.Date()).type(SemanticType.DATE).usage(Usage.INPUT), field("F", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N").usage(Usage.INPUT));
        MockCodec codec = MockCodec.of(op);
        List<WireValue> in = List.of(new WireValue.Date(java.time.LocalDate.of(2026, 1, 31)), new WireValue.Text("Y"));
        assertThat(codec.matches(c("c", map("D", t("2026-01-31"), "F", b(true)), Map.of()), in)).isTrue();
        assertThat(codec.matches(c("c", map("D", t("2026-01-30"), "F", b(true)), Map.of()), in)).isFalse();
        assertThat(codec.matches(c("c", map("D", t("2026-01-31"), "F", b(false)), Map.of()), in)).isFalse();
    }

    @Test
    void aBlankIncomingValueMatchesNothingThatIsGiven() {
        List<WireValue> in = new ArrayList<>(input("1", "ab", 3));
        in.set(0, new WireValue.Blank());
        assertThat(CODEC.matches(c("c", map("ID", t("1")), Map.of()), in)).isFalse();
    }

    @Test
    void matchingNeverThrowsForWhatCannotBeRead() {
        List<WireValue> in = input("1", "ab", 3);
        assertThat(CODEC.matches(c("c", map("NOPE", t("1")), Map.of()), in)).isFalse();
        assertThat(CODEC.matches(c("c", map("LIST", a()), Map.of()), in)).isFalse();
        assertThat(CODEC.matches(c("c", map("NOTE", s()), Map.of()), in)).isFalse();
        assertThat(CODEC.matches(c("c", map("ID", w(1)), Map.of()), in)).isFalse();
        assertThat(CODEC.matches(c("c", map("ID", t("1")), Map.of()), List.of())).isFalse();
        assertThat(CODEC.matches(c("c", map("ID", t("1")), Map.of()), List.of(new WireValue.Integer(BigInteger.ONE)))).isFalse();
        List<WireValue> wrong = new ArrayList<>(in);
        wrong.set(3, new WireValue.Text("abc"));
        assertThat(CODEC.matches(c("c", map("N", w(3)), Map.of()), wrong)).isFalse();
    }

    // ---- what the program returns

    private static List<WireValue> returned(Map<String, MockValue> returns, List<WireValue> input) {
        return CODEC.returned(returns, input);
    }

    @Test
    void whatIsNotMentionedComesBackAsReceivedOrEmpty() {
        List<WireValue> in = input("1001", "ab", 3);
        List<WireValue> out = returned(Map.of(), in);
        assertThat(out).hasSize(13);
        assertThat(out.get(0)).isEqualTo(in.get(0)); // input
        assertThat(out.get(1)).isEqualTo(in.get(1)); // inputoutput
        assertThat(out.get(2)).isEqualTo(new WireValue.Text(" ")); // output: blank
        assertThat(out.get(3)).isEqualTo(in.get(3));
        assertThat(out.get(4)).isEqualTo(new WireValue.Decimal(new BigDecimal("0.00")));
        assertThat(out.get(11)).isEqualTo(in.get(11)); // inherit: as received
        assertThat(((WireValue.Array) out.get(5)).elements()).hasSize(3);
        assertThat(((WireValue.Struct) ((WireValue.Array) out.get(5)).elements().get(0)).members()).containsExactly(new WireValue.Text("    "), new WireValue.Decimal(new BigDecimal("0.00")));
    }

    @Test
    void whatIsGivenIsReturnedInPlaceOfTheReceivedOrTheEmpty() {
        List<WireValue> in = input("1001", "ab", 3);
        List<WireValue> out = returned(map("STAT", t("S"), "ECHO", t("zz"), "N", w(9), "AMT", t("2.5")), in);
        assertThat(out.get(2)).isEqualTo(new WireValue.Text("S"));
        assertThat(out.get(1)).isEqualTo(new WireValue.Text("zz  "));
        assertThat(out.get(3)).isEqualTo(new WireValue.Integer(BigInteger.valueOf(9)));
        assertThat(out.get(4)).isEqualTo(new WireValue.Decimal(new BigDecimal("2.50")));
        assertThat(out.get(0)).isEqualTo(in.get(0));
    }

    @Test
    void aStructPartlyGivenHasTheRestEmptyEvenWhenItWasReceived() {
        List<WireValue> in = new ArrayList<>(input("1", "ab", 3));
        in.set(6, new WireValue.Struct(List.of(new WireValue.Text("rx"), new WireValue.Text("ry"))));
        List<WireValue> out = returned(map("NOTE", s("B", t("b"))), in);
        assertThat(out.get(6)).isEqualTo(new WireValue.Struct(List.of(new WireValue.Text("  "), new WireValue.Text("b "))));
        assertThat(returned(Map.of(), in).get(6)).isEqualTo(in.get(6));
    }

    @Test
    void anArrayPartlyGivenIsFilledUpToItsCountWithEmptyElementsAndMembersNotGivenAreEmpty() {
        List<WireValue> out = returned(map("ROWS", a(s("SKU", t("A1"), "QTY", t("2.00")), s("SKU", t("B2")))), input("1", "ab", 3));
        List<WireValue> rows = ((WireValue.Array) out.get(5)).elements();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).isEqualTo(new WireValue.Struct(List.of(new WireValue.Text("A1  "), new WireValue.Decimal(new BigDecimal("2.00")))));
        assertThat(rows.get(1)).isEqualTo(new WireValue.Struct(List.of(new WireValue.Text("B2  "), new WireValue.Decimal(new BigDecimal("0.00")))));
        assertThat(rows.get(2)).isEqualTo(new WireValue.Struct(List.of(new WireValue.Text("    "), new WireValue.Decimal(new BigDecimal("0.00")))));
        List<WireValue> tags = ((WireValue.Array) returned(map("TAGS", a(t("x"))), input("1", "ab", 3)).get(7)).elements();
        assertThat(tags).containsExactly(new WireValue.Text("x  "), new WireValue.Text("   "));
        assertThat(((WireValue.Array) returned(map("TAGS", a()), input("1", "ab", 3)).get(7)).elements()).hasSize(2);
    }

    @Test
    void anInputParameterIsNeverReplacedAndAValueThatCannotBeCarriedIsAsIfNotGiven() {
        List<WireValue> in = input("1001", "ab", 3);
        List<WireValue> out = returned(map("ID", t("other"), "STAT", w(1), "N", t("x"), "NOPE", t("x")), in);
        assertThat(out.get(0)).isEqualTo(in.get(0));
        assertThat(out.get(2)).isEqualTo(new WireValue.Text(" "));
        assertThat(out.get(3)).isEqualTo(in.get(3));
    }

    @Test
    void anInputThatIsShorterThanTheParametersGivesEmptyValues() {
        List<WireValue> out = returned(Map.of(), List.of(new WireValue.Text("x")));
        assertThat(out).hasSize(13);
        assertThat(out.get(0)).isEqualTo(new WireValue.Text("x"));
        assertThat(out.get(1)).isEqualTo(new WireValue.Text("    "));
    }

    @Test
    void theOutputIsWhatDecodeAccepts() {
        OperationCodec codec = OperationCodec.of(operation());
        List<WireValue> in = input("1001", "ab", 3);
        DecodeResult empty = codec.decode(returned(Map.of(), in));
        assertThat(empty.problems()).isEmpty();
        DecodeResult given = codec.decode(returned(map("STAT", t("S"), "ROWS", a(s("SKU", t("A1"), "QTY", t("2.00")))), in));
        assertThat(given.problems()).isEmpty();
        assertThat(given.response().orElseThrow()).containsKey("stat");
    }

    @Test
    void aFailingCaseIsStillAValidDefinition() {
        MockCase fails = new MockCase(Optional.of("down"), map("ID", t("1")), Map.of(), Optional.of(100), Optional.of(MockFailure.UNAVAILABLE), next());
        assertThat(CODEC.check(def(fails))).isEmpty();
    }

    // ---- a list and its count field (kickoff 09, review of PR 12)

    /** CNT output int; LST output list of 3 trimmed by CNT; RCNT output int; RLST output list whose count is RCNT; ECHO input. */
    private static ApiOperation countOperation() {
        return Ops.op(
                field("CNT", int4()).response().usage(Usage.OUTPUT),
                field("LST", chr(2)).count(3).trimCount("CNT").response().usage(Usage.OUTPUT),
                field("RCNT", int4()).response().usage(Usage.OUTPUT),
                field("RLST", chr(2)).countRef("RCNT").response().usage(Usage.OUTPUT),
                field("ID", chr(4)).usage(Usage.INPUT));
    }

    private static final ApiOperation COUNTS = countOperation();
    private static final MockCodec COUNT_CODEC = MockCodec.of(COUNTS);

    private static List<WireValue> blankInput() {
        return List.of(new WireValue.Blank(), new WireValue.Blank(), new WireValue.Blank(), new WireValue.Blank(), new WireValue.Text("ab  "));
    }

    private static List<MockProblem> countCheck(Map<String, MockValue> returns) {
        return COUNT_CODEC.check(def(c("c", Map.of(), returns)));
    }

    @Test
    void aListThatIsGivenWithoutItsCountHasTheCountSetToTheNumberOfElements() {
        List<WireValue> out = COUNT_CODEC.returned(map("LST", a(t("a"), t("b"))), blankInput());
        assertThat(out.get(0)).isEqualTo(new WireValue.Integer(BigInteger.TWO));
        assertThat(((WireValue.Array) out.get(1)).elements()).hasSize(3);
        assertThat(out.get(2)).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
        List<WireValue> byRef = COUNT_CODEC.returned(map("RLST", a(t("a"), t("b"), t("c"), t("d"))), blankInput());
        assertThat(byRef.get(2)).isEqualTo(new WireValue.Integer(BigInteger.valueOf(4)));
        assertThat(((WireValue.Array) byRef.get(3)).elements()).hasSize(4);
        assertThat(byRef.get(0)).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
    }

    @Test
    void anEmptyListSetsTheCountToZeroAndNoListLeavesTheCountAlone() {
        List<WireValue> out = COUNT_CODEC.returned(map("LST", a()), blankInput());
        assertThat(out.get(0)).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
        List<WireValue> none = COUNT_CODEC.returned(Map.of(), blankInput());
        assertThat(none.get(0)).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
        assertThat(COUNT_CODEC.returned(map("CNT", w(2)), blankInput()).get(0)).isEqualTo(new WireValue.Integer(BigInteger.TWO));
    }

    @Test
    void theCountIsDecodedToTheElementsGiven() {
        OperationCodec codec = OperationCodec.of(COUNTS);
        DecodeResult decoded = codec.decode(COUNT_CODEC.returned(map("LST", a(t("a"), t("b"))), blankInput()));
        assertThat(decoded.problems()).isEmpty();
        assertThat(((ApiValue.Arr) decoded.response().orElseThrow().get("lst")).elements()).extracting(e -> ((ApiValue.Text) e).value()).containsExactly("a", "b");
        DecodeResult byRef = codec.decode(COUNT_CODEC.returned(map("RLST", a(t("x"), t("y"), t("z"))), blankInput()));
        assertThat(byRef.problems()).isEmpty();
        assertThat(((ApiValue.Arr) byRef.response().orElseThrow().get("rlst")).elements()).hasSize(3);
    }

    @Test
    void aCountThatIsGivenIsUsedAsGiven() {
        List<WireValue> out = COUNT_CODEC.returned(map("LST", a(t("a")), "CNT", w(3)), blankInput());
        assertThat(out.get(0)).isEqualTo(new WireValue.Integer(BigInteger.valueOf(3)));
        DecodeResult decoded = OperationCodec.of(COUNTS).decode(out);
        assertThat(decoded.problems()).isEmpty();
        assertThat(((ApiValue.Arr) decoded.response().orElseThrow().get("lst")).elements()).hasSize(3);
    }

    @Test
    void aCountThatTheListCannotHoldIsMck004() {
        MockValue negative = w(-1);
        List<MockProblem> problems = countCheck(map("LST", a(t("a")), "CNT", negative));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).isEqualTo(new MockProblem("MCK004", Severity.ERROR, negative.source(),
                "In case 'c': the value for CNT is not a count that the list LST can hold: it is negative or more than the 3 elements it holds"));
        assertThat(countCheck(map("LST", a(t("a")), "CNT", w(4)))).extracting(MockProblem::code).containsExactly("MCK004");
        assertThat(countCheck(map("LST", a(t("a")), "CNT", w(3)))).extracting(MockProblem::code).containsExactly("MCK006");
        assertThat(countCheck(map("CNT", w(500)))).extracting(MockProblem::code).containsExactly("MCK004");
        assertThat(countCheck(map("CNT", w(3)))).extracting(MockProblem::code).containsExactly("MCK006");
    }

    @Test
    void aCountOfAListWithoutAFixedSizeCannotBeMoreThanTheElementsGiven() {
        assertThat(countCheck(map("RLST", a(t("a")), "RCNT", w(2))).get(0).message())
                .isEqualTo("In case 'c': the value for RCNT is not a count that the list RLST can hold: it is negative or more than the 1 elements it holds");
        assertThat(countCheck(map("RCNT", w(1))).get(0).code()).isEqualTo("MCK004");
        assertThat(countCheck(map("RCNT", w(0)))).isEmpty();
        assertThat(countCheck(map("RLST", a(t("a"), t("b")), "RCNT", w(2)))).isEmpty();
        assertThat(countCheck(map("RLST", a(t("a"), t("b")), "RCNT", w(1)))).extracting(MockProblem::code).containsExactly("MCK006");
    }

    @Test
    void aCountThatDiffersFromTheElementsGivenButCanBeHeldIsMck006AndOnlyAWarning() {
        MockValue count = w(2);
        List<MockProblem> problems = countCheck(map("LST", a(t("a"), t("b"), t("c")), "CNT", count));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).isEqualTo(new MockProblem("MCK006", Severity.WARNING, count.source(),
                "In case 'c': the count CNT is not the number of elements given for LST (3)"));
        assertThat(problems.get(0).isError()).isFalse();
        assertThat(countCheck(map("LST", a(t("a"), t("b")), "CNT", w(2)))).isEmpty();
        assertThat(countCheck(map("LST", a(), "CNT", w(0)))).isEmpty();
    }

    @Test
    void theNumberOfElementsMustFitTheCountFieldWhenItIsToBeWritten() {
        ApiOperation op = Ops.op(field("CNT", chr(1)).type(SemanticType.INTEGER).response().usage(Usage.OUTPUT),
                field("LST", chr(2)).count(12).trimCount("CNT").response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        MockValue ten = a(t("a"), t("b"), t("c"), t("d"), t("e"), t("f"), t("g"), t("h"), t("i"), t("j"));
        List<MockProblem> problems = codec.check(def(c("c", Map.of(), map("LST", ten))));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).code()).isEqualTo("MCK004");
        assertThat(problems.get(0).at()).isEqualTo(ten.source());
        assertThat(problems.get(0).message()).startsWith("In case 'c': the number of elements given for LST cannot be written to its count field CNT (VAL003: ");
        assertThat(codec.check(def(c("c", Map.of(), map("LST", a(t("a"), t("b")))))).stream().map(MockProblem::code)).isEmpty();
        List<WireValue> out = codec.returned(map("LST", ten), List.of(new WireValue.Blank(), new WireValue.Blank()));
        assertThat(out.get(0)).isEqualTo(new WireValue.Text(" "));
    }

    @Test
    void aCountFieldThatTheProgramDoesNotReturnIsNotSetOrChecked() {
        ApiOperation op = Ops.op(field("CNT", int4()).usage(Usage.INPUT), field("LST", chr(2)).count(3).trimCount("CNT").response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        assertThat(codec.check(def(c("c", Map.of(), map("LST", a(t("a"), t("b"))))))).isEmpty();
        List<WireValue> out = codec.returned(map("LST", a(t("a"))), List.of(new WireValue.Integer(BigInteger.valueOf(9)), new WireValue.Blank()));
        assertThat(out.get(0)).isEqualTo(new WireValue.Integer(BigInteger.valueOf(9)));
        ApiOperation inputList = Ops.op(field("CNT", int4()).response().usage(Usage.OUTPUT), field("LST", chr(2)).count(3).trimCount("CNT").usage(Usage.INPUT));
        assertThat(MockCodec.of(inputList).check(def(c("c", Map.of(), map("CNT", w(7)))))).isEmpty();
    }

    @Test
    void aCountFieldInsideAStructIsNotFollowed() {
        ApiOperation op = Ops.op(struct("HDR", field("N", int4())).response().usage(Usage.OUTPUT),
                field("LST", chr(2)).count(3).trimCount("HDR.N").response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        assertThat(codec.check(def(c("c", Map.of(), map("LST", a(t("a"), t("b"))))))).isEmpty();
        List<WireValue> out = codec.returned(map("LST", a(t("a"), t("b"))), List.of(new WireValue.Blank(), new WireValue.Blank()));
        assertThat(out.get(0)).isInstanceOf(WireValue.Struct.class);
        assertThat(((WireValue.Struct) out.get(0)).members()).containsExactly(new WireValue.Integer(BigInteger.ZERO));
    }

    // ---- kickoff 15, Part B: a count that is a member of the same struct

    /** An array of structs; each LINE has a count N and up to 5 lines T, its own count resolved per element. */
    private static ApiOperation memberCountOperation() {
        return Ops.op(struct("LINE", field("N", int4()), field("T", chr(2)).count(5).trimCount("N"))
                .count(3).response().usage(Usage.OUTPUT));
    }

    private static final ApiOperation MEMBER_COUNTS = memberCountOperation();
    private static final MockCodec MEMBER_COUNT_CODEC = MockCodec.of(MEMBER_COUNTS);

    private static List<MockProblem> memberCountCheck(MockValue lines) {
        return MEMBER_COUNT_CODEC.check(def(c("c", Map.of(), map("LINE", lines))));
    }

    @Test
    void eachElementOfAnArrayOfStructsUsesItsOwnMemberCountToFillOrCheckIt() {
        MockValue lines = a(s("N", w(0), "T", a()), s("N", w(2), "T", a(t("a"), t("b"))),
                s("T", a(t("p"), t("q"), t("r"), t("s"), t("t"))));
        assertThat(memberCountCheck(lines)).isEmpty();
        List<WireValue> out = MEMBER_COUNT_CODEC.returned(map("LINE", lines), List.of(new WireValue.Blank()));
        WireValue.Array array = (WireValue.Array) out.get(0);
        assertThat(((WireValue.Struct) array.elements().get(0)).members().get(0)).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
        assertThat(((WireValue.Struct) array.elements().get(1)).members().get(0)).isEqualTo(new WireValue.Integer(BigInteger.TWO));
        // N was not given for the third element: filled from the number of elements given for T.
        assertThat(((WireValue.Struct) array.elements().get(2)).members().get(0)).isEqualTo(new WireValue.Integer(BigInteger.valueOf(5)));
    }

    @Test
    void aMemberCountThatTheListCannotHoldIsMck004() {
        assertThat(memberCountCheck(a(s("N", w(-1), "T", a(t("a")))))).extracting(MockProblem::code).containsExactly("MCK004");
        assertThat(memberCountCheck(a(s("N", w(6), "T", a(t("a")))))).extracting(MockProblem::code).containsExactly("MCK004");
    }

    @Test
    void aMemberCountThatDiffersFromTheElementsGivenIsMck006() {
        assertThat(memberCountCheck(a(s("N", w(1), "T", a(t("a"), t("b")))))).extracting(MockProblem::code).containsExactly("MCK006");
    }

    @Test
    void aHiddenMemberCountFieldIsStillUsedForFillingAndChecking() {
        ApiOperation op = Ops.op(struct("LINE", field("N", int4()).hidden(), field("T", chr(2)).count(5).trimCount("N"))
                .count(1).response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        MockValue lines = a(s("T", a(t("a"), t("b"))));
        assertThat(codec.check(def(c("c", Map.of(), map("LINE", lines))))).isEmpty();
        List<WireValue> out = codec.returned(map("LINE", lines), List.of(new WireValue.Blank()));
        WireValue.Struct element = (WireValue.Struct) ((WireValue.Array) out.get(0)).elements().get(0);
        assertThat(element.members().get(0)).isEqualTo(new WireValue.Integer(BigInteger.TWO));
        DecodeResult decoded = OperationCodec.of(op).decode(out);
        assertThat(decoded.problems()).isEmpty();
        assertThat(((ApiValue.Obj) ((ApiValue.Arr) decoded.response().orElseThrow().get("line")).elements().get(0)).members()).doesNotContainKey("n");
    }

    @Test
    void aMemberCountFieldIsNotCheckedWhenTheListIsOnTheRequestSide() {
        ApiOperation op = Ops.op(struct("LINE", field("N", int4()), field("T", chr(2)).count(5).trimCount("N").usage(Usage.INPUT))
                .count(1).response().usage(Usage.OUTPUT));
        MockCodec codec = MockCodec.of(op);
        assertThat(codec.check(def(c("c", Map.of(), map("LINE", a(s("N", w(99), "T", a(t("a")))))))))
                .isEmpty();
    }

    @Test
    void aWarningDoesNotMakeADefinitionUnusableButAnErrorDoes() {
        assertThat(new MockProblem("MCK006", Severity.WARNING, AT, "m").isError()).isFalse();
    }
}

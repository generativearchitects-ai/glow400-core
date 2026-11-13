package ai.generativearchitects.glow400.pcml.validation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.codec.ApiValue;
import ai.generativearchitects.glow400.pcml.codec.DecodeResult;
import ai.generativearchitects.glow400.pcml.codec.EncodeResult;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.codec.WireValue;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** For every valid fixture of kickoff 02: load, validate without errors, encode a sample request, decode a sample output. */
class EndToEndTest {

    private static ApiOperation load(String repository, String operationId) {
        ApiRepositoryModel model = Fixtures.model(repository);
        List<Diagnostic> diagnostics = Validator.validate(model);
        assertThat(diagnostics).as("diagnostics of %s", repository).noneMatch(d -> d.severity() == Severity.ERROR);
        return Fixtures.operation(model, operationId);
    }

    private static String pad(String text, int length) {
        return text + " ".repeat(length - text.length());
    }

    private static WireValue.Text t(String text, int length) {
        return new WireValue.Text(pad(text, length));
    }

    private static WireValue.Struct s(WireValue... members) {
        return new WireValue.Struct(List.of(members));
    }

    private static ApiValue.Text text(String s) {
        return new ApiValue.Text(s);
    }

    private static Map<String, ApiValue> map(Object... pairs) {
        Map<String, ApiValue> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (ApiValue) pairs[i + 1]);
        }
        return map;
    }

    private static List<WireValue> encode(OperationCodec codec, Map<String, ApiValue> request, Map<String, String> injected) {
        EncodeResult result = codec.encode(request, injected);
        assertThat(result.problems()).isEmpty();
        return result.parameters().orElseThrow();
    }

    private static Map<String, ApiValue> decode(OperationCodec codec, WireValue... output) {
        DecodeResult result = codec.decode(List.of(output));
        assertThat(result.problems()).isEmpty();
        return result.response().orElseThrow();
    }

    @Test
    void minimal() {
        OperationCodec codec = OperationCodec.of(load("minimal", "ordstatus"));
        assertThat(encode(codec, map("orderNumber", text("1042")), Map.of())).containsExactly(t("1042", 10), t("", 1), t("", 8));
        assertThat(decode(codec, t("1042", 10), t("A", 1), t("20261110", 8))).containsExactly(
                Map.entry("status", text("A")), Map.entry("deliveryDate", new ApiValue.Date(LocalDate.of(2026, 11, 10))));
        assertThat(decode(codec, t("1042", 10), t("A", 1), t("", 8))).containsOnlyKeys("status");
    }

    @Test
    void inputOutput() {
        OperationCodec codec = OperationCodec.of(load("inputoutput", "updqty"));
        assertThat(encode(codec, map("itemId", text("X1"), "quantity", new ApiValue.Integer(BigInteger.valueOf(5))), Map.of()))
                .containsExactly(t("X1", 8), new WireValue.Decimal(new BigDecimal("5")), t("", 1));
        assertThat(decode(codec, t("X1", 8), new WireValue.Decimal(new BigDecimal("6")), t("K", 1))).containsExactly(
                Map.entry("quantity", new ApiValue.Integer(BigInteger.valueOf(6))), Map.entry("status", text("K")));
    }

    @Test
    void defaultsBoth() {
        OperationCodec codec = OperationCodec.of(load("defaults-both", "getbal"));
        assertThat(encode(codec, map("acct", text("A1"), "bal", new ApiValue.Decimal(new BigDecimal("12.5"))), Map.of()))
                .containsExactly(t("A1", 10), new WireValue.Decimal(new BigDecimal("12.50")));
        assertThat(decode(codec, t("A1", 10), new WireValue.Decimal(new BigDecimal("99.99")))).containsExactly(
                Map.entry("acct", text("A1")), Map.entry("bal", new ApiValue.Decimal(new BigDecimal("99.99"))));
    }

    @Test
    void structsWithAnArrayTrimmedByACountAndAHiddenMember() {
        OperationCodec codec = OperationCodec.of(load("structs", "listlines"));
        List<WireValue> parameters = encode(codec, map("orderNumber", text("7")), Map.of());
        assertThat(parameters).hasSize(3);
        assertThat(parameters.get(0)).isEqualTo(t("7", 10));
        assertThat(parameters.get(1)).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
        assertThat(((WireValue.Array) parameters.get(2)).elements()).hasSize(500);

        WireValue first = s(t("ABC", 10), new WireValue.Text("00005"), t("zz", 3), s(new WireValue.Decimal(new BigDecimal("10.50")), t("EUR", 3)));
        WireValue second = s(t("DEF", 10), new WireValue.Text("00012"), t("zz", 3), s(new WireValue.Decimal(new BigDecimal("0.99")), t("USD", 3)));
        WireValue blank = s(t("", 10), t("", 5), t("", 3), s(new WireValue.Decimal(new BigDecimal("0.00")), t("", 3)));
        List<WireValue> rows = new ArrayList<>(List.of(first, second));
        for (int i = 2; i < 500; i++) {
            rows.add(blank);
        }
        Map<String, ApiValue> response = decode(codec, t("7", 10), new WireValue.Integer(BigInteger.TWO), new WireValue.Array(rows));
        assertThat(response).containsOnlyKeys("lines");
        ApiValue.Arr lines = (ApiValue.Arr) response.get("lines");
        assertThat(lines.elements()).hasSize(2);
        assertThat(lines.elements().get(0)).isEqualTo(new ApiValue.Obj(map(
                "itemCode", text("ABC"), "quantity", new ApiValue.Integer(BigInteger.valueOf(5)),
                "price", new ApiValue.Obj(map("amount", new ApiValue.Decimal(new BigDecimal("10.50")), "currency", text("EUR"))))));
        assertThat(((ApiValue.Obj) lines.elements().get(1)).members()).doesNotContainKey("filler");
    }

    @Test
    void aRequestStructAndArrayInTheBody() {
        OperationCodec codec = OperationCodec.of(load("body-struct", "neworder"));
        List<WireValue> parameters = encode(codec, map(
                "header", new ApiValue.Obj(map("customerId", text("C1"))),
                "tags", new ApiValue.Arr(List.of(text("a"), text("b")))), Map.of());
        assertThat(parameters).containsExactly(
                s(t("C1", 8), t("", 40)),
                new WireValue.Array(List.of(t("a", 6), t("b", 6), t("", 6), t("", 6))),
                t("", 10));
        assertThat(decode(codec, s(t("C1", 8), t("", 40)), new WireValue.Array(List.of()), t("ORD1", 10))).containsExactly(Map.entry("orderNumber", text("ORD1")));
        EncodeResult missing = codec.encode(map("header", new ApiValue.Obj(map("note", text("x")))), Map.of());
        assertThat(missing.problems()).extracting(p -> p.code() + " " + p.exposedPath()).containsExactly("VAL001 header.customerId");
    }

    @Test
    void severalOperationsInOneFile() {
        ApiRepositoryModel model = Fixtures.model("operations-list");
        assertThat(Validator.validate(model)).noneMatch(d -> d.severity() == Severity.ERROR);
        OperationCodec status = OperationCodec.of(Fixtures.operation(model, "get-order-status"));
        assertThat(encode(status, map("orderNumber", new ApiValue.Integer(BigInteger.valueOf(1042))), Map.of("P0ENV", "PRD")))
                .containsExactly(t("PRD", 4), t("0000001042", 10), t("", 1));
        assertThat(decode(status, t("PRD", 4), t("0000001042", 10), t("D", 1))).containsExactly(Map.entry("status", text("D")));
        OperationCodec cancel = OperationCodec.of(Fixtures.operation(model, "cancel-order"));
        assertThat(encode(cancel, map("orderNumber", new ApiValue.Integer(BigInteger.valueOf(7)), "reason", text("duplicate")), Map.of("P0ENV", "TST")))
                .containsExactly(t("TST", 4), t("0000000007", 10), t("duplicate", 20));
        assertThat(cancel.encode(map("orderNumber", new ApiValue.Integer(BigInteger.ONE)), Map.of()).problems())
                .extracting(p -> p.code() + " " + p.fieldPath()).containsExactly("VAL001 P0ENV");
    }

    @Test
    void theSameProgramWithADifferentInjectedActionPerOperation() {
        ApiRepositoryModel model = Fixtures.model("action-parameter");
        assertThat(Validator.validate(model)).noneMatch(d -> d.severity() == Severity.ERROR);
        for (String action : List.of("cancel-order", "hold-order")) {
            OperationCodec codec = OperationCodec.of(Fixtures.operation(model, action));
            String literal = action.startsWith("cancel") ? "CANCEL" : "HOLD";
            assertThat(encode(codec, map("orderNumber", text("55")), Map.of("P0ACT", literal))).containsExactly(t(literal, 10), t("55", 10), t("", 20));
            assertThat(decode(codec, t(literal, 10), t("55", 10), t("done", 20))).containsExactly(Map.entry("result", text("done")));
        }
    }

    @Test
    void injectedParametersAndAnErrorMappingHaveNoWarningsAtAll() {
        ApiRepositoryModel model = Fixtures.model("injected");
        assertThat(Validator.validate(model)).isEmpty();
        OperationCodec codec = OperationCodec.of(Fixtures.operation(model, "ordstatus"));
        assertThat(encode(codec, map("orderNumber", text("9")), Map.of("P0ENV", "PRD", "P0TEN", "ACME")))
                .containsExactly(t("PRD", 4), t("ACME", 8), t("9", 10), t("", 1), t("", 2), t("", 8), t("", 60));
        assertThat(decode(codec, t("PRD", 4), t("ACME", 8), t("9", 10), t("S", 1), t("", 2), t("", 8), t("", 60)))
                .containsExactly(Map.entry("status", text("S")));
        // The error condition is true: the result is the error of the mapping, with the status of its code, and no response.
        var failed = codec.decode(List.of(t("PRD", 4), t("ACME", 8), t("9", 10), t("S", 1), t("KO", 2), t("NOTFOUND", 8), t("no such order", 60)));
        assertThat(failed.problems()).isEmpty();
        assertThat(failed.response()).isEmpty();
        assertThat(failed.error().orElseThrow().status()).isEqualTo(404);
        assertThat(failed.error().orElseThrow().code()).contains("NOTFOUND");
        assertThat(failed.error().orElseThrow().message()).contains("no such order");
    }

    @Test
    void mixedCaseRpgNames() {
        OperationCodec codec = OperationCodec.of(load("dclcase", "dclcase"));
        assertThat(encode(codec, map("orderNumber", text("1")), Map.of())).containsExactly(t("1", 10), t("", 8), t("", 30), t("", 2));
        assertThat(decode(codec, t("1", 10), t("20261110", 8), t("Smith", 30), t("x", 2))).containsExactly(
                Map.entry("orderDate", text("20261110")), Map.entry("cust_Name", text("Smith")), Map.entry("leadTrail", text("x")));
    }
}

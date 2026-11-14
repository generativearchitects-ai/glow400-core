package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.int4;
import static ai.generativearchitects.glow400.pcml.codec.Ops.opWith;
import static ai.generativearchitects.glow400.pcml.codec.Ops.struct;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The error mapping on the program's output: when it holds, what the outcome is, and how its configuration is checked. */
class ErrorMappingTest {

    private static ErrorMapping mapping(ErrorCondition when, String code, String message, int status, Map<String, Integer> byCode) {
        return new ErrorMapping(when, Optional.ofNullable(code), Optional.ofNullable(message), status, byCode);
    }

    private static WireValue.Text t(String s) {
        return new WireValue.Text(s);
    }

    /** Fields R (flag, internal), C (code), M (message), OUT (response). */
    private static OperationCodec codec(ErrorMapping mapping) {
        return OperationCodec.of(opWith(mapping, field("R", chr(2)).internal(), field("C", chr(4)).internal(),
                field("M", chr(12)).internal(), field("OUT", chr(4)).response().exposed("out")));
    }

    private static DecodeResult decode(OperationCodec codec, WireValue r, WireValue c, WireValue m, WireValue out) {
        return codec.decode(List.of(r, c, m, out));
    }

    // ---- the condition

    @Test
    void equalsIsExactAndCaseSensitiveAfterPaddingIsRemoved() {
        OperationCodec codec = codec(mapping(new ErrorCondition.Equals("R", "E"), null, null, 422, Map.of()));
        assertThat(decode(codec, t("E "), t(""), t(""), t("ok")).error()).isPresent();
        assertThat(decode(codec, t("e "), t(""), t(""), t("ok")).error()).isEmpty();
        assertThat(decode(codec, t("EE"), t(""), t(""), t("ok")).error()).isEmpty();
        assertThat(decode(codec, t(" E"), t(""), t(""), t("ok")).error()).isEmpty();
        assertThat(decode(codec, t("  "), t(""), t(""), t("ok")).error()).isEmpty();
    }

    @Test
    void notEqualsHoldsForEverythingElseAndTheBlankToo() {
        OperationCodec codec = codec(mapping(new ErrorCondition.NotEquals("R", "0"), null, null, 409, Map.of()));
        assertThat(decode(codec, t("0 "), t(""), t(""), t("ok")).error()).isEmpty();
        assertThat(decode(codec, t("1 "), t(""), t(""), t("ok")).error()).isPresent();
        assertThat(decode(codec, t("  "), t(""), t(""), t("ok")).error().orElseThrow().status()).isEqualTo(409);
    }

    @Test
    void inHoldsForAnyListedValue() {
        OperationCodec codec = codec(mapping(new ErrorCondition.In("R", List.of("A", "BB", "")), null, null, 422, Map.of()));
        assertThat(decode(codec, t("A "), t(""), t(""), t("ok")).error()).isPresent();
        assertThat(decode(codec, t("BB"), t(""), t(""), t("ok")).error()).isPresent();
        assertThat(decode(codec, t("  "), t(""), t(""), t("ok")).error()).isPresent();
        assertThat(decode(codec, t("C "), t(""), t(""), t("ok")).error()).isEmpty();
    }

    @Test
    void whenTheConditionIsFalseTheResponseIsBuiltAsBefore() {
        OperationCodec codec = codec(mapping(new ErrorCondition.Equals("R", "E"), null, null, 422, Map.of()));
        DecodeResult result = decode(codec, t("  "), t(""), t(""), t("ok"));
        assertThat(result.error()).isEmpty();
        assertThat(result.problems()).isEmpty();
        assertThat(result.response().orElseThrow()).containsOnlyKeys("out");
    }

    @Test
    void anOperationWithoutAnErrorMappingNeverHasAnError() {
        OperationCodec codec = OperationCodec.of(Ops.op(field("OUT", chr(4)).response().exposed("out")));
        DecodeResult result = codec.decode(List.of(t("ok")));
        assertThat(result.error()).isEmpty();
        assertThat(result.response()).isPresent();
    }

    // ---- the outcome

    @Test
    void theStatusIsTheOneOfTheCodeOrTheDefault() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("R", "E"), "C", "M", 422, Map.of("NF", 404, "GONE", 410));
        OperationCodec codec = codec(mapping);
        ErrorOutcome known = decode(codec, t("E"), t("NF"), t("Not here"), t("")).error().orElseThrow();
        assertThat(known.status()).isEqualTo(404);
        assertThat(known.code()).contains("NF");
        assertThat(known.message()).contains("Not here");
        assertThat(decode(codec, t("E"), t("GONE"), t("m"), t("")).error().orElseThrow().status()).isEqualTo(410);
        ErrorOutcome unknown = decode(codec, t("E"), t("ZZ"), t("m"), t("")).error().orElseThrow();
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.code()).contains("ZZ");
        assertThat(decode(codec, t("E"), t("nf"), t("m"), t("")).error().orElseThrow().status()).isEqualTo(422);
    }

    @Test
    void noResponseIsBuiltAndNoProblemIsReportedForGarbageInTheResponseFields() {
        OperationCodec codec = codec(mapping(new ErrorCondition.Equals("R", "E"), "C", "M", 422, Map.of()));
        for (WireValue garbage : List.<WireValue>of(new WireValue.Struct(List.of()), new WireValue.Integer(BigInteger.TEN), new WireValue.Blank(),
                new WireValue.Array(List.of()), t("\u0000\u0001"))) {
            DecodeResult result = decode(codec, t("E"), t("X"), t("boom"), garbage);
            assertThat(result.response()).isEmpty();
            assertThat(result.problems()).isEmpty();
            assertThat(result.error()).isPresent();
        }
    }

    @Test
    void aBlankOrUnconfiguredCodeAndMessageAreAbsent() {
        OperationCodec both = codec(mapping(new ErrorCondition.Equals("R", "E"), "C", "M", 500, Map.of("", 404)));
        ErrorOutcome blank = decode(both, t("E"), t("    "), t("            "), t("")).error().orElseThrow();
        assertThat(blank.code()).isEmpty();
        assertThat(blank.message()).isEmpty();
        assertThat(blank.status()).isEqualTo(500);
        OperationCodec none = codec(mapping(new ErrorCondition.Equals("R", "E"), null, null, 503, Map.of()));
        ErrorOutcome unset = decode(none, t("E"), t("CODE"), t("text"), t("")).error().orElseThrow();
        assertThat(unset.code()).isEmpty();
        assertThat(unset.message()).isEmpty();
        assertThat(unset.status()).isEqualTo(503);
        OperationCodec codeOnly = codec(mapping(new ErrorCondition.Equals("R", "E"), "C", null, 422, Map.of()));
        assertThat(decode(codeOnly, t("E"), t("CODE"), t("text"), t("")).error().orElseThrow().message()).isEmpty();
        OperationCodec messageOnly = codec(mapping(new ErrorCondition.Equals("R", "E"), null, "M", 422, Map.of()));
        assertThat(decode(messageOnly, t("E"), t("CODE"), t("text"), t("")).error().orElseThrow().code()).isEmpty();
    }

    @Test
    void thePaddingOfTheCodeAndMessageIsRemovedByTheirOwnSettings() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("R", "E"), "C", "M", 422, Map.of("42", 404));
        OperationCodec codec = OperationCodec.of(opWith(mapping, field("R", chr(2)).internal(), field("C", chr(4)).pad(Pad.LEFT, '0').internal(),
                field("M", chr(8)).pad(Pad.NONE).internal()));
        DecodeResult result = codec.decode(List.of(t("E "), t("0042"), t("hi      ")));
        assertThat(result.error().orElseThrow().code()).contains("42");
        assertThat(result.error().orElseThrow().status()).isEqualTo(404);
        assertThat(result.error().orElseThrow().message()).contains("hi      ");
    }

    @Test
    void theMappingFieldsMayHaveAResponseOrRequestSideToo() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("R", "E"), "C", null, 422, Map.of());
        OperationCodec codec = OperationCodec.of(opWith(mapping, field("R", chr(2)).response().exposed("r"), field("C", chr(4)).both().exposed("c")));
        DecodeResult result = codec.decode(List.of(t("E "), t("X   ")));
        assertThat(result.error().orElseThrow().code()).contains("X");
        assertThat(result.response()).isEmpty();
    }

    @Test
    void numbersAndDatesAreReadAsText() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("N", "5"), "D", "T", 422, Map.of("2026-01-31", 409));
        OperationCodec codec = OperationCodec.of(opWith(mapping, field("N", int4()).internal(),
                field("D", new WireType.Date()).type(ai.generativearchitects.glow400.pcml.api.SemanticType.DATE).internal(),
                field("T", new WireType.Time()).type(ai.generativearchitects.glow400.pcml.api.SemanticType.TIME).internal()));
        DecodeResult result = codec.decode(List.of(new WireValue.Integer(BigInteger.valueOf(5)), new WireValue.Date(LocalDate.of(2026, 1, 31)),
                new WireValue.Time(LocalTime.of(10, 20, 30))));
        assertThat(result.error().orElseThrow().status()).isEqualTo(409);
        assertThat(result.error().orElseThrow().message()).contains("10:20:30");
        OperationCodec decimal = OperationCodec.of(opWith(mapping(new ErrorCondition.Equals("P", "1.50"), "F", "S", 422, Map.of()),
                field("P", new WireType.Packed(5, 2)).internal(), field("F", new WireType.Float(8)).internal(),
                field("S", new WireType.Timestamp()).internal()));
        DecodeResult second = decimal.decode(List.of(new WireValue.Decimal(new BigDecimal("1.50")), new WireValue.Float(2.5),
                new WireValue.Timestamp(LocalDateTime.of(2026, 1, 31, 10, 20, 30))));
        assertThat(second.error().orElseThrow().code()).contains("2.5");
        assertThat(second.error().orElseThrow().message()).contains("2026-01-31T10:20:30");
    }

    @Test
    void aMappingFieldOfTheWrongShapeIsVal101AndGivesNeitherResponseNorError() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("R", "E"), "C", "M", 422, Map.of());
        OperationCodec codec = codec(mapping);
        for (WireValue bad : List.<WireValue>of(new WireValue.Struct(List.of()), new WireValue.Array(List.of()), new WireValue.Bytes(new byte[1]))) {
            DecodeResult condition = decode(codec, bad, t(""), t(""), t("ok"));
            assertThat(condition.response()).isEmpty();
            assertThat(condition.error()).isEmpty();
            assertThat(condition.problems()).extracting(ValueProblem::code).containsExactly("VAL101");
            assertThat(condition.problems().get(0).fieldPath()).isEqualTo("R");
            DecodeResult code = decode(codec, t("E"), bad, t(""), t("ok"));
            assertThat(code.error()).isEmpty();
            assertThat(code.problems()).extracting(ValueProblem::fieldPath).containsExactly("C");
            DecodeResult message = decode(codec, t("E"), t(""), bad, t("ok"));
            assertThat(message.problems()).extracting(ValueProblem::fieldPath).containsExactly("M");
        }
        DecodeResult fine = decode(codec, t("  "), new WireValue.Struct(List.of()), new WireValue.Struct(List.of()), t("ok"));
        assertThat(fine.problems()).isEmpty();
        assertThat(fine.response()).isPresent();
    }

    @Test
    void problemMessagesNeverCarryTheProgramText() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("R", "E"), "C", "M", 422, Map.of());
        DecodeResult result = decode(codec(mapping), new WireValue.Struct(List.of(t("SECRET"))), t("SECRET"), t("SECRET"), t("SECRET"));
        assertThat(result.problems()).isNotEmpty();
        result.problems().forEach(p -> assertThat(p.message() + p.fieldPath() + p.exposedPath()).doesNotContain("SECRET"));
    }

    // ---- configuration

    private static List<ConfigurationIssue> issues(ErrorMapping mapping, Ops.F... fields) {
        ApiOperation operation = opWith(mapping, fields);
        return OperationCodec.check(operation);
    }

    @Test
    void aConditionValueOrAStatusKeyLongerThanItsFieldIsAnIssue() {
        Ops.F r = field("R", chr(2)).internal();
        Ops.F c = field("C", chr(4)).internal();
        assertThat(issues(mapping(new ErrorCondition.Equals("R", "EE"), "C", null, 422, Map.of("ABCD", 404)), r, c)).isEmpty();
        List<ConfigurationIssue> equals = issues(mapping(new ErrorCondition.Equals("R", "EEE"), null, null, 422, Map.of()), r, c);
        assertThat(equals).hasSize(1);
        assertThat(equals.get(0).fieldPath()).isEqualTo("R");
        assertThat(equals.get(0).message()).isEqualTo("a value of the error condition is longer than the field");
        assertThat(issues(mapping(new ErrorCondition.NotEquals("R", "EEE"), null, null, 422, Map.of()), r, c)).hasSize(1);
        assertThat(issues(mapping(new ErrorCondition.In("R", List.of("E", "EEE")), null, null, 422, Map.of()), r, c)).hasSize(1);
        List<ConfigurationIssue> key = issues(mapping(new ErrorCondition.Equals("R", "E"), "C", null, 422, Map.of("ABCDE", 404)), r, c);
        assertThat(key).hasSize(1);
        assertThat(key.get(0).fieldPath()).isEqualTo("C");
        assertThat(key.get(0).message()).isEqualTo("a statusByCode key is longer than the error code field");
        assertThat(issues(mapping(new ErrorCondition.Equals("R", "E"), null, null, 422, Map.of("ABCDE", 404)), r, c)).isEmpty();
        assertThat(issues(mapping(new ErrorCondition.Equals("R", "éé"), null, null, 422, Map.of()), r, c)).isEmpty();
        assertThat(issues(mapping(new ErrorCondition.Equals("R", "😀😀😀"), null, null, 422, Map.of()), r, c)).hasSize(1);
        // a numeric field has no length to compare with
        assertThat(issues(mapping(new ErrorCondition.Equals("N", "12345678901234"), null, null, 422, Map.of()), field("N", int4()).internal())).isEmpty();
    }

    @Test
    void aVarcharWithAFieldLengthIsComparedLikeAChar() {
        Ops.F v = field("V", Ops.varchar(2)).internal();
        assertThat(issues(mapping(new ErrorCondition.Equals("V", "abc"), null, null, 422, Map.of()), v)).hasSize(1);
        assertThat(issues(mapping(new ErrorCondition.Equals("V", "ab"), null, null, 422, Map.of()), v)).isEmpty();
    }

    @Test
    void aFieldThatCannotBeReadAsTextIsAnIssue() {
        WireType.Char refLength = new WireType.Char(new Length.Ref("N"), Optional.empty());
        Ops.F ok = field("OK", chr(2)).internal();
        Ops.F bytes = field("B", new WireType.Byte(new Length.Fixed(2))).internal();
        Ops.F array = field("A", chr(2)).count(3).internal();
        Ops.F struct = struct("S", field("X", chr(2))).internal();
        List<Ops.F> bads = List.of(bytes, array, struct);
        List<String> names = List.of("B", "A", "S");
        for (int i = 0; i < bads.size(); i++) {
            Ops.F bad = bads.get(i);
            String name = names.get(i);
            ErrorMapping inCondition = mapping(new ErrorCondition.Equals(name, "x"), null, null, 422, Map.of());
            assertThat(issues(inCondition, bad, ok)).as(name).hasSize(1);
            assertThat(issues(inCondition, bad, ok).get(0).fieldPath()).isEqualTo(name);
            assertThat(issues(inCondition, bad, ok).get(0).message()).isEqualTo("the field of the error condition is not text or a number: "
                    + "a struct, an array and a byte field cannot be read as text");
            assertThat(issues(mapping(new ErrorCondition.Equals("OK", "x"), name, null, 422, Map.of()), bad, ok)).as(name + " code").hasSize(1);
            assertThat(issues(mapping(new ErrorCondition.Equals("OK", "x"), null, name, 422, Map.of()), bad, ok).get(0).message())
                    .as(name + " message").startsWith("the error message field is not text or a number");
        }
        assertThat(refLength.length()).isInstanceOf(Length.Ref.class);
        assertThat(issues(mapping(new ErrorCondition.Equals("OK", "x"), "OK", "OK", 422, Map.of()), ok)).isEmpty();
    }

    @Test
    void aFieldThatIsNotThereIsLeftToTheLoaderAndTheDecoder() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("NOPE", "x"), "NOPE2", "NOPE3", 422, Map.of("ABCDEFGH", 404));
        assertThat(issues(mapping, field("OK", chr(2)).internal())).isEmpty();
        OperationCodec codec = OperationCodec.of(opWith(mapping, field("OK", chr(2)).internal()));
        DecodeResult result = codec.decode(List.of(t("x")));
        assertThat(result.problems()).extracting(ValueProblem::code).containsExactly("VAL101");
        assertThat(result.problems().get(0).message()).isEqualTo("The program's output has no value for a field the error mapping reads");
    }

    @Test
    void aMemberOfAStructCanBeRead() {
        ErrorMapping mapping = mapping(new ErrorCondition.Equals("S.X", "E"), "S.Y", null, 422, Map.of());
        OperationCodec codec = OperationCodec.of(opWith(mapping, struct("S", field("X", chr(2)), field("Y", chr(4))).internal()));
        DecodeResult result = codec.decode(List.of(new WireValue.Struct(List.of(t("E "), t("CODE")))));
        assertThat(result.error().orElseThrow().code()).contains("CODE");
    }
}

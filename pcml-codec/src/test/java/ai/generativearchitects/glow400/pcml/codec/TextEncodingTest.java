package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.absent;
import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.codec;
import static ai.generativearchitects.glow400.pcml.codec.Ops.decimal;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.integer;
import static ai.generativearchitects.glow400.pcml.codec.Ops.request;
import static ai.generativearchitects.glow400.pcml.codec.Ops.text;
import static ai.generativearchitects.glow400.pcml.codec.Ops.varchar;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Encoding into char and varchar fields, by semantic type, padding and length. */
class TextEncodingTest {

    private static final Map<String, String> NONE = Map.of();

    private static WireValue encodeOne(Ops.F field, ApiValue value) {
        EncodeResult result = codec(field).encode(request(field.exposed, value), NONE);
        assertThat(result.problems()).isEmpty();
        return result.parameters().orElseThrow().get(0);
    }

    private static List<ValueProblem> problemsOf(Ops.F field, ApiValue value) {
        return codec(field).encode(request(field.exposed, value), NONE).problems();
    }

    private static void assertProblem(Ops.F field, ApiValue value, String code) {
        List<ValueProblem> problems = problemsOf(field, value);
        assertThat(problems).extracting(ValueProblem::code).containsExactly(code);
        assertThat(problems.get(0).fieldPath()).isEqualTo(field.name);
        assertThat(problems.get(0).exposedPath()).isEqualTo(field.exposed);
    }

    // ---- padding and length

    @Test
    void padsOnTheRightByDefault() {
        assertThat(encodeOne(field("A", chr(5)), text("ab"))).isEqualTo(new WireValue.Text("ab   "));
    }

    @Test
    void padsOnTheLeftWithThePadCharacter() {
        assertThat(encodeOne(field("A", chr(5)).pad(Pad.LEFT, '0'), text("42"))).isEqualTo(new WireValue.Text("00042"));
    }

    @Test
    void leavesTheTextAsItIsWithoutPadding() {
        assertThat(encodeOne(field("A", chr(5)).pad(Pad.NONE), text("ab"))).isEqualTo(new WireValue.Text("ab"));
    }

    @Test
    void neverPadsAVarchar() {
        assertThat(encodeOne(field("A", varchar(5)), text("ab"))).isEqualTo(new WireValue.Text("ab"));
        assertThat(encodeOne(field("A", varchar(5)).pad(Pad.LEFT, '0'), text("ab"))).isEqualTo(new WireValue.Text("ab"));
    }

    @Test
    void anExactFitIsNotPadded() {
        assertThat(encodeOne(field("A", chr(3)).pad(Pad.LEFT, '0'), text("abc"))).isEqualTo(new WireValue.Text("abc"));
    }

    @Test
    void textLongerThanTheFieldIsVal003AndNeverTruncated() {
        assertProblem(field("A", chr(3)), text("abcd"), "VAL003");
        assertProblem(field("A", varchar(3)), text("abcd"), "VAL003");
        assertProblem(field("A", chr(3)).pad(Pad.NONE), text("abcd"), "VAL003");
    }

    @Test
    void lengthCountsCharactersNotCodeUnits() {
        // U+1D11E is one character and two UTF-16 code units.
        assertThat(encodeOne(field("A", chr(3)), text("a𝄞b"))).isEqualTo(new WireValue.Text("a𝄞b"));
        assertThat(encodeOne(field("A", chr(4)), text("a𝄞b"))).isEqualTo(new WireValue.Text("a𝄞b "));
        assertProblem(field("A", chr(2)), text("a𝄞b"), "VAL003");
    }

    @Test
    void anEmptyTextIsPaddedToTheFullLength() {
        assertThat(encodeOne(field("A", chr(3)), text(""))).isEqualTo(new WireValue.Text("   "));
    }

    // ---- absent

    @Test
    void anAbsentValueIsNullAsPadded() {
        assertThat(encodeOne(field("A", chr(4)), absent())).isEqualTo(new WireValue.Text("    "));
        assertThat(encodeOne(field("A", chr(4)).nullAs("N/A"), absent())).isEqualTo(new WireValue.Text("N/A "));
        assertThat(encodeOne(field("A", chr(4)).nullAs("--").pad(Pad.LEFT, '.'), absent())).isEqualTo(new WireValue.Text("..--"));
        assertThat(encodeOne(field("A", varchar(4)).nullAs("--"), absent())).isEqualTo(new WireValue.Text("--"));
    }

    @Test
    void aMissingRequiredFieldIsVal001() {
        assertProblem(field("A", chr(4)).required(), absent(), "VAL001");
        EncodeResult missing = codec(field("A", chr(4)).required()).encode(Map.of(), NONE);
        assertThat(missing.problems()).extracting(ValueProblem::code).containsExactly("VAL001");
        assertThat(missing.parameters()).isEmpty();
    }

    // ---- semantic types

    @Test
    void stringTakesText() {
        assertProblem(field("A", chr(4)), integer(1), "VAL002");
        assertProblem(field("A", chr(4)), new ApiValue.Bool(true), "VAL002");
    }

    @Test
    void integerBecomesDigits() {
        assertThat(encodeOne(field("A", chr(5)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0'), integer(42))).isEqualTo(new WireValue.Text("00042"));
        assertThat(encodeOne(field("A", chr(5)).type(SemanticType.INTEGER), integer(0))).isEqualTo(new WireValue.Text("0    "));
        assertThat(encodeOne(field("A", chr(20)).type(SemanticType.INTEGER).pad(Pad.NONE),
                new ApiValue.Integer(new BigInteger("12345678901234567890")))).isEqualTo(new WireValue.Text("12345678901234567890"));
    }

    @Test
    void integerRejectsNegativeTooManyDigitsAndOtherKinds() {
        assertProblem(field("A", chr(5)).type(SemanticType.INTEGER), integer(-1), "VAL003");
        assertProblem(field("A", chr(2)).type(SemanticType.INTEGER), integer(100), "VAL003");
        assertProblem(field("A", chr(5)).type(SemanticType.INTEGER), text("1"), "VAL002");
        assertProblem(field("A", chr(5)).type(SemanticType.INTEGER), decimal("1.5"), "VAL002");
    }

    @Test
    void decimalBecomesPlainNotation() {
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.DECIMAL), decimal("12.50"))).isEqualTo(new WireValue.Text("12.50   "));
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.DECIMAL), decimal("1E+3"))).isEqualTo(new WireValue.Text("1000    "));
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.DECIMAL), decimal("0.000"))).isEqualTo(new WireValue.Text("0.000   "));
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.DECIMAL), integer(7))).isEqualTo(new WireValue.Text("7       "));
    }

    @Test
    void decimalRejectsNegativeTooLongAndOtherKinds() {
        assertProblem(field("A", chr(8)).type(SemanticType.DECIMAL), decimal("-0.5"), "VAL003");
        assertProblem(field("A", chr(8)).type(SemanticType.DECIMAL), integer(-5), "VAL003");
        assertProblem(field("A", chr(4)).type(SemanticType.DECIMAL), decimal("12.345"), "VAL003");
        assertProblem(field("A", chr(8)).type(SemanticType.DECIMAL), text("1"), "VAL002");
    }

    @Test
    void booleanUsesTheConfiguredTexts() {
        assertThat(encodeOne(field("A", chr(2)).type(SemanticType.BOOLEAN), new ApiValue.Bool(true))).isEqualTo(new WireValue.Text("1 "));
        assertThat(encodeOne(field("A", chr(2)).type(SemanticType.BOOLEAN), new ApiValue.Bool(false))).isEqualTo(new WireValue.Text("0 "));
        Ops.F yn = field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N");
        assertThat(encodeOne(yn, new ApiValue.Bool(true))).isEqualTo(new WireValue.Text("Y"));
        assertThat(encodeOne(yn, new ApiValue.Bool(false))).isEqualTo(new WireValue.Text("N"));
        assertProblem(yn, text("Y"), "VAL002");
    }

    @Test
    void datesTimesAndDateTimesUseTheFormat() {
        Ops.F date = field("A", chr(8)).type(SemanticType.DATE);
        assertThat(encodeOne(date, new ApiValue.Date(LocalDate.of(2026, 11, 5)))).isEqualTo(new WireValue.Text("20261105"));
        assertThat(encodeOne(field("A", chr(10)).type(SemanticType.DATE), new ApiValue.Date(LocalDate.of(2026, 11, 5))))
                .isEqualTo(new WireValue.Text("2026-11-05"));
        assertThat(encodeOne(field("A", chr(6)).type(SemanticType.TIME), new ApiValue.Time(LocalTime.of(9, 5, 7))))
                .isEqualTo(new WireValue.Text("090507"));
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.TIME), new ApiValue.Time(LocalTime.of(9, 5, 7))))
                .isEqualTo(new WireValue.Text("09:05:07"));
        assertThat(encodeOne(field("A", chr(19)).type(SemanticType.DATETIME).format("yyyy-MM-dd'T'HH:mm:ss"),
                new ApiValue.DateTime(LocalDateTime.of(2026, 11, 5, 9, 5, 7)))).isEqualTo(new WireValue.Text("2026-11-05T09:05:07"));
        assertThat(encodeOne(field("A", chr(12)).type(SemanticType.TIME).format("HH:mm:ss.SSS"),
                new ApiValue.Time(LocalTime.of(9, 5, 7, 120_000_000)))).isEqualTo(new WireValue.Text("09:05:07.120"));
    }

    @Test
    void aDateOrTimeThatTheFormatCannotHoldIsVal003() {
        Ops.F time = field("A", chr(6)).type(SemanticType.TIME);
        assertProblem(time, new ApiValue.Time(LocalTime.of(9, 5, 7, 1)), "VAL003");
        assertProblem(field("A", chr(12)).type(SemanticType.TIME).format("HH:mm:ss.SSS"), new ApiValue.Time(LocalTime.of(9, 5, 7, 1_000)), "VAL003");
        assertProblem(field("A", chr(8)).type(SemanticType.DATE), new ApiValue.Date(LocalDate.of(10000, 1, 1)), "VAL003");
        assertProblem(field("A", chr(8)).type(SemanticType.DATE), new ApiValue.Date(LocalDate.of(-1, 1, 1)), "VAL003");
        assertProblem(field("A", chr(8)).type(SemanticType.DATE), new ApiValue.Time(LocalTime.NOON), "VAL002");
        assertProblem(time, new ApiValue.Date(LocalDate.of(2026, 1, 1)), "VAL002");
        assertProblem(field("A", chr(19)).type(SemanticType.DATETIME).format("yyyy-MM-dd'T'HH:mm:ss"), new ApiValue.Date(LocalDate.of(2026, 1, 1)), "VAL002");
    }

    @Test
    void anAbsentDateIsNullAs() {
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.DATE), absent())).isEqualTo(new WireValue.Text("        "));
        assertThat(encodeOne(field("A", chr(8)).type(SemanticType.DATE).nullAs("00000000"), absent())).isEqualTo(new WireValue.Text("00000000"));
    }

    // ---- messages and several problems

    @Test
    void messagesNeverContainTheValue() {
        String secret = "SECRET-VALUE-12345";
        for (ValueProblem problem : problemsOf(field("A", chr(3)), text(secret))) {
            assertThat(problem.message()).doesNotContain(secret).doesNotContain("SECRET");
            assertThat(problem.toString()).doesNotContain("SECRET");
        }
        for (ValueProblem problem : problemsOf(field("A", chr(5)).type(SemanticType.INTEGER), text(secret))) {
            assertThat(problem.message()).doesNotContain("SECRET");
        }
    }

    @Test
    void everyProblemIsReportedNotOnlyTheFirst() {
        EncodeResult result = codec(field("A", chr(2)), field("B", chr(2)).required(), field("C", chr(2)).type(SemanticType.INTEGER))
                .encode(request("a", text("too long"), "c", text("x"), "zzz", text("?")), NONE);
        assertThat(result.parameters()).isEmpty();
        assertThat(result.problems()).extracting(ValueProblem::code, ValueProblem::exposedPath)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("VAL006", "zzz"),
                        org.assertj.core.groups.Tuple.tuple("VAL003", "a"),
                        org.assertj.core.groups.Tuple.tuple("VAL001", "b"),
                        org.assertj.core.groups.Tuple.tuple("VAL002", "c"));
    }

    @Test
    void anUnknownFieldIsVal006AndOutputFieldsAreUnknownInARequest() {
        EncodeResult result = codec(field("A", chr(2)), field("OUT", chr(2)).response())
                .encode(request("a", text("x"), "out", text("y"), "other", text("z")), NONE);
        assertThat(result.problems()).extracting(ValueProblem::code, ValueProblem::exposedPath)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("VAL006", "out"), org.assertj.core.groups.Tuple.tuple("VAL006", "other"));
        assertThat(result.problems().get(0).fieldPath()).isEmpty();
    }
}

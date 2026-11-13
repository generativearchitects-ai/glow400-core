package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.codec;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.varchar;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Decoding one returned value, by wire type and semantic type. */
class ScalarDecodingTest {

    /** The decoded value of a single response field, or empty if the field is absent from the response. */
    private static Optional<ApiValue> decode(Ops.F f, WireValue wire) {
        DecodeResult result = codec(f.response()).decode(List.of(wire));
        assertThat(result.problems()).as("problems").isEmpty();
        return Optional.ofNullable(result.response().orElseThrow().get(f.exposed));
    }

    private static List<String> problems(Ops.F f, WireValue wire) {
        return codec(f.response()).decode(List.of(wire)).problems().stream().map(ValueProblem::code).toList();
    }

    private static WireValue.Text t(String s) {
        return new WireValue.Text(s);
    }

    // ---- padding

    @Test
    void padRightRemovesTrailingPadCharacters() {
        assertThat(decode(field("A", chr(5)), t("ab   "))).contains(new ApiValue.Text("ab"));
        assertThat(decode(field("A", chr(5)), t("   ab"))).contains(new ApiValue.Text("   ab"));
        assertThat(decode(field("A", chr(5)).pad(Pad.RIGHT, '*'), t("ab***"))).contains(new ApiValue.Text("ab"));
        assertThat(decode(field("A", chr(3)), t("   "))).contains(new ApiValue.Text(""));
        assertThat(decode(field("A", chr(5)), t("a b  "))).contains(new ApiValue.Text("a b"));
    }

    @Test
    void padLeftRemovesLeadingPadCharacters() {
        assertThat(decode(field("A", chr(5)).pad(Pad.LEFT, '0'), t("00ab0"))).contains(new ApiValue.Text("ab0"));
        assertThat(decode(field("A", chr(5)).pad(Pad.LEFT, ' '), t("  ab "))).contains(new ApiValue.Text("ab "));
        assertThat(decode(field("A", chr(3)).pad(Pad.LEFT, '0'), t("000"))).contains(new ApiValue.Text(""));
    }

    @Test
    void noPaddingAndVarcharAreLeftAsTheyAre() {
        assertThat(decode(field("A", chr(5)).pad(Pad.NONE), t("ab   "))).contains(new ApiValue.Text("ab   "));
        assertThat(decode(field("A", varchar(5)), t("ab   "))).contains(new ApiValue.Text("ab   "));
        assertThat(decode(field("A", varchar(5)).pad(Pad.NONE, ' '), t("  ab"))).contains(new ApiValue.Text("  ab"));
    }

    @Test
    void aStringNeverDecodesToAbsent() {
        assertThat(decode(field("A", chr(3)), t("   "))).contains(new ApiValue.Text(""));
        assertThat(decode(field("A", varchar(3)), t(""))).contains(new ApiValue.Text(""));
        assertThat(decode(field("A", chr(3)).nullAs("N/A"), t("N/A"))).contains(new ApiValue.Text("N/A"));
    }

    // ---- integer and decimal

    @Test
    void integersAreReadFromDigits() {
        Ops.F f = field("A", chr(5)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0');
        assertThat(decode(f, t("00042"))).contains(new ApiValue.Integer(BigInteger.valueOf(42)));
        assertThat(decode(f, t("00000"))).contains(new ApiValue.Integer(BigInteger.ZERO));
        assertThat(decode(f, t("12345"))).contains(new ApiValue.Integer(BigInteger.valueOf(12345)));
        assertThat(decode(field("A", chr(5)).type(SemanticType.INTEGER), t("42   "))).contains(new ApiValue.Integer(BigInteger.valueOf(42)));
        assertThat(decode(field("A", chr(3)).type(SemanticType.INTEGER).pad(Pad.LEFT, ' '), t("  7"))).contains(new ApiValue.Integer(BigInteger.valueOf(7)));
        assertThat(decode(field("A", chr(3)).type(SemanticType.INTEGER).pad(Pad.NONE), t("007"))).contains(new ApiValue.Integer(BigInteger.valueOf(7)));
    }

    @Test
    void textThatIsNotAWholeNumberIsVal102() {
        Ops.F f = field("A", chr(5)).type(SemanticType.INTEGER);
        for (String bad : new String[] {"12a  ", "-5   ", "+5   ", "1.5  ", "1 2  ", " 5   ", "1e3  "}) {
            assertThat(problems(f, t(bad))).as(bad).containsExactly("VAL102");
        }
    }

    @Test
    void blankNumbersAndTheNullTextDecodeToAbsent() {
        Ops.F f = field("A", chr(5)).type(SemanticType.INTEGER);
        assertThat(decode(f, t("     "))).isEmpty();
        assertThat(decode(f, t(""))).isEmpty();
        assertThat(decode(field("A", chr(5)).type(SemanticType.INTEGER).pad(Pad.LEFT, ' '), t("     "))).isEmpty();
        assertThat(decode(field("A", chr(5)).type(SemanticType.DECIMAL), t("     "))).isEmpty();
        assertThat(decode(field("A", chr(5)).type(SemanticType.INTEGER).nullAs("N/A"), t("N/A  "))).isEmpty();
        assertThat(decode(field("A", chr(5)).type(SemanticType.DECIMAL).nullAs("-"), t("-    "))).isEmpty();
        assertThat(problems(field("A", chr(5)).type(SemanticType.INTEGER).nullAs("N/A"), t("N/B  "))).containsExactly("VAL102");
    }

    @Test
    void anAllPaddingNumberKeepsOneCharacter() {
        // "0000" with pad:left '0' is the number 0, not "nothing".
        Ops.F f = field("A", chr(4)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0');
        assertThat(decode(f, t("0000"))).contains(new ApiValue.Integer(BigInteger.ZERO));
        assertThat(decode(field("A", chr(4)).type(SemanticType.DECIMAL).pad(Pad.LEFT, '0'), t("0000"))).contains(new ApiValue.Decimal(BigDecimal.ZERO));
    }

    @Test
    void decimalsAreReadWithTheirScale() {
        Ops.F f = field("A", chr(8)).type(SemanticType.DECIMAL);
        assertThat(decode(f, t("12.50   "))).contains(new ApiValue.Decimal(new BigDecimal("12.50")));
        assertThat(decode(f, t("7       "))).contains(new ApiValue.Decimal(new BigDecimal("7")));
        assertThat(decode(f, t("0.000   "))).contains(new ApiValue.Decimal(new BigDecimal("0.000")));
        for (String bad : new String[] {"1.     ", "1,5    ", "-1.5   ", "1.2.3  ", "1e5    ", "abc    "}) {
            assertThat(problems(f, t(bad))).as(bad).containsExactly("VAL102");
        }
    }

    @Test
    void leftPaddingWithZeroDoesNotLoseTheZeroBeforeTheDecimalPoint() {
        Ops.F f = field("A", chr(8)).type(SemanticType.DECIMAL).pad(Pad.LEFT, '0');
        assertThat(decode(f, t("000000.5"))).contains(new ApiValue.Decimal(new BigDecimal("0.5")));
        assertThat(decode(f, t("00012.50"))).contains(new ApiValue.Decimal(new BigDecimal("12.50")));
        assertThat(decode(f, t("000.0000"))).contains(new ApiValue.Decimal(new BigDecimal("0.0000")));
    }

    // ---- boolean

    @Test
    void booleansMatchTheirTextsExactlyAfterPadding() {
        Ops.F f = field("A", chr(2)).type(SemanticType.BOOLEAN);
        assertThat(decode(f, t("1 "))).contains(new ApiValue.Bool(true));
        assertThat(decode(f, t("0 "))).contains(new ApiValue.Bool(false));
        assertThat(decode(f, t("  "))).contains(new ApiValue.Bool(false));
        assertThat(decode(f, t(""))).contains(new ApiValue.Bool(false));
        assertThat(problems(f, t("2 "))).containsExactly("VAL102");
        assertThat(problems(f, t("10"))).containsExactly("VAL102");
        Ops.F yn = field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N");
        assertThat(decode(yn, t("Y"))).contains(new ApiValue.Bool(true));
        assertThat(decode(yn, t("N"))).contains(new ApiValue.Bool(false));
        assertThat(problems(yn, t("y"))).containsExactly("VAL102");
        assertThat(problems(yn, t("1"))).containsExactly("VAL102");
        Ops.F padded = field("A", chr(3)).type(SemanticType.BOOLEAN).booleans("T", "F").pad(Pad.LEFT, '_');
        assertThat(decode(padded, t("__T"))).contains(new ApiValue.Bool(true));
    }

    // ---- date, time, date-time

    @Test
    void datesTimesAndDateTimesAreReadByTheirFormat() {
        assertThat(decode(field("A", chr(8)).type(SemanticType.DATE), t("20261105"))).contains(new ApiValue.Date(LocalDate.of(2026, 11, 5)));
        assertThat(decode(field("A", chr(10)).type(SemanticType.DATE), t("2026-11-05"))).contains(new ApiValue.Date(LocalDate.of(2026, 11, 5)));
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME), t("090507"))).contains(new ApiValue.Time(LocalTime.of(9, 5, 7)));
        assertThat(decode(field("A", chr(8)).type(SemanticType.TIME), t("09:05:07"))).contains(new ApiValue.Time(LocalTime.of(9, 5, 7)));
        assertThat(decode(field("A", chr(19)).type(SemanticType.DATETIME).format("yyyy-MM-dd'T'HH:mm:ss"), t("2026-11-05T09:05:07")))
                .contains(new ApiValue.DateTime(LocalDateTime.of(2026, 11, 5, 9, 5, 7)));
        assertThat(decode(field("A", chr(12)).type(SemanticType.TIME).format("HH:mm:ss.SSS"), t("09:05:07.120")))
                .contains(new ApiValue.Time(LocalTime.of(9, 5, 7, 120_000_000)));
        assertThat(decode(field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"), t("20261105090507")))
                .contains(new ApiValue.DateTime(LocalDateTime.of(2026, 11, 5, 9, 5, 7)));
    }

    @Test
    void aBlankOrAllZeroDateIsAbsent() {
        Ops.F date = field("A", chr(8)).type(SemanticType.DATE);
        assertThat(decode(date, t("        "))).isEmpty();
        assertThat(decode(date, t("00000000"))).isEmpty();
        assertThat(decode(date, t(""))).isEmpty();
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME), t("      "))).isEmpty();
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME), t(""))).isEmpty();
        assertThat(decode(field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"), t("00000000000000"))).isEmpty();
        assertThat(decode(field("A", chr(10)).type(SemanticType.DATE).pad(Pad.LEFT, '*'), t("0000000000"))).isEmpty();
        // Zeros inside a value are not "no date": a day 00 is simply not a date.
        assertThat(problems(date, t("20260100"))).containsExactly("VAL102");
    }

    @Test
    void aTimeOfAllZerosIsMidnightAndOnlyABlankTimeIsAbsent() {
        // Decision 47: "entirely zeros means absent" is for DATE and DATETIME, where all zeros is not a valid value.
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME), t("000000"))).contains(new ApiValue.Time(LocalTime.MIDNIGHT));
        assertThat(decode(field("A", chr(8)).type(SemanticType.TIME), t("00:00:00"))).contains(new ApiValue.Time(LocalTime.MIDNIGHT));
        assertThat(decode(field("A", chr(9)).type(SemanticType.TIME).format("HHmmssSSS"), t("000000000"))).contains(new ApiValue.Time(LocalTime.MIDNIGHT));
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME).pad(Pad.LEFT, ' '), t("000000"))).contains(new ApiValue.Time(LocalTime.MIDNIGHT));
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME).pad(Pad.NONE), t("000000"))).contains(new ApiValue.Time(LocalTime.MIDNIGHT));
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME), t("      "))).isEmpty();
        // The test is on the raw text, before padding is removed: a field of spaces is blank even though padding removal empties it.
        assertThat(decode(field("A", chr(6)).type(SemanticType.TIME).pad(Pad.LEFT, ' '), t("      "))).isEmpty();
        // DATE and DATETIME are unchanged.
        assertThat(decode(field("A", chr(8)).type(SemanticType.DATE), t("00000000"))).isEmpty();
        assertThat(decode(field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"), t("00000000000000"))).isEmpty();
        assertThat(decode(field("A", chr(8)).type(SemanticType.DATE), t("        "))).isEmpty();
        assertThat(decode(field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"), t("              "))).isEmpty();
    }

    @Test
    void aTextThatIsNotADateOrTimeIsVal102() {
        Ops.F date = field("A", chr(8)).type(SemanticType.DATE);
        for (String bad : new String[] {"20261301", "20260230", "2026110x", "2026-1-5", "0000000 ", "2026110", "202611050"}) {
            assertThat(problems(date, t(bad))).as(bad).containsExactly("VAL102");
        }
        // A trailing space is only the padding.
        assertThat(decode(date, t("20261105 "))).contains(new ApiValue.Date(LocalDate.of(2026, 11, 5)));
        Ops.F time = field("A", chr(6)).type(SemanticType.TIME);
        for (String bad : new String[] {"240000", "096000", "090060", "09:0507"}) {
            assertThat(problems(time, t(bad))).as(bad).containsExactly("VAL102");
        }
        assertThat(problems(field("A", chr(10)).type(SemanticType.DATE), t("2026/11/05"))).containsExactly("VAL102");
    }

    // ---- other wire types

    @Test
    void integersPackedFloatsAndBytes() {
        assertThat(decode(field("A", new WireType.Int(4, Optional.empty())), new WireValue.Integer(BigInteger.valueOf(-7)))).contains(new ApiValue.Integer(BigInteger.valueOf(-7)));
        assertThat(decode(field("A", new WireType.Packed(7, 2)), new WireValue.Decimal(new BigDecimal("12.50")))).contains(new ApiValue.Decimal(new BigDecimal("12.50")));
        assertThat(decode(field("A", new WireType.Zoned(5, 0)).type(SemanticType.INTEGER), new WireValue.Decimal(new BigDecimal("42"))))
                .contains(new ApiValue.Integer(BigInteger.valueOf(42)));
        assertThat(decode(field("A", new WireType.Float(8)), new WireValue.Float(0.1))).contains(new ApiValue.Decimal(new BigDecimal("0.1")));
        assertThat(decode(field("A", new WireType.Float(4)), new WireValue.Float((double) 0.1f))).contains(new ApiValue.Decimal(new BigDecimal("0.1")));
        assertThat(decode(field("A", new WireType.Byte(new Length.Fixed(2))), new WireValue.Bytes(new byte[] {1, 2}))).contains(new ApiValue.Binary(new byte[] {1, 2}));
        assertThat(decode(field("A", new WireType.Date()), new WireValue.Date(LocalDate.of(2026, 1, 2)))).contains(new ApiValue.Date(LocalDate.of(2026, 1, 2)));
        assertThat(decode(field("A", new WireType.Time()), new WireValue.Time(LocalTime.NOON))).contains(new ApiValue.Time(LocalTime.NOON));
        LocalDateTime stamp = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 6);
        assertThat(decode(field("A", new WireType.Timestamp()), new WireValue.Timestamp(stamp))).contains(new ApiValue.DateTime(stamp));
    }

    @Test
    void aPackedValueThatIsNotWholeCannotBeReadAsAnInteger() {
        assertThat(problems(field("A", new WireType.Packed(5, 0)).type(SemanticType.INTEGER), new WireValue.Decimal(new BigDecimal("1.5")))).containsExactly("VAL102");
    }

    @Test
    void aFloatThatIsNotFiniteIsVal102() {
        assertThat(problems(field("A", new WireType.Float(8)), new WireValue.Float(Double.NaN))).containsExactly("VAL102");
        assertThat(problems(field("A", new WireType.Float(8)), new WireValue.Float(Double.POSITIVE_INFINITY))).containsExactly("VAL102");
        assertThat(problems(field("A", new WireType.Float(4)), new WireValue.Float(Double.NEGATIVE_INFINITY))).containsExactly("VAL102");
    }

    @Test
    void aValueOfTheWrongWireKindIsVal101() {
        WireValue wrong = new WireValue.Float(1.0);
        for (WireType wire : List.of(chr(2), varchar(2), new WireType.Int(4, Optional.empty()), new WireType.Packed(3, 0),
                new WireType.Byte(new Length.Fixed(1)), new WireType.Date(), new WireType.Time(), new WireType.Timestamp())) {
            assertThat(problems(field("A", wire), wrong)).as(wire.toString()).containsExactly("VAL101");
        }
        assertThat(problems(field("A", new WireType.Float(8)), t("1"))).containsExactly("VAL101");
        assertThat(problems(field("A", new WireType.Byte(new Length.Fixed(2))), new WireValue.Bytes(new byte[3]))).containsExactly("VAL101");
    }

    @Test
    void aDateOnADateWireWithBlankIsAbsent() {
        assertThat(decode(field("A", new WireType.Date()), new WireValue.Blank())).isEmpty();
        assertThat(decode(field("A", chr(3)).type(SemanticType.INTEGER), new WireValue.Blank())).isEmpty();
    }
}

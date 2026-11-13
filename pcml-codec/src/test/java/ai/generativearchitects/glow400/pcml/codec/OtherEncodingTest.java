package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.absent;
import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.codec;
import static ai.generativearchitects.glow400.pcml.codec.Ops.decimal;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.integer;
import static ai.generativearchitects.glow400.pcml.codec.Ops.request;
import static ai.generativearchitects.glow400.pcml.codec.Ops.text;
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
import java.util.Map;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** Encoding into int, packed, zoned, float, byte, date, time and timestamp fields, injected values and blanks. */
class OtherEncodingTest {

    private static final Map<String, String> NONE = Map.of();

    private static WireValue one(Ops.F f, ApiValue value) {
        EncodeResult result = codec(f).encode(request(f.exposed, value), NONE);
        assertThat(result.problems()).as("problems").isEmpty();
        return result.parameters().orElseThrow().get(0);
    }

    private static List<String> codes(Ops.F f, ApiValue value) {
        return codec(f).encode(request(f.exposed, value), NONE).problems().stream().map(ValueProblem::code).toList();
    }

    private static WireType.Int int2(Integer precision) {
        return new WireType.Int(2, Optional.ofNullable(precision));
    }

    // ---- int

    @Test
    void signedIntegersHaveTheRangeOfTheirLength() {
        record Case(int bytes, String min, String max) { }
        for (Case c : List.of(new Case(2, "-32768", "32767"), new Case(4, "-2147483648", "2147483647"),
                new Case(8, "-9223372036854775808", "9223372036854775807"))) {
            for (Integer precision : new Integer[] {null, c.bytes * 8 - 1}) {
                Ops.F f = field("A", new WireType.Int(c.bytes, Optional.ofNullable(precision)));
                assertThat(one(f, new ApiValue.Integer(new BigInteger(c.min)))).isEqualTo(new WireValue.Integer(new BigInteger(c.min)));
                assertThat(one(f, new ApiValue.Integer(new BigInteger(c.max)))).isEqualTo(new WireValue.Integer(new BigInteger(c.max)));
                assertThat(codes(f, new ApiValue.Integer(new BigInteger(c.min).subtract(BigInteger.ONE)))).containsExactly("VAL003");
                assertThat(codes(f, new ApiValue.Integer(new BigInteger(c.max).add(BigInteger.ONE)))).containsExactly("VAL003");
            }
        }
    }

    @Test
    void unsignedIntegersHaveTheRangeOfTheirLength() {
        record Case(int bytes, String max) { }
        for (Case c : List.of(new Case(2, "65535"), new Case(4, "4294967295"), new Case(8, "18446744073709551615"))) {
            Ops.F f = field("A", new WireType.Int(c.bytes, Optional.of(c.bytes * 8)));
            assertThat(one(f, integer(0))).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
            assertThat(one(f, new ApiValue.Integer(new BigInteger(c.max)))).isEqualTo(new WireValue.Integer(new BigInteger(c.max)));
            assertThat(codes(f, integer(-1))).containsExactly("VAL003");
            assertThat(codes(f, new ApiValue.Integer(new BigInteger(c.max).add(BigInteger.ONE)))).containsExactly("VAL003");
        }
    }

    @Test
    void anIntegerNeedsAWholeNumber() {
        assertThat(codes(field("A", int2(null)), text("1"))).containsExactly("VAL002");
        assertThat(codes(field("A", int2(null)), decimal("1"))).containsExactly("VAL002");
        assertThat(codes(field("A", int2(null)).required(), absent())).containsExactly("VAL001");
        assertThat(one(field("A", int2(null)), absent())).isEqualTo(new WireValue.Integer(BigInteger.ZERO));
    }

    // ---- packed and zoned

    @Test
    void packedAndZonedCheckDigitsAndDecimals() {
        for (WireType wire : List.of(new WireType.Packed(5, 2), new WireType.Zoned(5, 2))) {
            Ops.F f = field("A", wire);
            assertThat(one(f, decimal("123.45"))).isEqualTo(new WireValue.Decimal(new BigDecimal("123.45")));
            assertThat(one(f, decimal("-999.99"))).isEqualTo(new WireValue.Decimal(new BigDecimal("-999.99")));
            assertThat(one(f, decimal("1.2"))).isEqualTo(new WireValue.Decimal(new BigDecimal("1.20")));
            assertThat(one(f, integer(7))).isEqualTo(new WireValue.Decimal(new BigDecimal("7.00")));
            assertThat(one(f, decimal("0"))).isEqualTo(new WireValue.Decimal(new BigDecimal("0.00")));
            assertThat(one(f, decimal("1.2300"))).isEqualTo(new WireValue.Decimal(new BigDecimal("1.23")));
            assertThat(one(f, decimal("0.000"))).isEqualTo(new WireValue.Decimal(new BigDecimal("0.00")));
            assertThat(codes(f, decimal("1000"))).containsExactly("VAL003");
            assertThat(codes(f, decimal("1.234"))).containsExactly("VAL003");
            assertThat(codes(f, decimal("-1000.00"))).containsExactly("VAL003");
            assertThat(codes(f, decimal("12E+1"))).isEmpty();
            assertThat(codes(f, decimal("1E+3"))).containsExactly("VAL003");
            assertThat(codes(f, text("1"))).containsExactly("VAL002");
        }
    }

    @Test
    void aPackedFieldWithoutDecimalsTakesIntegers() {
        Ops.F f = field("A", new WireType.Packed(3, 0)).type(SemanticType.INTEGER);
        assertThat(one(f, integer(999))).isEqualTo(new WireValue.Decimal(new BigDecimal("999")));
        assertThat(one(f, integer(-999))).isEqualTo(new WireValue.Decimal(new BigDecimal("-999")));
        assertThat(codes(f, integer(1000))).containsExactly("VAL003");
        assertThat(codes(f, decimal("1"))).containsExactly("VAL002");
    }

    @Test
    void aPackedFieldWhereAllDigitsAreDecimalsTakesOnlyFractions() {
        Ops.F f = field("A", new WireType.Packed(2, 2));
        assertThat(one(f, decimal("0.99"))).isEqualTo(new WireValue.Decimal(new BigDecimal("0.99")));
        assertThat(one(f, decimal("0"))).isEqualTo(new WireValue.Decimal(new BigDecimal("0.00")));
        assertThat(codes(f, decimal("1"))).containsExactly("VAL003");
    }

    @Test
    void absentPackedIsZeroWithItsScale() {
        assertThat(one(field("A", new WireType.Zoned(7, 3)), absent())).isEqualTo(new WireValue.Decimal(new BigDecimal("0.000")));
    }

    // ---- float

    @Test
    void floatsAreFiniteDoubles() {
        Ops.F f = field("A", new WireType.Float(8));
        assertThat(one(f, decimal("1.5"))).isEqualTo(new WireValue.Float(1.5));
        assertThat(one(f, integer(3))).isEqualTo(new WireValue.Float(3.0));
        assertThat(one(f, absent())).isEqualTo(new WireValue.Float(0.0));
        assertThat(codes(f, decimal("1E+400"))).containsExactly("VAL003");
        assertThat(codes(f, text("1"))).containsExactly("VAL002");
    }

    @Test
    void singlePrecisionFloatsMustFitAFloat() {
        Ops.F f = field("A", new WireType.Float(4));
        assertThat(one(f, decimal("3.0E+38"))).isEqualTo(new WireValue.Float(3.0E38));
        assertThat(codes(f, decimal("1E+39"))).containsExactly("VAL003");
        assertThat(codes(field("A", new WireType.Float(8)), decimal("1E+39"))).isEmpty();
    }

    // ---- byte

    @Test
    void bytesHaveAnExactLength() {
        Ops.F f = field("A", new WireType.Byte(new Length.Fixed(3)));
        assertThat(one(f, new ApiValue.Binary(new byte[] {1, 2, 3}))).isEqualTo(new WireValue.Bytes(new byte[] {1, 2, 3}));
        assertThat(codes(f, new ApiValue.Binary(new byte[] {1, 2}))).containsExactly("VAL003");
        assertThat(codes(f, new ApiValue.Binary(new byte[] {1, 2, 3, 4}))).containsExactly("VAL003");
        assertThat(codes(f, text("abc"))).containsExactly("VAL002");
        assertThat(one(f, absent())).isEqualTo(new WireValue.Bytes(new byte[3]));
    }

    // ---- date, time, timestamp

    @Test
    void datesAndTimesPassThroughForTheCallLayerToFormat() {
        assertThat(one(field("A", new WireType.Date()), new ApiValue.Date(LocalDate.of(2026, 11, 5)))).isEqualTo(new WireValue.Date(LocalDate.of(2026, 11, 5)));
        assertThat(one(field("A", new WireType.Time()), new ApiValue.Time(LocalTime.of(9, 5)))).isEqualTo(new WireValue.Time(LocalTime.of(9, 5)));
        LocalDateTime stamp = LocalDateTime.of(2026, 11, 5, 9, 5, 7, 123_456_789);
        assertThat(one(field("A", new WireType.Timestamp()), new ApiValue.DateTime(stamp))).isEqualTo(new WireValue.Timestamp(stamp));
        assertThat(codes(field("A", new WireType.Date()), text("2026-11-05"))).containsExactly("VAL002");
        assertThat(codes(field("A", new WireType.Time()), new ApiValue.Date(LocalDate.EPOCH))).containsExactly("VAL002");
        assertThat(codes(field("A", new WireType.Timestamp()), new ApiValue.Date(LocalDate.EPOCH))).containsExactly("VAL002");
    }

    @Test
    void anAbsentDateTimeOrTimestampInARequestIsVal001WhetherOrNotRequired() {
        for (WireType wire : List.of(new WireType.Date(), new WireType.Time(), new WireType.Timestamp())) {
            assertThat(codes(field("A", wire), absent())).as(wire.toString()).containsExactly("VAL001");
            assertThat(codes(field("A", wire).required(), absent())).as(wire.toString()).containsExactly("VAL001");
        }
    }

    // ---- values nobody gave: output, internal, hidden

    @Test
    void outputAndInternalParametersGetTheirBlankOrZero() {
        EncodeResult result = codec(
                field("TXT", chr(3)).response(), field("NUM", int2(null)).internal(), field("PCK", new WireType.Packed(5, 2)).response(),
                field("FLT", new WireType.Float(8)).response(), field("BYT", new WireType.Byte(new Length.Fixed(2))).response(),
                field("DAT", new WireType.Date()).response(), field("TIM", new WireType.Time()).internal(),
                field("STP", new WireType.Timestamp()).response(), field("VCH", Ops.varchar(5)).response())
                .encode(Map.of(), NONE);
        assertThat(result.problems()).isEmpty();
        assertThat(result.parameters().orElseThrow()).containsExactly(
                new WireValue.Text("   "), new WireValue.Integer(BigInteger.ZERO), new WireValue.Decimal(new BigDecimal("0.00")),
                new WireValue.Float(0.0), new WireValue.Bytes(new byte[2]), new WireValue.Blank(), new WireValue.Blank(),
                new WireValue.Blank(), new WireValue.Text(""));
    }

    @Test
    void aBothFieldIsEncodedFromTheRequest() {
        Ops.F f = field("A", chr(3)).both();
        assertThat(one(f, text("ab"))).isEqualTo(new WireValue.Text("ab "));
    }

    @Test
    void theParametersComeInPcmlOrderWhateverTheRequestOrder() {
        EncodeResult result = codec(field("A", chr(1)), field("B", chr(1)), field("C", chr(1)))
                .encode(request("c", text("3"), "a", text("1"), "b", text("2")), NONE);
        assertThat(result.parameters().orElseThrow()).containsExactly(new WireValue.Text("1"), new WireValue.Text("2"), new WireValue.Text("3"));
    }

    // ---- injected

    @Test
    void injectedTextIsEncodedByTheFieldsRules() {
        Map<String, String> injected = Map.of("ENV", "PRD", "NUM", "-12", "DEC", "1.50", "FLT", "2.5", "PAD", "7");
        EncodeResult result = codec(
                field("ENV", chr(5)).inject("${environment.code}"), field("NUM", int2(null)).inject("${environment.v}"),
                field("DEC", new WireType.Packed(5, 2)).inject("${environment.v}"), field("FLT", new WireType.Float(8)).inject("${environment.v}"),
                field("PAD", chr(3)).pad(Pad.LEFT, '0').inject("${environment.v}")).encode(Map.of(), injected);
        assertThat(result.problems()).isEmpty();
        assertThat(result.parameters().orElseThrow()).containsExactly(
                new WireValue.Text("PRD  "), new WireValue.Integer(BigInteger.valueOf(-12)), new WireValue.Decimal(new BigDecimal("1.50")),
                new WireValue.Float(2.5), new WireValue.Text("007"));
    }

    @Test
    void injectedTextThatDoesNotFitIsAProblem() {
        EncodeResult result = codec(field("ENV", chr(3)).inject("${environment.v}"), field("NUM", int2(null)).inject("${environment.v}"), field("BIG", int2(null)).inject("${environment.v}"),
                field("DEC", new WireType.Packed(3, 1)).inject("${environment.v}"), field("DEC2", new WireType.Packed(3, 1)).inject("${environment.v}"),
                field("FLT", new WireType.Float(8)).inject("${environment.v}"))
                .encode(Map.of(), Map.of("ENV", "TOOLONG", "NUM", "abc", "BIG", "99999", "DEC", "1.55", "DEC2", "n/a", "FLT", "x1"));
        assertThat(result.parameters()).isEmpty();
        assertThat(result.problems()).extracting(ValueProblem::code, ValueProblem::fieldPath).containsExactly(
                Tuple.tuple("VAL003", "ENV"), Tuple.tuple("VAL004", "NUM"), Tuple.tuple("VAL003", "BIG"),
                Tuple.tuple("VAL003", "DEC"), Tuple.tuple("VAL004", "DEC2"), Tuple.tuple("VAL004", "FLT"));
        assertThat(result.problems()).extracting(ValueProblem::message).noneMatch(m -> m.contains("TOOLONG") || m.contains("abc") || m.contains("99999"));
    }

    @Test
    void aMissingInjectedValueIsVal001() {
        EncodeResult result = codec(field("ENV", chr(3)).inject("${environment.v}")).encode(Map.of(), NONE);
        assertThat(result.problems()).extracting(ValueProblem::code, ValueProblem::fieldPath).containsExactly(Tuple.tuple("VAL001", "ENV"));
    }

    @Test
    void anInjectedValueIsNotARequestField() {
        EncodeResult result = codec(field("ENV", chr(3)).inject("${environment.v}")).encode(request("env", text("a")), Map.of("ENV", "PRD"));
        assertThat(result.problems()).extracting(ValueProblem::code).containsExactly("VAL006");
    }
}

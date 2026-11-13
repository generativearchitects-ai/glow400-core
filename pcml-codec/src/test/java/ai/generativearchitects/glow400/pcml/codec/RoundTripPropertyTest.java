package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.codec;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.request;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

/**
 * Property-based tests: for every supported combination of wire type, semantic type and padding, decoding what was
 * encoded gives the original value (after the documented normalisations), and encoding never produces a value that
 * breaks the field's length or digits.
 */
class RoundTripPropertyTest {

    private static final Map<String, String> NONE = Map.of();

    /** Encodes one request field as a BOTH field and decodes the program's answer back. */
    private static ApiValue roundTrip(Ops.F f, ApiValue value) {
        Ops.F both = f.both();
        OperationCodec codec = codec(both);
        EncodeResult encoded = codec.encode(request(both.exposed, value), NONE);
        assertThat(encoded.problems()).as("encoding problems").isEmpty();
        DecodeResult decoded = codec.decode(encoded.parameters().orElseThrow());
        assertThat(decoded.problems()).as("decoding problems").isEmpty();
        return decoded.response().orElseThrow().getOrDefault(both.exposed, new ApiValue.Absent());
    }

    // ---- text

    @Property
    void textRoundTripsWithEveryPadding(@ForAll @AlphaChars @StringLength(max = 10) String value) {
        for (Ops.F f : List.of(field("A", chr(10)), field("A", chr(10)).pad(Pad.LEFT, '0'), field("A", chr(10)).pad(Pad.LEFT, ' '),
                field("A", chr(10)).pad(Pad.NONE), field("A", varchar(10)), field("A", chr(10)).pad(Pad.RIGHT, '*'))) {
            assertThat(roundTrip(f, new ApiValue.Text(value))).isEqualTo(new ApiValue.Text(value));
        }
    }

    @Property
    void whatTextEncodesToNeverBreaksTheLength(@ForAll @StringLength(max = 30) String value, @ForAll @IntRange(min = 1, max = 12) int length) {
        for (Ops.F f : List.of(field("A", chr(length)), field("A", varchar(length)), field("A", chr(length)).pad(Pad.LEFT, '0'))) {
            EncodeResult r = codec(f).encode(request("a", new ApiValue.Text(value)), NONE);
            if (value.codePointCount(0, value.length()) > length) {
                assertThat(r.problems()).extracting(ValueProblem::code).containsExactly("VAL003");
            } else {
                String wire = ((WireValue.Text) r.parameters().orElseThrow().get(0)).value();
                assertThat(wire.codePointCount(0, wire.length())).isLessThanOrEqualTo(length);
                if (!(f.wire instanceof WireType.VarChar)) {
                    assertThat(wire.codePointCount(0, wire.length())).isEqualTo(length);
                }
                assertThat(wire).contains(value);
            }
        }
    }

    // ---- numbers in text

    @Provide
    Arbitrary<BigInteger> digits8() {
        return Arbitraries.bigIntegers().between(BigInteger.ZERO, new BigInteger("99999999"));
    }

    @Property
    void integersInTextRoundTrip(@ForAll("digits8") BigInteger value) {
        for (Ops.F f : List.of(field("A", chr(8)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0'), field("A", chr(8)).type(SemanticType.INTEGER),
                field("A", chr(10)).type(SemanticType.INTEGER).pad(Pad.LEFT, ' '), field("A", chr(8)).type(SemanticType.INTEGER).pad(Pad.NONE),
                field("A", varchar(8)).type(SemanticType.INTEGER))) {
            assertThat(roundTrip(f, new ApiValue.Integer(value))).isEqualTo(new ApiValue.Integer(value));
        }
    }

    @Property
    void decimalsInTextRoundTripWithTheirScale(@ForAll("digits8") BigInteger unscaled, @ForAll @IntRange(min = 0, max = 4) int scale) {
        BigDecimal value = new BigDecimal(unscaled, scale);
        for (Ops.F f : List.of(field("A", chr(12)).type(SemanticType.DECIMAL).pad(Pad.LEFT, '0'), field("A", chr(12)).type(SemanticType.DECIMAL),
                field("A", chr(12)).type(SemanticType.DECIMAL).pad(Pad.LEFT, ' '), field("A", varchar(12)).type(SemanticType.DECIMAL))) {
            assertThat(roundTrip(f, new ApiValue.Decimal(value))).isEqualTo(new ApiValue.Decimal(value));
        }
    }

    // ---- booleans

    @Property
    void booleansRoundTrip(@ForAll boolean value) {
        for (Ops.F f : List.of(field("A", chr(1)).type(SemanticType.BOOLEAN), field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N"),
                field("A", chr(3)).type(SemanticType.BOOLEAN).booleans("yes", "no").pad(Pad.LEFT, '_'), field("A", varchar(4)).type(SemanticType.BOOLEAN))) {
            assertThat(roundTrip(f, new ApiValue.Bool(value))).isEqualTo(new ApiValue.Bool(value));
        }
    }

    // ---- dates and times in text

    @Provide
    Arbitrary<LocalDate> dates() {
        return Arbitraries.longs().between(LocalDate.of(0, 1, 1).toEpochDay(), LocalDate.of(9999, 12, 31).toEpochDay()).map(LocalDate::ofEpochDay);
    }

    @Provide
    Arbitrary<LocalTime> timesInMilliseconds() {
        return Arbitraries.integers().between(0, 24 * 3600 * 1000 - 1).map(ms -> LocalTime.ofNanoOfDay(ms * 1_000_000L));
    }

    @Property
    void datesInTextRoundTrip(@ForAll("dates") LocalDate value) {
        for (Ops.F f : List.of(field("A", chr(8)).type(SemanticType.DATE), field("A", chr(10)).type(SemanticType.DATE),
                field("A", chr(10)).type(SemanticType.DATE).format("dd/MM/yyyy"), field("A", varchar(10)).type(SemanticType.DATE).format("yyyyMMdd"),
                field("A", chr(12)).type(SemanticType.DATE).format("yyyy.MM.dd  ").pad(Pad.NONE))) {
            assertThat(roundTrip(f, new ApiValue.Date(value))).isEqualTo(new ApiValue.Date(value));
        }
    }

    @Property
    void timesInTextRoundTrip(@ForAll("timesInMilliseconds") LocalTime value) {
        LocalTime wholeSeconds = value.withNano(0);
        // A format without a fraction takes whole seconds only; the codec refuses anything else instead of cutting it.
        assertThat(roundTrip(field("A", chr(8)).type(SemanticType.TIME), new ApiValue.Time(wholeSeconds))).isEqualTo(new ApiValue.Time(wholeSeconds));
        assertThat(roundTrip(field("A", chr(6)).type(SemanticType.TIME), new ApiValue.Time(wholeSeconds)))
                .isEqualTo(wholeSeconds.equals(LocalTime.MIDNIGHT) ? new ApiValue.Absent() : new ApiValue.Time(wholeSeconds));
        assertThat(roundTrip(field("A", chr(12)).type(SemanticType.TIME).format("HH:mm:ss.SSS"), new ApiValue.Time(value))).isEqualTo(new ApiValue.Time(value));
        // 00:00:00 written as all zeros reads back as "no time": the documented normalisation.
        assertThat(roundTrip(field("A", chr(9)).type(SemanticType.TIME).format("HHmmssSSS"), new ApiValue.Time(value)))
                .isEqualTo(value.equals(LocalTime.MIDNIGHT) ? new ApiValue.Absent() : new ApiValue.Time(value));
    }

    @Property
    void dateTimesInTextRoundTrip(@ForAll("dates") LocalDate date, @ForAll("timesInMilliseconds") LocalTime time) {
        LocalDateTime value = LocalDateTime.of(date, time);
        for (Ops.F f : List.of(field("A", chr(17)).type(SemanticType.DATETIME).format("yyyyMMddHHmmssSSS"),
                field("A", chr(23)).type(SemanticType.DATETIME).format("yyyy-MM-dd'T'HH:mm:ss.SSS"))) {
            assertThat(roundTrip(f, new ApiValue.DateTime(value))).isEqualTo(new ApiValue.DateTime(value));
        }
    }

    // ---- other wire types

    @Property
    void signedIntegersRoundTrip(@ForAll long value) {
        assertThat(roundTrip(field("A", new WireType.Int(8, Optional.empty())), new ApiValue.Integer(BigInteger.valueOf(value))))
                .isEqualTo(new ApiValue.Integer(BigInteger.valueOf(value)));
        short small = (short) value;
        assertThat(roundTrip(field("A", new WireType.Int(2, Optional.of(15))), new ApiValue.Integer(BigInteger.valueOf(small))))
                .isEqualTo(new ApiValue.Integer(BigInteger.valueOf(small)));
        int medium = (int) value;
        assertThat(roundTrip(field("A", new WireType.Int(4, Optional.empty())), new ApiValue.Integer(BigInteger.valueOf(medium))))
                .isEqualTo(new ApiValue.Integer(BigInteger.valueOf(medium)));
    }

    @Property
    void unsignedIntegersRoundTrip(@ForAll long value) {
        BigInteger unsigned64 = new BigInteger(Long.toUnsignedString(value));
        assertThat(roundTrip(field("A", new WireType.Int(8, Optional.of(64))), new ApiValue.Integer(unsigned64))).isEqualTo(new ApiValue.Integer(unsigned64));
        BigInteger unsigned32 = BigInteger.valueOf(Integer.toUnsignedLong((int) value));
        assertThat(roundTrip(field("A", new WireType.Int(4, Optional.of(32))), new ApiValue.Integer(unsigned32))).isEqualTo(new ApiValue.Integer(unsigned32));
        BigInteger unsigned16 = BigInteger.valueOf(value & 0xFFFF);
        assertThat(roundTrip(field("A", new WireType.Int(2, Optional.of(16))), new ApiValue.Integer(unsigned16))).isEqualTo(new ApiValue.Integer(unsigned16));
    }

    @Provide
    Arbitrary<BigInteger> digits15() {
        BigInteger limit = BigInteger.TEN.pow(15).subtract(BigInteger.ONE);
        return Arbitraries.bigIntegers().between(limit.negate(), limit);
    }

    @Property
    void packedAndZonedDecimalsRoundTripAndNeverBreakTheirDigits(@ForAll("digits15") BigInteger unscaled, @ForAll @IntRange(min = 0, max = 6) int scale) {
        BigDecimal value = new BigDecimal(unscaled, scale);
        for (WireType wire : List.of(new WireType.Packed(15, scale), new WireType.Zoned(15, scale))) {
            EncodeResult r = codec(field("A", wire)).encode(request("a", new ApiValue.Decimal(value)), NONE);
            BigDecimal wireValue = ((WireValue.Decimal) r.parameters().orElseThrow().get(0)).value();
            assertThat(wireValue.scale()).isEqualTo(scale);
            assertThat(wireValue.precision()).isLessThanOrEqualTo(15);
            assertThat(wireValue).isEqualByComparingTo(value);
            assertThat(roundTrip(field("A", wire), new ApiValue.Decimal(value))).isEqualTo(new ApiValue.Decimal(wireValue));
        }
    }

    @Property
    void packedFieldsAcceptExactlyTheValuesThatFit(@ForAll("digits15") BigInteger unscaled, @ForAll @IntRange(min = 0, max = 5) int scale,
                                                  @ForAll @IntRange(min = 1, max = 9) int digits, @ForAll @IntRange(min = 0, max = 5) int decimals) {
        if (decimals > digits) {
            return;
        }
        BigDecimal value = new BigDecimal(unscaled, scale);
        EncodeResult r = codec(field("A", new WireType.Packed(digits, decimals))).encode(request("a", new ApiValue.Decimal(value)), NONE);
        BigDecimal stripped = value.stripTrailingZeros();
        boolean fits = Math.max(stripped.scale(), 0) <= decimals
                && (value.signum() == 0 ? 0 : stripped.precision() - stripped.scale()) <= digits - decimals;
        assertThat(r.problems().isEmpty()).isEqualTo(fits);
        if (fits) {
            BigDecimal wire = ((WireValue.Decimal) r.parameters().orElseThrow().get(0)).value();
            assertThat(wire.scale()).isEqualTo(decimals);
            assertThat(wire.abs().compareTo(BigDecimal.TEN.pow(digits - decimals))).isLessThan(0);
        } else {
            assertThat(r.problems()).extracting(ValueProblem::code).containsExactly("VAL003");
        }
    }

    @Property
    void doublesRoundTrip(@ForAll double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        assertThat(roundTrip(field("A", new WireType.Float(8)), new ApiValue.Decimal(decimal))).isEqualTo(new ApiValue.Decimal(decimal));
    }

    @Property
    void singlePrecisionFloatsRoundTrip(@ForAll float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return;
        }
        BigDecimal decimal = new BigDecimal(Float.toString(value));
        assertThat(roundTrip(field("A", new WireType.Float(4)), new ApiValue.Decimal(decimal))).isEqualTo(new ApiValue.Decimal(decimal));
    }

    @Property
    void bytesRoundTrip(@ForAll byte[] value) {
        Ops.F f = field("A", new WireType.Byte(new Length.Fixed(value.length)));
        assertThat(roundTrip(f, new ApiValue.Binary(value))).isEqualTo(new ApiValue.Binary(value));
    }

    @Property
    void datesTimesAndTimestampsPassThroughUnchanged(@ForAll("dates") LocalDate date, @ForAll("timesInMilliseconds") LocalTime time) {
        assertThat(roundTrip(field("A", new WireType.Date()), new ApiValue.Date(date))).isEqualTo(new ApiValue.Date(date));
        assertThat(roundTrip(field("A", new WireType.Time()), new ApiValue.Time(time))).isEqualTo(new ApiValue.Time(time));
        assertThat(roundTrip(field("A", new WireType.Timestamp()), new ApiValue.DateTime(LocalDateTime.of(date, time))))
                .isEqualTo(new ApiValue.DateTime(LocalDateTime.of(date, time)));
    }

    // ---- structs and arrays

    @Property
    void arraysRoundTripAndTrailingBlanksAreDropped(@ForAll @IntRange(min = 0, max = 6) int size, @ForAll("smallNumbers") List<Integer> values) {
        List<Integer> given = values.subList(0, Math.min(values.size(), size));
        Ops.F f = field("A", new WireType.Int(4, Optional.empty())).count(6).trimBlank();
        List<ApiValue> elements = new ArrayList<>();
        given.forEach(n -> elements.add(new ApiValue.Integer(BigInteger.valueOf(n))));
        ApiValue result = roundTrip(f, new ApiValue.Arr(elements));
        int end = given.size();
        while (end > 0 && given.get(end - 1) == 0) {
            end--;
        }
        List<ApiValue> expected = new ArrayList<>();
        given.subList(0, end).forEach(n -> expected.add(new ApiValue.Integer(BigInteger.valueOf(n))));
        assertThat(result).isEqualTo(new ApiValue.Arr(expected));
    }

    @Provide
    Arbitrary<List<Integer>> smallNumbers() {
        return Arbitraries.integers().between(0, 3).list().ofMaxSize(8);
    }

    @Property
    void structsOfMixedFieldsRoundTrip(@ForAll @AlphaChars @StringLength(max = 5) String name, @ForAll @IntRange(min = 0, max = 99999) int qty, @ForAll("dates") LocalDate date) {
        Ops.F f = Ops.struct("S", field("N", chr(5)), field("Q", chr(5)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0'),
                field("D", chr(8)).type(SemanticType.DATE)).both();
        ApiValue.Obj value = Ops.obj("n", new ApiValue.Text(name), "q", new ApiValue.Integer(BigInteger.valueOf(qty)), "d", new ApiValue.Date(date));
        assertThat(roundTrip(f, value)).isEqualTo(value);
    }
}

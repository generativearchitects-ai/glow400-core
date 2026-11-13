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
import org.junit.jupiter.api.Test;

/**
 * Property tests with the in-house generator: for every supported combination of wire type, semantic type and padding,
 * decoding what was encoded gives the original value (after the documented normalisations), and encoding never produces a
 * value that breaks the field's length or digits. Each property runs {@link Properties#DEFAULT_CASES} cases; the boundary
 * values come first.
 */
class RoundTripPropertiesTest {

    private static final Map<String, String> NONE = Map.of();

    /** Encodes one field as a BOTH field and decodes the program's answer back. */
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

    @Test
    void textRoundTripsWithEveryPadding() {
        Properties.check("text round trip", g -> {
            String value = g.text(Gen.LETTERS, 0, 10);
            for (Ops.F f : List.of(field("A", chr(10)), field("A", chr(10)).pad(Pad.LEFT, '0'), field("A", chr(10)).pad(Pad.LEFT, ' '),
                    field("A", chr(10)).pad(Pad.NONE), field("A", varchar(10)), field("A", chr(10)).pad(Pad.RIGHT, '*'))) {
                assertThat(roundTrip(f, new ApiValue.Text(value))).isEqualTo(new ApiValue.Text(value));
            }
        });
    }

    @Test
    void whatTextEncodesToNeverBreaksTheLength() {
        Properties.check("text length", g -> {
            int length = g.intIn(1, 12);
            String value = g.textAround(Gen.LETTERS_AND_SPACES, length, 8);
            for (Ops.F f : List.of(field("A", chr(length)), field("A", varchar(length)), field("A", chr(length)).pad(Pad.LEFT, '0'))) {
                EncodeResult r = codec(f).encode(request("a", new ApiValue.Text(value)), NONE);
                if (value.length() > length) {
                    assertThat(r.problems()).extracting(ValueProblem::code).containsExactly("VAL003");
                } else {
                    String wire = ((WireValue.Text) r.parameters().orElseThrow().get(0)).value();
                    assertThat(wire.length()).isLessThanOrEqualTo(length);
                    if (!(f.wire instanceof WireType.VarChar)) {
                        assertThat(wire.length()).isEqualTo(length);
                    }
                    assertThat(wire).contains(value);
                }
            }
        });
    }

    // ---- numbers in text

    @Test
    void integersInTextRoundTrip() {
        Properties.check("integer in text", g -> {
            BigInteger value = g.digits(8);
            for (Ops.F f : List.of(field("A", chr(8)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0'), field("A", chr(8)).type(SemanticType.INTEGER),
                    field("A", chr(10)).type(SemanticType.INTEGER).pad(Pad.LEFT, ' '), field("A", chr(8)).type(SemanticType.INTEGER).pad(Pad.NONE),
                    field("A", varchar(8)).type(SemanticType.INTEGER))) {
                assertThat(roundTrip(f, new ApiValue.Integer(value))).isEqualTo(new ApiValue.Integer(value));
            }
        });
    }

    @Test
    void decimalsInTextRoundTripWithTheirScale() {
        Properties.check("decimal in text", g -> {
            BigDecimal value = g.decimal(8, g.intIn(0, 4));
            for (Ops.F f : List.of(field("A", chr(12)).type(SemanticType.DECIMAL).pad(Pad.LEFT, '0'), field("A", chr(12)).type(SemanticType.DECIMAL),
                    field("A", chr(12)).type(SemanticType.DECIMAL).pad(Pad.LEFT, ' '), field("A", varchar(12)).type(SemanticType.DECIMAL))) {
                assertThat(roundTrip(f, new ApiValue.Decimal(value))).isEqualTo(new ApiValue.Decimal(value));
            }
        });
    }

    // ---- booleans

    @Test
    void booleansRoundTrip() {
        Properties.check("boolean", g -> {
            boolean value = g.bool();
            for (Ops.F f : List.of(field("A", chr(1)).type(SemanticType.BOOLEAN), field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N"),
                    field("A", chr(3)).type(SemanticType.BOOLEAN).booleans("yes", "no").pad(Pad.LEFT, '_'), field("A", varchar(4)).type(SemanticType.BOOLEAN))) {
                assertThat(roundTrip(f, new ApiValue.Bool(value))).isEqualTo(new ApiValue.Bool(value));
            }
        });
    }

    // ---- dates and times in text

    @Test
    void datesInTextRoundTrip() {
        Properties.check("date in text", g -> {
            LocalDate value = g.date();
            for (Ops.F f : List.of(field("A", chr(8)).type(SemanticType.DATE), field("A", chr(10)).type(SemanticType.DATE),
                    field("A", chr(10)).type(SemanticType.DATE).format("dd/MM/yyyy"), field("A", varchar(10)).type(SemanticType.DATE).format("yyyyMMdd"),
                    field("A", chr(12)).type(SemanticType.DATE).format("yyyy.MM.dd  ").pad(Pad.NONE))) {
                assertThat(roundTrip(f, new ApiValue.Date(value))).isEqualTo(new ApiValue.Date(value));
            }
        });
    }

    @Test
    void timesInTextRoundTrip() {
        Properties.check("time in text", g -> {
            LocalTime value = g.time();
            LocalTime wholeSeconds = value.withNano(0);
            // A format without a fraction takes whole seconds only; the codec refuses anything else instead of cutting it.
            assertThat(roundTrip(field("A", chr(8)).type(SemanticType.TIME), new ApiValue.Time(wholeSeconds))).isEqualTo(new ApiValue.Time(wholeSeconds));
            assertThat(roundTrip(field("A", chr(6)).type(SemanticType.TIME), new ApiValue.Time(wholeSeconds)))
                    .isEqualTo(wholeSeconds.equals(LocalTime.MIDNIGHT) ? new ApiValue.Absent() : new ApiValue.Time(wholeSeconds));
            assertThat(roundTrip(field("A", chr(12)).type(SemanticType.TIME).format("HH:mm:ss.SSS"), new ApiValue.Time(value))).isEqualTo(new ApiValue.Time(value));
            // 00:00:00 written as all zeros reads back as "no time": the rule of kickoff 03, changed in the next commit.
            assertThat(roundTrip(field("A", chr(9)).type(SemanticType.TIME).format("HHmmssSSS"), new ApiValue.Time(value)))
                    .isEqualTo(value.equals(LocalTime.MIDNIGHT) ? new ApiValue.Absent() : new ApiValue.Time(value));
        });
    }

    @Test
    void dateTimesInTextRoundTrip() {
        Properties.check("date-time in text", g -> {
            LocalDateTime value = g.dateTime();
            for (Ops.F f : List.of(field("A", chr(17)).type(SemanticType.DATETIME).format("yyyyMMddHHmmssSSS"),
                    field("A", chr(23)).type(SemanticType.DATETIME).format("yyyy-MM-dd'T'HH:mm:ss.SSS"))) {
                assertThat(roundTrip(f, new ApiValue.DateTime(value))).isEqualTo(new ApiValue.DateTime(value));
            }
        });
    }

    // ---- other wire types

    @Test
    void signedIntegersRoundTrip() {
        Properties.check("signed int", g -> {
            long value = g.longValue();
            assertThat(roundTrip(field("A", new WireType.Int(8, Optional.empty())), new ApiValue.Integer(BigInteger.valueOf(value))))
                    .isEqualTo(new ApiValue.Integer(BigInteger.valueOf(value)));
            short small = (short) value;
            assertThat(roundTrip(field("A", new WireType.Int(2, Optional.of(15))), new ApiValue.Integer(BigInteger.valueOf(small))))
                    .isEqualTo(new ApiValue.Integer(BigInteger.valueOf(small)));
            int medium = (int) value;
            assertThat(roundTrip(field("A", new WireType.Int(4, Optional.empty())), new ApiValue.Integer(BigInteger.valueOf(medium))))
                    .isEqualTo(new ApiValue.Integer(BigInteger.valueOf(medium)));
        });
    }

    @Test
    void unsignedIntegersRoundTrip() {
        Properties.check("unsigned int", g -> {
            long value = g.longValue();
            BigInteger unsigned64 = new BigInteger(Long.toUnsignedString(value));
            assertThat(roundTrip(field("A", new WireType.Int(8, Optional.of(64))), new ApiValue.Integer(unsigned64))).isEqualTo(new ApiValue.Integer(unsigned64));
            BigInteger unsigned32 = BigInteger.valueOf(Integer.toUnsignedLong((int) value));
            assertThat(roundTrip(field("A", new WireType.Int(4, Optional.of(32))), new ApiValue.Integer(unsigned32))).isEqualTo(new ApiValue.Integer(unsigned32));
            BigInteger unsigned16 = BigInteger.valueOf(value & 0xFFFF);
            assertThat(roundTrip(field("A", new WireType.Int(2, Optional.of(16))), new ApiValue.Integer(unsigned16))).isEqualTo(new ApiValue.Integer(unsigned16));
        });
    }

    @Test
    void packedAndZonedDecimalsRoundTripAndNeverBreakTheirDigits() {
        Properties.check("packed and zoned", g -> {
            int scale = g.intIn(0, 6);
            BigDecimal value = new BigDecimal(g.signedDigits(15), scale);
            for (WireType wire : List.of(new WireType.Packed(15, scale), new WireType.Zoned(15, scale))) {
                EncodeResult r = codec(field("A", wire)).encode(request("a", new ApiValue.Decimal(value)), NONE);
                BigDecimal wireValue = ((WireValue.Decimal) r.parameters().orElseThrow().get(0)).value();
                assertThat(wireValue.scale()).isEqualTo(scale);
                assertThat(wireValue.precision()).isLessThanOrEqualTo(15);
                assertThat(wireValue).isEqualByComparingTo(value);
                assertThat(roundTrip(field("A", wire), new ApiValue.Decimal(value))).isEqualTo(new ApiValue.Decimal(wireValue));
            }
        });
    }

    @Test
    void packedFieldsAcceptExactlyTheValuesThatFit() {
        Properties.check("packed fits", g -> {
            int digits = g.intIn(1, 9);
            int decimals = Math.min(digits, g.intIn(0, 5));
            BigDecimal value = new BigDecimal(g.signedDigits(15), g.intIn(0, 5));
            EncodeResult r = codec(field("A", new WireType.Packed(digits, decimals))).encode(request("a", new ApiValue.Decimal(value)), NONE);
            BigDecimal stripped = value.stripTrailingZeros();
            boolean fits = Math.max(stripped.scale(), 0) <= decimals && (value.signum() == 0 ? 0 : stripped.precision() - stripped.scale()) <= digits - decimals;
            assertThat(r.problems().isEmpty()).isEqualTo(fits);
            if (fits) {
                BigDecimal wire = ((WireValue.Decimal) r.parameters().orElseThrow().get(0)).value();
                assertThat(wire.scale()).isEqualTo(decimals);
                assertThat(wire.abs().compareTo(BigDecimal.TEN.pow(digits - decimals))).isLessThan(0);
            } else {
                assertThat(r.problems()).extracting(ValueProblem::code).containsExactly("VAL003");
            }
        });
    }

    @Test
    void doublesRoundTrip() {
        Properties.check("double", g -> {
            BigDecimal decimal = BigDecimal.valueOf(g.finiteDouble());
            assertThat(roundTrip(field("A", new WireType.Float(8)), new ApiValue.Decimal(decimal))).isEqualTo(new ApiValue.Decimal(decimal));
        });
    }

    @Test
    void singlePrecisionFloatsRoundTrip() {
        Properties.check("float", g -> {
            BigDecimal decimal = new BigDecimal(Float.toString(g.finiteFloat()));
            assertThat(roundTrip(field("A", new WireType.Float(4)), new ApiValue.Decimal(decimal))).isEqualTo(new ApiValue.Decimal(decimal));
        });
    }

    @Test
    void bytesRoundTrip() {
        Properties.check("bytes", g -> {
            byte[] value = g.bytes(g.intIn(0, 16));
            Ops.F f = field("A", new WireType.Byte(new Length.Fixed(value.length)));
            assertThat(roundTrip(f, new ApiValue.Binary(value))).isEqualTo(new ApiValue.Binary(value));
        });
    }

    @Test
    void datesTimesAndTimestampsPassThroughUnchanged() {
        Properties.check("date, time, timestamp", g -> {
            LocalDate date = g.date();
            LocalTime time = g.time();
            assertThat(roundTrip(field("A", new WireType.Date()), new ApiValue.Date(date))).isEqualTo(new ApiValue.Date(date));
            assertThat(roundTrip(field("A", new WireType.Time()), new ApiValue.Time(time))).isEqualTo(new ApiValue.Time(time));
            assertThat(roundTrip(field("A", new WireType.Timestamp()), new ApiValue.DateTime(LocalDateTime.of(date, time))))
                    .isEqualTo(new ApiValue.DateTime(LocalDateTime.of(date, time)));
        });
    }

    // ---- structs and arrays

    @Test
    void arraysRoundTripAndTrailingBlanksAreDropped() {
        Properties.check("array with dropBlank", g -> {
            int size = g.intIn(0, 6);
            List<Integer> given = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                given.add(g.intIn(0, 3));
            }
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
        });
    }

    @Test
    void structsOfMixedFieldsRoundTrip() {
        Properties.check("struct", g -> {
            Ops.F f = Ops.struct("S", field("N", chr(5)), field("Q", chr(5)).type(SemanticType.INTEGER).pad(Pad.LEFT, '0'),
                    field("D", chr(8)).type(SemanticType.DATE)).both();
            ApiValue.Obj value = Ops.obj("n", new ApiValue.Text(g.text(Gen.LETTERS, 0, 5)), "q", new ApiValue.Integer(g.digits(5)), "d", new ApiValue.Date(g.date()));
            assertThat(roundTrip(f, value)).isEqualTo(value);
        });
    }
}

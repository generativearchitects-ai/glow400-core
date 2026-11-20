package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.codec;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static ai.generativearchitects.slay400.pcml.codec.Ops.request;
import static ai.generativearchitects.slay400.pcml.codec.Ops.text;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.model.Length;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * kickoff 15, Part A: a char or varchar field with CCSID 65535 ("not converted") is bytes by default, the same way
 * a {@code byte} field is; the overlay may read it as text instead with {@code type: string}.
 */
class RawBytesTest {

    private static final Map<String, String> NONE = Map.of();

    private static WireType.Char rawChar(int length) {
        return new WireType.Char(new Length.Fixed(length), Optional.of(65535));
    }

    private static WireType.VarChar rawVarchar(int length) {
        return new WireType.VarChar(new Length.Fixed(length), Optional.empty(), Optional.of(65535));
    }

    private static Ops.F bytesField(WireType wire) {
        return field("A", wire).type(SemanticType.BINARY);
    }

    @Test
    void aRawBytesCharIsExactlyItsDeclaredLengthOnTheRequestSide() {
        EncodeResult ok = codec(bytesField(rawChar(3))).encode(request("a", new ApiValue.Binary(new byte[] {1, 2, 3})), NONE);
        assertThat(ok.problems()).isEmpty();
        assertThat(ok.parameters().orElseThrow()).containsExactly(new WireValue.Bytes(new byte[] {1, 2, 3}));
        assertThat(codec(bytesField(rawChar(3))).encode(request("a", new ApiValue.Binary(new byte[] {1, 2})), NONE).problems())
                .extracting(ValueProblem::code).containsExactly("VAL003");
        assertThat(codec(bytesField(rawChar(3))).encode(Map.of(), NONE).parameters().orElseThrow()).containsExactly(new WireValue.Bytes(new byte[3]));
    }

    @Test
    void aRawBytesCharIsExactlyItsDeclaredLengthOnTheResponseSide() {
        DecodeResult decoded = codec(bytesField(rawChar(3)).response()).decode(List.of(new WireValue.Bytes(new byte[] {4, 5, 6})));
        assertThat(decoded.response().orElseThrow()).containsExactly(Map.entry("a", new ApiValue.Binary(new byte[] {4, 5, 6})));
        assertThat(codec(bytesField(rawChar(3)).response()).decode(List.of(new WireValue.Bytes(new byte[] {1, 2}))).problems())
                .extracting(ValueProblem::code).containsExactly("VAL101");
    }

    @Test
    void aRawBytesVarcharKeepsItsCurrentLengthUpToTheDeclaredMaximumOnTheRequestSide() {
        EncodeResult shortValue = codec(bytesField(rawVarchar(4))).encode(request("a", new ApiValue.Binary(new byte[] {1, 2})), NONE);
        assertThat(shortValue.problems()).isEmpty();
        assertThat(shortValue.parameters().orElseThrow()).containsExactly(new WireValue.Bytes(new byte[] {1, 2}));
        assertThat(codec(bytesField(rawVarchar(4))).encode(request("a", new ApiValue.Binary(new byte[] {1, 2, 3, 4, 5})), NONE).problems())
                .extracting(ValueProblem::code).containsExactly("VAL003");
        assertThat(codec(bytesField(rawVarchar(4))).encode(Map.of(), NONE).parameters().orElseThrow()).containsExactly(new WireValue.Bytes(new byte[0]));
    }

    @Test
    void aRawBytesVarcharKeepsItsCurrentLengthUpToTheDeclaredMaximumOnTheResponseSide() {
        DecodeResult decoded = codec(bytesField(rawVarchar(4)).response()).decode(List.of(new WireValue.Bytes(new byte[] {7})));
        assertThat(decoded.response().orElseThrow()).containsExactly(Map.entry("a", new ApiValue.Binary(new byte[] {7})));
        assertThat(codec(bytesField(rawVarchar(4)).response()).decode(List.of(new WireValue.Bytes(new byte[] {1, 2, 3, 4, 5}))).problems())
                .extracting(ValueProblem::code).containsExactly("VAL101");
    }

    @Test
    void theOverlayMayReadRawBytesAsTextInTheJobsCcsid() {
        Ops.F f = field("A", rawChar(3)).type(SemanticType.STRING);
        EncodeResult r = codec(f).encode(request("a", text("ab")), NONE);
        assertThat(r.problems()).isEmpty();
        assertThat(r.parameters().orElseThrow()).containsExactly(new WireValue.Text("ab "));
        DecodeResult decoded = codec(field("A", rawChar(3)).type(SemanticType.STRING).response()).decode(List.of(new WireValue.Text("ab ")));
        assertThat(decoded.response().orElseThrow()).containsExactly(Map.entry("a", text("ab")));
    }

    @Test
    void anAllZeroRawBytesValueIsBlankTheSameWayAByteFieldIs() {
        Ops.F f = bytesField(rawChar(2)).count(2).response().trimBlank();
        WireValue.Array a = new WireValue.Array(List.of(new WireValue.Bytes(new byte[] {1, 2}), new WireValue.Bytes(new byte[2])));
        DecodeResult r = codec(f).decode(List.of(a));
        assertThat(((ApiValue.Arr) r.response().orElseThrow().get("a")).elements()).hasSize(1);
    }

    @Test
    void aRawBytesCharRoundTripsForAnyValueOfItsExactLength() {
        Properties.check("rawBytesCharRoundTrip", gen -> {
            int length = gen.intIn(0, 20);
            byte[] value = gen.bytes(length);
            Ops.F f = bytesField(rawChar(length));
            WireValue encoded = codec(f).encode(request("a", new ApiValue.Binary(value)), NONE).parameters().orElseThrow().get(0);
            assertThat(encoded).as("length=%d", length).isEqualTo(new WireValue.Bytes(value));
            ApiValue decoded = codec(bytesField(rawChar(length)).response()).decode(List.of(encoded)).response().orElseThrow().get("a");
            assertThat(decoded).as("length=%d", length).isEqualTo(new ApiValue.Binary(value));
        });
    }

    @Test
    void aRawBytesVarcharRoundTripsForAnyValueUpToItsDeclaredMaximum() {
        Properties.check("rawBytesVarcharRoundTrip", gen -> {
            int max = gen.intIn(0, 20);
            int length = gen.intIn(0, max);
            byte[] value = gen.bytes(length);
            Ops.F f = bytesField(rawVarchar(max));
            WireValue encoded = codec(f).encode(request("a", new ApiValue.Binary(value)), NONE).parameters().orElseThrow().get(0);
            assertThat(encoded).as("max=%d length=%d", max, length).isEqualTo(new WireValue.Bytes(value));
            ApiValue decoded = codec(bytesField(rawVarchar(max)).response()).decode(List.of(encoded)).response().orElseThrow().get("a");
            assertThat(decoded).as("max=%d length=%d", max, length).isEqualTo(new ApiValue.Binary(value));
        });
    }
}

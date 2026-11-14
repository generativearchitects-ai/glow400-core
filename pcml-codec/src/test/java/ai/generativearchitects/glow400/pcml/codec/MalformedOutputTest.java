package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.int4;
import static ai.generativearchitects.glow400.pcml.codec.Ops.opWith;
import static ai.generativearchitects.glow400.pcml.codec.Ops.struct;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a program may return is data, and decoding never throws for data: output of the wrong shape is VAL101, for the
 * fields, for a field that holds a count, for a PCML count reference and for the fields of the error mapping.
 */
class MalformedOutputTest {

    private static WireValue t(String s) {
        return new WireValue.Text(s);
    }

    private static WireValue num(long n) {
        return new WireValue.Integer(BigInteger.valueOf(n));
    }

    private static WireValue ws(WireValue... members) {
        return new WireValue.Struct(List.of(members));
    }

    private static WireValue wa(WireValue... elements) {
        return new WireValue.Array(List.of(elements));
    }

    /** HDR (a struct with the count N) and TAG (3 elements, trimmed by HDR.N). */
    private static OperationCodec trimmed() {
        return Ops.codec(struct("HDR", field("N", int4())).internal(), field("TAG", chr(2)).count(3).response().trimCount("HDR.N"));
    }

    private static OperationCodec mapped(ErrorMapping mapping) {
        return OperationCodec.of(opWith(mapping, struct("HDR", field("N", chr(2))).internal(), field("OUT", chr(2)).response().exposed("out")));
    }

    private static List<String> codes(DecodeResult result) {
        return result.problems().stream().map(ValueProblem::code).toList();
    }

    private static void assertRefused(DecodeResult result, String... paths) {
        assertThat(result.response()).isEmpty();
        assertThat(result.error()).isEmpty();
        assertThat(codes(result)).isNotEmpty().containsOnly("VAL101");
        if (paths.length > 0) {
            assertThat(result.problems()).extracting(ValueProblem::fieldPath).containsExactly(paths);
        }
    }

    // ---- a count field inside a struct

    @Test
    void aStructThatHoldsTheCountMustBeAStructWithItsMembers() {
        WireValue tag = wa(t("a"), t("b"), t("c"));
        OperationCodec codec = trimmed();
        assertRefused(codec.decode(List.of(t("x"), tag)), "HDR");
        assertRefused(codec.decode(List.of(ws(), tag)), "HDR");
        assertRefused(codec.decode(List.of(ws(num(1), num(2)), tag)), "HDR");
        assertRefused(codec.decode(List.of(wa(num(1)), tag)), "HDR");
        assertRefused(codec.decode(List.of(num(1), tag)), "HDR");
        assertRefused(codec.decode(List.of(new WireValue.Bytes(new byte[1]), tag)), "HDR");
    }

    @Test
    void aBlankStructHasNoCountToReadAndThatIsAProblemToo() {
        DecodeResult result = trimmed().decode(List.of(new WireValue.Blank(), wa(t("a"), t("b"), t("c"))));
        assertRefused(result, "TAG");
        assertThat(result.problems().get(0).message()).isEqualTo("The field that holds the number of elements cannot be read: the output does not have its struct");
    }

    @Test
    void theListThatTheCountTrimsMustHaveTheNumberOfElementsOfTheField() {
        OperationCodec codec = trimmed();
        WireValue hdr = ws(num(2));
        assertRefused(codec.decode(List.of(hdr, wa(t("a"), t("b")))), "TAG");
        assertRefused(codec.decode(List.of(hdr, wa(t("a"), t("b"), t("c"), t("d")))), "TAG");
        assertRefused(codec.decode(List.of(hdr, t("x"))), "TAG");
        assertRefused(codec.decode(List.of(hdr, ws())), "TAG");
        DecodeResult fine = codec.decode(List.of(hdr, wa(t("a"), t("b"), t("c"))));
        assertThat(fine.problems()).isEmpty();
        assertThat(((ApiValue.Arr) fine.response().orElseThrow().get("tag")).elements()).hasSize(2);
    }

    @Test
    void everyMismatchIsReportedNotOnlyTheFirst() {
        DecodeResult result = trimmed().decode(List.of(ws(), wa(t("a"))));
        assertThat(result.problems()).extracting(ValueProblem::fieldPath).containsExactly("HDR", "TAG");
    }

    // ---- the error mapping

    @Test
    void theFieldsOfTheErrorMappingAreReadOnlyFromOutputOfTheRightShape() {
        ErrorMapping mapping = new ErrorMapping(new ErrorCondition.Equals("HDR.N", "KO"), Optional.empty(), Optional.empty(), 422, Map.of());
        OperationCodec codec = mapped(mapping);
        assertRefused(codec.decode(List.of(t("x"), t("ok"))), "HDR");
        assertRefused(codec.decode(List.of(ws(), t("ok"))), "HDR");
        assertRefused(codec.decode(List.of(ws(t("KO"), t("KO")), t("ok"))), "HDR");
        assertRefused(codec.decode(List.of(num(1), t("ok"))), "HDR");
        DecodeResult blank = codec.decode(List.of(new WireValue.Blank(), t("ok")));
        assertRefused(blank, "HDR.N");
        assertThat(codec.decode(List.of(ws(t("KO")), t("ok"))).error()).isPresent();
        assertThat(codec.decode(List.of(ws(t("  ")), t("ok"))).response()).isPresent();
    }

    @Test
    void theCodeAndMessageFieldsOfTheErrorMappingAreProtectedToo() {
        ErrorMapping mapping = new ErrorMapping(new ErrorCondition.Equals("OUT", "KO"), Optional.of("HDR.N"), Optional.of("HDR.N"), 422, Map.of());
        OperationCodec codec = mapped(mapping);
        assertRefused(codec.decode(List.of(ws(), t("KO"))), "HDR");
        assertRefused(codec.decode(List.of(t("x"), t("KO"))), "HDR");
        assertThat(codec.decode(List.of(ws(t("E1")), t("KO"))).error().orElseThrow().code()).contains("E1");
    }

    // ---- a PCML count reference

    @Test
    void aCountReferenceNextToTheArrayNeedsTheShapeOfItsStruct() {
        OperationCodec codec = Ops.codec(struct("S", field("N", int4()), field("TAG", chr(2)).count(3).countRef("N")).response().exposed("s"));
        assertThat(codec.decode(List.of(ws(num(2), wa(t("a"), t("b"), t("c"))))).problems()).isEmpty();
        assertRefused(codec.decode(List.of(t("x"))), "S");
        assertRefused(codec.decode(List.of(ws(num(2)))), "S");
        // the count says 2 and the list has 1: that is the count's problem, not the shape's
        DecodeResult tooShort = codec.decode(List.of(ws(num(2), wa(t("a")))));
        assertThat(tooShort.response()).isEmpty();
        assertThat(codes(tooShort)).containsExactly("VAL103");
        assertRefused(codec.decode(List.of(ws(num(2), t("x")))), "S.TAG");
        assertRefused(codec.decode(List.of(ws(num(2), wa(t("a"), t("b"), t("c")), t("extra")))), "S");
        // a blank struct is a blank struct: nothing to read
        assertThat(codec.decode(List.of(new WireValue.Blank())).problems()).isEmpty();
    }

    @Test
    void anArrayOfStructsIsCheckedElementByElement() {
        OperationCodec codec = Ops.codec(struct("R", field("X", chr(2)), field("Y", int4())).count(2).response().exposed("r"));
        assertThat(codec.decode(List.of(wa(ws(t("a"), num(1)), new WireValue.Blank()))).problems()).isEmpty();
        assertRefused(codec.decode(List.of(wa(ws(t("a")), ws(t("a"), num(1))))), "R[0]");
        assertRefused(codec.decode(List.of(wa(ws(t("a"), num(1)), t("x")))), "R[1]");
        assertRefused(codec.decode(List.of(wa(ws(t("a"), num(1))))), "R");
        assertRefused(codec.decode(List.of(wa(ws(num(1), num(1)), ws(t("a"), t("x"))))), "R[0].X", "R[1].Y");
    }

    // ---- the exceptions the codec throws are not reachable from data

    @Test
    void aFieldThatCannotBeEncodedHasNoCodecSoTheThrowsInsideItAreNotReachable() {
        // byte and date fields cannot be injected, a text field cannot hold BINARY, an array counted by another field is not a request field
        List<Ops.F> bad = List.of(
                field("A", new WireType.Byte(new ai.generativearchitects.glow400.pcml.model.Length.Fixed(2))).inject("ab"),
                field("A", new WireType.Date()).type(ai.generativearchitects.glow400.pcml.api.SemanticType.DATE).inject("2026-01-31"),
                field("A", new WireType.Time()).type(ai.generativearchitects.glow400.pcml.api.SemanticType.TIME).inject("10:20:30"),
                field("A", new WireType.Timestamp()).type(ai.generativearchitects.glow400.pcml.api.SemanticType.DATETIME).inject("x"),
                field("A", chr(4)).type(ai.generativearchitects.glow400.pcml.api.SemanticType.BINARY),
                field("A", Ops.varchar(4)).type(ai.generativearchitects.glow400.pcml.api.SemanticType.BINARY));
        for (Ops.F field : bad) {
            assertThat(OperationCodec.check(Ops.op(field))).isNotEmpty();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> OperationCodec.of(Ops.op(field))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(BigDecimal.ONE).isNotNull();
        assertThat(new ArrayList<>(List.of(1))).hasSize(1);
    }
}

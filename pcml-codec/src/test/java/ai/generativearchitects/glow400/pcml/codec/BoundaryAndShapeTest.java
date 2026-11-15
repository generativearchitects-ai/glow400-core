package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.codec;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.opWith;
import static ai.generativearchitects.glow400.pcml.codec.Ops.request;
import static ai.generativearchitects.glow400.pcml.codec.Ops.struct;
import static ai.generativearchitects.glow400.pcml.codec.Ops.text;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Boundaries at a length of zero, the shapes the program may return, and messages: what the mutation analysis found untested. */
class BoundaryAndShapeTest {

    private static WireType.Byte bytes(int length) {
        return new WireType.Byte(new Length.Fixed(length));
    }

    private static List<String> issues(Ops.F... fields) {
        return OperationCodec.check(Ops.op(fields)).stream().map(i -> i.fieldPath() + ": " + i.message()).toList();
    }

    private static WireValue.Text t(String s) {
        return new WireValue.Text(s);
    }

    // ---- hash codes of the values that hold bytes

    @Test
    void valuesThatHoldBytesHashByTheirContent() {
        assertThat(new ApiValue.Binary(new byte[] {1, 2}).hashCode()).isEqualTo(new ApiValue.Binary(new byte[] {1, 2}).hashCode()).isNotZero();
        assertThat(new ApiValue.Binary(new byte[] {1, 2}).hashCode()).isNotEqualTo(new ApiValue.Binary(new byte[] {1, 3}).hashCode());
        assertThat(new WireValue.Bytes(new byte[] {1, 2}).hashCode()).isEqualTo(new WireValue.Bytes(new byte[] {1, 2}).hashCode()).isNotZero();
        assertThat(new WireValue.Bytes(new byte[] {1, 2}).hashCode()).isNotEqualTo(new WireValue.Bytes(new byte[] {1, 3}).hashCode());
    }

    // ---- a field of length zero is a field like any other

    @Test
    void aTextFieldOfLengthZeroHoldsOnlyTheEmptyText() {
        OperationCodec codec = codec(field("A", chr(0)));
        assertThat(codec.encode(request("a", text("")), Map.of()).problems()).isEmpty();
        assertThat(codec.encode(request("a", text("x")), Map.of()).problems()).extracting(ValueProblem::code).containsExactly("VAL003");
        assertThat(issues(field("A", chr(0)))).isEmpty();
        assertThat(issues(field("A", chr(0)).inject(""))).isEmpty();
    }

    @Test
    void aFieldOfLengthZeroHasNoRoomForANullTextABooleanTextOrADateFormat() {
        assertThat(issues(field("A", chr(0)).nullAs("x"))).containsExactly("A: nullAs is longer than the field");
        assertThat(issues(field("A", chr(0)).type(SemanticType.BOOLEAN))).containsExactly("A: trueValue or falseValue is longer than the field");
        assertThat(issues(field("A", chr(0)).type(SemanticType.DATE).format("yyyyMMdd")))
                .containsExactly("A: the format produces 8 characters, which is not the length of the field");
    }

    @Test
    void aByteFieldOfLengthZeroHoldsNoBytes() {
        OperationCodec codec = codec(field("B", bytes(0)));
        assertThat(codec.encode(request("b", new ApiValue.Binary(new byte[0])), Map.of()).problems()).isEmpty();
        assertThat(codec.encode(request("b", new ApiValue.Binary(new byte[1])), Map.of()).problems()).extracting(ValueProblem::code).containsExactly("VAL003");
        OperationCodec response = codec(field("B", bytes(0)).response());
        assertThat(response.decode(List.of(new WireValue.Bytes(new byte[0]))).problems()).isEmpty();
        assertThat(response.decode(List.of(new WireValue.Bytes(new byte[1]))).problems()).extracting(ValueProblem::code).containsExactly("VAL101");
        // a fixed length of zero is not "given by another field"
        assertThat(issues(field("B", bytes(0)), field("C", bytes(0)).inject("")).stream().filter(m -> m.contains("another field"))).isEmpty();
    }

    // ---- blank elements of an array

    @Test
    void trailingBlankElementsAreDroppedWhateverTheTypeOfTheElements() {
        WireValue bytesArray = new WireValue.Array(List.of(new WireValue.Bytes(new byte[] {1, 2}), new WireValue.Blank(), new WireValue.Bytes(new byte[] {0, 0})));
        ApiValue.Arr kept = (ApiValue.Arr) codec(field("A", bytes(2)).count(3).response().trimBlank()).decode(List.of(bytesArray)).response().orElseThrow().get("a");
        assertThat(kept.elements()).hasSize(1);
        WireValue dates = new WireValue.Array(List.of(new WireValue.Date(java.time.LocalDate.of(2026, 1, 31)), new WireValue.Blank(), new WireValue.Blank()));
        ApiValue.Arr keptDates = (ApiValue.Arr) codec(field("A", new WireType.Date()).type(SemanticType.DATE).count(3).response().trimBlank())
                .decode(List.of(dates)).response().orElseThrow().get("a");
        assertThat(keptDates.elements()).hasSize(1);
        WireValue texts = new WireValue.Array(List.of(t("ab"), new WireValue.Blank(), t("  ")));
        ApiValue.Arr keptTexts = (ApiValue.Arr) codec(field("A", chr(2)).count(3).response().trimBlank()).decode(List.of(texts)).response().orElseThrow().get("a");
        assertThat(keptTexts.elements()).hasSize(1);
    }

    @Test
    void anElementOfTheWrongShapeIsNotBlankAndIsReported() {
        WireValue wrongForBytes = new WireValue.Array(List.of(t("x"), t("x"), t("x")));
        DecodeResult bytes = codec(field("A", bytes(2)).count(3).response().trimBlank()).decode(List.of(wrongForBytes));
        assertThat(bytes.problems()).extracting(ValueProblem::code).contains("VAL101");
        WireValue wrongForDates = new WireValue.Array(List.of(t("x"), t("x"), t("x")));
        DecodeResult dates = codec(field("A", new WireType.Date()).type(SemanticType.DATE).count(3).response().trimBlank()).decode(List.of(wrongForDates));
        assertThat(dates.problems()).extracting(ValueProblem::code).contains("VAL101");
    }

    @Test
    void aBlankReturnedForAFieldIsAbsentAndForAStructAnEmptyObject() {
        DecodeResult scalar = codec(field("A", chr(4)).response()).decode(List.of(new WireValue.Blank()));
        assertThat(scalar.problems()).isEmpty();
        assertThat(scalar.response().orElseThrow()).isEmpty();
        DecodeResult struct = codec(struct("S", field("X", chr(2))).response().exposed("s")).decode(List.of(new WireValue.Blank()));
        assertThat(struct.problems()).isEmpty();
        assertThat(struct.response().orElseThrow()).containsEntry("s", new ApiValue.Obj(Map.of()));
        // an array of structs with a blank element keeps the position of the element
        DecodeResult elements = codec(struct("S", field("X", chr(2))).count(2).response().exposed("s"))
                .decode(List.of(new WireValue.Array(List.of(new WireValue.Blank(), new WireValue.Struct(List.of(t("ab")))))));
        assertThat(elements.problems()).isEmpty();
        assertThat(((ApiValue.Arr) elements.response().orElseThrow().get("s")).elements().get(0)).isEqualTo(new ApiValue.Obj(Map.of()));
    }

    // ---- references

    @Test
    void aReferenceThatIsJustTheProgramNameIsNotAField() {
        ErrorMapping mapping = new ErrorMapping(new ErrorCondition.Equals("PGM", "x"), Optional.empty(), Optional.empty(), 422, Map.of());
        OperationCodec codec = OperationCodec.of(opWith(mapping, field("A", chr(2)).response().exposed("a")));
        DecodeResult result = codec.decode(List.of(t("ab")));
        assertThat(result.problems()).extracting(ValueProblem::code).containsExactly("VAL101");
    }

    @Test
    void aProblemInAMemberOfAStructIsFoundWithItsPath() {
        assertThat(issues(struct("S", field("X", chr(2)).example("abc")))).containsExactly("S.X: the example cannot be encoded in the field (VAL003)");
        assertThat(issues(struct("S", field("X", chr(2)).type(SemanticType.BOOLEAN).booleans("YES", "NO")))).hasSize(1);
    }

    // ---- messages

    @Test
    void aDateOrTimeFieldNamesWhatItExpectsInItsProblems() {
        Object[][] kinds = {{new WireType.Date(), SemanticType.DATE, "The value is not a date", "A date was expected"},
                {new WireType.Time(), SemanticType.TIME, "The value is not a time", "A time was expected"},
                {new WireType.Timestamp(), SemanticType.DATETIME, "The value is not a datetime", "A timestamp was expected"}};
        for (Object[] kind : kinds) {
            OperationCodec request = codec(field("A", (WireType) kind[0]).type((SemanticType) kind[1]));
            assertThat(request.encode(request("a", text("x")), Map.of()).problems()).extracting(ValueProblem::message).containsExactly((String) kind[2]);
            OperationCodec response = codec(field("A", (WireType) kind[0]).type((SemanticType) kind[1]).response());
            assertThat(response.decode(List.of(t("x"))).problems()).extracting(ValueProblem::message).containsExactly((String) kind[3]);
        }
    }

    @Test
    void theMessageOfADecimalWithTooManyDigitsStatesTheIntegerDigitsAllowed() {
        OperationCodec codec = codec(field("A", new WireType.Packed(9, 2)));
        EncodeResult result = codec.encode(request("a", Ops.decimal("12345678")), Map.of());
        assertThat(result.problems()).extracting(ValueProblem::message).containsExactly("The number has too many digits: at most 7 before the decimal point");
        assertThat(codec.encode(request("a", Ops.decimal("1234567.99")), Map.of()).problems()).isEmpty();
    }

    @Test
    void aBlankNumberInsideAnArrayIsAbsentAndKeepsItsPlace() {
        for (SemanticType type : List.of(SemanticType.INTEGER, SemanticType.DECIMAL)) {
            WireValue values = new WireValue.Array(List.of(t("12"), t("  ")));
            DecodeResult result = codec(field("A", chr(2)).type(type).count(2).response()).decode(List.of(values));
            assertThat(result.problems()).isEmpty();
            List<ApiValue> elements = ((ApiValue.Arr) result.response().orElseThrow().get("a")).elements();
            assertThat(elements).hasSize(2);
            assertThat(elements.get(1)).isEqualTo(new ApiValue.Absent());
        }
    }

    @Test
    void aWholeNumberFieldAndADecimalFieldSayWhatTheyExpect() {
        EncodeResult decimal = codec(field("A", new WireType.Packed(5, 2))).encode(request("a", text("x")), Map.of());
        assertThat(decimal.problems()).extracting(ValueProblem::message).containsExactly("The value is not a number");
        EncodeResult whole = codec(field("A", new WireType.Packed(5, 0)).type(SemanticType.INTEGER)).encode(request("a", text("x")), Map.of());
        assertThat(whole.problems()).extracting(ValueProblem::message).containsExactly("The value is not a whole number");
        EncodeResult floating = codec(field("A", new WireType.Float(8))).encode(request("a", text("x")), Map.of());
        assertThat(floating.problems()).extracting(ValueProblem::message).containsExactly("The value is not a number");
        EncodeResult integer = codec(field("A", Ops.int4())).encode(request("a", text("x")), Map.of());
        assertThat(integer.problems()).extracting(ValueProblem::message).containsExactly("The value is not a whole number");
    }

    @Test
    void anObjectOrAListWhereASingleValueIsExpectedIsReported() {
        OperationCodec codec = codec(field("A", chr(4)));
        for (ApiValue wrong : List.of(Ops.obj("x", text("a")), Ops.arr(text("a")))) {
            EncodeResult result = codec.encode(request("a", wrong), Map.of());
            assertThat(result.problems()).extracting(ValueProblem::code).containsExactly("VAL002");
            assertThat(result.problems()).extracting(ValueProblem::message).containsExactly("A single value was expected");
        }
    }

    @Test
    void aBlankElementInTheMiddleOfAnArrayIsAbsentAndKeepsItsPlace() {
        WireValue values = new WireValue.Array(List.of(t("ab"), new WireValue.Blank(), t("cd")));
        DecodeResult result = codec(field("A", chr(2)).count(3).response()).decode(List.of(values));
        assertThat(result.problems()).isEmpty();
        assertThat(((ApiValue.Arr) result.response().orElseThrow().get("a")).elements())
                .containsExactly(new ApiValue.Text("ab"), new ApiValue.Absent(), new ApiValue.Text("cd"));
    }

    @Test
    void anElementOfAnArrayOfObjectsIsBlankWhenItsMembersAreBlankWhateverTheirShape() {
        Ops.F rows = struct("S", field("X", chr(2)), field("T", chr(2)).count(2)).count(3).response().trimBlank().exposed("s");
        WireValue full = new WireValue.Struct(List.of(t("ab"), new WireValue.Array(List.of(t("  "), t("  ")))));
        WireValue blankMembers = new WireValue.Struct(List.of(t("  "), new WireValue.Array(List.of(t("  "), t("  ")))));
        WireValue blankArrayMember = new WireValue.Struct(List.of(t("  "), new WireValue.Blank()));
        DecodeResult result = codec(rows).decode(List.of(new WireValue.Array(List.of(full, blankMembers, blankArrayMember))));
        assertThat(result.problems()).isEmpty();
        assertThat(((ApiValue.Arr) result.response().orElseThrow().get("s")).elements()).hasSize(1);
        // a member that is not blank keeps the element
        WireValue filled = new WireValue.Struct(List.of(t("  "), new WireValue.Array(List.of(t("zz"), t("  ")))));
        DecodeResult kept = codec(rows).decode(List.of(new WireValue.Array(List.of(blankMembers, filled, blankMembers))));
        assertThat(((ApiValue.Arr) kept.response().orElseThrow().get("s")).elements()).hasSize(2);
        // a struct of the wrong shape is not blank and is reported
        DecodeResult wrong = codec(rows).decode(List.of(new WireValue.Array(List.of(new WireValue.Struct(List.of(t("  "))), t("  "), t("  ")))));
        assertThat(wrong.problems()).extracting(ValueProblem::code).contains("VAL101");
    }

    @Test
    void aTimeOrDateTimeThatTheFormatCannotHoldIsNotWrittenWithPartOfItLost() {
        OperationCodec time = codec(field("A", chr(6)).type(SemanticType.TIME));
        assertThat(time.encode(request("a", new ApiValue.Time(java.time.LocalTime.of(10, 20, 30))), Map.of()).problems()).isEmpty();
        EncodeResult fraction = time.encode(request("a", new ApiValue.Time(java.time.LocalTime.of(10, 20, 30, 123_000_000))), Map.of());
        assertThat(fraction.problems()).extracting(ValueProblem::code).containsExactly("VAL003");
        assertThat(fraction.problems().get(0).message()).isEqualTo("The value cannot be written in the field's format without losing part of it");
        OperationCodec dateTime = codec(field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"));
        assertThat(dateTime.encode(request("a", new ApiValue.DateTime(java.time.LocalDateTime.of(2026, 1, 31, 10, 20, 30))), Map.of()).problems()).isEmpty();
        EncodeResult lost = dateTime.encode(request("a", new ApiValue.DateTime(java.time.LocalDateTime.of(2026, 1, 31, 10, 20, 30, 5_000_000))), Map.of());
        assertThat(lost.problems()).extracting(ValueProblem::code).containsExactly("VAL003");
    }
}

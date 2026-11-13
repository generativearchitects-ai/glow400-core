package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.int4;
import static ai.generativearchitects.glow400.pcml.codec.Ops.op;
import static ai.generativearchitects.glow400.pcml.codec.Ops.struct;
import static ai.generativearchitects.glow400.pcml.codec.Ops.varchar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What makes an operation impossible to encode or decode, found by the codec's own rules (OVL003). */
class ConfigurationCheckTest {

    private static List<String> issues(Ops.F... fields) {
        return OperationCodec.check(op(fields)).stream().map(ConfigurationIssue::message).toList();
    }

    private static List<ConfigurationIssue> issueObjects(Ops.F... fields) {
        return OperationCodec.check(op(fields));
    }

    private static void assertOk(Ops.F... fields) {
        assertThat(issues(fields)).isEmpty();
        OperationCodec.of(op(fields));
    }

    private static void assertIssue(String fragment, Ops.F... fields) {
        assertThat(issues(fields)).as("issues").anySatisfy(m -> assertThat(m).contains(fragment));
        assertThatThrownBy(() -> OperationCodec.of(op(fields))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(fragment);
    }

    // ---- semantic type against wire type

    @Test
    void everySemanticTypeIsAllowedOnExactlyTheWireTypesOfTheTable() {
        Map<String, WireType> wires = new java.util.LinkedHashMap<>();
        wires.put("char", chr(20));
        wires.put("varchar", varchar(20));
        wires.put("int", int4());
        wires.put("packed2", new WireType.Packed(5, 2));
        wires.put("packed0", new WireType.Packed(5, 0));
        wires.put("zoned2", new WireType.Zoned(5, 2));
        wires.put("zoned0", new WireType.Zoned(5, 0));
        wires.put("float", new WireType.Float(8));
        wires.put("byte", new WireType.Byte(new Length.Fixed(3)));
        wires.put("date", new WireType.Date());
        wires.put("time", new WireType.Time());
        wires.put("timestamp", new WireType.Timestamp());
        Map<String, List<SemanticType>> allowed = Map.ofEntries(
                Map.entry("char", List.of(SemanticType.STRING, SemanticType.INTEGER, SemanticType.DECIMAL, SemanticType.BOOLEAN,
                        SemanticType.DATE, SemanticType.TIME, SemanticType.DATETIME)),
                Map.entry("varchar", List.of(SemanticType.STRING, SemanticType.INTEGER, SemanticType.DECIMAL, SemanticType.BOOLEAN,
                        SemanticType.DATE, SemanticType.TIME, SemanticType.DATETIME)),
                Map.entry("int", List.of(SemanticType.INTEGER)),
                Map.entry("packed2", List.of(SemanticType.DECIMAL)),
                Map.entry("packed0", List.of(SemanticType.DECIMAL, SemanticType.INTEGER)),
                Map.entry("zoned2", List.of(SemanticType.DECIMAL)),
                Map.entry("zoned0", List.of(SemanticType.DECIMAL, SemanticType.INTEGER)),
                Map.entry("float", List.of(SemanticType.DECIMAL)),
                Map.entry("byte", List.of(SemanticType.BINARY)),
                Map.entry("date", List.of(SemanticType.DATE)),
                Map.entry("time", List.of(SemanticType.TIME)),
                Map.entry("timestamp", List.of(SemanticType.DATETIME)));
        for (var wire : wires.entrySet()) {
            for (SemanticType semantic : SemanticType.values()) {
                boolean shouldBeAllowed = allowed.get(wire.getKey()).contains(semantic);
                boolean flagged = issues(field("A", wire.getValue()).type(semantic)).stream().anyMatch(m -> m.contains("not allowed"));
                assertThat(flagged).as(semantic + " on " + wire.getKey()).isEqualTo(!shouldBeAllowed);
            }
        }
    }

    @Test
    void integerOnPackedOrZonedNeedsNoDecimalPlaces() {
        assertIssue("INTEGER is not allowed for a packed field with decimal places", field("A", new WireType.Packed(5, 2)).type(SemanticType.INTEGER));
        assertIssue("INTEGER is not allowed for a zoned field with decimal places", field("A", new WireType.Zoned(5, 1)).type(SemanticType.INTEGER));
        assertOk(field("A", new WireType.Packed(5, 0)).type(SemanticType.INTEGER), field("B", new WireType.Zoned(5, 0)).type(SemanticType.INTEGER));
    }

    // ---- pad

    @Test
    void padOtherThanNoneNeedsAChar() {
        assertIssue("pad applies only to char fields", field("A", new WireType.Packed(5, 0)).pad(Pad.LEFT, '0'));
        assertIssue("pad applies only to char fields", field("A", int4()).pad(Pad.RIGHT));
        assertIssue("pad applies only to char fields", field("A", new WireType.Date()).pad(Pad.LEFT));
        assertIssue("pad applies only to char fields, not to varchar", field("A", varchar(3)).pad(Pad.LEFT, '0'));
        assertIssue("pad applies only to char fields, not to varchar", field("A", varchar(3)).pad(Pad.RIGHT));
        assertOk(field("A", int4()).pad(Pad.NONE), field("B", chr(3)).pad(Pad.LEFT, '0'), field("C", varchar(3)).pad(Pad.NONE, '0'));
    }

    // ---- date, time and their formats on char

    @Test
    void aDateOrTimeOnCharNeedsAFormatOrADefaultForItsLength() {
        assertOk(field("A", chr(8)).type(SemanticType.DATE), field("B", chr(10)).type(SemanticType.DATE),
                field("C", chr(6)).type(SemanticType.TIME), field("D", chr(8)).type(SemanticType.TIME));
        assertIssue("no format is given and there is no default format", field("A", chr(9)).type(SemanticType.DATE));
        assertIssue("no format is given and there is no default format", field("A", chr(7)).type(SemanticType.TIME));
        assertIssue("no format is given and there is no default format", field("A", chr(14)).type(SemanticType.DATETIME));
        assertOk(field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"), field("B", chr(11)).type(SemanticType.DATE).format("yyyy/MM/dd "));
    }

    @Test
    void aFormatWithUnsupportedPartsIsReported() {
        for (String pattern : new String[] {"yy-MM-dd", "yyyy-M-dd", "yyyy-MM-d", "dd.MM.yy", "EEE yyyyMMdd", "yyyyMMdd'", "yyyyyyMMdd", "yyyy-MM-ddd",
                "yyyyMMddyyyy", "SSSSSSSSSS", ""}) {
            assertIssue("the format is not supported", field("A", chr(8)).type(SemanticType.DATE).format(pattern));
        }
    }

    @Test
    void aFormatMustSuitTheType() {
        assertIssue("a date pattern needs", field("A", chr(8)).type(SemanticType.DATE).format("yyyyMMdd"
                .replace("dd", "HH")));
        assertIssue("a date pattern needs", field("A", chr(14)).type(SemanticType.DATE).format("yyyyMMddHHmmss"));
        assertIssue("a time pattern needs", field("A", chr(6)).type(SemanticType.TIME).format("HHmm  "));
        assertIssue("a time pattern needs", field("A", chr(10)).type(SemanticType.TIME).format("yyyyHHmmss"));
        assertIssue("a time pattern needs", field("A", chr(14)).type(SemanticType.TIME).format("yyyyMMddHHmmss"));
        assertIssue("a date-time pattern needs", field("A", chr(8)).type(SemanticType.DATETIME).format("yyyyMMdd"));
        assertIssue("a date-time pattern needs", field("A", chr(6)).type(SemanticType.DATETIME).format("HHmmss"));
        assertOk(field("A", chr(15)).type(SemanticType.TIME).format("HH:mm:ss.SSSSSS"), field("B", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss"));
    }

    @Test
    void theFormatMustProduceTheLengthOfACharFieldOrFitAVarchar() {
        assertIssue("the format produces 8 characters, which is not the length of the field", field("A", chr(10)).type(SemanticType.DATE).format("yyyyMMdd"));
        assertIssue("the format produces 10 characters, which is not the length of the field", field("A", chr(8)).type(SemanticType.DATE).format("yyyy-MM-dd"));
        assertOk(field("A", varchar(10)).type(SemanticType.DATE).format("yyyyMMdd"), field("B", varchar(8)).type(SemanticType.DATE).format("yyyyMMdd"));
        assertIssue("do not fit the field", field("A", varchar(7)).type(SemanticType.DATE).format("yyyyMMdd"));
    }

    // ---- boolean texts

    @Test
    void booleanTextsMustFitTheFieldAndDiffer() {
        assertOk(field("A", chr(1)).type(SemanticType.BOOLEAN), field("B", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "N"),
                field("C", chr(4)).type(SemanticType.BOOLEAN).booleans("true", "false".substring(0, 4)));
        assertIssue("trueValue or falseValue is longer than the field", field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("YES", "N"));
        assertIssue("trueValue or falseValue is longer than the field", field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "NO"));
        assertIssue("trueValue and falseValue are equal", field("A", chr(1)).type(SemanticType.BOOLEAN).booleans("Y", "Y"));
        assertIssue("trueValue and falseValue are equal", field("A", chr(1)).type(SemanticType.BOOLEAN).trueValue("0"));
        assertOk(field("A", chr(1)).type(SemanticType.BOOLEAN).trueValue("Y").falseValue("N"));
    }

    @Test
    void booleanTextsOnAnotherTypeAreReported() {
        assertIssue("apply only to boolean fields", field("A", chr(3)).trueValue("Y"));
        assertIssue("apply only to boolean fields", field("A", chr(3)).falseValue("N"));
        assertIssue("apply only to boolean fields", field("A", int4()).booleans("Y", "N"));
        assertIssue("apply only to boolean fields", struct("S", field("M", chr(1))).falseValue("N"));
    }

    // ---- nullAs

    @Test
    void nullAsMustFitTheField() {
        assertOk(field("A", chr(3)).nullAs("N/A"), field("B", varchar(3)).nullAs("123"), field("C", chr(3)).nullAs(""));
        assertIssue("nullAs is longer than the field", field("A", chr(2)).nullAs("N/A"));
        assertIssue("nullAs is longer than the field", field("A", varchar(2)).nullAs("N/A"));
        assertOk(field("A", chr(1)).nullAs("𝄞"));
    }

    // ---- examples

    @Test
    void anExampleMustBeAnEncodableValueInItsApiForm() {
        assertOk(field("A", chr(5)).example("hello"), field("B", int4()).example("-42"), field("C", new WireType.Packed(5, 2)).example("123.45"),
                field("D", chr(8)).type(SemanticType.DATE).example("2026-11-05"), field("E", chr(6)).type(SemanticType.TIME).example("09:05:07"),
                field("F", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss").example("2026-11-05T09:05:07"),
                field("G", chr(1)).type(SemanticType.BOOLEAN).example("true"), field("H", new WireType.Byte(new Length.Fixed(3))).example("AQID"),
                field("I", new WireType.Float(8)).example("1.5"), field("J", chr(5)).type(SemanticType.INTEGER).example("42"),
                field("K", chr(8)).type(SemanticType.DECIMAL).example("12.5"));
        assertIssue("the example cannot be encoded in the field (VAL003)", field("A", chr(3)).example("toolong"));
        assertIssue("the example cannot be encoded in the field (VAL003)", field("A", new WireType.Packed(5, 2)).example("1234.5"));
        assertIssue("the example cannot be encoded in the field (VAL003)", field("A", int4()).example("99999999999"));
        assertIssue("the example cannot be encoded in the field (VAL003)", field("A", chr(5)).type(SemanticType.INTEGER).example("-1"));
        assertIssue("the example cannot be encoded in the field (VAL003)", field("A", new WireType.Byte(new Length.Fixed(3))).example("AQ=="));
        assertIssue("the example cannot be encoded in the field (VAL003)", field("A", chr(6)).type(SemanticType.TIME).example("09:05:07.5"));
    }

    @Test
    void anExampleThatIsNotOfItsTypeIsReported() {
        assertIssue("the example is not a valid integer value", field("A", int4()).example("1.5"));
        assertIssue("the example is not a valid integer value", field("A", int4()).example("abc"));
        assertIssue("the example is not a valid decimal value", field("A", new WireType.Packed(5, 2)).example("1,5"));
        assertIssue("the example is not a valid boolean value", field("A", chr(1)).type(SemanticType.BOOLEAN).example("1"));
        assertIssue("the example is not a valid boolean value", field("A", chr(1)).type(SemanticType.BOOLEAN).example("True"));
        assertIssue("the example is not a valid date value", field("A", chr(8)).type(SemanticType.DATE).example("20261105"));
        assertIssue("the example is not a valid date value", field("A", chr(8)).type(SemanticType.DATE).example("2026-13-05"));
        assertIssue("the example is not a valid time value", field("A", chr(6)).type(SemanticType.TIME).example("9:05"));
        assertIssue("the example is not a valid datetime value", field("A", chr(14)).type(SemanticType.DATETIME).format("yyyyMMddHHmmss").example("2026-11-05"));
        assertIssue("the example is not a valid binary value", field("A", new WireType.Byte(new Length.Fixed(3))).example("not base64!"));
    }

    @Test
    void anExampleOnAStructIsReportedAndAnExampleOfAnUnusableFieldIsNotJudged() {
        assertIssue("an example cannot be given for a struct", struct("S", field("M", chr(1))).example("x"));
        List<String> issues = issues(field("A", chr(8)).type(SemanticType.DATE).format("bad").example("2026-11-05"));
        assertThat(issues).hasSize(1).first().asString().contains("the format is not supported");
    }

    // ---- injection

    @Test
    void anInjectedLiteralMustBeEncodable() {
        assertOk(field("A", chr(5)).inject("PROD"), field("B", int4()).inject("-7"), field("C", new WireType.Packed(5, 2)).inject("12.5"),
                field("D", new WireType.Float(8)).inject("0.5"), field("E", chr(3)).pad(Pad.LEFT, '0').inject("7"),
                field("F", chr(5)).inject("${environment.code}"), field("G", int4()).inject("${tenant.number}"));
        assertIssue("the injected value cannot be encoded in the field (VAL003)", field("A", chr(3)).inject("TOOLONG"));
        assertIssue("the injected value cannot be encoded in the field (VAL004)", field("A", int4()).inject("abc"));
        assertIssue("the injected value cannot be encoded in the field (VAL003)", field("A", int4()).inject("99999999999"));
        assertIssue("the injected value cannot be encoded in the field (VAL004)", field("A", new WireType.Packed(5, 2)).inject("1.2.3"));
    }

    @Test
    void aByteOrDateFieldCannotBeInjectedEvenByAnExpression() {
        assertIssue("a byte field cannot be injected", field("A", new WireType.Byte(new Length.Fixed(1))).inject("${environment.x}"));
        assertIssue("a date field cannot be injected", field("A", new WireType.Date()).inject("2026-01-01"));
        assertIssue("a time field cannot be injected", field("A", new WireType.Time()).inject("${environment.x}"));
        assertIssue("a timestamp field cannot be injected", field("A", new WireType.Timestamp()).inject("${environment.x}"));
    }

    // ---- fields that depend on other fields

    @Test
    void aRequestArrayCannotTakeItsCountFromAnotherField() {
        assertIssue("an array whose count is given by another field is not supported yet on the request side", field("N", int4()).internal(), field("A", chr(1)).countRef("N"));
        assertIssue("an array whose count is given by another field is not supported yet on the request side", field("N", int4()).internal(), field("A", chr(1)).countRef("N").both());
        assertOk(field("N", int4()).internal(), field("A", chr(1)).countRef("N").response());
        assertOk(field("N", int4()).internal(), field("A", chr(1)).countRef("N").internal());
        assertOk(field("A", chr(1)).count(3));
    }

    @Test
    void aRequestFieldCannotTakeItsLengthFromAnotherField() {
        WireType.Char refChar = new WireType.Char(new Length.Ref("N"), Optional.empty());
        WireType.VarChar refVar = new WireType.VarChar(new Length.Ref("N"), Optional.empty(), Optional.empty());
        WireType.Byte refByte = new WireType.Byte(new Length.Ref("N"));
        assertIssue("a char field whose length is given by another field is not supported yet on the request side", field("N", int4()).internal(), field("A", refChar));
        assertIssue("a varchar field whose length is given by another field is not supported yet on the request side", field("N", int4()).internal(), field("A", refVar).both());
        assertIssue("a byte field whose length is given by another field is not supported yet on the request side", field("N", int4()).internal(), field("A", refByte));
        assertOk(field("N", int4()).internal(), field("A", refChar).response(), field("B", refVar).internal(), field("C", refByte).response());
    }

    @Test
    void aValueCannotBeInjectedIntoAFieldWhoseLengthIsGivenByAnotherField() {
        WireType.Char refChar = new WireType.Char(new Length.Ref("N"), Optional.empty());
        WireType.VarChar refVar = new WireType.VarChar(new Length.Ref("N"), Optional.empty(), Optional.empty());
        WireType.Byte refByte = new WireType.Byte(new Length.Ref("N"));
        for (String value : List.of("abc", "${environment.x}")) {
            assertIssue("a char field whose length is given by another field is not supported yet when the value is injected",
                    field("N", int4()).internal(), field("A", refChar).inject(value));
            assertIssue("a varchar field whose length is given by another field is not supported yet when the value is injected",
                    field("N", int4()).internal(), field("A", refVar).inject(value));
            assertIssue("a byte field whose length is given by another field is not supported yet when the value is injected",
                    field("N", int4()).internal(), field("A", refByte).inject(value));
        }
        assertOk(field("N", int4()).internal(), field("A", chr(4)).inject("abc"), field("B", varchar(4)).inject("x"));
    }

    @Test
    void theCountFieldOfATrimMustBeAWholeNumber() {
        Ops.F rows = field("R", chr(1)).count(5).response();
        assertOk(field("N", int4()).internal(), field("R", chr(1)).count(5).response().trimCount("N"));
        assertOk(field("N", chr(3)).type(SemanticType.INTEGER).internal(), field("R", chr(1)).count(5).response().trimCount("N"));
        assertOk(struct("H", field("N", int4())).internal(), field("R", chr(1)).count(5).response().trimCount("H.N"));
        assertIssue("trim.countField is not a whole number", field("N", chr(3)).internal(), field("R", chr(1)).count(5).response().trimCount("N"));
        assertIssue("trim.countField is not a whole number", field("N", new WireType.Packed(5, 2)).internal(), field("R", chr(1)).count(5).response().trimCount("N"));
        assertIssue("trim.countField is not a whole number", field("N", chr(1)).count(2).type(SemanticType.INTEGER).internal(), field("R", chr(1)).count(5).response().trimCount("N"));
        assertIssue("trim.countField is not a whole number", struct("H", field("N", int4())).internal(), field("R", chr(1)).count(5).response().trimCount("H"));
        assertIssue("trim.countField cannot be found", field("N", int4()).internal(), field("R", chr(1)).count(5).response().trimCount("X"));
        assertIssue("trim.countField cannot be found", field("N", int4()).internal(), field("R", chr(1)).count(5).response().trimCount("N.X"));
        assertIssue("trim.countField cannot be found", field("N", int4()).internal(), field("R", chr(1)).count(5).response().trimCount("H.N"));
        assertThat(rows).isNotNull();
    }

    @Test
    void theCountFieldOfAReferenceCountMustBeAWholeNumberToo() {
        assertOk(field("N", int4()).internal(), field("R", chr(1)).countRef("N").response());
        assertIssue("the field that gives the number of elements is not a whole number", field("N", chr(3)).internal(), field("R", chr(1)).countRef("N").response());
        assertIssue("the field that gives the number of elements cannot be found", field("R", chr(1)).countRef("N").response());
        assertOk(struct("S", field("N", int4()), field("T", chr(1)).countRef("N")).response());
        assertOk(field("N", int4()).internal(), struct("S", field("T", chr(1)).countRef("PGM.N")).response());
    }

    // ---- the field's own configuration

    @Test
    void aSingleBytePerCharacterCcsidIsNeeded() {
        for (int ccsid : new int[] {37, 273, 500, 1140, 65535, 99999, 0}) {
            assertOk(field("A", new WireType.Char(new Length.Fixed(5), Optional.of(ccsid))));
        }
        for (int ccsid : new int[] {1200, 1208, 13488, 17584, 61952, 930, 939, 935, 937}) {
            assertIssue("uses more than one byte per character", field("A", new WireType.Char(new Length.Fixed(5), Optional.of(ccsid))));
            assertIssue("uses more than one byte per character", field("A", new WireType.VarChar(new Length.Fixed(5), Optional.empty(), Optional.of(ccsid))));
        }
        assertIssue("chartype twobyte is not supported yet", field("A", chr(5)).attr("chartype", "twobyte"));
        assertOk(field("A", chr(5)).attr("chartype", "onebyte"));
    }

    @Test
    void anIntFieldHasASupportedLengthAndPrecision() {
        assertOk(field("A", new WireType.Int(2, Optional.of(15))), field("B", new WireType.Int(2, Optional.of(16))), field("C", new WireType.Int(4, Optional.of(31))),
                field("D", new WireType.Int(8, Optional.of(64))));
        assertIssue("an int field of 3 bytes is not supported", field("A", new WireType.Int(3, Optional.empty())));
        assertIssue("an int field of 1 bytes is not supported", field("A", new WireType.Int(1, Optional.empty())));
        assertIssue("the precision of an int field of 4 bytes must be 31 (signed) or 32 (unsigned)", field("A", new WireType.Int(4, Optional.of(20))));
        assertIssue("the precision of an int field of 2 bytes must be 15 (signed) or 16 (unsigned)", field("A", new WireType.Int(2, Optional.of(31))));
    }

    @Test
    void floatAndPackedFieldsHaveSupportedSizes() {
        assertOk(field("A", new WireType.Float(4)), field("B", new WireType.Float(8)));
        assertIssue("a float field of 5 bytes is not supported", field("A", new WireType.Float(5)));
        assertIssue("no more decimals than digits", field("A", new WireType.Packed(2, 3)));
        assertIssue("at least one digit", field("A", new WireType.Zoned(0, 0)));
        assertOk(field("A", new WireType.Packed(3, 3)), field("B", new WireType.Zoned(1, 0)));
    }

    // ---- where the issues point, and several together

    @Test
    void anIssueNamesTheFieldAndEveryIssueIsFound() {
        List<ConfigurationIssue> found = issueObjects(field("OK", chr(3)), field("A", chr(1)).type(SemanticType.BINARY),
                struct("S", field("M", int4()).type(SemanticType.STRING)), field("B", chr(2)).nullAs("TOO LONG"));
        assertThat(found).extracting(ConfigurationIssue::fieldPath).containsExactly("A", "S.M", "B");
    }

    @Test
    void ofRefusesAnOperationWithIssuesAndNamesThem() {
        assertThatThrownBy(() -> OperationCodec.of(op(field("A", chr(1)).type(SemanticType.BINARY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Operation op").hasMessageContaining("A: semantic type BINARY");
    }

    @Test
    void aStructHasNoSemanticTypeOfItsOwn() {
        assertOk(struct("S", field("A", chr(2))));
    }
}

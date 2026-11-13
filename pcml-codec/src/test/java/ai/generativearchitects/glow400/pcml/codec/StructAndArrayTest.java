package ai.generativearchitects.glow400.pcml.codec;

import static ai.generativearchitects.glow400.pcml.codec.Ops.absent;
import static ai.generativearchitects.glow400.pcml.codec.Ops.arr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.chr;
import static ai.generativearchitects.glow400.pcml.codec.Ops.codec;
import static ai.generativearchitects.glow400.pcml.codec.Ops.decimal;
import static ai.generativearchitects.glow400.pcml.codec.Ops.field;
import static ai.generativearchitects.glow400.pcml.codec.Ops.int4;
import static ai.generativearchitects.glow400.pcml.codec.Ops.integer;
import static ai.generativearchitects.glow400.pcml.codec.Ops.obj;
import static ai.generativearchitects.glow400.pcml.codec.Ops.request;
import static ai.generativearchitects.glow400.pcml.codec.Ops.struct;
import static ai.generativearchitects.glow400.pcml.codec.Ops.text;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** Structs and arrays, encoding and decoding, including array counts and trimming. */
class StructAndArrayTest {

    private static final Map<String, String> NONE = Map.of();

    private static WireValue.Text t(String s) {
        return new WireValue.Text(s);
    }

    private static WireValue.Integer i(long n) {
        return new WireValue.Integer(BigInteger.valueOf(n));
    }

    private static WireValue.Struct s(WireValue... members) {
        return new WireValue.Struct(List.of(members));
    }

    private static WireValue.Array a(WireValue... elements) {
        return new WireValue.Array(List.of(elements));
    }

    private static List<Tuple> problems(EncodeResult result) {
        return result.problems().stream().map(p -> Tuple.tuple(p.code(), p.fieldPath(), p.exposedPath())).toList();
    }

    private static List<Tuple> problems(DecodeResult result) {
        return result.problems().stream().map(p -> Tuple.tuple(p.code(), p.fieldPath(), p.exposedPath())).toList();
    }

    // ---- struct encoding

    private static Ops.F order() {
        return struct("ORD", field("ID", chr(3)).required(), field("NOTE", chr(4)).exposed("remark"), field("QTY", int4()));
    }

    @Test
    void aStructTakesAnObjectAndEncodesItsMembersInPcmlOrder() {
        EncodeResult r = codec(order()).encode(request("ord", obj("qty", integer(5), "id", text("A1"))), NONE);
        assertThat(r.problems()).isEmpty();
        assertThat(r.parameters().orElseThrow()).containsExactly(s(t("A1 "), t("    "), i(5)));
    }

    @Test
    void anAbsentStructIsBlankWhenNotRequired() {
        EncodeResult r = codec(order()).encode(Map.of(), NONE);
        assertThat(r.problems()).isEmpty();
        assertThat(r.parameters().orElseThrow()).containsExactly(s(t("   "), t("    "), i(0)));
    }

    @Test
    void aRequiredStructThatIsMissingIsVal001() {
        assertThat(problems(codec(order().required()).encode(Map.of(), NONE))).containsExactly(Tuple.tuple("VAL001", "ORD", "ord"));
    }

    @Test
    void aMemberThatIsRequiredAndMissingInAPresentStructIsVal001WithItsPath() {
        EncodeResult r = codec(order()).encode(request("ord", obj("remark", text("x"))), NONE);
        assertThat(problems(r)).containsExactly(Tuple.tuple("VAL001", "ORD.ID", "ord.id"));
    }

    @Test
    void aStructNeedsAnObjectAndAMemberNeedsItsOwnKind() {
        assertThat(problems(codec(order()).encode(request("ord", text("x")), NONE))).containsExactly(Tuple.tuple("VAL002", "ORD", "ord"));
        assertThat(problems(codec(order()).encode(request("ord", obj("id", integer(1))), NONE))).containsExactly(Tuple.tuple("VAL002", "ORD.ID", "ord.id"));
    }

    @Test
    void anUnknownMemberIsVal006AtTheStructsPath() {
        EncodeResult r = codec(order()).encode(request("ord", obj("id", text("A"), "zzz", text("?"), "note", text("x"))), NONE);
        // The member is exposed as "remark", so "note" (its PCML name) is not a field.
        assertThat(problems(r)).containsExactlyInAnyOrder(Tuple.tuple("VAL006", "ORD", "ord.zzz"), Tuple.tuple("VAL006", "ORD", "ord.note"));
    }

    @Test
    void hiddenMembersAreEncodedAsAbsentAndCannotBeSet() {
        Ops.F f = struct("ORD", field("ID", chr(3)), field("FILL", chr(2)).hidden(), field("DAT", new WireType.Date()).hidden());
        EncodeResult ok = codec(f).encode(request("ord", obj("id", text("A"))), NONE);
        assertThat(ok.problems()).isEmpty();
        assertThat(ok.parameters().orElseThrow()).containsExactly(s(t("A  "), t("  "), new WireValue.Blank()));
        EncodeResult set = codec(f).encode(request("ord", obj("id", text("A"), "fill", text("x"))), NONE);
        assertThat(problems(set)).containsExactly(Tuple.tuple("VAL006", "ORD", "ord.fill"));
    }

    @Test
    void aDateMemberOfAPresentRequestStructCannotBeLeftOutButAnAbsentStructHasBlankDates() {
        Ops.F f = struct("ORD", field("ID", chr(3)), field("DAT", new WireType.Date()));
        assertThat(problems(codec(f).encode(request("ord", obj("id", text("A"))), NONE))).containsExactly(Tuple.tuple("VAL001", "ORD.DAT", "ord.dat"));
        EncodeResult absent = codec(f).encode(Map.of(), NONE);
        assertThat(absent.problems()).isEmpty();
        assertThat(absent.parameters().orElseThrow()).containsExactly(s(t("   "), new WireValue.Blank()));
        EncodeResult given = codec(f).encode(request("ord", obj("dat", new ApiValue.Date(LocalDate.of(2026, 1, 2)))), NONE);
        assertThat(given.parameters().orElseThrow()).containsExactly(s(t("   "), new WireValue.Date(LocalDate.of(2026, 1, 2))));
    }

    @Test
    void structsNestAndAnObjectInsideAnObject() {
        Ops.F f = struct("OUTER", field("A", chr(2)), struct("INNER", field("B", chr(2)).required()));
        assertThat(codec(f).encode(request("outer", obj("a", text("x"), "inner", obj("b", text("y")))), NONE).parameters().orElseThrow())
                .containsExactly(s(t("x "), s(t("y "))));
        assertThat(problems(codec(f).encode(request("outer", obj("inner", obj())), NONE))).containsExactly(Tuple.tuple("VAL001", "OUTER.INNER.B", "outer.inner.b"));
    }

    // ---- array encoding

    @Test
    void anArrayHasItsFixedCountFillingMissingElementsWithBlanks() {
        Ops.F f = field("TAG", chr(2)).count(3);
        EncodeResult r = codec(f).encode(request("tag", arr(text("a"), text("b"))), NONE);
        assertThat(r.problems()).isEmpty();
        assertThat(r.parameters().orElseThrow()).containsExactly(a(t("a "), t("b "), t("  ")));
    }

    @Test
    void aMissingArrayIsAllBlanksAndARequiredOneIsVal001() {
        Ops.F f = field("TAG", chr(2)).count(2);
        assertThat(codec(f).encode(Map.of(), NONE).parameters().orElseThrow()).containsExactly(a(t("  "), t("  ")));
        assertThat(problems(codec(f.required()).encode(Map.of(), NONE))).containsExactly(Tuple.tuple("VAL001", "TAG", "tag"));
    }

    @Test
    void tooManyElementsIsVal005AndOtherKindsAreVal002() {
        Ops.F f = field("TAG", chr(2)).count(2);
        assertThat(problems(codec(f).encode(request("tag", arr(text("a"), text("b"), text("c"))), NONE))).containsExactly(Tuple.tuple("VAL005", "TAG", "tag"));
        assertThat(problems(codec(f).encode(request("tag", text("a")), NONE))).containsExactly(Tuple.tuple("VAL002", "TAG", "tag"));
    }

    @Test
    void anElementProblemHasTheElementIndexInItsPath() {
        Ops.F f = field("TAG", chr(2)).count(3);
        assertThat(problems(codec(f).encode(request("tag", arr(text("a"), text("toolong"), integer(1))), NONE)))
                .containsExactly(Tuple.tuple("VAL003", "TAG[1]", "tag[1]"), Tuple.tuple("VAL002", "TAG[2]", "tag[2]"));
    }

    @Test
    void anAbsentElementIsAFiller() {
        Ops.F f = field("TAG", chr(2)).count(2).required();
        assertThat(codec(f).encode(request("tag", arr(absent(), text("b"))), NONE).parameters().orElseThrow()).containsExactly(a(t("  "), t("b ")));
    }

    @Test
    void anArrayOfStructsEncodesEachElementAndFillsTheRestWithBlankStructs() {
        Ops.F f = struct("ROWS", field("X", chr(2)), field("Y", int4()).required()).count(3);
        EncodeResult r = codec(f).encode(request("rows", arr(obj("x", text("a"), "y", integer(1)), obj("y", integer(2)))), NONE);
        assertThat(r.problems()).isEmpty();
        assertThat(r.parameters().orElseThrow()).containsExactly(a(s(t("a "), i(1)), s(t("  "), i(2)), s(t("  "), i(0))));
        assertThat(problems(codec(f).encode(request("rows", arr(obj("x", text("a")), obj("y", integer(2), "zz", text("?")))), NONE)))
                .containsExactly(Tuple.tuple("VAL001", "ROWS[0].Y", "rows[0].y"), Tuple.tuple("VAL006", "ROWS[1]", "rows[1].zz"));
    }

    @Test
    void anArrayOfDatesIsFilledWithBlankWireValues() {
        Ops.F f = field("D", new WireType.Date()).count(2);
        EncodeResult r = codec(f).encode(request("d", arr(new ApiValue.Date(LocalDate.of(2026, 1, 2)))), NONE);
        assertThat(r.parameters().orElseThrow()).containsExactly(a(new WireValue.Date(LocalDate.of(2026, 1, 2)), new WireValue.Blank()));
    }

    @Test
    void outputArraysAreBlankAndAnArrayCountedByAnotherFieldIsEmpty() {
        EncodeResult r = codec(field("OUT", chr(2)).count(2).response(), field("N", int4()).internal(),
                field("DYN", chr(2)).countRef("N").response()).encode(Map.of(), NONE);
        assertThat(r.problems()).isEmpty();
        assertThat(r.parameters().orElseThrow()).containsExactly(a(t("  "), t("  ")), i(0), a());
    }

    @Test
    void aStructWithAnArrayMemberAndAnArrayOfArrays() {
        Ops.F f = struct("S", field("T", chr(1)).count(2));
        assertThat(codec(f).encode(request("s", obj("t", arr(text("a")))), NONE).parameters().orElseThrow()).containsExactly(s(a(t("a"), t(" "))));
    }

    // ---- struct and array decoding

    @Test
    void aStructDecodesToAnObjectWithoutAbsentMembers() {
        Ops.F f = struct("ORD", field("ID", chr(3)), field("DAT", chr(8)).type(SemanticType.DATE), field("FILL", chr(2)).hidden(), field("QTY", int4())).response();
        DecodeResult r = codec(f).decode(List.of(s(t("A1 "), t("        "), t("xx"), i(5))));
        assertThat(r.problems()).isEmpty();
        assertThat(r.response().orElseThrow()).containsExactly(Map.entry("ord", obj("id", text("A1"), "qty", ((ApiValue.Integer) integer(5)))));
    }

    @Test
    void aStructWithTheWrongNumberOfMembersIsVal101() {
        Ops.F f = struct("ORD", field("ID", chr(3)), field("QTY", int4())).response();
        assertThat(problems(codec(f).decode(List.of(s(t("A")))))).containsExactly(Tuple.tuple("VAL101", "ORD", "ord"));
        assertThat(problems(codec(f).decode(List.of(t("A"))))).containsExactly(Tuple.tuple("VAL101", "ORD", "ord"));
    }

    @Test
    void aMemberThatCannotBeReadHasItsPath() {
        Ops.F f = struct("ORD", field("ID", chr(3)), field("QTY", chr(3)).type(SemanticType.INTEGER)).response();
        assertThat(problems(codec(f).decode(List.of(s(t("A"), t("x1")))))).containsExactly(Tuple.tuple("VAL102", "ORD.QTY", "ord.qty"));
    }

    @Test
    void aFixedArrayMustComeBackWithItsNumberOfElements() {
        Ops.F f = field("TAG", chr(2)).count(2).response();
        assertThat(codec(f).decode(List.of(a(t("a "), t("b ")))).response().orElseThrow()).containsExactly(Map.entry("tag", arr(text("a"), text("b"))));
        assertThat(problems(codec(f).decode(List.of(a(t("a ")))))).containsExactly(Tuple.tuple("VAL101", "TAG", "tag"));
        assertThat(problems(codec(f).decode(List.of(t("a "))))).containsExactly(Tuple.tuple("VAL101", "TAG", "tag"));
    }

    @Test
    void anElementThatCannotBeReadHasItsIndex() {
        Ops.F f = field("N", chr(2)).type(SemanticType.INTEGER).count(3).response();
        assertThat(problems(codec(f).decode(List.of(a(t("1 "), t("x "), t("y ")))))).containsExactly(
                Tuple.tuple("VAL102", "N[1]", "n[1]"), Tuple.tuple("VAL102", "N[2]", "n[2]"));
    }

    // ---- trim.countField

    private static Ops.F rows(int count) {
        return struct("ROWS", field("X", chr(2)), field("Y", int4())).count(count).response();
    }

    private static WireValue row(String x, long y) {
        return s(t(x), i(y));
    }

    @Test
    void countFieldKeepsTheFirstNElementsAndTheCountIsNotInTheResponse() {
        DecodeResult r = codec(field("N", int4()).internal(), rows(3).trimCount("N"))
                .decode(List.of(i(2), a(row("a ", 1), row("b ", 2), row("c ", 3))));
        assertThat(r.problems()).isEmpty();
        assertThat(r.response().orElseThrow()).containsOnlyKeys("rows");
        assertThat(r.response().orElseThrow().get("rows")).isEqualTo(arr(obj("x", text("a"), "y", integer(1)), obj("x", text("b"), "y", integer(2))));
        assertThat(codec(field("N", int4()).internal(), rows(3).trimCount("N")).decode(List.of(i(0), a(row("a", 1), row("b", 2), row("c", 3))))
                .response().orElseThrow().get("rows")).isEqualTo(arr());
        assertThat(codec(field("N", int4()).internal(), rows(3).trimCount("N")).decode(List.of(i(3), a(row("a", 1), row("b", 2), row("c", 3))))
                .response().orElseThrow().get("rows")).isInstanceOf(ApiValue.Arr.class);
    }

    @Test
    void aBadCountIsVal103() {
        for (long n : new long[] {-1, 4}) {
            assertThat(problems(codec(field("N", int4()).internal(), rows(3).trimCount("N")).decode(List.of(i(n), a(row("a", 1), row("b", 2), row("c", 3))))))
                    .as("count " + n).containsExactly(Tuple.tuple("VAL103", "ROWS", "rows"));
        }
        // A blank or unreadable count is "not a number".
        Ops.F text = field("N", chr(3)).type(SemanticType.INTEGER).internal();
        assertThat(problems(codec(text, rows(1).trimCount("N")).decode(List.of(t("   "), a(row("a", 1)))))).containsExactly(Tuple.tuple("VAL103", "ROWS", "rows"));
        assertThat(problems(codec(text, rows(1).trimCount("N")).decode(List.of(new WireValue.Blank(), a(row("a", 1)))))).containsExactly(Tuple.tuple("VAL103", "ROWS", "rows"));
        assertThat(problems(codec(text, rows(1).trimCount("N")).decode(List.of(t("1x "), a(row("a", 1)))))).containsExactly(Tuple.tuple("VAL103", "ROWS", "rows"));
    }

    @Test
    void theCountFieldMayBeAMemberOfAStruct() {
        Ops.F f = struct("HDR", field("CNT", int4())).internal();
        DecodeResult r = codec(f, rows(2).trimCount("HDR.CNT")).decode(List.of(s(i(1)), a(row("a", 1), row("b", 2))));
        assertThat(r.problems()).isEmpty();
        assertThat(((ApiValue.Arr) r.response().orElseThrow().get("rows")).elements()).hasSize(1);
    }

    // ---- trim.dropBlank

    @Test
    void dropBlankRemovesTrailingBlankElementsOnly() {
        Ops.F f = rows(5).trimBlank();
        DecodeResult r = codec(f).decode(List.of(a(row("a ", 1), row("  ", 0), row("b ", 0), row("  ", 0), row("  ", 0))));
        assertThat(r.problems()).isEmpty();
        assertThat(((ApiValue.Arr) r.response().orElseThrow().get("rows")).elements()).hasSize(3);
        assertThat(((ApiValue.Arr) codec(f).decode(List.of(a(row("  ", 0), row("  ", 0), row("  ", 0), row("  ", 0), row("  ", 0))))
                .response().orElseThrow().get("rows")).elements()).isEmpty();
        assertThat(((ApiValue.Arr) codec(f).decode(List.of(a(row("a", 1), row("b", 2), row("c", 3), row("d", 4), row("e", 5))))
                .response().orElseThrow().get("rows")).elements()).hasSize(5);
    }

    @Test
    void blankMeansEveryScalarInsideIsBlankTextZeroOrZeroBytes() {
        Ops.F f = struct("R", field("T", chr(2)), field("P", new WireType.Packed(3, 1)), field("F", new WireType.Float(8)),
                field("B", new WireType.Byte(new ai.generativearchitects.glow400.pcml.model.Length.Fixed(2))), field("D", new WireType.Date()),
                field("V", Ops.varchar(3))).count(2).response().trimBlank();
        WireValue blank = s(t("  "), new WireValue.Decimal(new BigDecimal("0.0")), new WireValue.Float(0.0), new WireValue.Bytes(new byte[2]),
                new WireValue.Blank(), t(""));
        WireValue date = s(t("  "), new WireValue.Decimal(new BigDecimal("0.0")), new WireValue.Float(0.0), new WireValue.Bytes(new byte[2]),
                new WireValue.Date(LocalDate.EPOCH), t(""));
        assertThat(((ApiValue.Arr) codec(f).decode(List.of(a(blank, blank))).response().orElseThrow().get("r")).elements()).isEmpty();
        assertThat(((ApiValue.Arr) codec(f).decode(List.of(a(blank, date))).response().orElseThrow().get("r")).elements()).hasSize(2);
        for (WireValue notBlank : List.of(
                s(t("x "), new WireValue.Decimal(new BigDecimal("0.0")), new WireValue.Float(0.0), new WireValue.Bytes(new byte[2]), new WireValue.Blank(), t("")),
                s(t("  "), new WireValue.Decimal(new BigDecimal("0.1")), new WireValue.Float(0.0), new WireValue.Bytes(new byte[2]), new WireValue.Blank(), t("")),
                s(t("  "), new WireValue.Decimal(new BigDecimal("0.0")), new WireValue.Float(0.5), new WireValue.Bytes(new byte[2]), new WireValue.Blank(), t("")),
                s(t("  "), new WireValue.Decimal(new BigDecimal("0.0")), new WireValue.Float(0.0), new WireValue.Bytes(new byte[] {0, 1}), new WireValue.Blank(), t("")),
                s(t("  "), new WireValue.Decimal(new BigDecimal("0.0")), new WireValue.Float(0.0), new WireValue.Bytes(new byte[2]), new WireValue.Blank(), t("v")))) {
            assertThat(((ApiValue.Arr) codec(f).decode(List.of(a(blank, notBlank))).response().orElseThrow().get("r")).elements()).hasSize(2);
        }
    }

    @Test
    void anArrayOfScalarsIsTrimmedByBlankToo() {
        Ops.F f = field("N", int4()).count(4).response().trimBlank();
        assertThat(codec(f).decode(List.of(a(i(1), i(0), i(2), i(0)))).response().orElseThrow().get("n")).isEqualTo(arr(integer(1), integer(0), integer(2)));
        Ops.F bytes = field("B", new WireType.Byte(new ai.generativearchitects.glow400.pcml.model.Length.Fixed(1))).count(2).response().trimBlank();
        assertThat(codec(bytes).decode(List.of(a(new WireValue.Bytes(new byte[] {1}), new WireValue.Bytes(new byte[] {0})))).response().orElseThrow().get("b"))
                .isEqualTo(arr(new ApiValue.Binary(new byte[] {1})));
        Ops.F nested = struct("S", field("T", chr(1)).count(2)).count(2).response().trimBlank();
        DecodeResult n = codec(nested).decode(List.of(a(s(a(t("a"), t(" "))), s(a(t(" "), t(" "))))));
        assertThat(((ApiValue.Arr) n.response().orElseThrow().get("s")).elements()).hasSize(1);
        DecodeResult m = codec(nested).decode(List.of(a(s(a(t("a"), t(" "))), s(a(t(" "), t("b"))))));
        assertThat(((ApiValue.Arr) m.response().orElseThrow().get("s")).elements()).hasSize(2);
        // A malformed element is not blank, so it is kept, and then reported.
        assertThat(problems(codec(nested).decode(List.of(a(s(a(t("a"), t(" "))), s(t(" ")))))))
                .containsExactly(Tuple.tuple("VAL101", "S[1].T", "s[1].t"));
    }

    // ---- counts that name another field (PCML count="FIELD")

    @Test
    void anArrayTakesItsLengthFromTheFieldItsCountNames() {
        Ops.F f = field("TAG", chr(2)).countRef("N").response();
        DecodeResult r = codec(field("N", int4()).internal(), f).decode(List.of(i(2), a(t("a "), t("b "), t("  "), t("  "))));
        assertThat(r.response().orElseThrow().get("tag")).isEqualTo(arr(text("a"), text("b")));
        assertThat(problems(codec(field("N", int4()).internal(), f).decode(List.of(i(5), a(t("a "), t("b ")))))).containsExactly(Tuple.tuple("VAL103", "TAG", "tag"));
        assertThat(problems(codec(field("N", int4()).internal(), f).decode(List.of(i(-1), a(t("a ")))))).containsExactly(Tuple.tuple("VAL103", "TAG", "tag"));
    }

    @Test
    void theCountOfAMemberArrayIsAMemberOfTheSameStructInstance() {
        Ops.F f = struct("S", field("N", int4()), field("T", chr(1)).countRef("N")).count(2).response();
        DecodeResult r = codec(f).decode(List.of(a(s(i(1), a(t("a"), t("b"))), s(i(2), a(t("c"), t("d"))))));
        assertThat(r.problems()).isEmpty();
        assertThat(r.response().orElseThrow().get("s")).isEqualTo(arr(
                obj("n", integer(1), "t", arr(text("a"))), obj("n", integer(2), "t", arr(text("c"), text("d")))));
    }

    @Test
    void aCountCanNameATopLevelFieldThroughTheProgramName() {
        Ops.F f = struct("S", field("T", chr(1)).countRef("PGM.N")).response();
        DecodeResult r = codec(field("N", int4()).internal(), f).decode(List.of(i(1), s(a(t("a"), t("b")))));
        assertThat(r.problems()).isEmpty();
        assertThat(r.response().orElseThrow().get("s")).isEqualTo(obj("t", arr(text("a"))));
    }

    @Test
    void aCountNamingADottedPathThroughAStruct() {
        Ops.F f = field("T", chr(1)).countRef("HDR.N").response();
        DecodeResult r = codec(struct("HDR", field("N", int4())).internal(), f).decode(List.of(s(i(1)), a(t("a"), t("b"))));
        assertThat(r.response().orElseThrow().get("t")).isEqualTo(arr(text("a")));
    }

    @Test
    void aCountFromTheArrayNameAndTrimTogetherKeepTheFewer() {
        Ops.F f = field("T", chr(1)).countRef("N").response().trimBlank();
        DecodeResult r = codec(field("N", int4()).internal(), f).decode(List.of(i(3), a(t("a"), t(" "), t(" "), t("z"))));
        assertThat(r.response().orElseThrow().get("t")).isEqualTo(arr(text("a")));
    }

    // ---- the shape of the whole output

    @Test
    void theOutputMustHaveOneValuePerParameter() {
        assertThat(problems(codec(field("A", chr(1)).response(), field("B", chr(1)).response()).decode(List.of(t("a")))))
                .containsExactly(Tuple.tuple("VAL101", "", ""));
        assertThat(problems(codec(field("A", chr(1)).response()).decode(List.of(t("a"), t("b"))))).extracting(t -> t.toString()).hasSize(1);
    }

    @Test
    void onlyVisibleResponseFieldsAreReturnedAndAbsentOnesAreLeftOut() {
        DecodeResult r = codec(field("REQ", chr(1)), field("OUT", chr(2)).response(), field("BOTH", chr(2)).both(), field("INT", chr(1)).internal(),
                field("NUM", chr(3)).type(SemanticType.INTEGER).response(), field("INJ", chr(1)).inject("${tenant.x}"))
                .decode(List.of(t("r"), t("o "), t("b "), t("i"), t("   "), t("j")));
        assertThat(r.problems()).isEmpty();
        assertThat(r.response().orElseThrow()).containsOnlyKeys("out", "both");
        assertThat(r.response().orElseThrow()).containsEntry("out", text("o")).containsEntry("both", text("b"));
    }

    @Test
    void aBlankWireValueDecodesToNothing() {
        DecodeResult r = codec(field("D", new WireType.Date()).response(), struct("S", field("A", chr(1))).response(),
                field("T", chr(1)).count(2).response()).decode(List.of(new WireValue.Blank(), new WireValue.Blank(), new WireValue.Blank()));
        assertThat(r.problems()).isEmpty();
        assertThat(r.response().orElseThrow()).containsOnlyKeys("s", "t");
        assertThat(r.response().orElseThrow().get("s")).isEqualTo(obj());
        assertThat(r.response().orElseThrow().get("t")).isEqualTo(arr());
    }

    @Test
    void decodedMessagesNeverContainTheValue() {
        Ops.F f = field("N", chr(8)).type(SemanticType.INTEGER).response();
        DecodeResult r = codec(f).decode(List.of(t("SECRET99")));
        assertThat(r.problems()).extracting(ValueProblem::message).noneMatch(m -> m.contains("SECRET"));
        assertThat(decimal("1")).isNotNull();
    }
}

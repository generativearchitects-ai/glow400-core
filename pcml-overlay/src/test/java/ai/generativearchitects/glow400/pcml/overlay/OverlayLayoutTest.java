package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.overlay.OverlayLayout.Entry;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLayout.Operation;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLayout.Parameters;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLayout.Shape;
import org.junit.jupiter.api.Test;

/** Where the parameter entries are, and which shapes are described at all. */
class OverlayLayoutTest {

    private static Operation single(String text) {
        OverlayLayout layout = OverlayLayout.read(text).orElseThrow();
        assertThat(layout.operations()).hasSize(1);
        return layout.operations().get(0);
    }

    private static Entry entry(String text, String name) {
        return single(text).parameters().orElseThrow().entries().get(name);
    }

    private static final String HEAD = "pcml: A.pcml\nversion: 1.0.0\nprogram: A\nroute: /a\nmethod: POST\n";

    @Test
    void aFlowEntryHasItsBracesAndPairs() {
        String text = HEAD + "parameters:\n  P0X: { name: x, direction: both }  # note\n";
        Entry e = entry(text, "P0X");
        assertThat(e.shape()).isEqualTo(Shape.FLOW);
        assertThat(text.charAt(e.open())).isEqualTo('{');
        assertThat(text.charAt(e.close())).isEqualTo('}');
        assertThat(e.pairs()).extracting(OverlayLayout.Pair::key).containsExactly("name", "direction");
        assertThat(e.pairs()).allMatch(OverlayLayout.Pair::simple);
        assertThat(text.substring(e.pairs().get(0).valueStart(), e.pairs().get(0).valueEnd())).isEqualTo("x");
        assertThat(text.substring(e.pairs().get(1).valueStart(), e.pairs().get(1).valueEnd())).isEqualTo("both");
    }

    @Test
    void anEmptyFlowEntryAndQuotedValues() {
        String text = HEAD + "parameters:\n  A: {}\n  B: { name: \"q x\", type: 'integer' }\n";
        assertThat(entry(text, "A").shape()).isEqualTo(Shape.FLOW);
        assertThat(entry(text, "A").pairs()).isEmpty();
        Entry b = entry(text, "B");
        assertThat(text.substring(b.pairs().get(0).valueStart(), b.pairs().get(0).valueEnd())).isEqualTo("\"q x\"");
        assertThat(text.substring(b.pairs().get(1).valueStart(), b.pairs().get(1).valueEnd())).isEqualTo("'integer'");
    }

    @Test
    void aBlockEntryHasIndentAndEnd() {
        String text = HEAD + "parameters:\n  P0X:\n    name: x\n    description: d # c\n  P0Y: { }\n";
        Entry e = entry(text, "P0X");
        assertThat(e.shape()).isEqualTo(Shape.BLOCK);
        assertThat(e.indent()).isEqualTo(4);
        assertThat(text.substring(0, e.end())).endsWith("description: d");
        assertThat(e.pairs()).extracting(OverlayLayout.Pair::key).containsExactly("name", "description");
        Parameters p = single(text).parameters().orElseThrow();
        assertThat(p.indent()).isEqualTo(2);
        assertThat(text.substring(0, p.end())).endsWith("P0Y: { }");
    }

    @Test
    void theEndOfABlockEntryCoversNestedAndFlowValues() {
        String text = HEAD + "parameters:\n  P0X:\n    name: x\n    example: [1, 2]\n  P0Y:\n    fields:\n      A:\n        hidden: true\n";
        assertThat(text.substring(0, entry(text, "P0X").end())).endsWith("[1, 2]");
        assertThat(text.substring(0, entry(text, "P0Y").end())).endsWith("hidden: true");
        assertThat(entry(text, "P0X").pairs().get(1).simple()).isFalse();
    }

    @Test
    void valuesOnSeveralLinesOrEmptyAreNotSimple() {
        String text = HEAD + "parameters:\n  P0X:\n    name: \"a\n      b\"\n    description:\n    in: query\n";
        Entry e = entry(text, "P0X");
        assertThat(e.pairs()).extracting(OverlayLayout.Pair::simple).containsExactly(false, false, true);
    }

    @Test
    void unsupportedShapesSayWhy() {
        assertThat(entry(HEAD + "parameters:\n  A: &a { name: x }\n  B: { name: y }\n", "A")).satisfies(e -> {
            assertThat(e.shape()).isEqualTo(Shape.UNSUPPORTED);
            assertThat(e.why()).isEqualTo("anchor or alias");
        });
        assertThat(entry(HEAD + "parameters:\n  A: {\n    name: x }\n", "A").why()).isEqualTo("flow mapping over several lines");
        assertThat(entry(HEAD + "parameters:\n  A:\n", "A").why()).isEqualTo("entry without a value");
        assertThat(entry(HEAD + "parameters:\n  A: {name: x}\n  B: [1]\n", "B").why()).isEqualTo("the entry is not a mapping");
        assertThat(OverlayLayout.read(HEAD + "parameters:\n  A:\n\tname: x\n")).isEmpty();
        assertThat(entry(HEAD + "parameters:\n  A:\n    name:\tx\n", "A").why()).isEqualTo("tab character");
        assertThat(entry(HEAD + "parameters:\n  A: !!map { name: x }\n", "A").why()).isEqualTo("a tag on the mapping");
        assertThat(OverlayLayout.read(HEAD + "parameters:\n  A:\n    ? [a]\n    : b\n")).isEmpty();
        assertThat(entry(HEAD + "parameters:\n  A: { name: &n x }\n", "A").why()).isEqualTo("anchor or alias");
    }

    @Test
    void aMappingValueOnTheNextLineIsNotAFlowEntryOnTheLineOfItsKey() {
        assertThat(entry(HEAD + "parameters:\n  A:\n    { name: x }\n", "A").why()).isEqualTo("flow mapping not on the line of its key");
    }

    @Test
    void parametersThatCannotBeAppendedToSayWhy() {
        assertThat(single(HEAD).parameters()).isEmpty();
        assertThat(single(HEAD).why()).isEqualTo("the operation has no parameters mapping");
        assertThat(single(HEAD + "parameters: { A: {} }\n").why()).isEqualTo("parameters is not a block mapping");
        assertThat(single(HEAD + "parameters:\n").why()).isEqualTo("parameters is not a block mapping");
        assertThat(single(HEAD + "parameters: &p\n  A: {}\n").why()).isEqualTo("parameters has an anchor");
        assertThat(single(HEAD + "parameters:\n  A: {}\n  B:\n    description: |\n      text\n").why())
                .isEqualTo("an entry of parameters ends in a block scalar");
    }

    @Test
    void entriesAlignedAtDifferentColumnsAreNotUsable() {
        String text = HEAD + "parameters:\n  A: {}\n  ? B\n  : {}\n";
        assertThat(OverlayLayout.read(text)).isPresent();
        String misaligned = "pcml: A.pcml\nversion: 1.0.0\nprogram: A\nroute: /a\nmethod: POST\nparameters:\n    A: {}\n    B: {}\n";
        assertThat(single(misaligned).parameters().orElseThrow().indent()).isEqualTo(4);
    }

    @Test
    void anOperationsListGivesOneLayoutPerItemAtTheItemPosition() {
        String text = "pcml: A.pcml\nversion: 1.0.0\nparameters:\n  S: {}\noperations:\n  - program: A\n    route: /a\n    method: POST\n"
                + "    parameters:\n      P: { name: p }\n  - program: B\n    route: /b\n    method: POST\n";
        OverlayLayout layout = OverlayLayout.read(text).orElseThrow();
        assertThat(layout.operations()).hasSize(2);
        assertThat(layout.operations().get(0).line()).isEqualTo(6);
        assertThat(layout.operations().get(0).column()).isEqualTo(5);
        assertThat(layout.operationAt(6, 5)).contains(layout.operations().get(0));
        assertThat(layout.operationAt(11, 5)).contains(layout.operations().get(1));
        assertThat(layout.operationAt(1, 1)).isEmpty();
        assertThat(layout.operations().get(0).parameters().orElseThrow().entries()).containsOnlyKeys("P");
        assertThat(layout.operations().get(1).parameters()).isEmpty();
    }

    @Test
    void theSingleFormIsPositionedAtTheRoot() {
        Operation op = single(HEAD + "parameters:\n  A: {}\n");
        assertThat(op.line()).isEqualTo(1);
        assertThat(op.column()).isEqualTo(1);
    }

    @Test
    void anOperationsKeyThatIsNotAListAndItemsThatAreNotMappingsGiveNoOperations() {
        assertThat(OverlayLayout.read("operations: 3\n").orElseThrow().operations()).isEmpty();
        assertThat(OverlayLayout.read("operations:\n  - 1\n  - program: A\n").orElseThrow().operations()).hasSize(1);
    }

    @Test
    void textThatIsNotAMappingGivesNothing() {
        assertThat(OverlayLayout.read("")).isEmpty();
        assertThat(OverlayLayout.read("- a\n")).isEmpty();
        assertThat(OverlayLayout.read("a: [")).isEmpty();
    }

    @Test
    void offsetsCountUtf16UnitsAfterCharactersOutsideTheBasicPlane() {
        String text = "# \uD83D\uDE00 comment\n" + HEAD + "parameters:\n  A: { name: x }\n";
        Entry e = entry(text, "A");
        assertThat(text.charAt(e.open())).isEqualTo('{');
        assertThat(text.substring(e.pairs().get(0).valueStart(), e.pairs().get(0).valueEnd())).isEqualTo("x");
    }

    @Test
    void aByteOrderMarkDoesNotMoveOffsets() {
        String text = "\uFEFF" + HEAD + "parameters:\n  A: { name: x }\n";
        Entry e = entry(text, "A");
        assertThat(text.charAt(e.open())).isEqualTo('{');
    }

    @Test
    void crlfLinesGiveTheSameShapes() {
        String text = (HEAD + "parameters:\n  A:\n    name: x\n  B: { name: y }\n").replace("\n", "\r\n");
        assertThat(entry(text, "A").shape()).isEqualTo(Shape.BLOCK);
        assertThat(text.substring(0, entry(text, "A").end())).endsWith("name: x");
        assertThat(entry(text, "B").shape()).isEqualTo(Shape.FLOW);
    }
}

package ai.generativearchitects.slay400.pcml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.PcmlDocument;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The editor, shape by shape: what it writes, what it keeps, and what it refuses. */
class OverlayEditorTest {

    static final String PCML = """
            <?xml version="1.0"?>
            <pcml version="6.0">
              <struct name="ROW"><data name="A" type="char" length="2"/></struct>
              <program name="UPD" path="/QSYS.LIB/X.LIB/UPD.PGM">
                <data name="ID" type="char" length="10" usage="input"/>
                <data name="QTY" type="int" length="4" usage="inputoutput"/>
                <data name="NOTE" type="char" length="4" usage="inputoutput"/>
                <data name="AMT" type="packed" length="7" precision="2" usage="inputoutput"/>
                <data name="HDR" type="struct" struct="ROW" usage="inputoutput"/>
                <data name="OUT" type="char" length="4" usage="output"/>
              </program>
              <program name="OTHER" path="/QSYS.LIB/X.LIB/OTHER.PGM">
                <data name="Z" type="char" length="4" usage="inputoutput"/>
              </program>
            </pcml>
            """;

    static final PcmlDocument DOC = Fixtures.document(PCML);

    static String single(String parameters) {
        return "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\nroute: /upd\nmethod: POST\n" + parameters;
    }

    static ParameterEdit edit(String parameter, Direction direction, SemanticType type, String name) {
        return new ParameterEdit("upd", parameter, Optional.ofNullable(direction), Optional.ofNullable(type), Optional.ofNullable(name));
    }

    static ParameterEdit direction(String parameter, Direction direction) {
        return edit(parameter, direction, null, null);
    }

    static EditResult apply(String text, ParameterEdit... edits) {
        return OverlayEditor.apply(text, "api.yaml", DOC, List.of(edits));
    }

    static String edited(String text, ParameterEdit... edits) {
        EditResult result = apply(text, edits);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.applied()).containsExactly(edits);
        return result.text().orElseThrow();
    }

    // ---- flow mapping on one line

    @Test
    void aFlowEntryGetsTheMissingKeysInsideTheBraces() {
        assertThat(edited(single("parameters:\n  QTY: { name: quantity }\n"), direction("QTY", Direction.BOTH)))
                .isEqualTo(single("parameters:\n  QTY: { name: quantity, direction: both }\n"));
        assertThat(edited(single("parameters:\n  QTY: { name: quantity }\n"), edit("QTY", Direction.REQUEST, SemanticType.INTEGER, "q")))
                .isEqualTo(single("parameters:\n  QTY: { name: q, direction: request, type: integer }\n"));
    }

    @Test
    void anEmptyFlowEntryBecomesAFlowEntryWithTheKeys() {
        assertThat(edited(single("parameters:\n  QTY: {}\n"), edit("QTY", Direction.REQUEST, SemanticType.INTEGER, "qty")))
                .isEqualTo(single("parameters:\n  QTY: { direction: request, type: integer, name: qty }\n"));
        assertThat(edited(single("parameters:\n  QTY: {  }\n"), direction("QTY", Direction.RESPONSE)))
                .isEqualTo(single("parameters:\n  QTY: { direction: response }\n"));
    }

    @Test
    void existingKeysOfAFlowEntryAreReplacedInPlace() {
        String text = single("parameters:\n  QTY: { direction: request, type: string, name: old, in: query }\n");
        assertThat(edited(text, edit("QTY", Direction.BOTH, SemanticType.INTEGER, "new")))
                .isEqualTo(single("parameters:\n  QTY: { direction: both, type: integer, name: new, in: query }\n"));
    }

    @Test
    void quotedValuesAreReplacedAndAnUnchangedValueIsLeftAsWritten() {
        String text = single("parameters:\n  QTY: { direction: \"both\", name: 'q x' }\n");
        assertThat(edited(text, edit("QTY", Direction.BOTH, null, "q x"))).isEqualTo(text);
        assertThat(edited(text, edit("QTY", Direction.REQUEST, null, "r"))).isEqualTo(single("parameters:\n  QTY: { direction: request, name: r }\n"));
    }

    @Test
    void aNameThatNeedsQuotesIsWrittenQuoted() {
        assertThat(edited(single("parameters:\n  QTY: {}\n"), edit("QTY", null, null, "my qty")))
                .isEqualTo(single("parameters:\n  QTY: { name: \"my qty\" }\n"));
        assertThat(edited(single("parameters:\n  QTY: {}\n"), edit("QTY", null, null, "say \"hi\"\\")))
                .isEqualTo(single("parameters:\n  QTY: { name: \"say \\\"hi\\\"\\\\\" }\n"));
        assertThat(edited(single("parameters:\n  QTY: {}\n"), edit("QTY", null, null, "true")))
                .isEqualTo(single("parameters:\n  QTY: { name: \"true\" }\n"));
    }

    @Test
    void theCommentWrittenByInitGoesWhenADirectionIsSet() {
        String text = single("parameters:\n  QTY: { name: qty }  " + OverlayScaffold.INPUTOUTPUT_COMMENT + "\n  NOTE: { name: note }\n");
        assertThat(edited(text, direction("QTY", Direction.REQUEST)))
                .isEqualTo(single("parameters:\n  QTY: { name: qty, direction: request }\n  NOTE: { name: note }\n"));
    }

    @Test
    void theCommentWrittenByInitStaysWhenNoDirectionIsSet() {
        String text = single("parameters:\n  QTY: { name: qty }  " + OverlayScaffold.INPUTOUTPUT_COMMENT + "\n");
        assertThat(edited(text, edit("QTY", null, SemanticType.INTEGER, "q")))
                .isEqualTo(single("parameters:\n  QTY: { name: q, type: integer }  " + OverlayScaffold.INPUTOUTPUT_COMMENT + "\n"));
    }

    @Test
    void anotherCommentOnTheLineStaysEvenWhenADirectionIsSet() {
        String text = single("parameters:\n  QTY: { name: qty }  # keep me\n");
        assertThat(edited(text, direction("QTY", Direction.BOTH)))
                .isEqualTo(single("parameters:\n  QTY: { name: qty, direction: both }  # keep me\n"));
        String lookalike = single("parameters:\n  QTY: { name: qty } # inputoutput is special\n");
        assertThat(edited(lookalike, direction("QTY", Direction.BOTH)))
                .isEqualTo(single("parameters:\n  QTY: { name: qty, direction: both } # inputoutput is special\n"));
    }

    @Test
    void theCommentOfInitIsRemovedWithTheSpacesBeforeIt() {
        String text = single("parameters:\n  QTY: {}\t# inputoutput: run annotate\n");
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(single("parameters:\n  QTY: { direction: both }\n"));
    }

    // ---- block mapping

    @Test
    void aBlockEntryGetsLinesAtItsIndentation() {
        String text = single("parameters:\n  QTY:\n    name: qty\n  NOTE: { name: note }\n");
        assertThat(edited(text, edit("QTY", Direction.REQUEST, SemanticType.INTEGER, null)))
                .isEqualTo(single("parameters:\n  QTY:\n    name: qty\n    direction: request\n    type: integer\n  NOTE: { name: note }\n"));
    }

    @Test
    void aBlockEntryKeepsItsOtherKeysAndComments() {
        String text = single("parameters:\n  # before\n  QTY:   # on the key\n      # inside\n      name: qty   # on the line\n"
                + "      description: A text  # last\n\n  # after\n  NOTE: {}\n");
        assertThat(edited(text, edit("QTY", Direction.BOTH, null, "quantity"))).isEqualTo(single(
                "parameters:\n  # before\n  QTY:   # on the key\n      # inside\n      name: quantity   # on the line\n"
                        + "      description: A text  # last\n      direction: both\n\n  # after\n  NOTE: {}\n"));
    }

    @Test
    void aBlockEntryReplacesTheValuesOfItsKeys() {
        String text = single("parameters:\n  QTY:\n    direction: request\n    type: string\n    name: a\n");
        assertThat(edited(text, edit("QTY", Direction.RESPONSE, SemanticType.INTEGER, "b")))
                .isEqualTo(single("parameters:\n  QTY:\n    direction: response\n    type: integer\n    name: b\n"));
    }

    @Test
    void aBlockEntryWithAFlowMappingAsItsLastValueGetsLinesAfterIt() {
        String text = single("parameters:\n  HDR:\n    fields: { A: { name: a } }\n  NOTE: {}\n");
        assertThat(edited(text, direction("HDR", Direction.BOTH)))
                .isEqualTo(single("parameters:\n  HDR:\n    fields: { A: { name: a } }\n    direction: both\n  NOTE: {}\n"));
    }

    @Test
    void aBlockEntryWithANestedMappingLast() {
        String text = single("parameters:\n  HDR:\n    name: hdr\n    fields:\n      A:\n        name: a\n  NOTE: {}\n");
        assertThat(edited(text, direction("HDR", Direction.REQUEST)))
                .isEqualTo(single("parameters:\n  HDR:\n    name: hdr\n    fields:\n      A:\n        name: a\n    direction: request\n  NOTE: {}\n"));
    }

    @Test
    void aBlockEntryWithTheInputoutputCommentHasItRemovedWhenADirectionIsGiven() {
        // kickoff 14, Part A: init now writes the comment trailing 'name:' even in block form (a parameter large
        // enough for its own trim), so annotate must remove it from there too, the same way it already did in flow form.
        String text = single("parameters:\n  QTY:\n    name: qty  " + OverlayScaffold.INPUTOUTPUT_COMMENT + "\n"
                + "    description: a quantity\n");
        assertThat(edited(text, direction("QTY", Direction.RESPONSE))).isEqualTo(single(
                "parameters:\n  QTY:\n    name: qty\n    description: a quantity\n    direction: response\n"));
    }

    @Test
    void theLastBlockEntryOfAFileWithoutAFinalNewlineGetsItsLinesWithoutOne() {
        String text = single("parameters:\n  QTY:\n    name: qty");
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(single("parameters:\n  QTY:\n    name: qty\n    direction: both"));
    }

    // ---- no entry

    @Test
    void aMissingEntryIsAppendedAsAFlowLineAtTheEndOfTheMapping() {
        String text = single("parameters:\n  ID: { name: id }\n  # a comment\n\nsummary: s\n");
        assertThat(edited(text, edit("QTY", Direction.BOTH, SemanticType.INTEGER, "qty"))).isEqualTo(
                single("parameters:\n  ID: { name: id }\n  QTY: { direction: both, type: integer, name: qty }\n  # a comment\n\nsummary: s\n"));
    }

    @Test
    void aMissingEntryAfterABlockEntryGoesAfterItsLastLine() {
        String text = single("parameters:\n  ID:\n    name: id\n    description: x\n");
        assertThat(edited(text, direction("QTY", Direction.REQUEST)))
                .isEqualTo(single("parameters:\n  ID:\n    name: id\n    description: x\n  QTY: { direction: request }\n"));
    }

    @Test
    void severalMissingEntriesAreAppendedInTheOrderGiven() {
        String text = single("parameters:\n  ID: { name: id }\n");
        assertThat(edited(text, direction("NOTE", Direction.REQUEST), direction("QTY", Direction.BOTH), edit("AMT", null, null, "amount")))
                .isEqualTo(single("parameters:\n  ID: { name: id }\n  NOTE: { direction: request }\n  QTY: { direction: both }\n  AMT: { name: amount }\n"));
    }

    @Test
    void linesAddedToTheLastBlockEntryComeBeforeAnEntryAppendedAfterIt() {
        String text = single("parameters:\n  ID:\n    name: id\n");
        assertThat(edited(text, direction("QTY", Direction.BOTH), edit("ID", Direction.REQUEST, null, null), direction("NOTE", Direction.REQUEST)))
                .isEqualTo(single("parameters:\n  ID:\n    name: id\n    direction: request\n  QTY: { direction: both }\n  NOTE: { direction: request }\n"));
    }

    @Test
    void aMissingEntryAtTheEndOfAFileWithoutAFinalNewline() {
        String text = single("parameters:\n  ID: { name: id }");
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(single("parameters:\n  ID: { name: id }\n  QTY: { direction: both }"));
    }

    @Test
    void theKeyOfAnEntryThatNeedsQuotesIsQuoted() {
        String pcml = "<pcml version=\"6.0\"><program name=\"P\" path=\"/Q\"><data name=\"A$B\" type=\"char\" length=\"1\" usage=\"inputoutput\"/>"
                + "<data name=\"C\" type=\"char\" length=\"1\" usage=\"input\"/></program></pcml>";
        String text = "pcml: P.pcml\nversion: 1.0.0\nprogram: P\nroute: /p\nmethod: POST\nparameters:\n  C: { name: c }\n";
        EditResult result = OverlayEditor.apply(text, "api.yaml", Fixtures.document(pcml),
                List.of(new ParameterEdit("p", "A$B", Optional.of(Direction.BOTH), Optional.empty(), Optional.empty())));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.text()).contains(text + "  \"A$B\": { direction: both }\n");
    }

    // ---- line endings

    @Test
    void crlfFilesKeepTheirLineEndingsInEveryShape() {
        String text = single("parameters:\n  QTY: { name: qty }  " + OverlayScaffold.INPUTOUTPUT_COMMENT + "\n  NOTE:\n    name: note\n  ID: { name: id }\n")
                .replace("\n", "\r\n");
        String result = edited(text, direction("QTY", Direction.BOTH), direction("NOTE", Direction.REQUEST), direction("AMT", Direction.BOTH));
        assertThat(result).isEqualTo(single("parameters:\n  QTY: { name: qty, direction: both }\n  NOTE:\n    name: note\n    direction: request\n"
                + "  ID: { name: id }\n  AMT: { direction: both }\n").replace("\n", "\r\n"));
        assertThat(result.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void aFileThatMixesLineEndingsGetsTheEndingOfTheLineBefore() {
        String text = "pcml: UPD.pcml\r\nversion: 1.0.0\nprogram: UPD\r\nroute: /upd\nmethod: POST\nparameters:\n  ID: { name: id }\r\n";
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(text + "  QTY: { direction: both }\r\n");
        String lf = text.replace("  ID: { name: id }\r\n", "  ID: { name: id }\n");
        assertThat(edited(lf, direction("QTY", Direction.BOTH))).isEqualTo(lf + "  QTY: { direction: both }\n");
    }

    @Test
    void aFileWithOnlyCarriageReturnsIsEditedWithThem() {
        String text = single("parameters:\n  ID: { name: id }").replace("\n", "\r");
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(text + "\r  QTY: { direction: both }");
    }

    @Test
    void aFileThatEndsWithoutALineBreakGetsTheEndingOfItsFirstLine() {
        String text = single("parameters:\n  ID: { name: id }").replace("\n", "\r\n");
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(text + "\r\n  QTY: { direction: both }");
    }

    @Test
    void aByteOrderMarkIsKept() {
        String text = "\uFEFF" + single("parameters:\n  QTY: { name: qty }\n");
        EditResult result = apply(text, direction("QTY", Direction.BOTH));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.text()).contains("\uFEFF" + single("parameters:\n  QTY: { name: qty, direction: both }\n"));
    }

    @Test
    void textOutsideTheEditedEntriesAfterCharactersOutsideTheBasicPlaneIsKept() {
        String text = "# \uD83D\uDE00\n" + single("parameters:\n  QTY: { name: \"\uD83D\uDE00\" }\n  NOTE: {}\n");
        assertThat(edited(text, direction("NOTE", Direction.BOTH), direction("QTY", Direction.BOTH)))
                .isEqualTo("# \uD83D\uDE00\n" + single("parameters:\n  QTY: { name: \"\uD83D\uDE00\", direction: both }\n  NOTE: { direction: both }\n"));
    }

    // ---- several operations

    @Test
    void anOperationsListIsEditedAtTheOperationOfTheEdit() {
        String text = "pcml: UPD.pcml\nversion: 1.0.0\nparameters:\n  ID: { name: id }\noperations:\n  - id: upd\n    program: UPD\n    route: /upd\n    method: POST\n"
                + "    parameters:\n      QTY: { name: qty }\n  - id: other\n    program: OTHER\n    route: /other\n    method: POST\n    parameters:\n      Z: {}\n";
        EditResult result = apply(text, direction("QTY", Direction.BOTH), new ParameterEdit("other", "Z", Optional.of(Direction.REQUEST), Optional.empty(), Optional.empty()));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.text()).contains(text.replace("QTY: { name: qty }", "QTY: { name: qty, direction: both }").replace("Z: {}", "Z: { direction: request }"));
    }

    @Test
    void anEntryOfTheSharedParametersIsNotTouchedWhenTheOperationHasNoneOfItsOwn() {
        String text = "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\nparameters:\n  QTY: { name: qty }\noperations:\n  - id: upd\n    route: /upd\n    method: POST\n";
        EditResult result = apply(text, direction("QTY", Direction.BOTH));
        assertThat(result.applied()).isEmpty();
        assertThat(result.text()).contains(text);
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity).containsExactly(org.assertj.core.groups.Tuple.tuple("SCF001", Severity.WARNING));
        assertThat(result.diagnostics().get(0).message()).isEqualTo(
                "Operation 'upd', parameter 'QTY' was not edited: the operation has no parameters mapping; edit it by hand");
    }

    @Test
    void anOperationOfAListWithItsOwnParametersGetsAnAppendedEntry() {
        String text = "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\nparameters:\n  ID: { name: id }\noperations:\n  - id: upd\n    route: /upd\n    method: POST\n"
                + "    parameters:\n      NOTE: { name: note }\n";
        assertThat(edited(text, direction("QTY", Direction.BOTH))).isEqualTo(text + "      QTY: { direction: both }\n");
    }

    // ---- struct and arrays

    @Test
    void aStructTakesADirectionAndANameButNoType() {
        String text = single("parameters:\n  HDR: {}\n");
        assertThat(edited(text, edit("HDR", Direction.REQUEST, null, "header"))).isEqualTo(single("parameters:\n  HDR: { direction: request, name: header }\n"));
        EditResult result = apply(text, edit("HDR", Direction.REQUEST, SemanticType.STRING, null));
        assertThat(result.applied()).isEmpty();
        assertThat(result.text()).contains(text);
        assertThat(result.diagnostics().get(0).message()).isEqualTo(
                "Operation 'upd', parameter 'HDR' was not edited: its type cannot be 'string'; edit it by hand");
    }

    @Test
    void theTypesAreThoseTheCodecCanEncode() {
        FieldDef qty = DOC.programs().get(0).parameters().get(1);
        FieldDef note = DOC.programs().get(0).parameters().get(2);
        FieldDef amt = DOC.programs().get(0).parameters().get(3);
        FieldDef hdr = DOC.programs().get(0).parameters().get(4);
        assertThat(OverlayEditor.allowedTypes(qty)).contains(SemanticType.INTEGER).doesNotContain(SemanticType.BINARY, SemanticType.DATETIME);
        assertThat(OverlayEditor.allowedTypes(note)).contains(SemanticType.STRING);
        assertThat(OverlayEditor.allowedTypes(amt)).contains(SemanticType.DECIMAL);
        assertThat(OverlayEditor.allowedTypes(hdr)).isEmpty();
        assertThat(OverlayEditor.allowedTypes(qty)).isSorted();
    }

    @Test
    void aTypeTheCodecCannotEncodeIsNotWritten() {
        String text = single("parameters:\n  QTY: {}\n");
        EditResult result = apply(text, edit("QTY", Direction.BOTH, SemanticType.BINARY, null));
        assertThat(result.applied()).isEmpty();
        assertThat(result.text()).contains(text);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("SCF001");
    }

    // ---- refused shapes: SCF001 and the text unchanged

    private static void refused(String text, String why, ParameterEdit... edits) {
        EditResult result = apply(text, edits);
        assertThat(result.applied()).isEmpty();
        assertThat(result.text()).contains(text);
        assertThat(result.diagnostics()).hasSize(edits.length);
        assertThat(result.diagnostics()).allSatisfy(d -> {
            assertThat(d.code()).isEqualTo("SCF001");
            assertThat(d.severity()).isEqualTo(Severity.WARNING);
        });
        assertThat(result.diagnostics().get(0).message()).isEqualTo("Operation 'upd', parameter '" + edits[0].parameter()
                + "' was not edited: " + why + "; edit it by hand");
    }

    @Test
    void anAnchorOrAnAliasIsNotEdited() {
        refused(single("parameters:\n  QTY: &q { name: qty }\n  NOTE: *q\n"), "anchor or alias", direction("QTY", Direction.BOTH));
        refused(single("parameters:\n  QTY: &q { name: qty }\n  NOTE: *q\n"), "anchor or alias", direction("NOTE", Direction.BOTH));
        refused(single("parameters:\n  QTY: { name: &n qty }\n"), "anchor or alias", direction("QTY", Direction.BOTH));
    }

    @Test
    void aFlowMappingOverSeveralLinesIsNotEdited() {
        refused(single("parameters:\n  QTY: {\n    name: qty }\n"), "flow mapping over several lines", direction("QTY", Direction.BOTH));
        refused(single("parameters:\n  QTY:\n    { name: qty }\n"), "flow mapping not on the line of its key", direction("QTY", Direction.BOTH));
    }

    @Test
    void parametersInFlowStyleOrMissingAreNotEdited() {
        refused(single("parameters: { ID: { name: id } }\n"), "parameters is not a block mapping", direction("QTY", Direction.BOTH));
        refused(single(""), "the operation has no parameters mapping", direction("QTY", Direction.BOTH));
        refused(single("parameters:\n"), "parameters is not a block mapping", direction("QTY", Direction.BOTH));
    }

    @Test
    void aTabIsNotEdited() {
        refused(single("parameters:\n  QTY:\n    name:\tqty\n"), "tab character", direction("QTY", Direction.BOTH));
    }

    @Test
    void anEntryThatIsNotAMappingIsNotEdited() {
        refused(single("parameters:\n  QTY:\n"), "entry without a value", direction("QTY", Direction.BOTH));
        refused(single("parameters:\n  QTY: [a]\n"), "the entry is not a mapping", direction("QTY", Direction.BOTH));
    }

    @Test
    void aValueThatCannotBeReplacedIsNotEdited() {
        refused(single("parameters:\n  QTY: { direction: [a] }\n"), "the value of 'direction' is not a plain or quoted text on one line", direction("QTY", Direction.BOTH));
        refused(single("parameters:\n  QTY:\n    name: \"a\n      b\"\n"), "the value of 'name' is not a plain or quoted text on one line", edit("QTY", null, null, "x"));
        refused(single("parameters:\n  QTY:\n    direction:\n"), "the value of 'direction' is not a plain or quoted text on one line", direction("QTY", Direction.BOTH));
    }

    @Test
    void anEntryThatEndsInABlockScalarMakesTheMappingUnusableForAppending() {
        refused(single("parameters:\n  ID:\n    description: |\n      text\n"), "an entry of parameters ends in a block scalar", direction("QTY", Direction.BOTH));
        // an existing entry of such a mapping is also out of reach
        refused(single("parameters:\n  ID:\n    description: |\n      text\n"), "an entry of parameters ends in a block scalar", direction("ID", Direction.BOTH));
    }

    @Test
    void aFlowEntryWhoseLastKeyHasNoValueCannotTakeAnotherKey() {
        refused(single("parameters:\n  QTY: { name: qty, description: }\n"), "the last key of the entry has no value", direction("QTY", Direction.BOTH));
    }

    @Test
    void theOtherEditsAreStillAppliedWhenOneIsRefused() {
        String text = single("parameters:\n  QTY: &q { name: qty }\n  NOTE: {}\n");
        EditResult result = apply(text, direction("QTY", Direction.BOTH), direction("NOTE", Direction.REQUEST));
        assertThat(result.applied()).containsExactly(direction("NOTE", Direction.REQUEST));
        assertThat(result.text()).contains(single("parameters:\n  QTY: &q { name: qty }\n  NOTE: { direction: request }\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("SCF001");
    }

    @Test
    void anUnknownOperationOrParameterIsNotEdited() {
        String text = single("parameters:\n  QTY: {}\n");
        EditResult result = apply(text, new ParameterEdit("nope", "QTY", Optional.of(Direction.BOTH), Optional.empty(), Optional.empty()),
                direction("NOPE", Direction.BOTH));
        assertThat(result.applied()).isEmpty();
        assertThat(result.text()).contains(text);
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly(
                "Operation 'nope', parameter 'QTY' was not edited: the file has no such operation; edit it by hand",
                "Operation 'upd', parameter 'NOPE' was not edited: the program has no such parameter; edit it by hand");
    }

    @Test
    void theSecondEditOfTheSameParameterIsNotApplied() {
        String text = single("parameters:\n  QTY: {}\n");
        EditResult result = apply(text, direction("QTY", Direction.BOTH), direction("QTY", Direction.REQUEST));
        assertThat(result.applied()).containsExactly(direction("QTY", Direction.BOTH));
        assertThat(result.text()).contains(single("parameters:\n  QTY: { direction: both }\n"));
        assertThat(result.diagnostics().get(0).message()).isEqualTo(
                "Operation 'upd', parameter 'QTY' was not edited: there is more than one edit for this parameter; edit it by hand");
    }

    @Test
    void theWarningIsPositionedAtTheEntryOrElseAtTheOperation() {
        EditResult entry = apply(single("parameters:\n  QTY: &q {}\n"), direction("QTY", Direction.BOTH));
        assertThat(entry.diagnostics().get(0).location()).hasToString("api.yaml:7:3");
        EditResult operation = apply(single("parameters:\n  ID: &q {}\n"), direction("NOTE", Direction.BOTH));
        assertThat(operation.applied()).hasSize(1);
        EditResult none = apply(single(""), direction("QTY", Direction.BOTH));
        assertThat(none.diagnostics().get(0).location()).hasToString("api.yaml:1:1");
    }

    // ---- the safety check: SCF002 and no text

    private static void forbidden(String text, String message, ParameterEdit... edits) {
        EditResult result = apply(text, edits);
        assertThat(result.text()).isEmpty();
        assertThat(result.applied()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .contains(org.assertj.core.groups.Tuple.tuple("SCF002", Severity.ERROR));
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals("SCF002")).singleElement()
                .satisfies(d -> assertThat(d.message()).isEqualTo("The edited file was refused and nothing was written: " + message));
    }

    @Test
    void aDirectionThatContradictsThePcmlUsageIsRefused() {
        forbidden(single("parameters:\n  ID: {}\n"), "the new text gives a diagnostic that the old one did not (OVL016)",
                direction("ID", Direction.RESPONSE));
    }

    @Test
    void aNameThatClashesWithAnotherOnTheSameSideIsRefused() {
        forbidden(single("parameters:\n  ID: { name: id }\n  QTY: { direction: request }\n"),
                "the new text gives a diagnostic that the old one did not (OVL014)", edit("QTY", null, null, "id"));
    }

    @Test
    void aFileThatCannotBeReadIsRefused() {
        EditResult result = apply("a: [", direction("QTY", Direction.BOTH));
        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple("SCF002", "The overlay file cannot be read, so it was not edited"));
        assertThat(OverlayEditor.apply("# only a comment\n", "api.yaml", DOC, List.of(direction("QTY", Direction.BOTH))).text()).isEmpty();
    }

    @Test
    void anOperationThatHasAnErrorOfItsOwnCannotBeCheckedAndIsRefused() {
        forbidden(single("parameters:\n  QTY: {}\n  NOPE: {}\n"), "the edited parameter 'QTY' cannot be found in the new model",
                direction("QTY", Direction.BOTH));
    }

    // ---- the effect on the model

    @Test
    void theReloadedModelHasTheDirectionTypeAndName() {
        String text = single("parameters:\n  QTY: {}\n");
        String result = edited(text, edit("QTY", Direction.BOTH, SemanticType.INTEGER, "quantity"));
        var loaded = ai.generativearchitects.slay400.pcml.overlay.OverlayText.load("api.yaml", result, DOC,
                ai.generativearchitects.slay400.pcml.api.GatewayDefaults.standard());
        assertThat(loaded.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR)).isEmpty();
        var binding = loaded.operations().get(0).bindings().stream().filter(b -> b.fieldPath().equals("QTY")).findFirst().orElseThrow();
        assertThat(binding.direction()).isEqualTo(Direction.BOTH);
        assertThat(binding.semanticType()).contains(SemanticType.INTEGER);
        assertThat(binding.exposedName()).isEqualTo("quantity");
    }

    @Test
    void applyingTheSameEditsAgainChangesNothing() {
        String text = single("parameters:\n  QTY: { name: qty }  " + OverlayScaffold.INPUTOUTPUT_COMMENT + "\n  NOTE:\n    name: note\n");
        ParameterEdit[] edits = {edit("QTY", Direction.BOTH, SemanticType.INTEGER, "q"), edit("NOTE", Direction.REQUEST, null, "n"), direction("AMT", Direction.BOTH)};
        String once = edited(text, edits);
        assertThat(edited(once, edits)).isEqualTo(once);
    }

    @Test
    void noEditsChangeNothing() {
        String text = single("parameters:\n  QTY: {}\n");
        EditResult result = apply(text);
        assertThat(result.text()).contains(text);
        assertThat(result.applied()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
    }

    // ---- the edit itself

    @Test
    void anEditMustSetSomethingAndMustBeWritable() {
        assertThatThrownBy(() -> new ParameterEdit("a", "B", Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("An edit must set at least one of direction, type and name");
        assertThatThrownBy(() -> direction("B", Direction.INJECTED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A direction written in a parameter entry is never injected");
        assertThatThrownBy(() -> edit("B", null, null, " ")).isInstanceOf(IllegalArgumentException.class).hasMessage("Not a valid exposed name");
        assertThatThrownBy(() -> new ParameterEdit(null, "B", Optional.empty(), Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ParameterEdit("a", null, Optional.empty(), Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
    }
}

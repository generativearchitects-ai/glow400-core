package ai.generativearchitects.slay400.pcml.scaffold;

import static ai.generativearchitects.slay400.pcml.scaffold.OverlayEditorTest.DOC;
import static ai.generativearchitects.slay400.pcml.scaffold.OverlayEditorTest.direction;
import static ai.generativearchitects.slay400.pcml.scaffold.OverlayEditorTest.edit;
import static ai.generativearchitects.slay400.pcml.scaffold.OverlayEditorTest.single;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.GatewayDefaults;
import ai.generativearchitects.slay400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.overlay.OverlayText;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The check that decides whether an edited text may be returned, fed with texts that no correct edit could produce. */
class OverlayEditorSafetyTest {

    private static final GatewayDefaults FAIL = new GatewayDefaults(GatewayDefaults.standard().charCodec(), InputOutputPolicy.FAIL,
            GatewayDefaults.standard().emitters(), GatewayDefaults.standard().largeArrayThreshold(), GatewayDefaults.standard().emitterSources());

    private static Optional<String> refusal(String before, String after, ParameterEdit... edits) {
        return OverlayEditor.refusal(OverlayText.load("api.yaml", before, DOC, FAIL), OverlayText.load("api.yaml", after, DOC, FAIL), List.of(edits));
    }

    private static final String BASE = single("parameters:\n  ID: { name: id }\n  QTY: { name: qty }\n");

    @Test
    void aCorrectEditIsNotRefused() {
        assertThat(refusal(BASE, single("parameters:\n  ID: { name: id }\n  QTY: { name: qty, direction: both }\n"), direction("QTY", Direction.BOTH))).isEmpty();
    }

    @Test
    void aDiagnosticTheOldTextDidNotGiveIsRefused() {
        assertThat(refusal(BASE, single("parameters:\n  ID: { name: id, direction: response }\n  QTY: { name: qty }\n"), direction("ID", Direction.RESPONSE)))
                .contains("the new text gives a diagnostic that the old one did not (OVL016)");
    }

    @Test
    void aDiagnosticThatIsThereTwiceInTheNewTextButOnceInTheOldIsRefused() {
        String once = single("parameters:\n  NOPE: {}\n");
        String twice = single("parameters:\n  NOPE: {}\n  NOPE2: {}\n");
        assertThat(refusal(once, twice)).hasValueSatisfying(m -> assertThat(m).contains("OVL002"));
        assertThat(refusal(twice, once)).isEmpty();
    }

    @Test
    void aDifferentNumberOfOperationsIsRefused() {
        String one = "pcml: UPD.pcml\nversion: 1.0.0\noperations:\n  - id: a\n    program: OTHER\n    route: /a\n    method: POST\n    parameters:\n      Z: { direction: both }\n";
        String two = one + "  - id: b\n    program: OTHER\n    route: /b\n    method: POST\n    parameters:\n      Z: { direction: both }\n";
        ParameterEdit z = new ParameterEdit("a", "Z", Optional.of(Direction.BOTH), Optional.empty(), Optional.empty());
        assertThat(refusal(one, two, z)).contains("the new text builds a different set of operations");
    }

    @Test
    void aChangeOutsideTheEditedParametersIsRefused() {
        String after = single("parameters:\n  ID: { name: id }\n  QTY: { name: qty, direction: both }\n").replace("/upd", "/other");
        assertThat(refusal(BASE, after, direction("QTY", Direction.BOTH))).contains("the new text changes the operation 'upd' beyond the edited parameters");
        String renamed = single("parameters:\n  ID: { name: other }\n  QTY: { name: qty, direction: both }\n");
        assertThat(refusal(BASE, renamed, direction("QTY", Direction.BOTH))).contains("the new text changes the operation 'upd' beyond the edited parameters");
    }

    @Test
    void anotherIdIsAChangeBeyondTheEditedParameters() {
        String a = "pcml: UPD.pcml\nversion: 1.0.0\noperations:\n  - id: a\n    program: UPD\n    route: /a\n    method: POST\n    parameters:\n      QTY: {}\n";
        String b = a.replace("id: a", "id: b");
        assertThat(refusal(a, b, direction("QTY", Direction.REQUEST))).contains("the new text changes the operation 'a' beyond the edited parameters");
    }

    @Test
    void anEditedParameterThatIsNotInTheNewModelIsRefused() {
        assertThat(refusal(BASE, BASE, direction("NOPE", Direction.BOTH))).contains("the edited parameter 'NOPE' cannot be found in the new model");
    }

    @Test
    void anEditedParameterThatDoesNotHaveWhatWasAskedForIsRefused() {
        String both = single("parameters:\n  ID: { name: id }\n  QTY: { name: qty, direction: both, type: integer }\n");
        String request = both.replace("direction: both", "direction: request");
        assertThat(refusal(BASE, both, direction("QTY", Direction.REQUEST))).contains("the edited parameter 'QTY' does not have what was asked for");
        assertThat(refusal(BASE, both, edit("QTY", Direction.BOTH, SemanticType.DECIMAL, null))).contains("the edited parameter 'QTY' does not have what was asked for");
        assertThat(refusal(BASE, both, edit("QTY", Direction.BOTH, SemanticType.INTEGER, "other"))).contains("the edited parameter 'QTY' does not have what was asked for");
        assertThat(refusal(BASE, both, edit("QTY", Direction.BOTH, SemanticType.INTEGER, "qty"))).isEmpty();
        assertThat(refusal(BASE, request, edit("QTY", Direction.REQUEST, SemanticType.INTEGER, "qty"))).isEmpty();
    }

    @Test
    void theParameterIsLookedForInTheOperationOfTheEditOnly() {
        String before = "pcml: UPD.pcml\nversion: 1.0.0\noperations:\n  - id: a\n    program: OTHER\n    route: /a\n    method: POST\n    parameters:\n      Z: { direction: internal }\n"
                + "  - id: b\n    program: OTHER\n    route: /b\n    method: POST\n    parameters:\n      Z: { direction: internal }\n";
        String after = before.replaceFirst("internal", "both").replaceFirst("(?s)(.*)internal", "$1request");
        ParameterEdit a = new ParameterEdit("a", "Z", Optional.of(Direction.BOTH), Optional.empty(), Optional.empty());
        ParameterEdit b = new ParameterEdit("b", "Z", Optional.of(Direction.REQUEST), Optional.empty(), Optional.empty());
        assertThat(refusal(before, after, a, b)).isEmpty();
        ParameterEdit wrong = new ParameterEdit("b", "Z", Optional.of(Direction.BOTH), Optional.empty(), Optional.empty());
        assertThat(refusal(before, after, a, wrong)).contains("the edited parameter 'Z' does not have what was asked for");
    }
}

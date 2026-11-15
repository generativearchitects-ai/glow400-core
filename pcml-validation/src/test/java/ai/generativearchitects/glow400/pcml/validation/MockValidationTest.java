package ai.generativearchitects.glow400.pcml.validation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** MCK003, MCK004 and MCK005: a mock against the program and the codec of its operation. */
class MockValidationTest {

    private static List<Diagnostic> mockDiagnostics(String fixture) {
        return Validator.validate(Fixtures.model(fixture)).stream().filter(d -> d.code().startsWith("MCK")).toList();
    }

    private static List<Tuple> found(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> Tuple.tuple(d.code(), d.severity(), d.location().file(), d.location().line(), d.location().column(), d.message())).toList();
    }

    private static final String FILE = "apis/ORDERS/mock/ordstatus.yaml";

    @Test
    void aMockThatFitsItsProgramGivesNothing() {
        assertThat(mockDiagnostics("mocked")).isEmpty();
    }

    @Test
    void namesThatAreNotThereOrOnTheWrongSideAreMck003() {
        assertThat(found(mockDiagnostics("invalid/mck003"))).containsExactly(
                Tuple.tuple("MCK003", Severity.ERROR, FILE, 2, 19, "In case 1: 'NOPE' is not a parameter of the program ORDSTATUS"),
                Tuple.tuple("MCK003", Severity.ERROR, FILE, 3, 21, "In case 2: 'P0STAT' is not received by the program: it is an output parameter"),
                Tuple.tuple("MCK003", Severity.ERROR, FILE, 5, 24, "In case 3: 'P0ORDN' is not returned by the program: it is an input parameter"),
                Tuple.tuple("MCK003", Severity.ERROR, FILE, 7, 43, "In case 4: 'NOPE' is not a member of P0LINES"));
    }

    @Test
    void valuesTheCodecCannotCarryAreMck004WithItsCode() {
        assertThat(found(mockDiagnostics("invalid/mck004"))).containsExactly(
                Tuple.tuple("MCK004", Severity.ERROR, FILE, 3, 24, "In case 1: the value for P0STAT cannot be carried by the field (VAL003: The value is too long: at most 1 characters)"),
                Tuple.tuple("MCK004", Severity.ERROR, FILE, 5, 34, "In case 2: the value for P0LINES.QTY cannot be carried by the field (VAL002: The value is not of the kind of the field)"),
                Tuple.tuple("MCK004", Severity.ERROR, FILE, 6, 21, "In case 3: the value for P0ORDN cannot be carried by the field (VAL002: The value is not of the kind of the field)"));
    }

    @Test
    void aCaseThatCanNeverMatchIsMck005AndOnlyAWarning() {
        assertThat(found(mockDiagnostics("invalid/mck005"))).containsExactly(
                Tuple.tuple("MCK005", Severity.WARNING, FILE, 4, 5, "The case 'same' can never match: the case 'first' before it has the same 'when'"),
                Tuple.tuple("MCK005", Severity.WARNING, FILE, 8, 5, "The case 'never' can never match: the case 'all' before it has no 'when'"));
    }

    @Test
    void theDiagnosticsAreOrderedWithTheOthersByFileLineAndColumn() {
        List<Diagnostic> all = Validator.validate(Fixtures.model("invalid/mck005"));
        assertThat(all).extracting(d -> d.location().file()).isSorted();
        assertThat(all).extracting(Diagnostic::code).containsExactly("OVL006", "MCK005", "MCK005");
    }

    @Test
    void anOperationThatIsNotEncodableIsNotJudgedByItsMock() {
        ApiRepositoryModel model = Fixtures.model("mocked");
        ApiDefinition api = model.apis().get(0);
        ApiOperation operation = api.operations().get(0);
        // the same operation with a pad the codec cannot use on a numeric field: OVL003
        ApiOperation broken = new ApiOperation(operation.id(), operation.version(), operation.summary(), operation.description(), operation.tags(),
                operation.program(), operation.pcmlPath(), operation.overlayPath(), operation.route(), operation.method(), operation.callStrategy(),
                operation.bindings().stream().map(b -> b.fieldPath().equals("P0ORDN") ? withPad(b) : b).toList(), operation.errorMapping(), operation.cache(), operation.source());
        MockCase bad = new MockCase(Optional.of("bad"), Map.of(), Map.of("NOPE", new MockValue.Text("x", new SourceLocation(FILE, 2, 2))), Optional.empty(), Optional.empty(),
                new SourceLocation(FILE, 2, 1));
        ApiDefinition withMock = new ApiDefinition(api.name(), api.title(), api.version(), api.overlayPath(), List.of(broken, api.operations().get(1)),
                List.of(new MockDefinition("ordstatus", List.of(bad), new SourceLocation(FILE, 1, 1))));
        List<Diagnostic> diagnostics = Validator.validate(new ApiRepositoryModel(model.defaults(), withMock.operations(), List.of(withMock)));
        assertThat(diagnostics).extracting(Diagnostic::code).contains("OVL003").doesNotContain("MCK003");
    }

    private static ai.generativearchitects.glow400.pcml.api.FieldBinding withPad(ai.generativearchitects.glow400.pcml.api.FieldBinding b) {
        ai.generativearchitects.glow400.pcml.api.CodecSettings c = b.codec();
        return new ai.generativearchitects.glow400.pcml.api.FieldBinding(b.fieldPath(), b.field(), b.direction(), b.injection(), b.exposedName(), b.location(),
                Optional.of(ai.generativearchitects.glow400.pcml.api.SemanticType.BINARY), c, b.required(), b.description(), b.example(), b.hidden(), b.trim(),
                b.children(), b.source());
    }

    @Test
    void aRepositoryWithoutMockFilesGivesNoMockDiagnostic() {
        for (String fixture : List.of("minimal", "kitchen-sink", "structs", "operations-list")) {
            assertThat(mockDiagnostics(fixture)).isEmpty();
        }
    }
}

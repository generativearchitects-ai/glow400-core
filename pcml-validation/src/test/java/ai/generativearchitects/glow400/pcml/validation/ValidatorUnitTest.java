package ai.generativearchitects.glow400.pcml.validation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.CacheSettings;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** The validator's decisions on hand-built models. */
class ValidatorUnitTest {

    private static final SourceLocation OPERATION_AT = new SourceLocation("api.yaml", 3, 1);
    private static final SourceLocation BINDING_AT = new SourceLocation("api.yaml", 9, 5);
    private static final SourceLocation PCML_AT = new SourceLocation("a.pcml", 4, 5);
    private static final ErrorMapping ERRORS = new ErrorMapping(new ErrorCondition.Equals("E", "x"), Optional.empty(), Optional.empty(), 422, Map.of());

    private static FieldDef def(String name, WireType wire, Count count) {
        return new FieldDef(name, wire, Usage.INPUT, count, Map.of(), PCML_AT);
    }

    private static WireType.Char chr(int length) {
        return new WireType.Char(new Length.Fixed(length), Optional.empty());
    }

    private static FieldBinding binding(FieldDef field, Direction direction, SemanticType type, SourceLocation source, Optional<Trim> trim,
                                        List<FieldBinding> children) {
        Pad pad = field.wire() instanceof WireType.Char ? Pad.RIGHT : Pad.NONE;
        return new FieldBinding(field.name(), field, direction, Optional.empty(), field.name().toLowerCase(), Optional.empty(),
                Optional.ofNullable(type), new CodecSettings(pad, ' ', "", Optional.empty()), false, Optional.empty(), Optional.empty(),
                false, trim, children, source);
    }

    private static FieldBinding array(String name, int count, Direction direction, Optional<Trim> trim) {
        return binding(def(name, chr(2), new Count.Fixed(count)), direction, SemanticType.STRING, BINDING_AT, trim, List.of());
    }

    private static ApiRepositoryModel model(int threshold, Optional<ErrorMapping> errors, FieldBinding... bindings) {
        List<FieldDef> defs = new ArrayList<>();
        for (FieldBinding b : bindings) {
            defs.add(b.field());
        }
        ProgramDef program = new ProgramDef("PGM", Optional.empty(), Optional.empty(), Optional.empty(), defs, Map.of(), PCML_AT);
        ApiOperation operation = new ApiOperation("op", "1.0.0", Optional.empty(), Optional.empty(), List.of(), program, "a.pcml", "api.yaml", "/op",
                HttpMethod.POST, CallStrategy.AUTO, List.of(bindings), errors, Optional.<CacheSettings>empty(), OPERATION_AT);
        return new ApiRepositoryModel(new GatewayDefaults(new CodecSettings(Pad.RIGHT, ' ', "", Optional.empty()), InputOutputPolicy.BOTH, List.of(), threshold),
                List.of(operation));
    }

    private static List<Tuple> codesAndLines(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> Tuple.tuple(d.code(), d.severity(), d.location().line())).toList();
    }

    // ---- OVL005

    @Test
    void theThresholdItselfIsLarge() {
        for (int threshold : new int[] {1, 5, 100}) {
            assertThat(codesAndLines(Validator.validate(model(threshold, Optional.of(ERRORS), array("A", threshold, Direction.RESPONSE, Optional.empty())))))
                    .as("count = threshold " + threshold).containsExactly(Tuple.tuple("OVL005", Severity.WARNING, 9));
            assertThat(Validator.validate(model(threshold, Optional.of(ERRORS), array("A", threshold - 1, Direction.RESPONSE, Optional.empty()))))
                    .as("count below threshold " + threshold).isEmpty();
            assertThat(codesAndLines(Validator.validate(model(threshold, Optional.of(ERRORS), array("A", threshold + 1, Direction.RESPONSE, Optional.empty())))))
                    .as("count above threshold " + threshold).containsExactly(Tuple.tuple("OVL005", Severity.WARNING, 9));
        }
    }

    @Test
    void onlyTheResponseSideCounts() {
        ApiRepositoryModel request = model(10, Optional.of(ERRORS), array("A", 50, Direction.REQUEST, Optional.empty()));
        ApiRepositoryModel internal = model(10, Optional.of(ERRORS), array("A", 50, Direction.INTERNAL, Optional.empty()));
        assertThat(Validator.validate(request)).isEmpty();
        assertThat(Validator.validate(internal)).isEmpty();
        assertThat(Validator.validate(model(10, Optional.of(ERRORS), array("A", 50, Direction.BOTH, Optional.empty())))).extracting(Diagnostic::code).containsExactly("OVL005");
        assertThat(Validator.validate(model(10, Optional.of(ERRORS), array("A", 50, Direction.RESPONSE, Optional.empty())))).extracting(Diagnostic::code).containsExactly("OVL005");
    }

    @Test
    void anyTrimSilencesIt() {
        assertThat(Validator.validate(model(10, Optional.of(ERRORS), array("A", 50, Direction.RESPONSE, Optional.of(new Trim.DropBlank()))))).isEmpty();
        FieldBinding count = binding(def("N", new WireType.Int(4, Optional.empty()), new Count.None()), Direction.INTERNAL, SemanticType.INTEGER, BINDING_AT, Optional.empty(), List.of());
        assertThat(Validator.validate(model(10, Optional.of(ERRORS), count, array("A", 50, Direction.RESPONSE, Optional.of(new Trim.CountField("N")))))).isEmpty();
    }

    @Test
    void anArrayWhoseCountIsAnotherFieldAndAScalarAreNotLargeArrays() {
        FieldBinding count = binding(def("N", new WireType.Int(4, Optional.empty()), new Count.None()), Direction.INTERNAL, SemanticType.INTEGER, BINDING_AT, Optional.empty(), List.of());
        FieldBinding byField = binding(def("A", chr(2), new Count.Ref("N")), Direction.RESPONSE, SemanticType.STRING, BINDING_AT, Optional.empty(), List.of());
        FieldBinding scalar = binding(def("S", chr(2), new Count.None()), Direction.RESPONSE, SemanticType.STRING, BINDING_AT, Optional.empty(), List.of());
        assertThat(Validator.validate(model(1, Optional.of(ERRORS), count, byField, scalar))).isEmpty();
    }

    @Test
    void theMessageHasTheCountTheFieldAndTheThreshold() {
        Diagnostic d = Validator.validate(model(10, Optional.of(ERRORS), array("ROWS", 50, Direction.RESPONSE, Optional.empty()))).get(0);
        assertThat(d.message()).isEqualTo("Operation op, field ROWS: the response array has 50 elements and no trim, so every call returns all of them; "
                + "set 'trim' (at least 10 elements is large)");
    }

    // ---- OVL006

    @Test
    void anOperationWithoutAnErrorMappingGetsAWarningAtTheOperation() {
        List<Diagnostic> diagnostics = Validator.validate(model(100, Optional.empty(), array("A", 2, Direction.REQUEST, Optional.empty())));
        assertThat(codesAndLines(diagnostics)).containsExactly(Tuple.tuple("OVL006", Severity.WARNING, 3));
        assertThat(diagnostics.get(0).message()).isEqualTo("Operation op has no error mapping: a business error reported by the program is returned as a success");
        assertThat(Validator.validate(model(100, Optional.of(ERRORS), array("A", 2, Direction.REQUEST, Optional.empty())))).isEmpty();
    }

    // ---- positions

    @Test
    void aBindingDerivedFromThePcmlIsReportedAtTheOperation() {
        FieldBinding derived = binding(def("A", chr(2), new Count.None()), Direction.REQUEST, SemanticType.BINARY, PCML_AT, Optional.empty(), List.of());
        FieldBinding overlay = binding(def("B", chr(2), new Count.None()), Direction.REQUEST, SemanticType.BINARY, BINDING_AT, Optional.empty(), List.of());
        List<Diagnostic> diagnostics = Validator.validate(model(100, Optional.of(ERRORS), derived, overlay));
        assertThat(diagnostics).extracting(Diagnostic::code).containsExactly("OVL003", "OVL003");
        assertThat(diagnostics).extracting(d -> d.location()).containsExactly(OPERATION_AT, BINDING_AT);
        assertThat(diagnostics).extracting(Diagnostic::message).containsExactly(
                "Operation op, field A: semantic type BINARY is not allowed for wire type char",
                "Operation op, field B: semantic type BINARY is not allowed for wire type char");
    }

    @Test
    void aMemberIsFoundThroughItsParentAndReportedAtItsOwnPosition() {
        SourceLocation memberAt = new SourceLocation("api.yaml", 12, 7);
        FieldDef memberDef = def("M", chr(2), new Count.None());
        FieldBinding member = new FieldBinding("S.M", memberDef, Direction.REQUEST, Optional.empty(), "m", Optional.empty(), Optional.of(SemanticType.BINARY),
                new CodecSettings(Pad.RIGHT, ' ', "", Optional.empty()), false, Optional.empty(), Optional.empty(), false, Optional.empty(), List.of(), memberAt);
        FieldBinding other = new FieldBinding("S.X", def("X", chr(2), new Count.None()), Direction.REQUEST, Optional.empty(), "x", Optional.empty(),
                Optional.of(SemanticType.STRING), new CodecSettings(Pad.RIGHT, ' ', "", Optional.empty()), false, Optional.empty(), Optional.empty(), false,
                Optional.empty(), List.of(), BINDING_AT);
        FieldBinding parent = binding(def("S", new WireType.Struct("S_T"), new Count.None()), Direction.REQUEST, null, BINDING_AT, Optional.empty(), List.of(other, member));
        List<Diagnostic> diagnostics = Validator.validate(model(100, Optional.of(ERRORS), parent));
        assertThat(codesAndLines(diagnostics)).containsExactly(Tuple.tuple("OVL003", Severity.ERROR, 12));
        assertThat(diagnostics.get(0).message()).startsWith("Operation op, field S.M: ");
    }

    @Test
    void anIssueWithoutAFieldIsReportedAtTheOperationWithoutAFieldName() {
        // A count field that does not exist is an issue about the array, not about the missing field.
        FieldBinding trimmed = binding(def("R", chr(2), new Count.Fixed(3)), Direction.RESPONSE, SemanticType.STRING, BINDING_AT, Optional.of(new Trim.CountField("NOPE")), List.of());
        List<Diagnostic> diagnostics = Validator.validate(model(100, Optional.of(ERRORS), trimmed));
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).message()).isEqualTo("Operation op, field R: trim.countField cannot be found");
    }

    @Test
    void validatingTwiceGivesTheSameResult() {
        ApiRepositoryModel model = model(10, Optional.empty(), array("A", 50, Direction.RESPONSE, Optional.empty()));
        assertThat(Validator.validate(model)).isEqualTo(Validator.validate(model));
    }
}

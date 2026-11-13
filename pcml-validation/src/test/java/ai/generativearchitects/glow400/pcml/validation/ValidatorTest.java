package ai.generativearchitects.glow400.pcml.validation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** OVL003, OVL005 and OVL006 on fixture repositories: code, severity, file and line. */
class ValidatorTest {

    private static List<Diagnostic> validate(String repository) {
        return Validator.validate(Fixtures.model(repository));
    }

    private static List<Tuple> found(List<Diagnostic> diagnostics, String code) {
        return diagnostics.stream().filter(d -> d.code().equals(code))
                .map(d -> Tuple.tuple(d.severity(), d.location().file(), d.location().line())).toList();
    }

    private static Tuple error(String file, int line) {
        return Tuple.tuple(Severity.ERROR, file, line);
    }

    // ---- OVL003: one repository, one folder per condition

    @Test
    void ovl003IsReportedForEveryConditionAtTheBindingOrTheOperation() {
        List<Diagnostic> diagnostics = validate("invalid/ovl003");
        assertThat(found(diagnostics, "OVL003")).containsExactly(
                error("apis/ARRAYCOUNT/api.yaml", 1),        // request array counted by another field: no overlay entry for A
                error("apis/BADPATTERN/api.yaml", 8),        // unsupported date pattern
                error("apis/BOOLEANEQUAL/api.yaml", 8),      // trueValue equals falseValue
                error("apis/BOOLEANLONG/api.yaml", 8),       // trueValue longer than the field
                error("apis/BOOLEANTYPE/api.yaml", 8),       // trueValue on a type that is not boolean
                error("apis/CCSID/api.yaml", 1),             // multi-byte CCSID
                error("apis/EXAMPLE/api.yaml", 8),           // example that cannot be encoded
                error("apis/INJECT/api.yaml", 8),            // injected literal that cannot be encoded
                error("apis/INJECTREF/api.yaml", 11),        // injection into a field whose length names another field (C67)
                error("apis/INTEGERPACKED/api.yaml", 8),     // INTEGER on packed with decimals
                error("apis/NOFORMAT/api.yaml", 8),          // date on char without a usable format
                error("apis/NULLAS/api.yaml", 8),            // nullAs longer than the field
                error("apis/PAD/api.yaml", 8),               // pad on a type that is not char
                error("apis/PATTERNLENGTH/api.yaml", 8),     // pattern output differs from the field length
                error("apis/REFLENGTH/api.yaml", 1),         // char length given by another field, request side
                error("apis/SEMANTIC/api.yaml", 8),          // semantic type not allowed for the wire type
                error("apis/SEVERAL/api.yaml", 8),
                error("apis/SEVERAL/api.yaml", 9),
                error("apis/SEVERAL/api.yaml", 10),
                error("apis/TRIMCOUNT/api.yaml", 9),         // trim.countField is not a whole number
                error("apis/VARCHARPAD/api.yaml", 8));       // pad on a varchar (C89)
    }

    @Test
    void ovl003MessagesNameTheOperationAndTheFieldAndNeverAValue() {
        List<Diagnostic> diagnostics = validate("invalid/ovl003");
        assertThat(diagnostics).filteredOn(d -> d.code().equals("OVL003")).allSatisfy(d -> {
            assertThat(d.message()).startsWith("Operation p").contains(", field ");
            assertThat(d.message()).doesNotContain("TOOLONGFORTHIS").doesNotContain("notanumber").doesNotContain("NOT AVAILABLE");
        });
        Diagnostic example = diagnostics.stream().filter(d -> d.location().file().equals("apis/EXAMPLE/api.yaml") && d.code().equals("OVL003")).findFirst().orElseThrow();
        assertThat(example.message()).isEqualTo("Operation p11, field A: the example cannot be encoded in the field (VAL003)");
    }

    @Test
    void anOperationWithNoOvl003ErrorNeverFailsInTheCodec() {
        // The validator and the codec agree: this holds for every operation of every fixture repository.
        for (String repository : List.of("invalid/ovl003", "ovl003-ok", "advisory", "minimal", "inputoutput", "defaults-both", "structs",
                "body-struct", "operations-list", "action-parameter", "injected", "dclcase")) {
            ApiRepositoryModel model = Fixtures.model(repository);
            List<Diagnostic> diagnostics = Validator.validate(model);
            for (ApiOperation operation : model.operations()) {
                boolean reported = diagnostics.stream().anyMatch(d -> d.code().equals("OVL003") && d.message().startsWith("Operation " + operation.id() + ","));
                if (reported) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> OperationCodec.of(operation)).as(repository + "/" + operation.id())
                            .isInstanceOf(IllegalArgumentException.class);
                } else {
                    OperationCodec.of(operation);
                }
            }
        }
    }

    @Test
    void aLegalButUnusualConfigurationHasNoOvl003() {
        List<Diagnostic> diagnostics = validate("ovl003-ok");
        assertThat(diagnostics).extracting(Diagnostic::code).containsExactly("OVL006");
    }

    // ---- OVL005 and OVL006

    @Test
    void ovl005IsAWarningForALargeResponseArrayWithoutTrim() {
        List<Diagnostic> diagnostics = validate("advisory");
        // The threshold in gateway.yaml is 50: IDS has 50 elements, ECHO (BOTH) 500; 49 is below, TRIMMED has a trim, INPUT is request only.
        assertThat(found(diagnostics, "OVL005")).containsExactly(
                Tuple.tuple(Severity.WARNING, "apis/BIG/api.yaml", 8), Tuple.tuple(Severity.WARNING, "apis/BIG/api.yaml", 10));
        assertThat(diagnostics).filteredOn(d -> d.code().equals("OVL005")).extracting(Diagnostic::message).allSatisfy(m -> assertThat(m)
                .startsWith("Operation big, field ").contains("no trim").contains("at least 50"));
        assertThat(diagnostics.stream().filter(d -> d.code().equals("OVL005")).map(Diagnostic::message).toList())
                .anyMatch(m -> m.contains("field IDS") && m.contains("50 elements")).anyMatch(m -> m.contains("field ECHO") && m.contains("500 elements"));
    }

    @Test
    void ovl006IsAWarningForAnOperationWithoutAnErrorMapping() {
        List<Diagnostic> diagnostics = validate("advisory");
        assertThat(found(diagnostics, "OVL006")).containsExactly(Tuple.tuple(Severity.WARNING, "apis/BIG/api.yaml", 1));
        assertThat(diagnostics.stream().filter(d -> d.code().equals("OVL006")).findFirst().orElseThrow().message()).startsWith("Operation big has no error mapping");
    }

    @Test
    void diagnosticsAreOrderedByFileLineAndColumn() {
        List<Diagnostic> diagnostics = validate("invalid/ovl003");
        assertThat(diagnostics).isSortedAccordingTo(java.util.Comparator.comparing((Diagnostic d) -> d.location().file())
                .thenComparingInt(d -> d.location().line()).thenComparingInt(d -> d.location().column()));
    }

    @Test
    void anEmptyModelHasNothingToReport() {
        assertThat(Validator.validate(new ApiRepositoryModel(ai.generativearchitects.glow400.pcml.api.GatewayDefaults.standard(), List.of()))).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Validator.validate(null)).isInstanceOf(NullPointerException.class);
    }
}

package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One repository per diagnostic code: code, severity, file and line of every diagnostic. */
class DiagnosticsTest {

    private record Expected(String code, Severity severity, String file, int line) {
    }

    private static Expected error(String code, String file, int line) {
        return new Expected(code, Severity.ERROR, file, line);
    }

    private static Expected warning(String code, String file, int line) {
        return new Expected(code, Severity.WARNING, file, line);
    }

    private static List<Expected> diagnostics(String repository) {
        return Repos.load("invalid/" + repository).diagnostics().stream()
                .map(d -> new Expected(d.code(), d.severity(), d.location().file(), d.location().line()))
                .toList();
    }

    private static List<String> operationIds(String repository) {
        return Repos.load("invalid/" + repository).model().orElseThrow().operations().stream().map(ApiOperation::id).toList();
    }

    @Test
    void ovl001InputOutputWithoutDirectionIsAnErrorUnderPolicyFail() {
        assertThat(diagnostics("ovl001")).containsExactly(
                error("OVL001", "apis/GETBAL/api.yaml", 1), error("OVL001", "apis/GETBAL/api.yaml", 1));
        assertThat(operationIds("ovl001")).isEmpty();
    }

    @Test
    void ovl002NamesAParameterOrMemberThatIsNotInThePcml() {
        assertThat(diagnostics("ovl002")).containsExactly(
                error("OVL002", "apis/INJECT/api.yaml", 11),
                error("OVL002", "apis/NOMEMBER/api.yaml", 8),
                error("OVL002", "apis/NOPARAM/api.yaml", 9));
        assertThat(operationIds("ovl002")).isEmpty();
    }

    @Test
    void ovl004RoutePlaceholderAndPathBindingMustCorrespond() {
        assertThat(diagnostics("ovl004")).containsExactly(
                error("OVL004", "apis/BINDING/api.yaml", 8),
                error("OVL004", "apis/PLACEHOLDER/api.yaml", 3));
        assertThat(operationIds("ovl004")).isEmpty();
    }

    @Test
    void ovl010NotValidYaml() {
        assertThat(diagnostics("ovl010")).containsExactly(
                error("OVL010", "apis/BROKEN/api.yaml", 7),
                error("OVL010", "apis/DUPLICATE/api.yaml", 2),
                error("OVL010", "apis/TAG/api.yaml", 1));
        assertThat(operationIds("ovl010")).isEmpty();
    }

    @Test
    void ovl011UnknownKey() {
        assertThat(diagnostics("ovl011")).containsExactly(
                error("OVL011", "apis/PARAMETER/api.yaml", 8),
                error("OVL011", "apis/TOPLEVEL/api.yaml", 3));
        assertThat(operationIds("ovl011")).isEmpty();
    }

    @Test
    void ovl012MissingKeyOrInvalidValue() {
        assertThat(diagnostics("ovl012")).containsExactly(
                error("OVL012", "apis/EXPRESSION/api.yaml", 11),
                error("OVL012", "apis/METHOD/api.yaml", 4),
                error("OVL012", "apis/NOVERSION/api.yaml", 1),
                error("OVL012", "apis/STATUS/api.yaml", 12),
                error("OVL012", "apis/VERSION/api.yaml", 5));
        assertThat(operationIds("ovl012")).isEmpty();
    }

    @Test
    void ovl013PcmlNotFoundHasErrorsOrProgramMissing() {
        assertThat(diagnostics("ovl013")).containsExactly(
                error("PCML004", "apis/BADPCML/ORDSTATUS.pcml", 4),
                error("OVL013", "apis/BADPCML/api.yaml", 2),
                warning("PCML006", "apis/CIRCULAR/LOOPER.pcml", 3),
                error("OVL013", "apis/CIRCULAR/api.yaml", 1),
                error("OVL013", "apis/NOPCML/api.yaml", 2),
                error("OVL013", "apis/NOPROGRAM/api.yaml", 1));
        assertThat(operationIds("ovl013")).isEmpty();
    }

    @Test
    void ovl014DuplicateNamesIdsAndRoutes() {
        assertThat(diagnostics("ovl014")).containsExactly(
                error("OVL014", "apis/ID2/api.yaml", 1),
                error("OVL014", "apis/NAMES/api.yaml", 10),
                error("OVL014", "apis/ROUTE2/api.yaml", 1));
        // The first operation with an id, and the first with a route and method, are kept.
        assertThat(operationIds("ovl014")).containsExactly("shared-id", "route-one");
    }

    @Test
    void ovl015StructOrArrayCannotBeBoundToPathQueryOrHeader() {
        assertThat(diagnostics("ovl015")).containsExactly(
                error("OVL015", "apis/ARRAYHEADER/api.yaml", 9),
                error("OVL015", "apis/GETSTRUCT/api.yaml", 1),
                error("OVL015", "apis/GETSTRUCT/api.yaml", 1));
        assertThat(operationIds("ovl015")).isEmpty();
    }

    @Test
    void ovl016DirectionContradictsPcmlUsage() {
        assertThat(diagnostics("ovl016")).containsExactly(
                error("OVL016", "apis/INPUT/api.yaml", 8),
                error("OVL016", "apis/OUTPUT/api.yaml", 9));
        assertThat(operationIds("ovl016")).isEmpty();
    }

    @Test
    void ovl017CountFieldOrErrorFieldMustBeAScalarThatExists() {
        assertThat(diagnostics("ovl017")).containsExactly(
                error("OVL017", "apis/COUNTFIELD/api.yaml", 10),
                error("OVL017", "apis/ERRORFIELD/api.yaml", 11),
                error("OVL017", "apis/ERRORFIELD/api.yaml", 12));
        assertThat(operationIds("ovl017")).isEmpty();
    }

    @Test
    void ovl018InjectAndParametersConfigureTheSameParameter() {
        assertThat(diagnostics("ovl018")).containsExactly(error("OVL018", "apis/BOTH/api.yaml", 12));
        assertThat(operationIds("ovl018")).isEmpty();
    }

    @Test
    void ovl019FolderWithPcmlButNoOverlayIsAWarning() {
        assertThat(diagnostics("ovl019")).containsExactly(warning("OVL019", "apis/NOOVERLAY/ORDSTATUS.pcml", 1));
        assertThat(Repos.load("invalid/ovl019").hasErrors()).isFalse();
        assertThat(operationIds("ovl019")).isEmpty();
    }

    @Test
    void ovl020TwoOverlayFilesOrBothFormsInOneFile() {
        assertThat(diagnostics("ovl020")).containsExactly(
                error("OVL020", "apis/BOTHFORMS/api.yaml", 6),
                error("OVL020", "apis/TWOFILES/api.yml", 1));
        assertThat(operationIds("ovl020")).isEmpty();
    }

    @Test
    void everyOverlayDiagnosticCarriesAnOverlayFileExceptWhenThereIsNone() {
        for (String repository : List.of("ovl001", "ovl002", "ovl004", "ovl010", "ovl011", "ovl012", "ovl013", "ovl014",
                "ovl015", "ovl016", "ovl017", "ovl018", "ovl020")) {
            for (Diagnostic d : Repos.load("invalid/" + repository).diagnostics()) {
                if (d.code().startsWith("OVL")) {
                    assertThat(d.location().file()).as("%s %s", repository, d).matches(".*api\\.ya?ml");
                    assertThat(d.location().line()).as("%s %s", repository, d).isPositive();
                    assertThat(d.location().column()).as("%s %s", repository, d).isPositive();
                }
            }
        }
    }

    @Test
    void theDiagnosticsOfAllInvalidRepositoriesCoverEveryCodeOfTheBrief() {
        List<String> codes = List.of("001", "002", "004", "010", "011", "012", "013", "014", "015", "016", "017", "018", "019", "020");
        for (String code : codes) {
            assertThat(Repos.load("invalid/ovl" + code).diagnostics()).extracting(Diagnostic::code)
                    .as("repository ovl%s", code).contains("OVL" + code);
        }
    }
}

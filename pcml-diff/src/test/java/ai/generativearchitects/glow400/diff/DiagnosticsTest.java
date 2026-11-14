package ai.generativearchitects.glow400.diff;

import static ai.generativearchitects.glow400.diff.Repo.data;
import static ai.generativearchitects.glow400.diff.Repo.overlay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** API002 to API006: code, severity, message and position. */
class DiagnosticsTest {

    private static final String A = data("A", "type=\"char\" length=\"4\"", "input");
    private static final String AB = A + data("B", "type=\"char\" length=\"4\"", "input");
    private static final String PA = "  A: { name: a }\n";
    private static final String PB = "  B: { name: b }\n";

    private static Diagnostic only(DiffResult result, String code) {
        List<Diagnostic> found = result.diagnostics().stream().filter(d -> d.code().equals(code)).toList();
        assertThat(found).as(code).hasSize(1);
        return found.get(0);
    }

    @Test
    void api002IsOneErrorPerApiListingItsBreakingChangesAtTheFirstOfThem(@TempDir Path dir) {
        String before = overlay("/x", "POST", "", PA + PB);
        String after = overlay("/x", "POST", "", "  A: { name: a, type: integer }\n").replace("version: 1.0.0", "version: 1.1.0");
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "T", A, after);
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", AB, before), current);
        Diagnostic d = only(result, "API002");
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.message()).isEqualTo("API t has breaking changes but its version goes from 1.0.0 to 1.1.0; the major version must increase. "
                + "Breaking changes: t Request field b was removed; t Request field a changed type from string to integer");
        // the first breaking change is the removal of b, which is not in the current repository: the position is the operation's
        assertThat(d.location()).isEqualTo(current.operations().get(0).source());
    }

    @Test
    void thePositionOfAChangeIsTheOverlayPositionOfItsFieldInTheCurrentRepository(@TempDir Path dir) {
        String after = overlay("/x", "POST", "", "  A: { name: a, type: integer }\n").replace("version: 1.0.0", "version: 1.0.1");
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "T", A, after);
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", A, overlay("/x", "POST", "", PA)), current);
        assertThat(only(result, "API002").location()).isEqualTo(current.operations().get(0).bindings().get(0).source());
        assertThat(only(result, "API002").location().file()).isEqualTo("apis/T/api.yaml");
        assertThat(only(result, "API002").location().line()).isEqualTo(7);
    }

    @Test
    void aFieldOfAnObjectIsPositionedAtItsOwnOverlayEntry(@TempDir Path dir) {
        String pcml = data("R", "type=\"struct\" struct=\"ROW\"", "input");
        String before = overlay("/x", "POST", "", "  R:\n    name: r\n    fields:\n      X: { name: x }\n      Y: { name: y }\n");
        String after = overlay("/x", "POST", "", "  R:\n    name: r\n    fields:\n      X: { name: x, type: integer }\n      Y: { name: y }\n")
                .replace("version: 1.0.0", "version: 1.0.1");
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "T", pcml, after);
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", pcml, before), current);
        SourceLocation expected = current.operations().get(0).bindings().get(0).children().get(0).source();
        assertThat(only(result, "API002").location()).isEqualTo(expected);
        assertThat(expected.line()).isEqualTo(10);
    }

    @Test
    void aBindingDerivedFromThePcmlAloneIsPositionedAtTheOperation(@TempDir Path dir) {
        // field B has no overlay entry in the current repository, so its position is the operation's
        String after = overlay("/x", "POST", "", PA).replace("version: 1.0.0", "version: 1.0.1");
        String before = overlay("/x", "POST", "", PA + "  B: { name: b, type: integer }\n");
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "T", AB, after);
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", AB, before), current);
        assertThat(result.apis().get(0).changes()).extracting(Change::path).contains("request.b");
        assertThat(only(result, "API002").location()).isEqualTo(current.operations().get(0).source());
    }

    @Test
    void api003SummarisesTheChangesWhenThereIsAnyAndNothingWhenThereIsNone(@TempDir Path dir) {
        String after = overlay("/x", "POST", "summary: New\n", PA + PB).replace("version: 1.0.0", "version: 1.1.0");
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "T", AB, after);
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", A, overlay("/x", "POST", "", PA)), current);
        Diagnostic d = only(result, "API003");
        assertThat(d.severity()).isEqualTo(Severity.INFO);
        assertThat(d.message()).isEqualTo("API t: compatible (1 compatible, 2 internal, 1 documentation), version 1.0.0 to 1.1.0");
        assertThat(d.location()).isEqualTo(new SourceLocation("apis/T/api.yaml", 1, 1));
        DiffResult none = ApiDiff.compare(Repo.load(dir.resolve("n1"), "T", A, overlay("/x", "POST", "", PA)),
                Repo.load(dir.resolve("n2"), "T", A, overlay("/x", "POST", "", PA)));
        assertThat(none.diagnostics()).isEmpty();
    }

    @Test
    void api004ForACompatibleChangeWithoutAMinorIncreaseAndForALowerVersion(@TempDir Path dir) {
        String after = overlay("/x", "POST", "", PA + PB);
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "T", AB, after);
        DiffResult compatible = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", A, overlay("/x", "POST", "", PA)), current);
        Diagnostic d = only(compatible, "API004");
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.message()).isEqualTo("API t has compatible changes but its version goes from 1.0.0 to 1.0.0; the minor or major version must increase");
        assertThat(d.location()).isEqualTo(current.operations().get(0).bindings().get(1).source());
        DiffResult zero = ApiDiff.compare(Repo.load(dir.resolve("z1"), "T", A, overlay("/x", "POST", "", PA).replace("1.0.0", "0.1.0")),
                Repo.load(dir.resolve("z2"), "T", AB, after.replace("1.0.0", "0.1.0")));
        assertThat(only(zero, "API004").message()).endsWith("the patch, minor or major version must increase");
        DiffResult lower = ApiDiff.compare(Repo.load(dir.resolve("l1"), "T", A, overlay("/x", "POST", "", PA).replace("1.0.0", "2.0.0")),
                Repo.load(dir.resolve("l2"), "T", A, overlay("/x", "POST", "", PA)));
        Diagnostic lowerDiagnostic = only(lower, "API004");
        assertThat(lowerDiagnostic.message()).isEqualTo("The version of API t is 1.0.0, which is lower than the released version 2.0.0");
        assertThat(lowerDiagnostic.location()).isEqualTo(new SourceLocation("apis/T/api.yaml", 1, 1));
    }

    @Test
    void api005ForAnApiThatIsGoneAndApi006ForANewOne(@TempDir Path dir) {
        ApiRepositoryModel released = Repo.load(dir.resolve("released"), "Old", A, overlay("/x", "POST", "", PA));
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "New", A, overlay("/y", "POST", "", PA).replace("1.0.0", "3.2.1"));
        DiffResult result = ApiDiff.compare(released, current);
        assertThat(result.apis()).isEmpty();
        Diagnostic gone = only(result, "API005");
        assertThat(gone.severity()).isEqualTo(Severity.WARNING);
        assertThat(gone.message()).isEqualTo("API old is in the released repository and not in the current one");
        assertThat(gone.location()).isEqualTo(new SourceLocation("gateway.yaml", 1, 1));
        Diagnostic added = only(result, "API006");
        assertThat(added.severity()).isEqualTo(Severity.INFO);
        assertThat(added.message()).isEqualTo("API new is new in version 3.2.1");
        assertThat(added.location()).isEqualTo(new SourceLocation("apis/New/api.yaml", 1, 1));
    }

    @Test
    void diagnosticsAreOrderedByFileLineColumnThenCode(@TempDir Path dir) {
        ApiRepositoryModel released = Repo.load(dir.resolve("released"), "Old", A, overlay("/x", "POST", "", PA));
        String one = "pcml: T.pcml\nversion: 1.0.0\nprogram: T\noperations:\n  - { id: new-op, route: /y, method: POST, parameters: { A: { name: a } } }\n";
        String two = "pcml: T.pcml\nversion: 1.0.0\nprogram: T\noperations:\n  - { id: zed-op, route: /z, method: POST, parameters: { A: { name: a } } }\n";
        Repo.load(dir.resolve("current"), "New", A, one);
        ApiRepositoryModel current = Repo.load(dir.resolve("current"), "Zed", A, two);
        DiffResult result = ApiDiff.compare(released, current);
        assertThat(result.diagnostics()).isSortedAccordingTo(Comparator.comparing((Diagnostic d) -> d.location().file())
                .thenComparingInt(d -> d.location().line()).thenComparingInt(d -> d.location().column()).thenComparing(Diagnostic::code));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("API006", "API006", "API005");
    }

    @Test
    void messagesNameApisOperationsAndFieldsAndNeverValues(@TempDir Path dir) {
        String before = overlay("/x", "POST", "summary: SECRETSUMMARY\n", "  A: { name: a, example: SECRETEXAMPLE, description: SECRETDESC }\n");
        String after = overlay("/x", "POST", "summary: OTHER\n", "  A: { name: a, example: OTHEREX, description: OTHERDESC, type: integer }\n").replace("1.0.0", "1.0.1");
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("released"), "T", A, before), Repo.load(dir.resolve("current"), "T", A, after));
        assertThat(result.diagnostics()).isNotEmpty();
        result.diagnostics().forEach(d -> assertThat(d.message()).doesNotContain("SECRET").doesNotContain("OTHER"));
        result.apis().get(0).changes().forEach(c -> assertThat(c.description()).doesNotContain("SECRET").doesNotContain("OTHER"));
    }

    @Test
    void theTypesRejectNull() {
        assertThatThrownBy(() -> ApiDiff.compare(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Change(null, "", "", "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Change(Impact.NONE, null, "", "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Change(Impact.NONE, "", null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Change(Impact.NONE, "", "", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiChangeSet(null, java.util.Optional.empty(), java.util.Optional.empty(), Impact.NONE, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiChangeSet("a", null, java.util.Optional.empty(), Impact.NONE, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiChangeSet("a", java.util.Optional.empty(), null, Impact.NONE, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiChangeSet("a", java.util.Optional.empty(), java.util.Optional.empty(), null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DiffResult(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DiffResult(List.of(), null)).isInstanceOf(NullPointerException.class);
        List<Change> changes = new java.util.ArrayList<>(List.of(new Change(Impact.NONE, "", "", "")));
        ApiChangeSet set = new ApiChangeSet("a", java.util.Optional.empty(), java.util.Optional.empty(), Impact.NONE, changes);
        changes.clear();
        assertThat(set.changes()).hasSize(1);
    }
}

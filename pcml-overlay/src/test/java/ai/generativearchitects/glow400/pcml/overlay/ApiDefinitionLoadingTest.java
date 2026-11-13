package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** APIs: one per overlay file, named after the folder, with the optional {@code title} of overlay format v0.6. */
class ApiDefinitionLoadingTest {

    private static final String PCML = """
            <?xml version="1.0"?>
            <pcml version="6.0">
              <program name="P" path="/QSYS.LIB/DEMOLIB.LIB/P.PGM">
                <data name="A" type="char" length="4" usage="input"/>
              </program>
            </pcml>
            """;

    private static String single(String route, String extra) {
        return "program: P\npcml: P.pcml\nversion: 2.1.0\n" + extra + "route: " + route + "\nmethod: POST\n";
    }

    private static final String LIST = """
            pcml: P.pcml
            version: 3.0.0
            title: Two operations
            program: P
            operations:
              - id: first
                route: /one
                method: POST
              - id: second
                route: /two
                method: POST
            """;

    private static RepositoryResult load(Path dir, String folder, String overlay) {
        return new Repos.Temp(dir).file("apis/" + folder + "/P.pcml", PCML).file("apis/" + folder + "/api.yaml", overlay).load();
    }

    @Test
    void anApiIsNamedAfterItsFolderInLowerCaseAndTitledWithItsNameByDefault(@TempDir Path dir) {
        RepositoryResult result = load(dir, "OrdStatus", single("/o", ""));
        assertThat(result.diagnostics()).isEmpty();
        ApiDefinition api = result.model().orElseThrow().apis().get(0);
        assertThat(api.name()).isEqualTo("ordstatus");
        assertThat(api.title()).isEqualTo("ordstatus");
        assertThat(api.version()).isEqualTo("2.1.0");
        assertThat(api.overlayPath()).isEqualTo("apis/OrdStatus/api.yaml");
        assertThat(api.operations()).isEqualTo(result.model().orElseThrow().operations());
    }

    @Test
    void theTitleIsReadInBothFormsOfTheFile(@TempDir Path dir) {
        ApiDefinition one = load(dir, "A", single("/o", "title: Order status API\n")).model().orElseThrow().apis().get(0);
        assertThat(one.title()).isEqualTo("Order status API");
        ApiDefinition many = load(dir.resolve("m"), "B", LIST).model().orElseThrow().apis().get(0);
        assertThat(many.title()).isEqualTo("Two operations");
        assertThat(many.version()).isEqualTo("3.0.0");
        assertThat(many.operations()).extracting(o -> o.id()).containsExactly("first", "second");
    }

    @Test
    void aBlankTitleIsRejected(@TempDir Path dir) {
        RepositoryResult result = load(dir, "A", single("/o", "title: \" \"\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL012");
    }

    @Test
    void aFolderNameThatIsNotAnApiNameIsOvl012OnTheOverlayFile(@TempDir Path dir) {
        List<String> folders = List.of("my@api", "-x", "my api", ".x");
        for (String folder : folders) {
            RepositoryResult result = load(dir.resolve(String.valueOf(folders.indexOf(folder))), folder, single("/o", ""));
            assertThat(result.diagnostics()).as(folder).hasSize(1);
            Diagnostic d = result.diagnostics().get(0);
            assertThat(d.code()).isEqualTo("OVL012");
            assertThat(d.severity()).isEqualTo(Severity.ERROR);
            assertThat(d.location().file()).isEqualTo("apis/" + folder + "/api.yaml");
            assertThat(d.message()).contains("[a-z0-9][a-z0-9._-]*").contains(folder.toLowerCase());
            assertThat(result.model().orElseThrow().apis()).isEmpty();
        }
    }

    @Test
    void namesWithDotsDashesUnderscoresAndDigitsAreFine(@TempDir Path dir) {
        RepositoryResult result = load(dir, "Orders_v-2.1", single("/o", ""));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().apis()).extracting(ApiDefinition::name).containsExactly("orders_v-2.1");
    }

    @Test
    void anApiLosesTheOperationsThatAreDuplicates(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir)
                .file("apis/A/P.pcml", PCML).file("apis/A/api.yaml", single("/o", ""))
                .file("apis/B/P.pcml", PCML).file("apis/B/api.yaml", single("/o", "")).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL014");
        assertThat(result.model().orElseThrow().apis()).extracting(ApiDefinition::name).containsExactly("a");
        assertThat(result.model().orElseThrow().operations()).hasSize(1);
    }
}

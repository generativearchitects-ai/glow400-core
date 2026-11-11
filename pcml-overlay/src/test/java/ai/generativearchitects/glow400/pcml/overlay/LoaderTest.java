package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The repository layout: folders, files, paths and shared PCML. */
class LoaderTest {

    private static String overlay(String program, String route) {
        return "program: " + program + "\npcml: ORDSTATUS.pcml\nroute: " + route + "\nmethod: GET\nversion: 1.0.0\n"
                + "parameters:\n  P0ORDN: { name: orderNumber }\n";
    }

    private static List<String> ids(RepositoryResult result) {
        return result.model().orElseThrow().operations().stream().map(ApiOperation::id).toList();
    }

    private static List<Tuple> codes(RepositoryResult result) {
        return result.diagnostics().stream().map(d -> Tuple.tuple(d.code(), d.location().file())).toList();
    }

    // ---- the root

    @Test
    void aRootThatDoesNotExistOrIsAFileIsOvl013(@TempDir Path dir) throws IOException {
        RepositoryResult missing = OverlayLoader.loadRepository(dir.resolve("nope"));
        assertThat(missing.model()).isEmpty();
        assertThat(missing.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .containsExactly(Tuple.tuple("OVL013", Severity.ERROR));
        Path file = Files.writeString(dir.resolve("file.txt"), "x");
        assertThat(OverlayLoader.loadRepository(file).diagnostics()).extracting(Diagnostic::code).containsExactly("OVL013");
        assertThat(missing.hasErrors()).isTrue();
    }

    @Test
    void anEmptyRepositoryHasNoOperationsAndTheStandardDefaults(@TempDir Path dir) {
        RepositoryResult result = OverlayLoader.loadRepository(dir);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.model().orElseThrow().defaults()).isEqualTo(GatewayDefaults.standard());
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @Test
    void filesInApisAndOtherFilesInFoldersAreIgnored(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir)
                .file("apis/README.md", "not a folder")
                .file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", overlay("ORDSTATUS", "/a/{orderNumber}"))
                .file("apis/A/mock/response.json", "{}")
                .file("apis/A/tests/case.yaml", "not: an overlay")
                .file("apis/A/notes.txt", "x")
                .file("elsewhere/api.yaml", "ignored: true")
                .load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(ids(result)).containsExactly("ordstatus");
    }

    // ---- order and names

    @Test
    void foldersAreReadInNameOrderIgnoringCase(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir);
        for (String folder : List.of("b", "C", "A", "a2")) {
            repo.file("apis/" + folder + "/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                    .file("apis/" + folder + "/api.yaml", "id: op-" + folder + "\n" + overlay("ORDSTATUS", "/" + folder + "/{orderNumber}"));
        }
        assertThat(ids(repo.load())).containsExactly("op-A", "op-a2", "op-b", "op-C");
    }

    @Test
    void anApiYmlFileIsAnOverlayToo(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yml", overlay("ORDSTATUS", "/a/{orderNumber}")).load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).overlayPath()).isEqualTo("apis/A/api.yml");
    }

    @Test
    void aFolderWithPcmlFilesInAnyCaseButNoOverlayIsAWarning(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("apis/B/Z.PCML", Repos.ORDSTATUS_PCML)
                .file("apis/B/A.pcml", Repos.ORDSTATUS_PCML).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity, d -> d.location().file())
                .containsExactly(Tuple.tuple("OVL019", Severity.WARNING, "apis/B/A.pcml"));
        assertThat(new Repos.Temp(dir.resolve("none")).file("apis/B/readme.txt", "x").load().diagnostics()).isEmpty();
    }

    // ---- paths

    @Test
    void thePcmlCanLiveInAnotherFolderOfTheRepository(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("shared/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", overlay("ORDSTATUS", "/a/{orderNumber}").replace("pcml: ORDSTATUS.pcml", "pcml: ../../shared/ORDSTATUS.pcml"))
                .load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).pcmlPath()).isEqualTo("shared/ORDSTATUS.pcml");
    }

    @Test
    void aSharedPcmlIsParsedOnceAndItsDiagnosticsAreReportedOnce(@TempDir Path dir) {
        String pcml = Repos.ORDSTATUS_PCML.replace("<data name=\"P0DATE\"", "<data mystery=\"1\" name=\"P0DATE\"");
        Repos.Temp repo = new Repos.Temp(dir).file("shared/ORDSTATUS.pcml", pcml);
        for (String folder : List.of("A", "B")) {
            repo.file("apis/" + folder + "/api.yaml", "id: op-" + folder + "\n"
                    + overlay("ORDSTATUS", "/" + folder + "/{orderNumber}").replace("pcml: ORDSTATUS.pcml", "pcml: ../../shared/ORDSTATUS.pcml"));
        }
        RepositoryResult result = repo.load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().file())
                .containsExactly(Tuple.tuple("PCML007", "shared/ORDSTATUS.pcml"));
        assertThat(ids(result)).containsExactly("op-A", "op-B");
    }

    @Test
    void aPcmlWithErrorsIsReportedOnceAndEveryOverlayThatUsesItIsOvl013(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir).file("shared/BAD.pcml", "<pcml version=\"6.0\"><program name=\"ORDSTATUS\"><data name=\"X\" type=\"nope\"/></program></pcml>");
        for (String folder : List.of("A", "B")) {
            repo.file("apis/" + folder + "/api.yaml", overlay("ORDSTATUS", "/x").replace("pcml: ORDSTATUS.pcml", "pcml: ../../shared/BAD.pcml"));
        }
        RepositoryResult result = repo.load();
        assertThat(codes(result)).containsExactly(
                Tuple.tuple("OVL013", "apis/A/api.yaml"), Tuple.tuple("OVL013", "apis/B/api.yaml"),
                Tuple.tuple("PCML004", "shared/BAD.pcml"));
        assertThat(ids(result)).isEmpty();
    }

    @Test
    void aPcmlWithOnlyWarningsStillLoads(@TempDir Path dir) {
        String pcml = Repos.ORDSTATUS_PCML.replace("</pcml>", "  <struct name=\"LOOP\"><data name=\"N\" type=\"struct\" struct=\"LOOP\"/></struct>\n</pcml>");
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", pcml)
                .file("apis/A/api.yaml", overlay("ORDSTATUS", "/a/{orderNumber}")).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .containsExactly(Tuple.tuple("PCML006", Severity.WARNING));
        assertThat(ids(result)).containsExactly("ordstatus");
    }

    @Test
    void aPcmlOutsideTheRepositoryIsOvl012(@TempDir Path dir) throws IOException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(dir.resolve("outside.pcml"), Repos.ORDSTATUS_PCML);
        for (String path : List.of("../../../outside.pcml", dir.resolve("outside.pcml").toString())) {
            RepositoryResult result = new Repos.Temp(repo).file("apis/A/api.yaml",
                    overlay("ORDSTATUS", "/a").replace("pcml: ORDSTATUS.pcml", "pcml: " + path)).load();
            assertThat(codes(result)).as(path).containsExactly(Tuple.tuple("OVL012", "apis/A/api.yaml"));
            assertThat(result.diagnostics().get(0).location().line()).isEqualTo(2);
        }
    }

    @Test
    void aPcmlValueThatIsNotAValidPathIsOvl012(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/api.yaml",
                overlay("ORDSTATUS", "/a").replace("pcml: ORDSTATUS.pcml", "pcml: \"bad\\0name.pcml\"")).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line())
                .containsExactly(Tuple.tuple("OVL012", 2));
        assertThat(result.diagnostics().get(0).message()).contains("not a valid path");
    }

    @Test
    void aSymbolicLinkOutOfTheRepositoryIsOvl012(@TempDir Path dir) throws IOException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path outside = Files.writeString(dir.resolve("outside.pcml"), Repos.ORDSTATUS_PCML);
        Files.createDirectories(repo.resolve("apis/A"));
        Files.createSymbolicLink(repo.resolve("apis/A/ORDSTATUS.pcml"), outside);
        RepositoryResult result = new Repos.Temp(repo).file("apis/A/api.yaml", overlay("ORDSTATUS", "/a")).load();
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL012", "apis/A/api.yaml"));
        assertThat(ids(result)).isEmpty();
    }

    @Test
    void aMissingPcmlOrADirectoryInItsPlaceIsOvl013(@TempDir Path dir) throws IOException {
        RepositoryResult missing = new Repos.Temp(dir.resolve("a")).file("apis/A/api.yaml", overlay("ORDSTATUS", "/a")).load();
        assertThat(codes(missing)).containsExactly(Tuple.tuple("OVL013", "apis/A/api.yaml"));
        Files.createDirectories(dir.resolve("b/apis/A/ORDSTATUS.pcml"));
        RepositoryResult directory = new Repos.Temp(dir.resolve("b")).file("apis/A/api.yaml", overlay("ORDSTATUS", "/a")).load();
        assertThat(codes(directory)).containsExactly(Tuple.tuple("OVL013", "apis/A/api.yaml"));
    }

    @Test
    void aProgramThatIsNotInThePcmlIsOvl013AtTheProgramKey(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", overlay("ordstatus", "/a")).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line()).containsExactly(Tuple.tuple("OVL013", 1));
        assertThat(result.diagnostics().get(0).message()).contains("'ordstatus'");
    }

    // ---- across folders

    @Test
    void idsAndRoutesMustBeUniqueAcrossFolders(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir);
        repo.api("A", overlay("ORDSTATUS", "/same/{orderNumber}"));
        repo.api("B", overlay("ORDSTATUS", "/same/{orderNumber}").replace("program:", "id: other\nprogram:"));
        repo.api("C", overlay("ORDSTATUS", "/c/{orderNumber}"));
        RepositoryResult result = repo.load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().file())
                .containsExactly(Tuple.tuple("OVL014", "apis/B/api.yaml"), Tuple.tuple("OVL014", "apis/C/api.yaml"));
        assertThat(ids(result)).containsExactly("ordstatus");
    }

    @Test
    void diagnosticsAreOrderedByFileThenPosition(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir).file("gateway.yaml", "defaults: { inputoutput: maybe }\n");
        repo.api("B", overlay("ORDSTATUS", "/b").replace("version: 1.0.0", "version: x"));
        repo.api("A", overlay("ORDSTATUS", "/a").replace("version: 1.0.0", "version: y"));
        assertThat(codes(repo.load())).containsExactly(
                Tuple.tuple("OVL012", "apis/A/api.yaml"), Tuple.tuple("OVL012", "apis/B/api.yaml"),
                Tuple.tuple("OVL012", "gateway.yaml"));
    }

    // ---- unreadable files

    @Test
    void aFileThatIsNotUtf8IsOvl010(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("apis/A"));
        Files.write(dir.resolve("apis/A/ORDSTATUS.pcml"), Repos.ORDSTATUS_PCML.getBytes());
        Files.write(dir.resolve("apis/A/api.yaml"), new byte[] {(byte) 0xFF, (byte) 0xFE, 'a', (byte) 0xC3, 0x28});
        RepositoryResult result = OverlayLoader.loadRepository(dir);
        assertThat(codes(result)).containsExactly(Tuple.tuple("OVL010", "apis/A/api.yaml"));
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }
}

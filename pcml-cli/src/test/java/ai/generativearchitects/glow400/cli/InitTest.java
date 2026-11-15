package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.cli.Support.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code init}: what it creates, what it leaves alone, and what it says. */
class InitTest {

    @Test
    void createsGatewayYamlAndAnOverlayForEveryFolderWithOnePcmlFile(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Result result = Support.run(dir, "init");
        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEqualTo("""
                created gateway.yaml
                created apis/GETBAL/api.yaml
                created apis/ORDSTATUS/api.yaml
                found 2 PCML files, created 2 api.yaml
                """);
        assertThat(result.out()).isEqualTo("0 errors, 0 warnings, 0 notes\n");
        assertThat(Support.read(dir.resolve("gateway.yaml"))).isEqualTo(ai.generativearchitects.glow400.pcml.scaffold.GatewayScaffold.create());
        assertThat(Support.read(dir.resolve("apis/ORDSTATUS/api.yaml"))).isEqualTo("""
                pcml: ORDSTATUS.pcml
                version: 0.1.0
                title: ORDSTATUS
                operations:
                  - id: ordstatus
                    program: ORDSTATUS
                    route: /ordstatus
                    method: POST
                    parameters:
                      P0ORDN: { name: p0ordn }  # inputoutput: run annotate, or add direction: request | response | both | internal
                      P0STAT: { name: p0stat }
                      P0QTY: { name: p0qty }  # inputoutput: run annotate, or add direction: request | response | both | internal
                """);
        assertThat(Support.names(dir)).doesNotContain(".glow400");
        assertThat(Support.names(dir).stream().filter(n -> n.contains(".tmp"))).isEmpty();
    }

    @Test
    void theRepositoryThenFailsValidationWithOvl001OnlyAndAnnotateFixesIt(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        assertThat(Support.run(dir, "init").exit()).isZero();
        Result validate = Support.run(dir, "validate");
        assertThat(validate.exit()).isEqualTo(1);
        assertThat(validate.out().lines().filter(l -> l.contains(" error "))).isNotEmpty().allMatch(l -> l.contains("error OVL001:"));
        assertThat(validate.out()).contains("apis/ORDSTATUS/api.yaml:10:7: error OVL001: Parameter 'P0ORDN' has usage inputoutput and no direction in the overlay: set 'direction' for it");
        // GETBAL.DET (a struct): request, default name. GETBAL.DAYS: internal. ORDSTATUS.P0ORDN: request, default type and name.
        // ORDSTATUS.P0QTY: both, default name (its type has no alternative, so it is not asked).
        Result annotate = Support.runWithInput(dir, "request\n\ni\nreq\n\n\nb\n\n", "annotate");
        assertThat(annotate.err()).contains("ORDSTATUS.P0ORDN  inputoutput -> request\n").contains("ORDSTATUS.P0QTY  inputoutput -> both\n")
                .contains("GETBAL.DET  inputoutput -> request\n").contains("GETBAL.DAYS  inputoutput -> internal\n");
        assertThat(annotate.exit()).isZero();
        assertThat(Support.run(dir, "validate").exit()).isZero();
        Result bundle = Support.run(dir, "bundle", "--name", "orders", "--version", "1.0.0", "--out", "out/orders.rpgapi");
        assertThat(bundle.exit()).isZero();
        assertThat(dir.resolve("out/orders.rpgapi")).isRegularFile();
    }

    @Test
    void aSecondRunCreatesNothingAndChangesNoByte(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.run(dir, "init");
        java.util.Map<String, byte[]> before = snapshot(dir);
        Result second = Support.run(dir, "init");
        assertThat(second.exit()).isZero();
        assertThat(second.err()).isEqualTo("""
                kept gateway.yaml
                kept apis/GETBAL/api.yaml
                kept apis/ORDSTATUS/api.yaml
                found 2 PCML files, created 0 api.yaml
                """);
        assertThat(snapshot(dir)).containsOnlyKeys(before.keySet());
        before.forEach((name, bytes) -> assertThat(Support.bytes(dir.resolve(name))).as(name).isEqualTo(bytes));
    }

    private static java.util.Map<String, byte[]> snapshot(Path dir) {
        java.util.Map<String, byte[]> files = new java.util.TreeMap<>();
        for (String name : Support.names(dir)) {
            if (Files.isRegularFile(dir.resolve(name))) {
                files.put(name, Support.bytes(dir.resolve(name)));
            }
        }
        return files;
    }

    @Test
    void dryRunWritesNothingAndSaysWhatItWould(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        java.util.List<String> before = Support.names(dir);
        Result result = Support.run(dir, "init", "--dry-run");
        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEqualTo("""
                would create gateway.yaml
                would create apis/GETBAL/api.yaml
                would create apis/ORDSTATUS/api.yaml
                found 2 PCML files, would create 2 api.yaml
                """);
        assertThat(Support.names(dir)).isEqualTo(before);
    }

    @Test
    void anExistingFileIsKeptWhateverItHolds(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("gateway.yaml"), "# mine\nemitters: [descriptor]\n");
        Support.write(dir.resolve("apis/GETBAL/api.yml"), "not even yaml: [");
        Result result = Support.run(dir, "init");
        assertThat(result.err()).isEqualTo("""
                kept gateway.yaml
                kept apis/GETBAL/api.yml
                created apis/ORDSTATUS/api.yaml
                found 2 PCML files, created 1 api.yaml
                """);
        assertThat(Support.read(dir.resolve("gateway.yaml"))).isEqualTo("# mine\nemitters: [descriptor]\n");
        assertThat(Support.read(dir.resolve("apis/GETBAL/api.yml"))).isEqualTo("not even yaml: [");
        assertThat(dir.resolve("apis/GETBAL/api.yaml")).doesNotExist();
    }

    @Test
    void bothOverlayNamesAreKeptAndListed(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("apis/GETBAL/api.yml"), "a");
        Support.write(dir.resolve("apis/GETBAL/api.yaml"), "b");
        assertThat(Support.run(dir, "init").err()).contains("kept apis/GETBAL/api.yaml\n").contains("kept apis/GETBAL/api.yml\n");
    }

    @Test
    void aFolderWithNoPcmlFileOrSeveralIsSkippedWithCli004(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("apis/EMPTY/readme.txt"), "x");
        Support.write(dir.resolve("apis/TWO/A.pcml"), ScaffoldFixtures.ORDSTATUS);
        Support.write(dir.resolve("apis/TWO/b.PCML"), ScaffoldFixtures.GETBAL);
        Result result = Support.run(dir, "init");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo("""
                apis/EMPTY:1:1: warning CLI004: Skipped: this folder has no PCML file
                apis/TWO:1:1: warning CLI004: Skipped: this folder has more than one PCML file: [A.pcml, b.PCML]
                0 errors, 2 warnings, 0 notes
                """);
        assertThat(result.err()).isEqualTo("""
                created gateway.yaml
                created apis/GETBAL/api.yaml
                created apis/ORDSTATUS/api.yaml
                found 4 PCML files, created 2 api.yaml
                """);
        assertThat(dir.resolve("apis/EMPTY/api.yaml")).doesNotExist();
        assertThat(dir.resolve("apis/TWO/api.yaml")).doesNotExist();
        assertThat(Support.run(dir, "init", "--fail-on", "warning").exit()).isEqualTo(1);
    }

    @Test
    void aFileUnderApisAndAFolderWithOnlyAnOverlayAreNotFolders(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("apis/notes.txt"), "x");
        Support.write(dir.resolve("apis/HAND/api.yaml"), "# hand-written\n");
        Result result = Support.run(dir, "init");
        assertThat(result.out()).doesNotContain("CLI004");
        assertThat(result.err()).contains("kept apis/HAND/api.yaml\n").contains("found 2 PCML files, created 2 api.yaml\n");
    }

    @Test
    void aPcmlThatDoesNotParseGivesItsDiagnosticsAndNoOverlay(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("apis/BAD/BAD.pcml"), "<pcml version=\"6.0\"><program name=\"X\"");
        Result result = Support.run(dir, "init");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("apis/BAD/BAD.pcml:").contains("error PCML");
        assertThat(dir.resolve("apis/BAD/api.yaml")).doesNotExist();
        assertThat(dir.resolve("apis/ORDSTATUS/api.yaml")).isRegularFile();
        assertThat(result.err()).endsWith("found 3 PCML files, created 2 api.yaml\n");
    }

    @Test
    void aPcmlThatNoOverlayCanBeWrittenForGivesScf003(@TempDir Path dir) {
        Support.write(dir.resolve("apis/LOOP/LOOP.pcml"), "<pcml version=\"6.0\"><struct name=\"N\"><data name=\"NEXT\" type=\"struct\" struct=\"N\"/></struct>"
                + "<program name=\"P\" path=\"/Q\"><data name=\"X\" type=\"struct\" struct=\"N\" usage=\"input\"/></program></pcml>");
        Result result = Support.run(dir, "init");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error SCF003: Parameter 'X' of program 'P' uses a struct that is not defined or that refers to itself; write the overlay by hand");
        assertThat(dir.resolve("apis/LOOP/api.yaml")).doesNotExist();
        assertThat(dir.resolve("gateway.yaml")).isRegularFile();
    }

    @Test
    void anEmptyRepositoryGetsOnlyGatewayYaml(@TempDir Path dir) {
        Result result = Support.run(dir, "init");
        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEqualTo("created gateway.yaml\nfound 0 PCML files, created 0 api.yaml\n");
    }

    @Test
    void theRepositoryIsTheOneOfRepoAndMessagesAreRelativeToTheWorkingDirectory(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir.resolve("repo"));
        Result result = Support.run(dir, "init", "--repo", "repo");
        assertThat(result.err()).startsWith("created repo/gateway.yaml\ncreated repo/apis/GETBAL/api.yaml\n");
    }

    @Test
    void aRepositoryThatDoesNotExistIsAnErrorAndNothingIsCreated(@TempDir Path dir) {
        Result result = Support.run(dir, "init", "--repo", "nope");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(".:0:0: error OVL013: Repository root not found or not a directory\n1 error, 0 warnings, 0 notes\n");
        assertThat(result.err()).isEmpty();
        assertThat(dir.resolve("nope")).doesNotExist();
        assertThat(Support.names(dir)).containsExactly("");
    }

    @Test
    void aFileAsTheRepositoryIsRefusedToo(@TempDir Path dir) {
        Support.write(dir.resolve("file.txt"), "x");
        assertThat(Support.run(dir, "init", "--repo", "file.txt").exit()).isEqualTo(1);
    }

    @Test
    void standardOutputIsOnlyTheReportInEveryFormat(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("apis/EMPTY/readme.txt"), "x");
        Result json = Support.run(dir, "init", "--format", "json", "--dry-run");
        assertThat(json.out()).startsWith("{").endsWith("}\n").contains("\"CLI004\"").doesNotContain("created").doesNotContain("would create");
        assertThat(json.err()).contains("would create gateway.yaml");
        Result sarif = Support.run(dir, "init", "--format=sarif", "--dry-run");
        assertThat(sarif.out()).startsWith("{").contains("CLI004");
        assertThat(sarif.err()).doesNotContain("{");
    }

    @Test
    void theReportCanGoToAFile(@TempDir Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        Support.write(dir.resolve("apis/EMPTY/readme.txt"), "x");
        Result result = Support.run(dir, "init", "--report", "report.txt");
        assertThat(result.out()).isEmpty();
        assertThat(Support.read(dir.resolve("report.txt"))).contains("CLI004");
        assertThat(result.err()).endsWith("report written to report.txt\n");
    }

    @Test
    void anOverlayIsNotWrittenThroughAFolderThatLeadsOutOfTheRepository(@TempDir Path dir) throws IOException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Support.write(outside.resolve("O.pcml"), ScaffoldFixtures.ORDSTATUS);
        Files.createDirectories(repo.resolve("apis"));
        try {
            Files.createSymbolicLink(repo.resolve("apis/LINK"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        Result result = Support.run(dir, "init", "--repo", "repo");
        assertThat(result.out()).contains("apis/LINK:1:1: warning CLI004: Skipped: this folder leads out of the repository");
        assertThat(outside.resolve("api.yaml")).doesNotExist();
    }

    @Test
    void aFolderThatCannotBeWrittenIsCli003AndTheRunStops(@TempDir Path dir) throws IOException {
        ScaffoldFixtures.pcmlOnly(dir);
        Path folder = dir.resolve("apis/GETBAL");
        try {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("no POSIX permissions here");
        }
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(folder), "the folder is writable anyway (running as root?)");
            Result result = Support.run(dir, "init");
            assertThat(result.exit()).isEqualTo(1);
            assertThat(result.out()).startsWith("apis/GETBAL/api.yaml:1:1: error CLI003: The file apis/GETBAL/api.yaml could not be written: ");
            assertThat(result.err()).isEqualTo("created gateway.yaml\n");
            assertThat(dir.resolve("apis/ORDSTATUS/api.yaml")).doesNotExist();
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void init_help_isPrinted(@TempDir Path dir) {
        assertThat(Support.run(dir, "init", "--help").out()).contains("--dry-run").contains("CLI004");
    }
}

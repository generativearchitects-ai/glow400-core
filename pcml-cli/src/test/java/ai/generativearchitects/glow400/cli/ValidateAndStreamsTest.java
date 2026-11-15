package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.cli.Support.Result;
import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.emit.descriptor.Tool;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code validate}, the exit codes of the report, and the rule that the standard output is the report and nothing else. */
class ValidateAndStreamsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(String text) throws IOException {
        return (Map<String, Object>) Json.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aValidRepositoryWithWarningsOnlyIsExitCode0AndAnInvalidOneIsExitCode1(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("good"));
        Result good = Support.run(dir, "validate", "--repo", "good");
        assertThat(good.exit()).isZero();
        assertThat(good.out()).contains("warning OVL006").endsWith("0 errors, 1 warning, 0 notes\n");
        assertThat(good.err()).isEmpty();
        Support.repo("invalid/ovl012", dir.resolve("bad"));
        Result bad = Support.run(dir, "validate", "--repo", "bad");
        assertThat(bad.exit()).isEqualTo(1);
        assertThat(bad.out()).contains("error OVL012").contains("apis/METHOD/api.yaml:4:9");
        assertThat(bad.err()).isEmpty();
    }

    @Test
    void theRepositoryIsTheWorkingDirectoryByDefault(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Result result = Support.run(repo, "validate");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("OVL006");
        assertThat(Support.run(dir, "validate").out()).contains("0 errors, 0 warnings, 0 notes");
    }

    @Test
    void aRepositoryThatIsNotThereIsExitCode1WithADiagnostic(@TempDir Path dir) {
        Result result = Support.run(dir, "validate", "--repo", "missing");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error OVL013").contains("Repository root not found");
    }

    @Test
    void failOnWarningMakesAWarningFailAndErrorDoesNot(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        assertThat(Support.run(dir, "validate", "--repo", "repo", "--fail-on", "error").exit()).isZero();
        assertThat(Support.run(dir, "validate", "--repo", "repo", "--fail-on", "warning").exit()).isEqualTo(1);
        // a repository with nothing to report passes either way
        Support.write(dir.resolve("empty/placeholder.txt"), "x");
        assertThat(Support.run(dir, "validate", "--repo", "empty", "--fail-on", "warning").exit()).isZero();
    }

    @Test
    void withJsonOrSarifTheStandardOutputIsOneDocumentForSuccessAndFailure(@TempDir Path dir) throws IOException {
        Support.repo("minimal", dir.resolve("good"));
        Support.repo("invalid/ovl012", dir.resolve("bad"));
        for (String format : List.of("json", "sarif")) {
            for (String repo : List.of("good", "bad")) {
                Result result = Support.run(dir, "validate", "--repo", repo, "--format", format);
                Map<String, Object> root = document(result.out());
                assertThat(root).as(format + " " + repo).isNotEmpty();
                assertThat(result.out()).endsWith("}\n");
                assertThat(result.err()).as(format + " " + repo).isEmpty();
                assertThat(result.exit()).isEqualTo(repo.equals("good") ? 0 : 1);
            }
        }
        Map<String, Object> json = document(Support.run(dir, "validate", "--repo", "bad", "--format", "json").out());
        assertThat(json.get("command")).isEqualTo("validate");
        assertThat(((Map<?, ?>) json.get("tool")).get("name")).isEqualTo(Tool.NAME);
        assertThat(((Map<?, ?>) json.get("summary")).get("errors")).isNotEqualTo(0);
        Map<String, Object> sarif = document(Support.run(dir, "validate", "--repo", "bad", "--format", "sarif").out());
        assertThat(sarif.get("version")).isEqualTo("2.1.0");
    }

    @Test
    void theReportCanGoToAFileAndThenTheStandardOutputIsEmpty(@TempDir Path dir) {
        Support.repo("invalid/ovl012", dir.resolve("bad"));
        String onScreen = Support.run(dir, "validate", "--repo", "bad", "--format", "sarif").out();
        Result result = Support.run(dir, "validate", "--repo", "bad", "--format", "sarif", "--report", "out/report.sarif");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("report written to out/report.sarif\n");
        assertThat(Support.read(dir.resolve("out/report.sarif"))).isEqualTo(onScreen);
    }

    @Test
    void aReportFileThatCannotBeWrittenIsCli003OnTheErrorStream(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("blocker"), "a file, not a folder");
        Result result = Support.run(dir, "validate", "--repo", "repo", "--report", "blocker/report.txt");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("error CLI003: The report file blocker/report.txt could not be written: ").endsWith("0 notes\n");
    }

    @Test
    void messagesWithControlCharactersStayOnTheirOwnLineInTheTextReport(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("apis/ORDSTATUS/api.yaml"), Support.read(repo.resolve("apis/ORDSTATUS/api.yaml")).replace("P0STAT: { name: status }", "P0STAT: { name: \"bad\\u001b[2Jname\" }"));
        Result result = Support.run(dir, "validate", "--repo", "repo");
        assertThat(result.out()).doesNotContain("\u001b");
        assertThat(result.out().lines().count()).isEqualTo(result.out().lines().filter(l -> !l.isBlank()).count());
    }
}

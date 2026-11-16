package ai.generativearchitects.slay400.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code diff}: the released bundle against the repository as it is now. */
class DiffTaskTest {

    private static final String API = "apis/ORDSTATUS/api.yaml";

    private static Path released(Path dir, String fixture) {
        Path repo = Support.repo(fixture, dir.resolve("repo"));
        TaskResult result = new Tasks(dir).bundle(repo, new BundleRequest("orders", "r1", dir.resolve("released.rpgapi"), Optional.empty(), Optional.empty()));
        assertThat(result.hasError()).isFalse();
        return repo;
    }

    private static void edit(Path repo, String from, String to) {
        String text = Support.read(repo.resolve(API));
        assertThat(text).contains(from);
        Support.write(repo.resolve(API), text.replace(from, to));
    }

    @Test
    void aBreakingChangeWithoutAMajorIncreaseIsApi002AndWithItIsNot(@TempDir Path dir) {
        Path repo = released(dir, "minimal");
        TaskResult same = new Tasks(dir).diff(repo, dir.resolve("released.rpgapi"));
        assertThat(same.diagnostics()).extracting(Diagnostic::code).noneMatch(c -> c.startsWith("API00"));
        assertThat(same.written()).isEmpty();
        edit(repo, "P0STAT: { name: status }", "P0STAT: { name: state }");
        TaskResult breaking = new Tasks(dir).diff(repo, dir.resolve("released.rpgapi"));
        assertThat(breaking.hasError()).isTrue();
        assertThat(Support.lines(breaking.diagnostics())).anyMatch(l -> l.contains("API002: API ordstatus has breaking changes but its version goes from 1.0.0 to 1.0.0; the major version must increase."));
        edit(repo, "version: 1.0.0", "version: 2.0.0");
        TaskResult raised = new Tasks(dir).diff(repo, dir.resolve("released.rpgapi"));
        assertThat(raised.hasError()).isFalse();
        assertThat(raised.diagnostics()).extracting(Diagnostic::code).doesNotContain("API002").contains("API003");
    }

    @Test
    void aBundleThatCannotBeReadIsReportedWithItsBdlCodeAndStopsTheTask(@TempDir Path dir) throws IOException {
        Path repo = released(dir, "minimal");
        Support.write(dir.resolve("not-a-bundle.rpgapi"), "just text, not a zip file at all, and some more text");
        TaskResult notZip = new Tasks(dir).diff(repo, dir.resolve("not-a-bundle.rpgapi"));
        assertThat(Support.lines(notZip.diagnostics())).anyMatch(l -> l.startsWith("not-a-bundle.rpgapi:1:1: BDL001: Not a readable zip file"));
        assertThat(notZip.diagnostics()).extracting(Diagnostic::code).noneMatch(c -> c.startsWith("API00"));
        TaskResult missing = new Tasks(dir).diff(repo, dir.resolve("nothing.rpgapi"));
        assertThat(Support.lines(missing.diagnostics())).anyMatch(l -> l.contains("BDL001: The file cannot be read"));
        byte[] bytes = Support.bytes(dir.resolve("released.rpgapi"));
        byte[] tampered = Arrays.copyOf(bytes, bytes.length);
        tampered[new String(bytes, StandardCharsets.ISO_8859_1).indexOf("3.1.0")] = 'X';
        Files.write(dir.resolve("tampered.rpgapi"), tampered);
        assertThat(new Tasks(dir).diff(repo, dir.resolve("tampered.rpgapi")).diagnostics()).extracting(Diagnostic::code).anyMatch(c -> c.equals("BDL001") || c.equals("BDL004"));
    }

    @Test
    void anInvalidRepositoryStopsTheTaskBeforeTheBundleIsRead(@TempDir Path dir) {
        released(dir, "minimal");
        Path bad = Support.repo("invalid/ovl012", dir.resolve("bad"));
        TaskResult result = new Tasks(dir).diff(bad, dir.resolve("released.rpgapi"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL012").noneMatch(c -> c.startsWith("API00") || c.startsWith("BDL"));
    }
}

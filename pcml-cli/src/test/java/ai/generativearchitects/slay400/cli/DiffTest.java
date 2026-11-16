package ai.generativearchitects.slay400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.cli.Support.Result;
import ai.generativearchitects.slay400.emit.descriptor.Json;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code diff}: the released bundle against the repository as it is now. */
class DiffTest {

    private static final String API = "apis/ORDSTATUS/api.yaml";

    private static Path released(Path dir, String fixture) {
        Support.repo(fixture, dir.resolve("repo"));
        assertThat(Support.run(dir, "bundle", "--repo", "repo", "--name", "orders", "--version", "r1", "--out", "released.rpgapi").exit()).isZero();
        return dir.resolve("repo");
    }

    private static void edit(Path repo, String from, String to) {
        String text = Support.read(repo.resolve(API));
        assertThat(text).contains(from);
        Support.write(repo.resolve(API), text.replace(from, to));
    }

    // ---- the end-to-end case

    @Test
    void aBreakingChangeWithoutAMajorIncreaseIsApi002AndExitCode1AndWithItExitCode0(@TempDir Path dir) {
        Path repo = released(dir, "minimal");
        Result same = Support.run(dir, "diff", "--repo", "repo", "--against", "released.rpgapi");
        assertThat(same.exit()).isZero();
        assertThat(same.out()).doesNotContain("API00");
        edit(repo, "P0STAT: { name: status }", "P0STAT: { name: state }");
        Result breaking = Support.run(dir, "diff", "--repo", "repo", "--against", "released.rpgapi");
        assertThat(breaking.exit()).isEqualTo(1);
        assertThat(breaking.out()).contains("error API002: API ordstatus has breaking changes but its version goes from 1.0.0 to 1.0.0; the major version must increase.")
                .contains("Response field status was removed").contains("note API003: API ordstatus: breaking");
        assertThat(breaking.err()).isEmpty();
        edit(repo, "version: 1.0.0", "version: 2.0.0");
        Result raised = Support.run(dir, "diff", "--repo", "repo", "--against", "released.rpgapi");
        assertThat(raised.exit()).isZero();
        assertThat(raised.out()).doesNotContain("API002").contains("note API003");
    }

    @Test
    void theReportOfADiffIsOneDocumentInJsonAndSarif(@TempDir Path dir) throws IOException {
        Path repo = released(dir, "minimal");
        edit(repo, "P0STAT: { name: status }", "P0STAT: { name: state }");
        for (String format : new String[] {"json", "sarif"}) {
            Result result = Support.run(dir, "diff", "--repo", "repo", "--against", "released.rpgapi", "--format", format);
            assertThat(result.exit()).isEqualTo(1);
            assertThat(Json.read(new ByteArrayInputStream(result.out().getBytes(StandardCharsets.UTF_8)))).isInstanceOf(Map.class);
            assertThat(result.out()).contains("API002");
            assertThat(result.err()).isEmpty();
        }
    }

    @Test
    void aNoteNeverFailsTheCommandEvenWithFailOnWarning(@TempDir Path dir) throws IOException {
        // the injected fixture has nothing to warn about, so only the summary note is left
        Path repo = released(dir, "injected");
        assertThat(Support.run(dir, "validate", "--repo", "repo", "--fail-on", "warning").exit()).isZero();
        String text = Support.read(repo.resolve(API));
        Support.write(repo.resolve(API), text.replace("version: 1.0.0", "version: 1.0.1") + "\n# documentation only\n");
        Files.writeString(repo.resolve(API), Files.readString(repo.resolve(API)).replace("P0ORDN: { name: orderNumber }", "P0ORDN: { name: orderNumber, description: A changed description }"));
        Result result = Support.run(dir, "diff", "--repo", "repo", "--against", "released.rpgapi", "--fail-on", "warning");
        assertThat(result.out()).contains("note API003").doesNotContain(": warning ").doesNotContain(": error ");
        assertThat(result.exit()).isZero();
    }

    // ---- the released bundle cannot be used

    @Test
    void aBundleThatCannotBeReadIsReportedWithItsBdlCodeAndStopsTheCommand(@TempDir Path dir) throws IOException {
        released(dir, "minimal");
        Support.write(dir.resolve("not-a-bundle.rpgapi"), "just text, not a zip file at all, and some more text");
        Result notZip = Support.run(dir, "diff", "--repo", "repo", "--against", "not-a-bundle.rpgapi");
        assertThat(notZip.exit()).isEqualTo(1);
        assertThat(notZip.out()).contains("not-a-bundle.rpgapi:1:1: error BDL001: Not a readable zip file").doesNotContain("API00");
        Result missing = Support.run(dir, "diff", "--repo", "repo", "--against", "nothing.rpgapi");
        assertThat(missing.exit()).isEqualTo(1);
        assertThat(missing.out()).contains("error BDL001: The file cannot be read");
        byte[] bytes = Support.bytes(dir.resolve("released.rpgapi"));
        byte[] tampered = Arrays.copyOf(bytes, bytes.length);
        int at = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("3.1.0");
        tampered[at] = 'X';
        Files.write(dir.resolve("tampered.rpgapi"), tampered);
        Result bad = Support.run(dir, "diff", "--repo", "repo", "--against", "tampered.rpgapi");
        assertThat(bad.exit()).isEqualTo(1);
        assertThat(bad.out()).containsPattern("error BDL00[14]");
    }

    @Test
    void anInvalidRepositoryStopsTheCommandBeforeTheBundleIsRead(@TempDir Path dir) {
        released(dir, "minimal");
        Support.repo("invalid/ovl012", dir.resolve("bad"));
        Result result = Support.run(dir, "diff", "--repo", "bad", "--against", "released.rpgapi");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error OVL012").doesNotContain("API00").doesNotContain("BDL");
    }
}

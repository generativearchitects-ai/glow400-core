package ai.generativearchitects.slay400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.cli.Support.Result;
import ai.generativearchitects.slay400.pcml.scaffold.OverlayScaffold;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kickoff 14, Part A's main case (review of PR #20, "to change" #1): a parameter with no direction yet, the common
 * case the RPG compiler writes for anything that is not {@code const}, still gets its trim from {@code init}. Once
 * {@code annotate} gives it a direction, {@code validate} has nothing left to say about it.
 */
class LargeResponsesTest {

    // ROWS has no usage attribute (usage "inherit"): the undirected case init previously left without a trim.
    // 200 elements clears the default threshold (100).
    private static final String BIGGRID = """
            <?xml version="1.0"?>
            <pcml version="6.0">
              <program name="BIGGRID" path="/QSYS.LIB/DEMOLIB.LIB/BIGGRID.PGM">
                <data name="ROWS" type="char" length="2" count="200"/>
              </program>
            </pcml>
            """;

    private static void initialise(Path dir) {
        Support.write(dir.resolve("apis/BIGGRID/BIGGRID.pcml"), BIGGRID);
        assertThat(Support.run(dir, "init").exit()).isZero();
        String overlay = Support.read(dir.resolve("apis/BIGGRID/api.yaml"));
        assertThat(overlay).contains("trim:").contains("dropBlank: true").contains(OverlayScaffold.INPUTOUTPUT_COMMENT);
    }

    @Test
    void annotatedToResponseValidatesWithNoOvl005OrOvl022(@TempDir Path dir) {
        initialise(dir);
        Result annotate = Support.runWithInput(dir, "response\nstring\n", "annotate");
        assertThat(annotate.exit()).as(annotate.err()).isZero();
        Result result = Support.run(dir, "validate");
        assertThat(result.out()).doesNotContain("OVL005").doesNotContain("OVL022");
    }

    @Test
    void annotatedToBothValidatesWithNoOvl005OrOvl022(@TempDir Path dir) {
        initialise(dir);
        Result annotate = Support.runWithInput(dir, "both\nstring\n", "annotate");
        assertThat(annotate.exit()).as(annotate.err()).isZero();
        Result result = Support.run(dir, "validate");
        assertThat(result.out()).doesNotContain("OVL005").doesNotContain("OVL022");
    }
}

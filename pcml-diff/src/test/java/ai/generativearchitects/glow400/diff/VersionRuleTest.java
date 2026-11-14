package ai.generativearchitects.glow400.diff;

import static ai.generativearchitects.glow400.diff.Repo.data;
import static ai.generativearchitects.glow400.diff.Repo.overlay;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every cell of the version table of decision 57. */
class VersionRuleTest {

    private static final String ONE = data("A", "type=\"char\" length=\"4\"", "output");
    private static final String TWO = ONE + data("B", "type=\"char\" length=\"4\"", "output");
    private static final String PA = "  A: { name: a }\n";
    private static final String PB = "  B: { name: b }\n";

    /** The released and the current overlay and PCML for an impact. */
    private static String[] edit(Impact impact) {
        String released = overlay("/x", "POST", "", PA + PB);
        return switch (impact) {
            case BREAKING -> new String[] {TWO, released, ONE, overlay("/x", "POST", "", PA)};
            case COMPATIBLE -> new String[] {ONE, overlay("/x", "POST", "", PA), TWO, released};
            case INTERNAL -> new String[] {ONE, overlay("/x", "POST", "", PA), ONE, overlay("/x", "POST", "", "  A: { name: a, pad: left }\n")};
            case DOCUMENTATION -> new String[] {ONE, overlay("/x", "POST", "summary: One\n", PA), ONE, overlay("/x", "POST", "summary: Two\n", PA)};
            case NONE -> new String[] {ONE, overlay("/x", "POST", "", PA), ONE, overlay("/x", "POST", "", PA)};
        };
    }

    private static List<String> codes(DiffResult result) {
        return result.diagnostics().stream().map(d -> d.code()).filter(c -> c.equals("API002") || c.equals("API004")).toList();
    }

    static Stream<Arguments> table() {
        return Stream.of(
                // released 1.0.0 and above
                Arguments.of("1.2.3", "2.0.0", Impact.BREAKING, ""), Arguments.of("1.2.3", "3.1.4", Impact.BREAKING, ""),
                Arguments.of("1.2.3", "1.3.0", Impact.BREAKING, "API002"), Arguments.of("1.2.3", "1.2.4", Impact.BREAKING, "API002"),
                Arguments.of("1.2.3", "1.2.3", Impact.BREAKING, "API002"), Arguments.of("1.2.3", "1.0.0", Impact.BREAKING, "API004"),
                Arguments.of("1.2.3", "1.3.0", Impact.COMPATIBLE, ""), Arguments.of("1.2.3", "2.0.0", Impact.COMPATIBLE, ""),
                Arguments.of("1.2.3", "1.2.4", Impact.COMPATIBLE, "API004"), Arguments.of("1.2.3", "1.2.3", Impact.COMPATIBLE, "API004"),
                Arguments.of("1.2.3", "1.2.2", Impact.COMPATIBLE, "API004"), Arguments.of("1.2.3", "0.9.0", Impact.COMPATIBLE, "API004"),
                Arguments.of("1.2.3", "1.2.3", Impact.INTERNAL, ""), Arguments.of("1.2.3", "1.2.4", Impact.INTERNAL, ""),
                Arguments.of("1.2.3", "1.2.2", Impact.INTERNAL, "API004"), Arguments.of("1.2.3", "9.0.0", Impact.INTERNAL, ""),
                Arguments.of("1.2.3", "1.2.3", Impact.DOCUMENTATION, ""), Arguments.of("1.2.3", "1.3.0", Impact.DOCUMENTATION, ""),
                Arguments.of("1.2.3", "1.2.2", Impact.DOCUMENTATION, "API004"),
                Arguments.of("1.2.3", "1.2.3", Impact.NONE, ""), Arguments.of("1.2.3", "2.0.0", Impact.NONE, ""),
                Arguments.of("1.2.3", "1.2.2", Impact.NONE, "API004"), Arguments.of("1.2.3", "0.0.1", Impact.NONE, "API004"),
                // released below 1.0.0
                Arguments.of("0.4.2", "0.5.0", Impact.BREAKING, ""), Arguments.of("0.4.2", "1.0.0", Impact.BREAKING, ""),
                Arguments.of("0.4.2", "0.4.3", Impact.BREAKING, "API002"), Arguments.of("0.4.2", "0.4.2", Impact.BREAKING, "API002"),
                Arguments.of("0.4.2", "0.3.0", Impact.BREAKING, "API004"),
                Arguments.of("0.4.2", "0.4.3", Impact.COMPATIBLE, ""), Arguments.of("0.4.2", "0.5.0", Impact.COMPATIBLE, ""),
                Arguments.of("0.4.2", "1.0.0", Impact.COMPATIBLE, ""), Arguments.of("0.4.2", "0.4.2", Impact.COMPATIBLE, "API004"),
                Arguments.of("0.4.2", "0.4.1", Impact.COMPATIBLE, "API004"),
                Arguments.of("0.4.2", "0.4.2", Impact.INTERNAL, ""), Arguments.of("0.4.2", "0.4.1", Impact.INTERNAL, "API004"),
                Arguments.of("0.4.2", "0.4.2", Impact.DOCUMENTATION, ""), Arguments.of("0.4.2", "0.4.3", Impact.DOCUMENTATION, ""),
                Arguments.of("0.4.2", "0.4.1", Impact.DOCUMENTATION, "API004"),
                Arguments.of("0.4.2", "0.4.2", Impact.NONE, ""), Arguments.of("0.4.2", "0.4.1", Impact.NONE, "API004"),
                // pre-releases are lower than their release
                Arguments.of("1.0.0", "1.0.0-rc.1", Impact.NONE, "API004"), Arguments.of("1.0.0-rc.1", "1.0.0", Impact.NONE, ""),
                Arguments.of("1.0.0-rc.1", "2.0.0", Impact.BREAKING, ""));
    }

    @ParameterizedTest(name = "{0} to {1}, {2}: {3}")
    @MethodSource("table")
    void theVersionMustMoveEnoughForTheImpact(String released, String current, Impact impact, String expected, @TempDir Path dir) {
        String[] e = edit(impact);
        ApiRepositoryModel before = Repo.load(dir.resolve("released"), "T", e[0], e[1].replace("version: 1.0.0", "version: " + released));
        ApiRepositoryModel after = Repo.load(dir.resolve("current"), "T", e[2], e[3].replace("version: 1.0.0", "version: " + current));
        DiffResult result = ApiDiff.compare(before, after);
        assertThat(result.apis().get(0).impact()).isEqualTo(impact);
        assertThat(codes(result)).isEqualTo(expected.isEmpty() ? List.of() : List.of(expected));
    }

    @Test
    void aBreakingChangeWithAnEnoughIncreaseIsNotReportedAsAnErrorButStillSummarised(@TempDir Path dir) {
        String[] e = edit(Impact.BREAKING);
        DiffResult result = ApiDiff.compare(Repo.load(dir.resolve("a"), "T", e[0], e[1]),
                Repo.load(dir.resolve("b"), "T", e[2], e[3].replace("version: 1.0.0", "version: 2.0.0")));
        assertThat(result.diagnostics()).extracting(d -> d.code()).containsExactly("API003");
    }
}

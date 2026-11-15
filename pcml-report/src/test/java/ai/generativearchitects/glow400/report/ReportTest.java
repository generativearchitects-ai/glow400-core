package ai.generativearchitects.glow400.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.emit.descriptor.Tool;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The three formats: golden files, ordering, paths, hostile messages, and reading the JSON back. */
class ReportTest {

    private static final Path BASE = Path.of("/work/repo");

    private static Diagnostic d(String code, Severity severity, String message, String file, int line, int column) {
        return new Diagnostic(code, severity, message, new SourceLocation(file, line, column));
    }

    /** One fixed list, in no particular order: errors, a warning, a note, an absolute path under and outside the base. */
    private static List<Diagnostic> fixed() {
        return List.of(
                d("OVL012", Severity.ERROR, "'route' must start with /", "apis/ORDERS/api.yaml", 12, 5),
                d("OVL006", Severity.WARNING, "Operation orders has no error mapping", "apis/ORDERS/api.yaml", 1, 1),
                d("OVL004", Severity.ERROR, "A message with \"quotes\" and a tab\there", "apis/ORDERS/api.yaml", 12, 5),
                d("API003", Severity.INFO, "API orders: compatible (1 compatible), version 1.0.0 to 1.1.0", "/work/repo/apis/ORDERS/api.yaml", 1, 1),
                d("BDL003", Severity.ERROR, "The bundle has no manifest.json", "/elsewhere/old.rpgapi", 1, 1),
                d("OVL010", Severity.ERROR, "Cannot read file as UTF-8 text", "apis/A B/api.yaml", 0, 0));
    }

    private static Report report() {
        return new Report("validate", BASE, fixed());
    }

    private static void golden(String name, String actual) throws IOException {
        String text = actual.replace(Tool.version(), "@VERSION@");
        if (Boolean.getBoolean("glow400.updateGolden")) {
            Path file = Path.of("src/test/resources/golden/" + name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return;
        }
        try (InputStream in = ReportTest.class.getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("write the golden file with -Dglow400.updateGolden=true").isNotNull();
            assertThat(text).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ---- golden files

    @Test
    void theTextReportIsTheCommittedOne() throws IOException {
        golden("report.txt", TextReport.render(report()));
    }

    @Test
    void theJsonReportIsTheCommittedOne() throws IOException {
        golden("report.json", JsonReport.render(report()));
    }

    @Test
    void theSarifReportIsTheCommittedOne() throws IOException {
        golden("report.sarif", SarifReport.render(report()));
    }

    // ---- determinism, ordering, paths

    @Test
    void theSameInputGivesTheSameBytesWhateverTheOrder() {
        List<Diagnostic> shuffled = new ArrayList<>(fixed());
        Collections.reverse(shuffled);
        Report other = new Report("validate", BASE, shuffled);
        assertThat(TextReport.render(other)).isEqualTo(TextReport.render(report()));
        assertThat(JsonReport.render(other)).isEqualTo(JsonReport.render(report()));
        assertThat(SarifReport.render(other)).isEqualTo(SarifReport.render(report()));
        assertThat(TextReport.render(report())).isEqualTo(TextReport.render(report()));
    }

    @Test
    void diagnosticsAreSortedByFileLineColumnThenCode() {
        List<Diagnostic> list = List.of(d("B", Severity.ERROR, "m", "b", 1, 1), d("A", Severity.ERROR, "m", "b", 1, 1), d("Z", Severity.ERROR, "m", "a", 9, 9),
                d("Y", Severity.ERROR, "m", "b", 1, 2), d("X", Severity.ERROR, "m", "b", 2, 1), d("W", Severity.ERROR, "m", "b", 10, 1));
        String text = TextReport.render(new Report("c", BASE, list));
        assertThat(text.lines().toList().subList(0, 6)).extracting(l -> l.substring(l.indexOf("error ") + 6, l.indexOf("error ") + 7))
                .containsExactly("Z", "A", "B", "Y", "X", "W");
        assertThat(JsonReport.render(new Report("c", BASE, list)).indexOf("\"Z\"")).isLessThan(JsonReport.render(new Report("c", BASE, list)).indexOf("\"A\""));
    }

    @Test
    void aPathUnderTheBaseIsRelativeWithSlashesAndAnyOtherPathIsAsGiven() {
        Report report = report();
        assertThat(report.display("/work/repo/apis/ORDERS/api.yaml")).isEqualTo("apis/ORDERS/api.yaml");
        assertThat(report.display("apis/ORDERS/api.yaml")).isEqualTo("apis/ORDERS/api.yaml");
        assertThat(report.display("/elsewhere/old.rpgapi")).isEqualTo("/elsewhere/old.rpgapi");
        assertThat(report.display("/work/repo")).isEqualTo("/work/repo");
        assertThat(report.display("/work/repository/x")).isEqualTo("/work/repository/x");
        assertThat(report.display("/work/repo/../repo/a/b")).isEqualTo("a/b");
        assertThat(report.display("gateway.yaml")).isEqualTo("gateway.yaml");
        assertThat(report.display("bad\0name")).isEqualTo("bad\0name");
        assertThat(new Report("c", Path.of("/work/repo/."), List.of()).display("/work/repo/x/y")).isEqualTo("x/y");
    }

    @Test
    void noMachineOrMomentLeaksIntoAReport() {
        for (String text : List.of(TextReport.render(report()), JsonReport.render(report()), SarifReport.render(report()))) {
            assertThat(text).doesNotContain("/work/repo").doesNotContain(System.getProperty("user.home")).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}T");
        }
    }

    // ---- the text report

    @Test
    void theTextReportIsOneLinePerDiagnosticAndASummary() {
        List<String> lines = TextReport.render(report()).lines().toList();
        assertThat(lines).hasSize(7);
        assertThat(lines.get(0)).isEqualTo("/elsewhere/old.rpgapi:1:1: error BDL003: The bundle has no manifest.json");
        assertThat(lines.get(6)).isEqualTo("4 errors, 1 warning, 1 note");
        assertThat(TextReport.render(report())).endsWith("note\n");
    }

    @Test
    void theSummaryUsesTheSingularForOne() {
        assertThat(TextReport.render(new Report("c", BASE, List.of()))).isEqualTo("0 errors, 0 warnings, 0 notes\n");
        assertThat(TextReport.render(new Report("c", BASE, List.of(d("A", Severity.ERROR, "m", "f", 1, 1))))).endsWith("1 error, 0 warnings, 0 notes\n");
        assertThat(TextReport.render(new Report("c", BASE, List.of(d("A", Severity.WARNING, "m", "f", 1, 1))))).endsWith("0 errors, 1 warning, 0 notes\n");
        assertThat(TextReport.render(new Report("c", BASE, List.of(d("A", Severity.INFO, "m", "f", 1, 1))))).endsWith("0 errors, 0 warnings, 1 note\n");
        assertThat(TextReport.render(new Report("c", BASE, List.of(d("A", Severity.INFO, "m", "f", 1, 1), d("B", Severity.INFO, "m", "f", 1, 1)))))
                .endsWith("0 errors, 0 warnings, 2 notes\n");
    }

    private static final String HOSTILE = "a\nforged.yaml:1:1: error X: b\u001b[2J\u0007cé‮d e\ud800f😀";

    @Test
    void aMessageCannotMoveTheCursorOrForgeALineInTheTextReport() {
        String text = TextReport.render(new Report("c", BASE, List.of(d("OVL004", Severity.ERROR, HOSTILE, "apis/x\ny.yaml", 3, 4))));
        assertThat(text.lines().toList()).hasSize(2);
        assertThat(text).contains("apis/x\\u000ay.yaml:3:4: error OVL004: a\\u000aforged.yaml:1:1: error X: b\\u001b[2J\\u0007cé\\u202ed\\u2028e\\ud800f😀\\ue000")
                .doesNotContain("\u001b").doesNotContain("\u0007").doesNotContain("‮").doesNotContain(" ");
        assertThat(text.chars().filter(c -> c == '\n').count()).isEqualTo(2);
        assertThat(TextReport.escape("\u0085\u007f\u0000")).isEqualTo("\\u0085\\u007f\\u0000");
        assertThat(TextReport.escape("󠀁")).isEqualTo("\\u{e0001}");
        assertThat(TextReport.escape("plain ASCII ~ and ü€中")).isEqualTo("plain ASCII ~ and ü€中");
    }

    // ---- the JSON report, read back

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) throws IOException {
        return (Map<String, Object>) Json.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theJsonReportHasTheShapeOfTheSpecification() throws IOException {
        Map<String, Object> root = json(JsonReport.render(report()));
        assertThat(root.keySet()).containsExactly("reportSchemaVersion", "tool", "command", "summary", "diagnostics");
        assertThat(root.get("reportSchemaVersion")).isEqualTo(1);
        assertThat((Map<String, Object>) root.get("tool")).containsExactly(Map.entry("name", Tool.NAME), Map.entry("version", Tool.version()));
        assertThat(root.get("command")).isEqualTo("validate");
        assertThat((Map<String, Object>) root.get("summary")).containsExactly(Map.entry("errors", 4), Map.entry("warnings", 1), Map.entry("notes", 1));
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) root.get("diagnostics");
        assertThat(diagnostics).hasSize(6);
        assertThat(diagnostics.get(0).keySet()).containsExactly("code", "severity", "message", "file", "line", "column");
        assertThat(diagnostics.get(0)).containsEntry("code", "BDL003").containsEntry("severity", "error").containsEntry("file", "/elsewhere/old.rpgapi")
                .containsEntry("line", 1).containsEntry("column", 1);
        assertThat(diagnostics).extracting(m -> m.get("severity")).containsExactly("error", "error", "note", "warning", "error", "error");
        assertThat(diagnostics.get(1)).containsEntry("line", 0).containsEntry("column", 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aHostileMessageSurvivesTheJsonReportExactly() throws IOException {
        Map<String, Object> root = json(JsonReport.render(new Report("c", BASE, List.of(d("OVL004", Severity.ERROR, HOSTILE.replace("\ud800", ""), "a\nb", 1, 1)))));
        Map<String, Object> entry = ((List<Map<String, Object>>) root.get("diagnostics")).get(0);
        assertThat(entry.get("message")).isEqualTo(HOSTILE.replace("\ud800", ""));
        assertThat(entry.get("file")).isEqualTo("a\nb");
        assertThat(JsonReport.render(new Report("c", BASE, List.of()))).contains("\"diagnostics\": []");
    }

    // ---- the SARIF report, read back

    @SuppressWarnings("unchecked")
    @Test
    void theSarifReportHasTheStructureOfSarif210() throws IOException {
        Map<String, Object> root = json(SarifReport.render(report()));
        assertThat(root.keySet()).containsExactly("version", "runs");
        assertThat(root.get("version")).isEqualTo("2.1.0");
        List<Map<String, Object>> runs = (List<Map<String, Object>>) root.get("runs");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).keySet()).containsExactly("tool", "results");
        Map<String, Object> driver = (Map<String, Object>) ((Map<String, Object>) runs.get(0).get("tool")).get("driver");
        assertThat(driver.keySet()).containsExactly("name", "version", "informationUri", "rules");
        assertThat(driver.get("name")).isEqualTo(Tool.NAME);
        assertThat(driver.get("version")).isEqualTo(Tool.version());
        assertThat(driver.get("informationUri")).isEqualTo(Tool.REPOSITORY_URL);
        assertThat((List<Map<String, Object>>) driver.get("rules")).extracting(r -> r.get("id")).containsExactly("API003", "BDL003", "OVL004", "OVL006", "OVL010", "OVL012");
        List<Map<String, Object>> results = (List<Map<String, Object>>) runs.get(0).get("results");
        assertThat(results).hasSize(6);
        Map<String, Object> first = results.get(0);
        assertThat(first.keySet()).containsExactly("ruleId", "level", "message", "locations");
        assertThat(first.get("ruleId")).isEqualTo("BDL003");
        assertThat(first.get("level")).isEqualTo("error");
        assertThat(((Map<String, Object>) first.get("message")).get("text")).isEqualTo("The bundle has no manifest.json");
        assertThat(results).extracting(r -> r.get("level")).containsExactly("error", "error", "note", "warning", "error", "error");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> physical(Map<String, Object> result) {
        List<Map<String, Object>> locations = (List<Map<String, Object>>) result.get("locations");
        assertThat(locations).hasSize(1);
        return (Map<String, Object>) locations.get(0).get("physicalLocation");
    }

    @SuppressWarnings("unchecked")
    @Test
    void theLocationsAreRelativeUnderTheBaseAndPositionsStartAtOne() throws IOException {
        Map<String, Object> run = ((List<Map<String, Object>>) json(SarifReport.render(report())).get("runs")).get(0);
        List<Map<String, Object>> results = (List<Map<String, Object>>) run.get("results");
        Map<String, Object> outside = physical(results.get(0));
        assertThat(((Map<String, Object>) outside.get("artifactLocation"))).containsExactly(Map.entry("uri", "/elsewhere/old.rpgapi"));
        Map<String, Object> zero = physical(results.get(1));
        assertThat(((Map<String, Object>) zero.get("artifactLocation"))).containsExactly(Map.entry("uri", "apis/A%20B/api.yaml"), Map.entry("uriBaseId", "%SRCROOT%"));
        assertThat((Map<String, Object>) zero.get("region")).containsExactly(Map.entry("startLine", 1), Map.entry("startColumn", 1));
        Map<String, Object> absoluteUnder = physical(results.get(2));
        assertThat(((Map<String, Object>) absoluteUnder.get("artifactLocation"))).containsExactly(Map.entry("uri", "apis/ORDERS/api.yaml"), Map.entry("uriBaseId", "%SRCROOT%"));
        Map<String, Object> real = physical(results.get(4));
        assertThat((Map<String, Object>) real.get("region")).containsExactly(Map.entry("startLine", 12), Map.entry("startColumn", 5));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aHostileMessageSurvivesTheSarifReportExactlyAndAPathIsEncoded() throws IOException {
        String message = HOSTILE.replace("\ud800", "");
        Map<String, Object> run = ((List<Map<String, Object>>) json(SarifReport.render(new Report("c", BASE, List.of(d("OVL004", Severity.ERROR, message, "a b\n#c%d.yaml", 1, 1)))))
                .get("runs")).get(0);
        Map<String, Object> result = ((List<Map<String, Object>>) run.get("results")).get(0);
        assertThat(((Map<String, Object>) result.get("message")).get("text")).isEqualTo(message);
        assertThat(((Map<String, Object>) physical(result).get("artifactLocation")).get("uri")).isEqualTo("a%20b%0A%23c%25d.yaml");
        assertThat(SarifReport.uri("x/é.yaml")).isEqualTo("x/%C3%A9.yaml");
        assertThat(SarifReport.uri("C:x")).isNotBlank();
        Map<String, Object> empty = ((List<Map<String, Object>>) json(SarifReport.render(new Report("c", BASE, List.of()))).get("runs")).get(0);
        assertThat((List<Object>) empty.get("results")).isEmpty();
        assertThat((List<Object>) ((Map<String, Object>) ((Map<String, Object>) empty.get("tool")).get("driver")).get("rules")).isEmpty();
    }

    @Test
    void anEmptyListIsAValidReportInEveryFormat() throws IOException {
        Report empty = new Report("diff", BASE, List.of());
        assertThat(TextReport.render(empty)).isEqualTo("0 errors, 0 warnings, 0 notes\n");
        assertThat(json(JsonReport.render(empty)).get("command")).isEqualTo("diff");
        assertThat(json(SarifReport.render(empty)).get("version")).isEqualTo("2.1.0");
    }

    @Test
    void theTypesRejectNull() {
        assertThatThrownBy(() -> new Report(null, BASE, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Report("c", null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Report("c", BASE, null)).isInstanceOf(NullPointerException.class);
    }
}

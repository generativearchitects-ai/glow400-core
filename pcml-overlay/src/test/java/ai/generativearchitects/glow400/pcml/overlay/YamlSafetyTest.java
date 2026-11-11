package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hostile or awkward YAML must be reported as diagnostics, never crash, hang or build objects. */
class YamlSafetyTest {

    private static RepositoryResult load(Path dir, String overlay) {
        return new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML).file("apis/A/api.yaml", overlay).load();
    }

    /** A valid overlay (the path parameter is bound) with extra lines appended. */
    private static String valid(String extra) {
        return Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n" + extra);
    }

    private static String base(String extra) {
        return Repos.Temp.overlay(extra);
    }

    @Test
    void anAliasBombIsRefused(@TempDir Path dir) {
        StringBuilder aliases = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            aliases.append(i == 0 ? "" : ", ").append("*a");
        }
        RepositoryResult result = load(dir, base("anchor: &a [x]\ntags: [" + aliases + "]\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL010");
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @Test
    void aNestedAliasBombIsRefusedQuickly(@TempDir Path dir) {
        StringBuilder yaml = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i <= 9; i++) {
            yaml.append("a").append(i).append(": &a").append(i).append(" [");
            for (int k = 0; k < 9; k++) {
                yaml.append(k == 0 ? "" : ", ").append("*a").append(i - 1);
            }
            yaml.append("]\n");
        }
        assertThatCode(() -> {
            RepositoryResult result = load(dir, base(yaml.toString()));
            assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL010");
        }).doesNotThrowAnyException();
    }

    @Test
    void anAliasThatContainsItselfIsRefused(@TempDir Path dir) {
        RepositoryResult result = load(dir, base("loop: &a [*a]\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL010");
    }

    @Test
    void aliasesOfScalarsAreFine(@TempDir Path dir) {
        RepositoryResult result = load(dir, valid("summary: &s Hello\ndescription: *s\n"));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).description()).contains("Hello");
    }

    @Test
    void veryDeepNestingIsRefused(@TempDir Path dir) {
        String deep = "[".repeat(100) + "]".repeat(100);
        RepositoryResult result = load(dir, base("tags: " + deep + "\n"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL010");
        String abyss = "[".repeat(100_000) + "]".repeat(100_000);
        assertThatCode(() -> load(dir.resolve("abyss"), base("tags: " + abyss + "\n"))).doesNotThrowAnyException();
    }

    @Test
    void customTagsAreRefusedEvenWhenTheyNameAClass(@TempDir Path dir) {
        for (String tag : new String[] {"!!java.lang.Runtime x", "!custom x", "!!python/object:os.system x", "!!set {a}"}) {
            RepositoryResult result = load(dir.resolve(String.valueOf(tag.hashCode())), base("summary: " + tag + "\n"));
            assertThat(result.diagnostics()).as(tag).extracting(Diagnostic::code).contains("OVL010");
            assertThat(result.model().orElseThrow().operations()).as(tag).isEmpty();
        }
    }

    @Test
    void environmentVariableSyntaxIsNeverSubstituted(@TempDir Path dir) {
        // The YAML engine can replace ${NAME} by an environment variable; this tool never does.
        RepositoryResult result = load(dir, valid("summary: ${PATH}\n"));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).summary()).contains("${PATH}");
    }

    @Test
    void windowsLineEndingsAndABomAreAccepted(@TempDir Path dir) throws IOException {
        String yaml = "﻿" + base("parameters:\n  P0ORDN: { name: orderNumber }\n").replace("\n", "\r\n");
        Files.createDirectories(dir.resolve("apis/A"));
        Files.writeString(dir.resolve("apis/A/ORDSTATUS.pcml"), Repos.ORDSTATUS_PCML, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("apis/A/api.yaml"), yaml, StandardCharsets.UTF_8);
        RepositoryResult result = OverlayLoader.loadRepository(dir);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).bindings().get(0).source().line()).isEqualTo(7);
    }

    @Test
    void unicodeValuesAreKept(@TempDir Path dir) {
        RepositoryResult result = load(dir, valid("summary: \"Bestellstatus für Größe – 注文\"\n"));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).summary()).contains("Bestellstatus für Größe – 注文");
    }

    @Test
    void positionsCountLinesAndColumnsFromOne(@TempDir Path dir) {
        RepositoryResult result = load(dir, "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\nversion: 1.0.0\n  bad: indented\n");
        assertThat(result.diagnostics()).extracting(d -> d.location().line()).containsExactly(6);
        assertThat(result.diagnostics().get(0).location().column()).isPositive();
    }
}

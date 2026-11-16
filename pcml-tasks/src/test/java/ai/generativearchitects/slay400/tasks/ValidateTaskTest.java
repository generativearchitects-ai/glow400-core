package ai.generativearchitects.slay400.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code validate}: load and validate, and write nothing. */
class ValidateTaskTest {

    @Test
    void aValidRepositoryGivesNoErrorAndWritesNothing(@TempDir Path dir) {
        Path repo = Support.repo("kitchen-sink", dir.resolve("repo"));
        List<String> before = Support.names(dir);
        TaskResult result = new Tasks(dir).validate(repo);
        assertThat(result.hasError()).isFalse();
        assertThat(result.written()).isEmpty();
        assertThat(Support.names(dir)).isEqualTo(before);
    }

    @Test
    void whatLoadingAndValidationFindAreReturnedInOrderWithTheLoaderFirst(@TempDir Path dir) {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        TaskResult result = new Tasks(dir).validate(repo);
        assertThat(result.hasError()).isTrue();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("OVL012");
        assertThat(result.has(Severity.ERROR)).isTrue();
    }

    @Test
    void aRepositoryThatDoesNotExistIsOvl013(@TempDir Path dir) {
        TaskResult result = new Tasks(dir).validate(dir.resolve("nope"));
        assertThat(Support.lines(result.diagnostics())).containsExactly(".:0:0: OVL013: Repository root not found or not a directory");
    }

    @Test
    void theValidationOfTheCodecIsPartOfIt(@TempDir Path dir) {
        Path repo = Support.repo("invalid/mck004", dir.resolve("repo"));
        assertThat(new Tasks(dir).validate(repo).diagnostics()).extracting(Diagnostic::code).contains("MCK004");
    }

    @Test
    void aRelativeRepositoryIsRelativeToTheCurrentDirectoryOfTheProcessNotToTheBase(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        TaskResult result = new Tasks(dir).validate(Path.of("no-such-folder-here"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL013");
    }

    @Test
    void aTaskRefusesNullsAndShowsPathsRelativeToItsBase(@TempDir Path dir) throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Tasks(null)).isInstanceOf(NullPointerException.class).hasMessage("baseDirectory");
        Tasks tasks = new Tasks(dir);
        assertThat(tasks.shown(dir.resolve("a/b.txt"))).isEqualTo("a/b.txt");
        assertThat(tasks.shown(dir.resolve("a/../a/./b.txt"))).isEqualTo("a/b.txt");
        assertThat(tasks.shown(dir)).isEqualTo(dir.toAbsolutePath().normalize().toString().replace('\\', '/'));
        assertThat(tasks.shown(dir.getParent().resolve("elsewhere/c.txt"))).isEqualTo(dir.getParent().resolve("elsewhere/c.txt").toString());
        assertThat(tasks.shown(dir.resolve("a\u001bb"))).isEqualTo("a\\u001bb");
        assertThat(new Tasks(dir.resolve("x/..")).shown(dir.resolve("y"))).isEqualTo("y");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tasks.bundle(dir, null)).isInstanceOf(NullPointerException.class).hasMessage("request");
    }

    @Test
    void theResultKnowsItsSeverities() {
        Diagnostic note = new Diagnostic("X001", Severity.INFO, "n", new ai.generativearchitects.slay400.pcml.model.SourceLocation("a", 1, 1));
        Diagnostic warning = new Diagnostic("X002", Severity.WARNING, "w", note.location());
        Diagnostic error = new Diagnostic("X003", Severity.ERROR, "e", note.location());
        assertThat(new TaskResult(List.of(), List.of()).has(Severity.WARNING)).isFalse();
        assertThat(new TaskResult(List.of(note), List.of()).has(Severity.WARNING)).isFalse();
        assertThat(new TaskResult(List.of(note), List.of()).has(Severity.INFO)).isTrue();
        assertThat(new TaskResult(List.of(warning), List.of()).has(Severity.WARNING)).isTrue();
        assertThat(new TaskResult(List.of(warning), List.of()).has(Severity.ERROR)).isFalse();
        assertThat(new TaskResult(List.of(warning), List.of()).hasError()).isFalse();
        assertThat(new TaskResult(List.of(note, warning, error), List.of()).hasError()).isTrue();
        assertThat(new TaskResult(List.of(error), List.of()).has(Severity.ERROR)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TaskResult(List.of(), List.of()).has(null)).isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TaskResult(null, List.of())).isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TaskResult(List.of(), null)).isInstanceOf(NullPointerException.class);
    }
}

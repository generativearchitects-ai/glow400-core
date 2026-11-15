package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.cli.Support.Result;
import ai.generativearchitects.glow400.emit.descriptor.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The command line itself: usage errors, help, the version, and the exit code of an unexpected failure. */
class UsageTest {

    static Stream<Arguments> usageErrors() {
        return Stream.of(
                Arguments.of(new String[] {}, "No command given"),
                Arguments.of(new String[] {"frobnicate"}, "Unknown command 'frobnicate'"),
                Arguments.of(new String[] {"--frobnicate"}, "Unknown option '--frobnicate'"),
                Arguments.of(new String[] {"validate", "--frobnicate"}, "Unknown option '--frobnicate' for validate"),
                Arguments.of(new String[] {"validate", "--out", "x"}, "Unknown option '--out' for validate"),
                Arguments.of(new String[] {"generate", "--name", "x", "--out", "o"}, "Unknown option '--name' for generate"),
                Arguments.of(new String[] {"validate", "extra"}, "Unexpected argument 'extra'"),
                Arguments.of(new String[] {"validate", "--repo"}, "The option --repo needs a value"),
                Arguments.of(new String[] {"validate", "--repo", "--format", "json"}, "The option --repo needs a value"),
                Arguments.of(new String[] {"validate", "--format", "xml"}, "The value of --format must be text, json or sarif"),
                Arguments.of(new String[] {"validate", "--format=xml"}, "The value of --format must be text, json or sarif"),
                Arguments.of(new String[] {"validate", "--fail-on", "note"}, "The value of --fail-on must be error or warning"),
                Arguments.of(new String[] {"validate", "--fail-on=info"}, "The value of --fail-on must be error or warning"),
                Arguments.of(new String[] {"validate", "--repo", "a", "--repo", "b"}, "The option --repo is given more than once"),
                Arguments.of(new String[] {"validate", "--stacktrace=yes"}, "The option --stacktrace takes no value"),
                Arguments.of(new String[] {"validate", "--repo="}, "The value of --repo must not be empty"),
                Arguments.of(new String[] {"generate"}, "The command generate needs the option --out"),
                Arguments.of(new String[] {"bundle", "--name", "n", "--version", "v"}, "The command bundle needs the option --out"),
                Arguments.of(new String[] {"bundle", "--version", "v", "--out", "f"}, "The command bundle needs the option --name"),
                Arguments.of(new String[] {"bundle", "--name", "n", "--out", "f"}, "The command bundle needs the option --version"),
                Arguments.of(new String[] {"diff"}, "The command diff needs the option --against"),
                Arguments.of(new String[] {"validate", "-x"}, "Unexpected argument '-x'"));
    }

    @ParameterizedTest
    @MethodSource("usageErrors")
    void aCommandLineThatCannotBeRunIsExitCode2WithOneLineAndNothingElse(String[] args, String message, @TempDir Path dir) {
        Result result = Support.run(dir, args);
        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo(Tool.NAME + ": " + message + ". Try '" + Tool.NAME + " --help'.\n");
    }

    @Test
    void anOptionMayGiveItsValueWithAnEqualsSign(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        Result result = Support.run(dir, "validate", "--repo=repo", "--format=json", "--fail-on=warning");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("\"command\": \"validate\"");
    }

    @Test
    void aValueThatBeginsWithOneDashIsAValueAndAnUnknownCommandIsEscaped(@TempDir Path dir) {
        assertThat(Support.run(dir, "validate", "--repo", "-x-").exit()).isEqualTo(1);
        Result hostile = Support.run(dir, "bad\u001b[2J\ncommand");
        assertThat(hostile.exit()).isEqualTo(2);
        assertThat(hostile.err()).isEqualTo(Tool.NAME + ": Unknown command 'bad\\u001b[2J\\u000acommand'. Try '" + Tool.NAME + " --help'.\n");
        Result longName = Support.run(dir, "x".repeat(100));
        assertThat(longName.err()).contains("Unknown command '" + "x".repeat(60) + "...'");
        assertThat(Support.run(dir, "x".repeat(60)).err()).contains("Unknown command '" + "x".repeat(60) + "'");
        assertThat(Support.run(dir, "x".repeat(61)).err()).contains("Unknown command '" + "x".repeat(60) + "...'");
        assertThat(Support.run(dir, "validate", "--" + "y".repeat(61)).err()).contains("Unknown option '--" + "y".repeat(58) + "...' for validate");
        assertThat(Support.run(dir, "--" + "z".repeat(70)).err()).contains("Unknown option '--" + "z".repeat(58) + "...'");
        assertThat(Support.run(dir, "validate", "--bad\u001b").err()).contains("Unknown option '--bad\\u001b' for validate");
    }

    @Test
    void theVersionIsTheNameAndTheVersionOfTheTool(@TempDir Path dir) {
        Result result = Support.run(dir, "--version");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo(Tool.NAME + " " + Tool.version() + "\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void helpIsPrintedForTheToolAndForEachCommandAndNeedsNoRepository(@TempDir Path dir) {
        Result general = Support.run(dir, "--help");
        assertThat(general.exit()).isZero();
        assertThat(general.out()).isEqualTo(Help.general()).startsWith("Usage: " + Tool.NAME + " <command> [options]");
        assertThat(general.err()).isEmpty();
        for (String command : List.of("validate", "generate", "bundle", "diff")) {
            Result help = Support.run(dir, command, "--help");
            assertThat(help.exit()).as(command).isZero();
            assertThat(help.out()).isEqualTo(Help.of(command)).startsWith("Usage: " + Tool.NAME + " " + command);
            assertThat(help.err()).isEmpty();
            assertThat(Support.run(dir, command, "--help", "--format", "json").out()).isEqualTo(Help.of(command));
        }
        assertThat(Support.run(dir, "generate", "--help").out()).contains("--out <dir>");
        assertThat(Support.run(dir, "bundle", "--help").out()).contains("--name <name>").contains("--source-revision");
        assertThat(Support.run(dir, "diff", "--help").out()).contains("--against <bundle file>");
        assertThat(Help.general()).contains("validate").contains("generate").contains("bundle").contains("diff").contains("Exit codes:");
    }

    @Test
    void anUnexpectedFailureIsExitCode3WithOneLineAndAStackTraceOnlyOnRequest(@TempDir Path dir) {
        Result quiet = Support.run(dir, "validate", "--repo", "bad\0path");
        assertThat(quiet.exit()).isEqualTo(3);
        assertThat(quiet.out()).isEmpty();
        assertThat(quiet.err().lines().toList()).hasSize(1);
        assertThat(quiet.err()).startsWith(Tool.NAME + ": unexpected failure: java.nio.file.InvalidPathException: ").doesNotContain("\tat ");
        Result trace = Support.run(dir, "validate", "--repo", "bad\0path", "--stacktrace");
        assertThat(trace.exit()).isEqualTo(3);
        assertThat(trace.err()).contains("\tat ").startsWith(Tool.NAME + ": unexpected failure: ");
    }

    @Test
    void whatIsWrittenIsFlushed(@TempDir Path dir) {
        Support.repo("minimal", dir.resolve("repo"));
        for (String[] args : List.of(new String[] {"--help"}, new String[] {"--version"}, new String[] {"validate", "--repo", "repo"})) {
            int[] flushes = {0};
            java.io.OutputStream counting = new java.io.OutputStream() {
                @Override
                public void write(int b) { }

                @Override
                public void flush() {
                    flushes[0]++;
                }
            };
            int exit = Cli.run(args, dir, new java.io.PrintStream(counting, false), new java.io.PrintStream(counting, false));
            assertThat(exit).isZero();
            assertThat(flushes[0]).as(String.join(" ", args)).isGreaterThan(0);
        }
    }

    @Test
    void theArgumentsAreNotOptional() {
        assertThatThrownBy(() -> Cli.run(null, Path.of("."), System.out, System.err)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cli.run(new String[0], null, System.out, System.err)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cli.run(new String[0], Path.of("."), null, System.err)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cli.run(new String[0], Path.of("."), System.out, null)).isInstanceOf(NullPointerException.class);
        assertThat(List.of(Cli.OK, Cli.FAILED, Cli.USAGE, Cli.UNEXPECTED)).containsExactly(0, 1, 2, 3);
    }
}

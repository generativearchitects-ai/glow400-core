package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.emit.descriptor.Tool;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.report.JsonReport;
import ai.generativearchitects.glow400.report.Report;
import ai.generativearchitects.glow400.report.SarifReport;
import ai.generativearchitects.glow400.report.TextReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The command line, as a function: arguments in, exit code out. It never exits the process and reads no environment
 * variable. Only {@code annotate} reads the input stream. The report goes to the output stream or to the report file; everything else, usage, progress and
 * the files written, goes to the error stream, so that the output of a JSON or SARIF report is one document and nothing else.
 */
public final class Cli {

    /** Completed, and nothing at or above {@code --fail-on}. */
    public static final int OK = 0;
    /** Diagnostics at or above {@code --fail-on}. */
    public static final int FAILED = 1;
    /** A command line that cannot be run. */
    public static final int USAGE = 2;
    /** An unexpected failure. */
    public static final int UNEXPECTED = 3;

    private static final Map<String, Command> COMMANDS = Map.of("init", new InitCommand(), "annotate", new AnnotateCommand(),
            "validate", new ValidateCommand(), "generate", new GenerateCommand(), "bundle", new BundleCommand(),
            "diff", new DiffCommand());

    private Cli() {
    }

    /**
     * Runs a command line.
     *
     * @param args             the arguments
     * @param workingDirectory what relative paths are relative to
     * @param in               what {@code annotate} reads the answers from, one per line
     * @param out              where the report, or the help and the version, are written
     * @param err              where everything else is written
     * @return the exit code
     */
    public static int run(String[] args, Path workingDirectory, InputStream in, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        boolean stacktrace = List.of(args).contains("--stacktrace");
        try {
            return dispatch(List.of(args), workingDirectory, in, out, err);
        } catch (UsageException e) {
            err.println(Tool.NAME + ": " + e.getMessage() + ". Try '" + Tool.NAME + " --help'.");
            return USAGE;
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            err.println(Tool.NAME + ": unexpected failure: " + e.getClass().getName() + ": " + TextReport.escape(String.valueOf(e.getMessage())));
            if (stacktrace) {
                e.printStackTrace(err);
            }
            return UNEXPECTED;
        }
    }

    private static int dispatch(List<String> args, Path workingDirectory, InputStream in, PrintStream out, PrintStream err) throws Exception {
        if (args.isEmpty()) {
            throw new UsageException("No command given");
        }
        String first = args.get(0);
        if (first.equals("--help")) {
            print(out, Help.general());
            return OK;
        }
        if (first.equals("--version")) {
            print(out, Tool.NAME + " " + Tool.version() + "\n");
            return OK;
        }
        if (!Options.COMMANDS.contains(first)) {
            throw new UsageException(first.startsWith("-") ? "Unknown option '" + Options.shown(first) + "'"
                    : "Unknown command '" + Options.shown(first) + "'");
        }
        Options options = Options.parse(first, args.subList(1, args.size()));
        if (options.help()) {
            print(out, Help.of(first));
            return OK;
        }
        Context context = new Context(options, workingDirectory, in, out, err);
        COMMANDS.get(first).run(context);
        return finish(context, first);
    }

    /** Writes the report and decides the exit code. */
    private static int finish(Context context, String command) {
        Report report = new Report(command, context.repo, context.diagnostics);
        String text = switch (context.options.format()) {
            case "json" -> JsonReport.render(report);
            case "sarif" -> SarifReport.render(report);
            default -> TextReport.render(report);
        };
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (context.options.value("--report").isPresent()) {
            Path file = context.workingDirectory.resolve(context.options.value("--report").get()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            } catch (IOException e) {
                Diagnostic d = new Diagnostic("CLI003", ai.generativearchitects.glow400.pcml.diagnostics.Severity.ERROR,
                        "The report file " + context.shown(file) + " could not be written: " + Writing.reason(e), new SourceLocation(context.shown(file), 1, 1));
                context.err.print(TextReport.render(new Report(command, context.repo, List.of(d))));
                return FAILED;
            }
            context.err.println("report written to " + context.shown(file));
        } else {
            context.out.write(bytes, 0, bytes.length);
            context.out.flush();
        }
        boolean fails = context.diagnostics.stream().anyMatch(d -> d.severity().ordinal() <= context.options.failOn().ordinal());
        return fails || context.hasError() ? FAILED : OK;
    }

    private static void print(PrintStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.flush();
    }
}

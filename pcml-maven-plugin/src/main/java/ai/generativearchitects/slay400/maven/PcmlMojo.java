package ai.generativearchitects.slay400.maven;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import ai.generativearchitects.slay400.report.JsonReport;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.SarifReport;
import ai.generativearchitects.slay400.report.TextReport;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import ai.generativearchitects.slay400.tasks.Writing;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * What the four goals have in common: the parameters every goal takes, the way a task result becomes log lines, the report
 * file and the failure of the build. A goal says only which task it runs. No state is kept between executions, so the goals
 * are thread-safe.
 */
abstract class PcmlMojo extends AbstractMojo {

    /** The folder holding {@code gateway.yaml} and {@code apis/}. */
    @Parameter(property = "pcml.repository", defaultValue = "${project.basedir}")
    File repository;

    /** The lowest severity that fails the build: {@code error} or {@code warning}. */
    @Parameter(property = "pcml.failOn", defaultValue = "error")
    String failOn = "error";

    /** Do nothing, and say so. */
    @Parameter(property = "pcml.skip", defaultValue = "false")
    boolean skip;

    /** A file to write the report to as well, in {@link #reportFormat}. */
    @Parameter(property = "pcml.reportFile")
    File reportFile;

    /** The format of the report file: {@code json} or {@code sarif}. */
    @Parameter(property = "pcml.reportFormat", defaultValue = "json")
    String reportFormat = "json";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** The name of the goal, as in {@code pcml:validate}, for messages. */
    abstract String goal();

    /** Runs the task. */
    abstract TaskResult run(Tasks tasks) throws IOException, MojoFailureException;

    /** Called after a task that did not fail the build; {@code result} is what the task returned. */
    void succeeded(TaskResult result) throws MojoExecutionException {
        // most goals have nothing more to do
    }

    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        Log log = getLog();
        if (skip) {
            log.info("pcml:" + goal() + " skipped: pcml.skip is set");
            return;
        }
        Severity lowest = failOn();
        String format = reportFormat();
        Path base = project.getBasedir().toPath();
        Tasks tasks = new Tasks(base);
        Path repo = repository.toPath();
        TaskResult result;
        try {
            result = run(tasks);
        } catch (IOException e) {
            throw new MojoExecutionException("pcml:" + goal() + " failed: " + Writing.reason(e), e);
        }
        List<Diagnostic> diagnostics = new ArrayList<>(result.diagnostics());
        if (reportFile != null) {
            writeReport(tasks, repo, format, diagnostics);
        }
        Report report = new Report(goal(), repo, diagnostics);
        for (Diagnostic diagnostic : report.sorted()) {
            String line = TextReport.line(report, diagnostic);
            switch (diagnostic.severity()) {
                case ERROR -> log.error(line);
                case WARNING -> log.warn(line);
                default -> log.info(line);
            }
        }
        boolean fails = diagnostics.stream().anyMatch(d -> d.severity().ordinal() <= lowest.ordinal());
        if (fails) {
            throw new MojoFailureException("pcml:" + goal() + " failed: " + TextReport.summary(report));
        }
        log.info("pcml:" + goal() + ": " + TextReport.summary(report));
        succeeded(result);
    }

    private Severity failOn() throws MojoFailureException {
        return switch (failOn) {
            case "error" -> Severity.ERROR;
            case "warning" -> Severity.WARNING;
            default -> throw new MojoFailureException("The value of failOn must be error or warning");
        };
    }

    private String reportFormat() throws MojoFailureException {
        if (!reportFormat.equals("json") && !reportFormat.equals("sarif")) {
            throw new MojoFailureException("The value of reportFormat must be json or sarif");
        }
        return reportFormat;
    }

    /** Writes the report; a file that cannot be written is one more error in the report, as for the command line. */
    private void writeReport(Tasks tasks, Path repo, String format, List<Diagnostic> diagnostics) {
        Path file = reportFile.toPath().toAbsolutePath().normalize();
        Report report = new Report(goal(), repo, diagnostics);
        String text = format.equals("sarif") ? SarifReport.render(report) : JsonReport.render(report);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            diagnostics.add(new Diagnostic("CLI003", Severity.ERROR,
                    "The report file " + tasks.shown(file) + " could not be written: " + Writing.reason(e), new SourceLocation(tasks.shown(file), 1, 1)));
        }
    }
}

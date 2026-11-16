package ai.generativearchitects.slay400.cli;

import java.nio.file.Path;

/** Starts the tool. The only place that exits the process, and the only place that names standard input. */
public final class Main {

    private Main() {
    }

    /** Runs the command line in the working directory and exits with its exit code. */
    public static void main(String[] args) {
        int exit = Cli.run(args, Path.of("").toAbsolutePath(), System.in, System.out, System.err);
        System.exit(exit);
    }
}

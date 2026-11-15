package ai.generativearchitects.glow400.cli;

import java.nio.file.Path;

/** Starts the tool. The only place that exits the process. */
public final class Main {

    private Main() {
    }

    /** Runs the command line in the working directory and exits with its exit code. */
    public static void main(String[] args) {
        int exit = Cli.run(args, Path.of("").toAbsolutePath(), System.out, System.err);
        System.exit(exit);
    }
}

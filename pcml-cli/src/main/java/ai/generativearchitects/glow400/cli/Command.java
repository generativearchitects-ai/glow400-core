package ai.generativearchitects.glow400.cli;

/** One of the commands: it works on the context and adds to its diagnostics. */
interface Command {

    /** Runs the command; the diagnostics are in the context, and so are any files written. */
    void run(Context context) throws Exception;
}

package ai.generativearchitects.glow400.cli;

/** A command line that cannot be run: an unknown command or option, or a missing or invalid value. Exit code 2. */
final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message, null, false, false);
    }
}

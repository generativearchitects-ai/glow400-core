package ai.generativearchitects.glow400.cli;

/** {@code validate}: load, validate, report. */
final class ValidateCommand implements Command {

    @Override
    public void run(Context context) {
        context.diagnostics.addAll(context.tasks.validate(context.repo).diagnostics());
    }
}

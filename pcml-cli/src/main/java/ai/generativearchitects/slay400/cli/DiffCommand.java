package ai.generativearchitects.slay400.cli;

/** {@code diff}: the released bundle against the repository as it is now. */
final class DiffCommand implements Command {

    @Override
    public void run(Context context) {
        context.diagnostics.addAll(context.tasks.diff(context.repo, context.workingDirectory.resolve(context.options.required("--against"))).diagnostics());
    }
}

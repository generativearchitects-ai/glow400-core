package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.tasks.BundleRequest;
import ai.generativearchitects.glow400.tasks.TaskResult;
import java.nio.file.Path;

/** {@code bundle}: the bundle of the repository, written to a temporary file and moved into place. */
final class BundleCommand implements Command {

    @Override
    public void run(Context context) {
        Path target = context.workingDirectory.resolve(context.options.required("--out")).toAbsolutePath().normalize();
        BundleRequest request = new BundleRequest(context.options.required("--name"), context.options.required("--version"), target,
                context.options.value("--source-repository"), context.options.value("--source-revision"));
        TaskResult result = context.tasks.bundle(context.repo, request);
        context.diagnostics.addAll(result.diagnostics());
        if (!result.hasError()) {
            result.written().forEach(path -> context.err.println("wrote " + context.shown(path)));
        }
    }
}

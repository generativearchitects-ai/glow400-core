package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.bundle.BundleReadResult;
import ai.generativearchitects.glow400.bundle.BundleReader;
import ai.generativearchitects.glow400.diff.ApiDiff;
import ai.generativearchitects.glow400.diff.DiffResult;
import java.nio.file.Path;

/** {@code diff}: the released bundle against the repository as it is now. */
final class DiffCommand implements Command {

    @Override
    public void run(Context context) {
        if (!context.loadAndValidate()) {
            return;
        }
        Path file = context.workingDirectory.resolve(context.options.required("--against")).toAbsolutePath().normalize();
        BundleReadResult released = BundleReader.read(file);
        context.diagnostics.addAll(released.diagnostics());
        if (released.bundle().isEmpty()) {
            return;
        }
        DiffResult result = ApiDiff.compare(released.bundle().get().model(), context.model);
        context.diagnostics.addAll(result.diagnostics());
    }
}

package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.tasks.TaskResult;
import java.io.IOException;
import java.nio.file.Path;

/** {@code generate}: run the emitters named in {@code gateway.yaml} and write their files under one folder. */
final class GenerateCommand implements Command {

    @Override
    public void run(Context context) throws IOException {
        Path out = context.workingDirectory.resolve(context.options.required("--out")).toAbsolutePath().normalize();
        TaskResult result = context.tasks.generate(context.repo, out);
        context.diagnostics.addAll(result.diagnostics());
        if (!result.hasError()) {
            result.written().forEach(path -> context.err.println("wrote " + context.shown(path)));
        }
    }
}

package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.bundle.BundleInfo;
import ai.generativearchitects.glow400.bundle.BundleResult;
import ai.generativearchitects.glow400.bundle.BundleWriter;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** {@code bundle}: the descriptor and OpenAPI emitters, then the bundle, written to a temporary file and moved into place. */
final class BundleCommand implements Command {

    private static final List<String> EMITTERS = List.of("descriptor", "openapi");

    @Override
    public void run(Context context) {
        if (!context.loadAndValidate()) {
            return;
        }
        // the two emitters are in this jar; if they are not, the packaging is broken and the command fails as an unexpected failure
        Map<String, Emitter> available = Context.emitters();
        var emitted = context.emit(EMITTERS, available);
        if (emitted.isEmpty()) {
            return;
        }
        BundleInfo info = new BundleInfo(context.options.required("--name"), context.options.required("--version"),
                context.options.value("--source-repository"), context.options.value("--source-revision"));
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        BundleResult result = BundleWriter.write(context.repo, context.model, emitted.get(), info, bundle);
        context.diagnostics.addAll(result.diagnostics());
        if (result.manifest().isEmpty()) {
            return;
        }
        Path target = context.workingDirectory.resolve(context.options.required("--out")).toAbsolutePath().normalize();
        write(context, target, bundle.toByteArray());
    }

    private static void write(Context context, Path target, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".bundle-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            context.err.println("wrote " + context.shown(target));
        } catch (IOException e) {
            context.error("CLI003", "The file " + context.shown(target) + " could not be written: " + Writing.reason(e), Context.GATEWAY);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // the bundle is not there either way; nothing more can be done about a temporary file
                }
            }
        }
    }
}

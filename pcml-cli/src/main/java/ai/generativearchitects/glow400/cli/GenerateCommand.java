package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code generate}: run the emitters named in {@code gateway.yaml} and write their files under one folder. */
final class GenerateCommand implements Command {

    @Override
    public void run(Context context) throws IOException {
        if (!context.loadAndValidate()) {
            return;
        }
        List<String> names = new ArrayList<>(new LinkedHashSet<>(context.model.defaults().emitters()));
        Map<String, Emitter> available = Context.emitters();
        for (String name : names) {
            if (!available.containsKey(name)) {
                List<String> all = context.model.defaults().emitters();
                List<SourceLocation> at = context.model.defaults().emitterSources();
                SourceLocation where = at.isEmpty() ? Context.GATEWAY : at.get(all.indexOf(name));
                context.error("CLI001", "The emitter '" + name + "' named in gateway.yaml is not available", where);
            }
        }
        if (context.hasError()) {
            return;
        }
        var emitted = context.emit(names, available);
        if (emitted.isEmpty()) {
            return;
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (EmittedFile file : emitted.get()) {
            if (files.putIfAbsent(file.path(), file.content()) != null) {
                context.error("CLI002", "Two emitted files have the path " + file.path(), Context.GATEWAY);
            }
        }
        Path out = context.workingDirectory.resolve(context.options.required("--out")).toAbsolutePath().normalize();
        checkOutputFolder(context, out);
        if (context.hasError()) {
            return;
        }
        Path physicalOut = Writing.physical(out);
        for (String path : files.keySet()) {
            if (!inside(out.resolve(path), physicalOut)) {
                context.error("CLI002", "The path " + path + " leads out of the output folder", Context.GATEWAY);
            }
        }
        if (context.hasError()) {
            return;
        }
        write(context, out, files);
    }

    /** Whether a file would be written inside the output folder, through any symbolic links on the way. */
    private static boolean inside(Path file, Path physicalOut) {
        try {
            return Writing.physical(file).startsWith(physicalOut);
        } catch (IOException e) {
            return false; // a link that leads nowhere
        }
    }

    /** The output folder cannot be the repository root, nor inside {@code apis/}, nor a file. */
    private static void checkOutputFolder(Context context, Path out) throws IOException {
        Path root = Writing.physical(context.repo);
        String shown = context.shown(out);
        Path target;
        try {
            target = Writing.physical(out);
        } catch (IOException e) {
            context.error("CLI003", "The output folder " + shown + " cannot be written: " + Writing.reason(e), Context.GATEWAY);
            return;
        }
        if (target.equals(root)) {
            context.error("CLI002", "The output folder " + shown + " is the repository root", Context.GATEWAY);
            return;
        }
        if (target.startsWith(root.resolve("apis"))) {
            context.error("CLI002", "The output folder " + shown + " is inside apis/", Context.GATEWAY);
            return;
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            context.error("CLI003", "The output folder " + shown + " cannot be written: not a folder", Context.GATEWAY);
            return;
        }
    }

    private static void write(Context context, Path out, Map<String, byte[]> files) {
        Set<Path> written = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = out.resolve(file.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
                written.add(target);
            } catch (IOException e) {
                context.error("CLI003", "The file " + context.shown(target) + " could not be written: " + Writing.reason(e), Context.GATEWAY);
                return;
            }
        }
        written.forEach(path -> context.err.println("wrote " + context.shown(path)));
    }
}

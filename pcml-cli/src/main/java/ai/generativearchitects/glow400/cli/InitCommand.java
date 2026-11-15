package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.parser.ParseResult;
import ai.generativearchitects.glow400.pcml.parser.PcmlParser;
import ai.generativearchitects.glow400.pcml.scaffold.GatewayScaffold;
import ai.generativearchitects.glow400.pcml.scaffold.OverlayScaffold;
import ai.generativearchitects.glow400.pcml.scaffold.ScaffoldResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code init}: writes {@code gateway.yaml}, and an {@code api.yaml} for every folder under {@code apis/} that holds one PCML
 * file and no overlay. Nothing that exists is overwritten or deleted, and the repository does not have to be valid.
 */
final class InitCommand implements Command {

    private static final List<String> OVERLAY_NAMES = List.of("api.yaml", "api.yml");

    @Override
    public void run(Context context) throws IOException {
        boolean dryRun = context.options.flag("--dry-run");
        if (!Files.isDirectory(context.repo)) {
            context.diagnostics.add(new Diagnostic("OVL013", Severity.ERROR, "Repository root not found or not a directory",
                    new SourceLocation(".", 0, 0)));
            return;
        }
        Path root = Writing.physical(context.repo);
        if (!put(context, context.repo.resolve("gateway.yaml"), GatewayScaffold.create(), dryRun)) {
            return;
        }
        int found = 0;
        int created = 0;
        Path apis = context.repo.resolve("apis");
        if (Files.isDirectory(apis)) {
            for (Path folder : children(context, apis, Files::isDirectory)) {
                String name = folder.getFileName().toString();
                String at = "apis/" + name;
                List<Path> pcmls = children(context, folder, p -> Files.isRegularFile(p)
                        && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pcml"));
                found += pcmls.size();
                boolean overlayExists = false;
                for (String overlay : OVERLAY_NAMES) {
                    if (Files.exists(folder.resolve(overlay), LinkOption.NOFOLLOW_LINKS)) {
                        context.err.println("kept " + context.shown(folder.resolve(overlay)));
                        overlayExists = true;
                    }
                }
                if (overlayExists) {
                    continue;
                }
                if (pcmls.size() != 1) {
                    skipped(context, at, pcmls.isEmpty() ? "this folder has no PCML file"
                            : "this folder has more than one PCML file: " + pcmls.stream().map(p -> p.getFileName().toString()).toList());
                    continue;
                }
                Path target = folder.resolve("api.yaml");
                if (!Writing.physical(target).startsWith(root)) {
                    skipped(context, at, "this folder leads out of the repository");
                    continue;
                }
                Optional<String> text = scaffold(context, name, at, pcmls.get(0));
                if (text.isEmpty()) {
                    continue;
                }
                if (!put(context, target, text.get(), dryRun)) {
                    return;
                }
                created++;
            }
        }
        context.err.println("found " + found + " PCML files, " + (dryRun ? "would create " : "created ") + created + " api.yaml");
    }

    /** The overlay for the PCML in a folder; empty, with the diagnostics, if there cannot be one. */
    private static Optional<String> scaffold(Context context, String folder, String at, Path pcml) throws IOException {
        String file = at + "/" + pcml.getFileName();
        ParseResult parsed;
        try (InputStream in = Files.newInputStream(pcml)) {
            parsed = PcmlParser.parse(file, in);
        } catch (IOException e) {
            context.diagnostics.add(new Diagnostic("PCML001", Severity.ERROR, "Cannot read source: " + Writing.reason(e),
                    new SourceLocation(file, 0, 0)));
            return Optional.empty();
        }
        context.diagnostics.addAll(parsed.diagnostics());
        if (parsed.hasErrors() || parsed.document().isEmpty()) {
            return Optional.empty();
        }
        ScaffoldResult result = OverlayScaffold.create(folder, pcml.getFileName().toString(), parsed.document().get());
        context.diagnostics.addAll(result.diagnostics());
        return result.text();
    }

    /**
     * Creates a file, or only says it would. Whatever is already at the path stays, and is listed as kept.
     *
     * @return false if the file could not be written
     */
    private static boolean put(Context context, Path target, String text, boolean dryRun) {
        String shown = context.shown(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            context.err.println("kept " + shown);
            return true;
        }
        if (dryRun) {
            context.err.println("would create " + shown);
            return true;
        }
        try {
            if (Writing.createNew(target, text.getBytes(StandardCharsets.UTF_8))) {
                context.err.println("created " + shown);
            } else {
                context.err.println("kept " + shown);
            }
            return true;
        } catch (IOException e) {
            context.error("CLI003", "The file " + shown + " could not be written: " + Writing.reason(e), target);
            return false;
        }
    }

    private static void skipped(Context context, String folder, String reason) {
        context.diagnostics.add(new Diagnostic("CLI004", Severity.WARNING, "Skipped: " + reason, new SourceLocation(folder, 1, 1)));
    }

    /** The children of a folder that pass the filter, in the order the loader reads them. */
    private static List<Path> children(Context context, Path folder, java.util.function.Predicate<Path> filter) {
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(filter).sorted(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .thenComparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) {
            context.error("CLI003", "The folder " + context.shown(folder) + " could not be read: " + Writing.reason(e), folder);
            return List.of();
        }
    }
}

package ai.generativearchitects.glow400.maven;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Copies of the fixture repositories on the real file system, and small file helpers. */
final class Support {

    private Support() {
    }

    static Path repo(String fixture, Path target) {
        Path source = Fixtures.path(fixture);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(file, to);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> names(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.map(p -> dir.relativize(p).toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The diagnostics as {@code file:line:column: code: message}, for assertions. */
    static List<String> lines(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.location().file() + ":" + d.location().line() + ":" + d.location().column() + ": " + d.code() + ": " + d.message()).toList();
    }
}

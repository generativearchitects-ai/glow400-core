package ai.generativearchitects.slay400.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Runs the tool in process on copies of the fixture repositories. */
final class Support {

    private Support() {
    }

    /** What a run printed and returned. */
    record Result(int exit, String out, String err) {
    }

    static Result run(Path workingDirectory, String... args) {
        return runWithInput(workingDirectory, "", args);
    }

    /** Runs with the given text as standard input. */
    static Result runWithInput(Path workingDirectory, String input, String... args) {
        return runWithStream(workingDirectory, new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), args);
    }

    /**
     * Standard input that hands out one line at a time and runs an action just before the line with the given index (from 0)
     * is read: what happens while a person is thinking about an answer.
     */
    static java.io.InputStream linesWithHook(String input, int beforeLine, Runnable action) {
        String[] lines = input.split("(?<=\n)");
        return new java.io.InputStream() {
            private int line;
            private byte[] current = new byte[0];
            private int at;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                if (at >= current.length) {
                    if (line >= lines.length) {
                        return -1;
                    }
                    if (line == beforeLine) {
                        action.run();
                    }
                    current = lines[line++].getBytes(StandardCharsets.UTF_8);
                    at = 0;
                }
                int n = Math.min(len, current.length - at);
                System.arraycopy(current, at, buffer, off, n);
                at += n;
                return n;
            }
        };
    }

    static Result runWithStream(Path workingDirectory, java.io.InputStream in, String... args) {
        return runWith(workingDirectory, in, args);
    }

    private static Result runWith(Path workingDirectory, java.io.InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Cli.run(args, workingDirectory, in,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** A copy of a fixture repository on the real file system. */
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

    static java.util.List<String> names(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.map(p -> dir.relativize(p).toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

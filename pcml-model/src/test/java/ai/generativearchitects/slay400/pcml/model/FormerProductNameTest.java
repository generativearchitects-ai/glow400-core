package ai.generativearchitects.slay400.pcml.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision 84 retired the former product name, so it must not survive anywhere in this repository: not in a file, a file
 * name, a folder name or the content of a bundle, in any letter case. Two lines are allowed to say what the project was
 * called, and only at the place where each is meant to be: one line of {@code README.md}, and the line of the
 * {@code CLAUDE.md} template that says the repository was renamed.
 *
 * <p>The name is put together here from two parts, so that this file does not contain it.
 */
class FormerProductNameTest {

    private static final String FORMER = "glow" + "400";

    private static final String README_LINE = "The project was called " + FORMER + " until 2026-11-16.";

    private static final String CLAUDE_LINE_START = "Formerly `" + FORMER + "-core`.";

    @Test
    void theRepositoryDoesNotContainTheFormerName() {
        assertThat(scan(repositoryRoot())).as("places that still carry the former product name").isEmpty();
    }

    @Test
    void theRepositoryStillSaysInOnePlaceWhatTheProjectWasCalled() throws IOException {
        assertThat(Files.readAllLines(repositoryRoot().resolve("README.md"))).contains(README_LINE);
    }

    @Test
    void theNameIsFoundInTextInAnyLetterCaseInFileNamesAndInsideBundles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "x " + FORMER.toUpperCase(Locale.ROOT) + " y\n");
        Files.writeString(dir.resolve("b.txt"), "mixed " + FORMER.substring(0, 1).toUpperCase(Locale.ROOT) + FORMER.substring(1) + "\n");
        Files.createDirectories(dir.resolve("pkg/" + FORMER));
        Files.writeString(dir.resolve("pkg/" + FORMER + "/c.txt"), "clean\n");
        Files.writeString(dir.resolve("clean.txt"), "slay400\n");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dir.resolve("d.rpgapi")))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(("{\"name\": \"" + FORMER + "\"}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dir.resolve("e.rpgapi")))) {
            out.putNextEntry(new ZipEntry(FORMER + ".txt"));
            out.closeEntry();
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dir.resolve("f.rpgapi")))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write("{\"name\": \"slay400\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertThat(scan(dir)).containsExactlyInAnyOrder(
                "a.txt", "b.txt", "pkg/" + FORMER, "pkg/" + FORMER + "/c.txt", "d.rpgapi!manifest.json", "e.rpgapi!" + FORMER + ".txt");
    }

    @Test
    void theTwoAllowedLinesAreAllowedOnlyWhereTheyAreMeantToBe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("README.md"), "# slay400-core\n\n" + README_LINE + "\n");
        Files.writeString(dir.resolve("CLAUDE.md"), "# slay400-core\n\n" + CLAUDE_LINE_START + " The product name is slay400.\n");
        assertThat(scan(dir)).isEmpty();

        Files.writeString(dir.resolve("README.md"), "# slay400-core\n\n" + README_LINE + "\nand again " + FORMER + "\n");
        assertThat(scan(dir)).containsExactly("README.md");

        Files.writeString(dir.resolve("README.md"), "# slay400-core\n");
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/NOTES.md"), README_LINE + "\n");
        Files.writeString(dir.resolve("docs/CLAUDE.md"), CLAUDE_LINE_START + "\n");
        assertThat(scan(dir)).containsExactlyInAnyOrder("docs/NOTES.md", "docs/CLAUDE.md");
    }

    @Test
    void buildOutputAndTheVersionControlFolderAreNotLookedAt(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("m/target"));
        Files.writeString(dir.resolve("m/target/out.txt"), FORMER + "\n");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), FORMER + "\n");
        assertThat(scan(dir)).isEmpty();
    }

    // ------------------------------------------------------------------ the scan

    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("mvnw")) && Files.exists(p.resolve("README.md"))) {
                return p;
            }
        }
        throw new IllegalStateException("The repository root was not found above " + here);
    }

    /** The places under the root that carry the name: paths relative to it, with {@code !entry} for the inside of a bundle. */
    private static List<String> scan(Path root) {
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root)).filter(p -> !skipped(root, p)).sorted().forEach(p -> {
                String where = root.relativize(p).toString().replace('\\', '/');
                if (where.toLowerCase(Locale.ROOT).contains(FORMER)) {
                    found.add(where);
                } else if (Files.isRegularFile(p)) {
                    scanFile(p, where, found);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /** The version control folder and every {@code target} folder, which hold copies and build output, not the repository. */
    private static boolean skipped(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("target")) {
                return true;
            }
        }
        return false;
    }

    private static void scanFile(Path file, String where, List<String> found) {
        try {
            if (where.endsWith(".rpgapi")) {
                try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
                    for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        if (entry.getName().toLowerCase(Locale.ROOT).contains(FORMER)
                                || hasName(new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1), where)) {
                            found.add(where + "!" + entry.getName());
                        }
                    }
                }
            } else if (hasName(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1), where)) {
                found.add(where);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the text has the name on a line that is not one of the two allowed lines at their places. */
    private static boolean hasName(String text, String where) {
        for (String line : text.split("\n", -1)) {
            if (line.toLowerCase(Locale.ROOT).contains(FORMER)) {
                boolean allowed = where.equals("README.md") && line.equals(README_LINE)
                        || where.equals("CLAUDE.md") && line.startsWith(CLAUDE_LINE_START);
                if (!allowed) {
                    return true;
                }
            }
        }
        return false;
    }
}

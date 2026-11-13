package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed expected output, compared byte for byte. To write the files again on purpose, run the tests with
 * {@code -Dglow400.updateGolden=true} and review the diff.
 */
final class GoldenFiles {

    private GoldenFiles() {
    }

    static void check(String name, byte[] actual) {
        String text = new String(actual, StandardCharsets.UTF_8);
        if (Boolean.getBoolean("glow400.updateGolden")) {
            try {
                Path file = Path.of("src/test/resources/golden/" + name);
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        try (InputStream in = GoldenFiles.class.getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("golden file " + name + " (write it with -Dglow400.updateGolden=true)").isNotNull();
            assertThat(text).as(name).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

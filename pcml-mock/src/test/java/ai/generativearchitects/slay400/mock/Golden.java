package ai.generativearchitects.slay400.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Golden files under {@code src/test/resources/golden}; {@code -Dslay400.updateGolden=true} rewrites them. */
final class Golden {

    private Golden() {
    }

    static Path path(String name) {
        return Path.of("src/test/resources/golden").resolve(name);
    }

    static void check(String name, String actual) {
        Path file = path(name);
        try {
            if (Boolean.getBoolean("slay400.updateGolden")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
            }
            assertThat(actual).as(name).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

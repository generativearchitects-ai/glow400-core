package ai.generativearchitects.slay400.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

/**
 * Fixture repositories on the class path: the ones of this module, and those of kickoff 02, which the overlay module
 * publishes in its test jar. A repository inside a jar is read through the jar's file system.
 */
final class Fixtures {

    private Fixtures() {
    }

    static Path path(String name) {
        URL url = Fixtures.class.getResource("/repos/" + name);
        if (url == null) {
            throw new IllegalArgumentException("No fixture repository " + name);
        }
        try {
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try {
                    FileSystem ignored = FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException e) {
                    // already open
                }
            }
            return Path.of(uri);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

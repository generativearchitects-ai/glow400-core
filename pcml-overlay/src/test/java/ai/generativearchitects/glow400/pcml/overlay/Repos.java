package ai.generativearchitects.glow400.pcml.overlay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/** Access to the synthetic repositories under {@code src/test/resources/repos}, and a builder for throw-away ones. */
final class Repos {

    static final String ORDSTATUS_PCML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pcml version="6.0">
              <program name="ORDSTATUS" path="/QSYS.LIB/DEMOLIB.LIB/ORDSTATUS.PGM">
                <data name="P0ORDN" type="char" length="10" usage="input"/>
                <data name="P0STAT" type="char" length="1" usage="output"/>
                <data name="P0DATE" type="char" length="8" usage="output"/>
              </program>
            </pcml>
            """;

    private Repos() {
    }

    /** @param name path below {@code repos/}, for example {@code minimal} or {@code invalid/ovl002} */
    static Path fixture(String name) {
        URL url = Repos.class.getResource("/repos/" + name);
        if (url == null) {
            throw new IllegalArgumentException("No fixture repository " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static RepositoryResult load(String fixture) {
        return OverlayLoader.loadRepository(fixture(fixture));
    }

    /** A repository written to a temporary directory from strings. */
    static final class Temp {
        private final Path root;

        Temp(Path root) {
            this.root = root;
        }

        Temp file(String relativePath, String content) {
            try {
                Path file = root.resolve(relativePath);
                Files.createDirectories(file.getParent());
                Files.writeString(file, content);
                return this;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** An {@code apis/<folder>} with the standard ORDSTATUS PCML and the given overlay. */
        Temp api(String folder, String overlay) {
            return file("apis/" + folder + "/ORDSTATUS.pcml", ORDSTATUS_PCML).file("apis/" + folder + "/api.yaml", overlay);
        }

        /** A complete single-operation overlay for ORDSTATUS, with the given lines appended. */
        static String overlay(String extra) {
            return "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /orders/{orderNumber}/status\nmethod: GET\nversion: 1.0.0\n"
                    + extra;
        }

        Path root() {
            return root;
        }

        RepositoryResult load() {
            return OverlayLoader.loadRepository(root);
        }
    }
}

package ai.generativearchitects.glow400.contract;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds small APIs from a few lines of PCML and overlay, for tests of one rule at a time. */
final class Apis {

    private Apis() {
    }

    static String data(String name, String attributes, String usage) {
        return "    <data name=\"" + name + "\" " + attributes + " usage=\"" + usage + "\"/>\n";
    }

    /** The overlay header for the program {@code T}, ending before the parameters. */
    static String header(String route, String method) {
        return "program: T\npcml: T.pcml\nroute: " + route + "\nmethod: " + method + "\nversion: 1.0.0\n";
    }

    /** The only API of a one-program repository in {@code dir}. */
    static ApiDefinition api(Path dir, String pcmlData, String overlay) {
        try {
            Path apiDir = dir.resolve("apis").resolve("T");
            Files.createDirectories(apiDir);
            Files.writeString(apiDir.resolve("T.pcml"), "<?xml version=\"1.0\"?>\n<pcml version=\"6.0\">\n"
                    + "  <struct name=\"ROW\">\n    <data name=\"X\" type=\"char\" length=\"2\"/>\n    <data name=\"Y\" type=\"int\" length=\"4\"/>\n"
                    + "    <data name=\"H\" type=\"char\" length=\"1\"/>\n  </struct>\n"
                    + "  <program name=\"T\" path=\"/QSYS.LIB/DEMOLIB.LIB/T.PGM\">\n" + pcmlData + "  </program>\n</pcml>\n");
            Files.writeString(apiDir.resolve("api.yaml"), overlay);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RepositoryResult result = OverlayLoader.loadRepository(dir);
        assertThat(result.diagnostics()).isEmpty();
        ApiRepositoryModel model = result.model().orElseThrow();
        return model.apis().get(0);
    }

    static OperationSurface operation(Path dir, String pcmlData, String overlay) {
        return ApiSurface.of(api(dir, pcmlData, overlay)).operations().get(0);
    }
}

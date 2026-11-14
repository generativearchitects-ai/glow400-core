package ai.generativearchitects.glow400.diff;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds small repositories from a few lines of PCML and overlay, and compares two of them. */
final class Repo {

    private Repo() {
    }

    static String data(String name, String attributes, String usage) {
        return "    <data name=\"" + name + "\" " + attributes + " usage=\"" + usage + "\"/>\n";
    }

    /** The overlay header of a single operation of program {@code T}. */
    static String header(String route, String method) {
        return "program: T\npcml: T.pcml\nroute: " + route + "\nmethod: " + method + "\nversion: 1.0.0\n";
    }

    /** The repository with folder {@code apis/<folder>} holding program {@code T} with the data lines, and the overlay. */
    static ApiRepositoryModel load(Path dir, String folder, String pcmlData, String overlay) {
        try {
            Path apiDir = dir.resolve("apis").resolve(folder);
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
        return result.model().orElseThrow();
    }

    static DiffResult diff(Path dir, String pcmlBefore, String overlayBefore, String pcmlAfter, String overlayAfter) {
        ApiRepositoryModel released = load(dir.resolve("released"), "T", pcmlBefore, overlayBefore);
        ApiRepositoryModel current = load(dir.resolve("current"), "T", pcmlAfter, overlayAfter);
        return ApiDiff.compare(released, current);
    }

    /** The overlay of one operation, with the given parameter lines (each indented by two spaces). */
    static String overlay(String route, String method, String extra, String parameters) {
        return header(route, method) + extra + "parameters:\n" + parameters;
    }

    /** {@code impact path} of every change of the only API, in order. */
    static List<String> changes(DiffResult result) {
        List<String> out = new ArrayList<>();
        result.apis().get(0).changes().forEach(c -> out.add(c.impact() + " " + c.path()));
        return out;
    }
}

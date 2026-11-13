package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import ai.generativearchitects.glow400.pcml.validation.Validator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds small APIs from a few lines of PCML and overlay, for tests of one rule at a time. */
final class Apis {

    private Apis() {
    }

    /** A repository with one folder {@code apis/<folder>}, one program {@code T} with the given data lines, and the overlay. */
    static ApiRepositoryModel model(Path dir, String folder, String pcmlData, String overlay) {
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
        List<Diagnostic> all = new ArrayList<>(result.diagnostics());
        result.model().ifPresent(model -> all.addAll(Validator.validate(model)));
        assertThat(all).filteredOn(d -> d.severity() == Severity.ERROR).isEmpty();
        return result.model().orElseThrow();
    }

    /** The overlay header for the program {@code T}, ending before the parameters. */
    static String header(String route, String method, String extra) {
        return "program: T\npcml: T.pcml\nroute: " + route + "\nmethod: " + method + "\nversion: 1.0.0\n" + extra;
    }

    /** The OpenAPI document of the only API of a one-program repository. */
    static JsonNode document(Path dir, String pcmlData, String overlay) {
        ApiRepositoryModel model = model(dir, "T", pcmlData, overlay);
        List<EmittedFile> files = new OpenApiEmitter().emit(model).files();
        assertThat(files).hasSize(1);
        JsonNode document = OpenApiSchema.yaml(files.get(0).content());
        assertThat(OpenApiSchema.validate(document)).isEmpty();
        return document;
    }

    static String data(String name, String attributes, String usage) {
        return "    <data name=\"" + name + "\" " + attributes + " usage=\"" + usage + "\"/>\n";
    }
}

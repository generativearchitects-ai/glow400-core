package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.GatewayDefaults;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import ai.generativearchitects.slay400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.slay400.pcml.overlay.RepositoryResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixture repositories of kickoff 02 (in the test jar of the overlay module), and small repositories written for one test. */
final class Repos {

    /** The valid fixture repositories. */
    static final List<String> VALID = List.of("action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink",
            "inputoutput", "minimal", "mocked", "operations-list", "structs");

    private Repos() {
    }

    static Path path(String name) {
        URL url = Repos.class.getResource("/repos/" + name);
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
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiRepositoryModel model(String name) {
        RepositoryResult result = OverlayLoader.loadRepository(path(name));
        assertThat(result.diagnostics()).as(name).noneMatch(d -> d.severity() == ai.generativearchitects.slay400.pcml.diagnostics.Severity.ERROR);
        return result.model().orElseThrow();
    }

    // ---- one small API

    static String data(String name, String attributes, String usage) {
        return "    <data name=\"" + name + "\" " + attributes + " usage=\"" + usage + "\"/>\n";
    }

    static String header(String route, String method) {
        return "program: T\npcml: T.pcml\nroute: " + route + "\nmethod: " + method + "\nversion: 1.0.0\n";
    }

    /** The only operation of a repository of one program {@code T}, built from a few lines of PCML and overlay. */
    static ApiOperation operation(Path dir, String pcmlData, String overlay) {
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
        return result.model().orElseThrow().operations().get(0);
    }

    // ---- routes without an overlay

    /** The operation with another id, route and method: a route table needs nothing else of it. */
    static ApiOperation copy(ApiOperation base, String id, String route, HttpMethod method) {
        return new ApiOperation(id, base.version(), base.summary(), base.description(), base.tags(), base.program(), base.pcmlPath(),
                base.overlayPath(), route, method, base.callStrategy(), base.bindings(), base.errorMapping(), base.cache(), base.source());
    }

    /** A model with one operation for each {@code "METHOD /route"}, with the ids {@code op0}, {@code op1} and so on, in one API. */
    static ApiRepositoryModel routes(String... methodAndRoute) {
        ApiOperation base = model("minimal").operations().get(0);
        List<ApiOperation> operations = new ArrayList<>();
        for (int i = 0; i < methodAndRoute.length; i++) {
            String[] parts = methodAndRoute[i].split(" ", 2);
            operations.add(copy(base, "op" + i, parts[1], HttpMethod.valueOf(parts[0])));
        }
        return new ApiRepositoryModel(GatewayDefaults.standard(), operations);
    }

    static ApiDefinition onlyApi(ApiRepositoryModel model) {
        return model.apis().get(0);
    }
}

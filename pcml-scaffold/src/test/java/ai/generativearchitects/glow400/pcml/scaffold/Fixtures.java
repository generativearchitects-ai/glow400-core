package ai.generativearchitects.glow400.pcml.scaffold;

import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.parser.ParseResult;
import ai.generativearchitects.glow400.pcml.parser.PcmlParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** The fixture repositories of kickoff 02, which the overlay module publishes in its test jar, and small helpers. */
final class Fixtures {

    /** One folder under {@code apis/} of a fixture repository that holds exactly one PCML file. */
    record Api(String repository, String folder, Path pcml, Path overlay) {
        String label() {
            return repository + "/" + folder;
        }
    }

    private Fixtures() {
    }

    static Path root() {
        URL url = Fixtures.class.getResource("/repos");
        if (url == null) {
            throw new IllegalStateException("The fixtures of pcml-overlay are not on the class path");
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

    /** Every folder of every fixture repository that has one PCML file, in a fixed order. */
    static List<Api> apis() {
        Path root = root();
        List<Api> apis = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path apisDir : walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("apis")
                    && Files.isDirectory(p)).sorted().toList()) {
                String repository = root.relativize(apisDir.getParent()).toString().replace('\\', '/');
                try (Stream<Path> folders = Files.list(apisDir)) {
                    for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                        List<Path> pcmls;
                        try (Stream<Path> files = Files.list(folder)) {
                            pcmls = files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pcml")).sorted().toList();
                        }
                        Path overlay = folder.resolve("api.yaml");
                        if (pcmls.size() == 1) {
                            apis.add(new Api(repository, folder.getFileName().toString(), pcmls.get(0), overlay));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return apis;
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ParseResult parse(String name, String pcml) {
        return PcmlParser.parse(name, new ByteArrayInputStream(pcml.getBytes(StandardCharsets.UTF_8)));
    }

    static PcmlDocument document(String pcml) {
        return parse("A.pcml", pcml).document().orElseThrow();
    }
}

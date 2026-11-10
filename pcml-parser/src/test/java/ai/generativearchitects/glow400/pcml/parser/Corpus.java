package ai.generativearchitects.glow400.pcml.parser;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/** Locates the synthetic PCML files under {@code src/test/resources/pcml}. */
final class Corpus {

    static final java.util.List<String> VALID = java.util.List.of(
            "scalars", "inputoutput", "structs", "arrays", "refs", "multi-program",
            "service-program", "placeholders", "unknown-attributes", "doctype");

    private Corpus() {
    }

    /** @param name path below {@code pcml/}, for example {@code scalars.pcml} or {@code invalid/pcml002-undefined-struct.pcml} */
    static Path path(String name) {
        URL url = Corpus.class.getResource("/pcml/" + name);
        if (url == null) {
            throw new IllegalArgumentException("No corpus file " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static ParseResult parse(String name) {
        return PcmlParser.parse(path(name));
    }
}

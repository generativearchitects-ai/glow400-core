package ai.generativearchitects.glow400.pcml.parser;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses PCML (Program Call Markup Language) XML into a {@link PcmlDocument}.
 *
 * <p>The parser never throws for problems in its input: it reports them as {@link Diagnostic}s.
 * Diagnostic codes:
 *
 * <table>
 *   <caption>Diagnostic codes</caption>
 *   <tr><td>PCML001</td><td>error</td><td>not well-formed XML, unreadable source, or root element is not {@code pcml}</td></tr>
 *   <tr><td>PCML002</td><td>error</td><td>{@code type="struct"} references a struct that is not defined</td></tr>
 *   <tr><td>PCML003</td><td>error</td><td>{@code count} or {@code length} references a field that does not exist in the same scope</td></tr>
 *   <tr><td>PCML004</td><td>error</td><td>missing required attribute, or unknown or invalid attribute value</td></tr>
 *   <tr><td>PCML005</td><td>error</td><td>duplicate program, struct or field name in the same scope</td></tr>
 *   <tr><td>PCML006</td><td>warning</td><td>circular struct reference</td></tr>
 *   <tr><td>PCML007</td><td>info</td><td>unknown element or attribute</td></tr>
 * </table>
 */
public final class PcmlParser {

    private PcmlParser() {
    }

    /**
     * Parses a PCML file. A file that cannot be read is reported as {@code PCML001}.
     *
     * @param file the file to parse
     * @return the document, if any, and the diagnostics
     */
    public static ParseResult parse(Path file) {
        Objects.requireNonNull(file, "file");
        String name = file.toString();
        try (InputStream in = Files.newInputStream(file)) {
            return parse(name, in);
        } catch (IOException e) {
            return failure(name, "Cannot read source: " + e.getMessage(), 0, 0);
        }
    }

    /**
     * Parses PCML from a stream. The stream is read but not closed.
     *
     * @param sourceName name used in diagnostics and source locations (typically a file name)
     * @param in         the PCML XML
     * @return the document, if any, and the diagnostics
     */
    public static ParseResult parse(String sourceName, InputStream in) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(in, "in");
        Element root;
        try {
            root = XmlReader.read(sourceName, in);
        } catch (XmlReader.MalformedXmlException e) {
            return failure(sourceName, "Not well-formed XML: " + e.getMessage(), e.line(), e.column());
        } catch (IOException e) {
            return failure(sourceName, "Cannot read source: " + e.getMessage(), 0, 0);
        }
        if (!root.name().equals("pcml")) {
            return failure(sourceName,
                    "Root element is <" + root.name() + ">, expected <pcml>",
                    root.location().line(), root.location().column());
        }
        ModelBuilder builder = new ModelBuilder(sourceName);
        PcmlDocument document = builder.build(root);
        return new ParseResult(Optional.of(document), builder.diagnostics());
    }

    private static ParseResult failure(String sourceName, String message, int line, int column) {
        return new ParseResult(Optional.empty(), List.of(
                new Diagnostic("PCML001", Severity.ERROR, message, new SourceLocation(sourceName, line, column))));
    }
}

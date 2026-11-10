package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityAndRobustnessTest {

    private static ParseResult parse(String xml) {
        return PcmlParser.parse("inline", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void externalEntitiesAreNotResolved(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE pcml [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<pcml version=\"6.0\"><program name=\"P\" path=\"&xxe;\"/></pcml>";
        ParseResult result = parse(xml);
        assertThat(result.toString()).doesNotContain("TOP-SECRET-CONTENT");
        result.document().ifPresent(doc ->
                assertThat(doc.programs().get(0).path().orElse("")).doesNotContain("TOP-SECRET-CONTENT"));
    }

    @Test
    void externalDtdIsNotLoaded(@TempDir Path dir) throws Exception {
        Path dtd = dir.resolve("evil.dtd");
        Files.writeString(dtd, "<!ENTITY loaded \"LOADED\">");
        String xml = "<!DOCTYPE pcml SYSTEM \"" + dtd.toUri() + "\">\n"
                + "<pcml version=\"6.0\"><program name=\"P\" path=\"x\"/></pcml>";
        ParseResult result = parse(xml);
        assertThat(result.document()).isPresent();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void entityExpansionBombIsContained() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<!DOCTYPE pcml [\n<!ENTITY a0 \"lol\">\n");
        for (int i = 1; i <= 10; i++) {
            String prev = "&a" + (i - 1) + ";";
            xml.append("<!ENTITY a").append(i).append(" \"").append(prev.repeat(10)).append("\">\n");
        }
        xml.append("]>\n<pcml version=\"6.0\"><program name=\"P\" path=\"&a10;\"/></pcml>");
        ParseResult[] result = new ParseResult[1];
        assertThatCode(() -> result[0] = parse(xml.toString())).doesNotThrowAnyException();
        assertThat(result[0].diagnostics()).extracting(Diagnostic::code).contains("PCML001");
    }

    @Test
    void emptyInputIsReportedNotThrown() {
        ParseResult result = parse("");
        assertThat(result.document()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("PCML001");
    }

    @Test
    void invalidBytesAreReportedNotThrown() {
        ParseResult result = PcmlParser.parse("bytes", new ByteArrayInputStream(new byte[] {'<', 'p', (byte) 0xC3, 0x28, '>'}));
        assertThat(result.document()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("PCML001");
    }

    @Test
    void missingFileIsReportedNotThrown(@TempDir Path dir) {
        ParseResult result = PcmlParser.parse(dir.resolve("nope.pcml"));
        assertThat(result.document()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("PCML001");
    }

    @Test
    void failingStreamIsReportedNotThrown() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };
        ParseResult result = PcmlParser.parse("broken", broken);
        assertThat(result.document()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("PCML001");
    }

    @Test
    void documentIsReturnedWhenXmlIsWellFormedDespiteErrors() {
        ParseResult result = parse("<pcml version=\"6.0\"><program name=\"P\"><data name=\"A\" type=\"nonsense\"/></program></pcml>");
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.document()).isPresent();
    }
}

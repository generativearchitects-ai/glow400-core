package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Programs, structs and fields report the position of the '&lt;' that starts their element. */
class SourcePositionTest {

    private static PcmlDocument parse(String xml) {
        return PcmlParser.parse("pos.pcml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .document().orElseThrow();
    }

    @Test
    void tagsOnSeparateLines() {
        PcmlDocument doc = parse("<pcml version=\"6.0\">\n"
                + "  <struct name=\"S\">\n"
                + "    <data name=\"F\" type=\"char\" length=\"1\"/>\n"
                + "  </struct>\n"
                + "\t<program name=\"P\">\n"
                + "      <data name=\"A\" type=\"char\" length=\"1\"/>\n"
                + "  </program>\n"
                + "</pcml>\n");
        assertThat(doc.structs().get("S").source()).isEqualTo(new SourceLocation("pos.pcml", 2, 3));
        assertThat(doc.structs().get("S").fields().get(0).source()).isEqualTo(new SourceLocation("pos.pcml", 3, 5));
        assertThat(doc.programs().get(0).source()).isEqualTo(new SourceLocation("pos.pcml", 5, 2));
        assertThat(doc.programs().get(0).parameters().get(0).source()).isEqualTo(new SourceLocation("pos.pcml", 6, 7));
    }

    @Test
    void adjacentTagsCommentsAndMultiLineTags() {
        PcmlDocument doc = parse("<pcml version=\"6.0\"><program name=\"A\"><data name=\"X\" type=\"char\" length=\"1\"/><!-- c --><data name=\"Y\"\n"
                + "   type=\"char\"\n"
                + "   length=\"1\"/>\n"
                + "  <data name=\"Z\" type=\"char\" length=\"1\"/></program></pcml>");
        var program = doc.programs().get(0);
        assertThat(program.source()).isEqualTo(new SourceLocation("pos.pcml", 1, 21));
        assertThat(program.parameters().get(0).source()).isEqualTo(new SourceLocation("pos.pcml", 1, 39));
        assertThat(program.parameters().get(1).source()).isEqualTo(new SourceLocation("pos.pcml", 1, 88));
        assertThat(program.parameters().get(2).source()).isEqualTo(new SourceLocation("pos.pcml", 4, 3));
    }

    @Test
    void commentsBetweenTagsAreIgnored() {
        PcmlDocument doc = parse("<pcml version=\"6.0\">\n<!-- <data name=\"GHOST\" type=\"char\" length=\"1\"/> -->\n"
                + "<program name=\"P\"><!-- note -->\n<data name=\"A\" type=\"char\" length=\"1\"/></program></pcml>");
        assertThat(doc.programs().get(0).parameters()).hasSize(1);
        assertThat(doc.programs().get(0).parameters().get(0).source()).isEqualTo(new SourceLocation("pos.pcml", 4, 1));
    }
}

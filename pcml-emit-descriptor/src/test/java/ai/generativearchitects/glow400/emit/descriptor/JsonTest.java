package ai.generativearchitects.glow400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The public face of the JSON reading and writing. */
class JsonTest {

    @Test
    void whatIsWrittenIsReadBack() throws IOException {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("b", 1);
        tree.put("a", List.of("x", true));
        tree.put("n", null);
        String text = Json.write(tree);
        assertThat(text).isEqualTo(JsonWriter.write(tree)).endsWith("}\n");
        Object read = Json.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        assertThat(read).isEqualTo(tree);
    }

    @Test
    void readingIsStrict() {
        assertThatThrownBy(() -> Json.read(new ByteArrayInputStream("{\"a\": 1, \"a\": 2}".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Json.read(new ByteArrayInputStream("{} {}".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(IOException.class);
    }
}

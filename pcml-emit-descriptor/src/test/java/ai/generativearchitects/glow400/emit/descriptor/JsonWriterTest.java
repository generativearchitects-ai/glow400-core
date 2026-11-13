package ai.generativearchitects.glow400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JSON text the descriptor is written as. */
class JsonWriterTest {

    @Test
    void writesTwoSpaceIndentationLfAndAFinalNewline() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("b", 1);
        map.put("a", List.of("x", true, 2L, false));
        map.put("c", null);
        map.put("d", Map.of());
        map.put("e", List.of());
        map.put("f", Map.of("k", Map.of("n", 1)));
        assertThat(JsonWriter.write(map)).isEqualTo("""
                {
                  "b": 1,
                  "a": [
                    "x",
                    true,
                    2,
                    false
                  ],
                  "c": null,
                  "d": {},
                  "e": [],
                  "f": {
                    "k": {
                      "n": 1
                    }
                  }
                }
                """);
    }

    @Test
    void keepsTheOrderOfTheMapAndWritesScalarsAtTheRoot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("z", 1);
        map.put("a", 2);
        assertThat(JsonWriter.write(map)).startsWith("{\n  \"z\": 1,\n  \"a\": 2\n}");
        assertThat(JsonWriter.write("text")).isEqualTo("\"text\"\n");
        assertThat(JsonWriter.write(null)).isEqualTo("null\n");
        assertThat(JsonWriter.write(List.of(new ArrayList<>()))).isEqualTo("[\n  []\n]\n");
    }

    @Test
    void escapesWhatJsonRequiresAndWhatWouldBeAmbiguous() {
        assertThat(JsonWriter.write("a\"b\\c\n\r\t\b\f")).isEqualTo("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\"\n");
        assertThat(JsonWriter.write("\u0001\u001f\u007f\u2028\u2029")).isEqualTo("\"\\u0001\\u001f\\u007f\\u2028\\u2029\"\n");
        assertThat(JsonWriter.write(" ~éü€")).isEqualTo("\" ~éü€\"\n");
        assertThat(JsonWriter.write("😀")).isEqualTo("\"😀\"\n");
    }

    @Test
    void aLoneSurrogateIsEscapedBecauseItCannotBeWrittenAsUtf8() {
        assertThat(JsonWriter.write("a\ud83dz")).isEqualTo("\"a\\ud83dz\"\n");
        assertThat(JsonWriter.write("a\ude00")).isEqualTo("\"a\\ude00\"\n");
        assertThat(JsonWriter.write("\ud83d")).isEqualTo("\"\\ud83d\"\n");
        assertThat(JsonWriter.write("\ud83d\ud83d\ude00")).isEqualTo("\"\\ud83d😀\"\n");
    }

    @Test
    void refusesWhatIsNotJson() {
        assertThatThrownBy(() -> JsonWriter.write(3.5)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Double");
    }
}

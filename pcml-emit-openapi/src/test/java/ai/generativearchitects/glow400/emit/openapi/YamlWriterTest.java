package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The YAML text the documents are written as. */
class YamlWriterTest {

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @Test
    void writesBlockStyleWithTwoSpacesAndTheOrderOfTheMap() {
        Map<String, Object> tree = map("b", 1, "a", map("x", true, "yy", List.of("p", "q")), "c", List.of(map("k", "v", "l", 2L), map("m", 3)),
                "d", map(), "e", List.of(), "f", null);
        assertThat(YamlWriter.write(tree)).isEqualTo("""
                b: 1
                a:
                  x: true
                  yy:
                    - p
                    - q
                c:
                  - k: v
                    l: 2
                  - m: 3
                d: {}
                e: []
                f: null
                """);
    }

    @Test
    void anEmptyMapAndAScalarInASequenceAreWrittenInline() {
        assertThat(YamlWriter.write(map("s", List.of(map(), "x", 1, false)))).isEqualTo("s:\n  - {}\n  - x\n  - 1\n  - false\n");
    }

    @Test
    void writesNumbersInPlainNotation() {
        assertThat(YamlWriter.write(map("a", new BigDecimal("1E+3"), "b", new BigDecimal("10.50"), "c", new BigInteger("123456789012345678901234567890"),
                "d", -5, "e", Long.MIN_VALUE))).isEqualTo("a: 1000\nb: 10.50\nc: 123456789012345678901234567890\nd: -5\ne: -9223372036854775808\n");
    }

    @Test
    void plainWordsStayPlainAndEverythingElseIsQuoted() {
        Map<String, Object> tree = map("abc", "word", "x-glow400-field", "a.b-c_d", "$ref", "#/components", "200", "1.0", "k", "with space", "e", "",
                "colon", "a: b", "hash", "a #b", "num", "123", "dash", "-x", "upper", "A1");
        assertThat(YamlWriter.write(tree)).isEqualTo("""
                abc: word
                x-glow400-field: a.b-c_d
                "$ref": "#/components"
                "200": "1.0"
                k: "with space"
                e: ""
                colon: "a: b"
                hash: "a #b"
                num: "123"
                dash: "-x"
                upper: A1
                """);
    }

    @Test
    void wordsThatSomeYamlReadersTakeForBooleansOrNullAreQuoted() {
        for (String word : List.of("true", "false", "null", "yes", "no", "on", "off", "y", "n", "TRUE", "Yes", "NULL", "No", "ON", "Off", "Y", "N")) {
            assertThat(YamlWriter.write(map("k", word))).as(word).isEqualTo("k: \"" + word + "\"\n");
        }
        assertThat(YamlWriter.write(map("k", "nope", "t", "yesterday", "y2", "y2"))).isEqualTo("k: nope\nt: yesterday\ny2: y2\n");
    }

    @Test
    void escapesLikeJson() {
        assertThat(YamlWriter.write(map("k", "a\"b\\c\n\r\td"))).isEqualTo("k: \"a\\\"b\\\\c\\n\\r\\td\"\n");
        assertThat(YamlWriter.write(map("k", "\u0001\u007f\u0085\u2028\u2029"))).isEqualTo("k: \"\\u0001\\u007f\\u0085\\u2028\\u2029\"\n");
        assertThat(YamlWriter.write(map("k", "é€😀"))).isEqualTo("k: \"é€😀\"\n");
        assertThat(YamlWriter.write(map("k", "a\ud83dz"))).isEqualTo("k: \"a\\ud83dz\"\n");
        assertThat(YamlWriter.write(map("k", "a\ude00"))).isEqualTo("k: \"a\\ude00\"\n");
        assertThat(YamlWriter.write(map("k", "\ud83d\ud83d\ude00"))).isEqualTo("k: \"\\ud83d😀\"\n");
    }

    @Test
    void refusesWhatItCannotWrite() {
        assertThatThrownBy(() -> YamlWriter.write(map("k", 1.5))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Double");
        assertThatThrownBy(() -> YamlWriter.write(map("k", List.of(List.of(1))))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside a list");
    }

    @Test
    void whatItWritesIsReadBackByAYamlParser() {
        Map<String, Object> tree = map("a", "yes", "b", "1.0", "c", List.of(map("k", "v: w", "l", "#x")), "d", "multi\nline", "e", "😀 é");
        var node = OpenApiSchema.yaml(YamlWriter.write(tree).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(node.get("a").asText()).isEqualTo("yes");
        assertThat(node.get("a").isTextual()).isTrue();
        assertThat(node.get("b").isTextual()).isTrue();
        assertThat(node.at("/c/0/k").asText()).isEqualTo("v: w");
        assertThat(node.at("/c/0/l").asText()).isEqualTo("#x");
        assertThat(node.get("d").asText()).isEqualTo("multi\nline");
        assertThat(node.get("e").asText()).isEqualTo("😀 é");
    }
}

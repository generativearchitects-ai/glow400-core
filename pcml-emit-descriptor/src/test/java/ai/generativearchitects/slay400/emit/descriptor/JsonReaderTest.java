package ai.generativearchitects.slay400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reader of untrusted documents: limits chosen by the caller, exact numbers, and messages that never quote the input. */
class JsonReaderTest {

    private static final JsonReader.Limits LIMITS = new JsonReader.Limits(10_000, 5, 50, 100);

    private static Object read(String text) throws IOException {
        return JsonReader.of(LIMITS).read(new StringReader(text));
    }

    private static void assertSyntax(String text) {
        assertThatThrownBy(() -> read(text)).isInstanceOf(JsonReader.SyntaxException.class)
                .hasMessage("The document is not valid JSON").hasNoCause();
    }

    private static void assertLimit(String text) {
        assertThatThrownBy(() -> read(text)).isInstanceOf(JsonReader.LimitExceededException.class).hasNoCause();
    }

    @Test
    void everyKindOfValueIsReadAndMembersKeepTheOrderOfTheDocument() throws IOException {
        Object tree = read("{\"b\": 1, \"a\": [true, false, null, \"x\"], \"c\": {\"d\": \"e\"}}");
        assertThat(tree).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) tree;
        assertThat(new java.util.ArrayList<Object>(map.keySet())).isEqualTo(List.of("b", "a", "c"));
        assertThat(map.get("b")).isEqualTo(1);
        assertThat(map.get("a")).isEqualTo(java.util.Arrays.asList(true, false, null, "x"));
        assertThat(map.get("c")).isEqualTo(Map.of("d", "e"));
        assertThat(read("\"alone\"")).isEqualTo("alone");
        assertThat(read("  [ ]  ")).isEqualTo(List.of());
    }

    @Test
    void wholeNumbersKeepAllTheirDigitsAndFractionsAreExactDecimals() throws IOException {
        assertThat(read("5")).isEqualTo(5);
        assertThat(read("5000000000")).isEqualTo(5_000_000_000L);
        assertThat(read("12345678901234567890")).isEqualTo(new BigInteger("12345678901234567890"));
        assertThat(read("-7")).isEqualTo(-7);
        Object price = read("1.50");
        assertThat(price).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) price).scale()).isEqualTo(2);
        assertThat(price).isEqualTo(new BigDecimal("1.50"));
        assertThat(read("0.1")).isEqualTo(new BigDecimal("0.1"));
        assertThat(read("1e2")).isEqualTo(new BigDecimal("1E+2"));
        assertThat(read("1.0")).isEqualTo(new BigDecimal("1.0"));
        // a huge exponent costs nothing to read; whoever uses the number has to look at its size
        assertThat(((BigDecimal) read("1e999999999")).precision()).isEqualTo(1);
    }

    @Test
    void aLoneSurrogateEscapeIsReadAsItIsWritten() throws IOException {
        assertThat(read("\"\\ud800\"")).isEqualTo("\ud800");
        assertThat(read("\"\\ud83d\\ude00\"")).isEqualTo("\ud83d\ude00");
    }

    @Test
    void whatIsNotJsonIsASyntaxErrorWithAFixedMessageAndAPlaceButNeverTheInput() {
        for (String text : new String[] {"{\"a\": SECRETWORD}", "", "   ", "{\"a\":1} SECRETWORD", "{\"a\":1,\"a\":2}", "[1,]", "{\"a\"", "\uFEFF{}",
                "{\"SECRETWORD\":1,\"SECRETWORD\":2}", "[1 2]", "{'a':1}", "tru", "[\"\u0001\"]"}) {
            assertSyntax(text);
            assertThatThrownBy(() -> read(text)).hasMessageNotContaining("SECRETWORD");
        }
        assertThatThrownBy(() -> read("{\"a\": SECRETWORD}")).isInstanceOfSatisfying(JsonReader.SyntaxException.class, e -> {
            assertThat(e.line()).isEqualTo(1);
            assertThat(e.column()).isBetween(7, 10);
        });
        assertThatThrownBy(() -> read("{\n\n  \"a\": SECRETWORD}")).isInstanceOfSatisfying(JsonReader.SyntaxException.class,
                e -> assertThat(e.line()).isEqualTo(3));
    }

    @Test
    void theNestingDepthIsLimitedAndTheLimitItselfIsAllowed() throws IOException {
        assertThat(read("[[[[[1]]]]]")).isNotNull();
        assertLimit("[[[[[[1]]]]]]");
        assertLimit("{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":1}}}}}}");
    }

    @Test
    void theNumberOfValuesIsLimitedAndTheLimitItselfIsAllowed() throws IOException {
        String fortyNine = "[" + "1,".repeat(48) + "1]"; // the array and 49 numbers: 50 values
        assertThat(((List<?>) read(fortyNine)).size()).isEqualTo(49);
        String fifty = "[" + "1,".repeat(49) + "1]";
        assertLimit(fifty);
        assertLimit("{\"a\":[" + "{},".repeat(50) + "{}]}");
        assertThatThrownBy(() -> read(fifty)).hasMessage("The document has too many values");
    }

    @Test
    void theLengthOfAStringAndOfAnObjectKeyAndOfANumberAreLimited() throws IOException {
        assertThat(read("\"" + "x".repeat(100) + "\"")).isEqualTo("x".repeat(100));
        assertLimit("\"" + "x".repeat(101) + "\"");
        assertThat(read("{\"" + "k".repeat(1000) + "\":1}")).isInstanceOf(Map.class);
        assertLimit("{\"" + "k".repeat(1001) + "\":1}");
        assertThat(read("1".repeat(40))).isEqualTo(new BigInteger("1".repeat(40)));
        assertLimit("1".repeat(41));
        assertLimit("1." + "1".repeat(40));
        assertThatThrownBy(() -> read("1".repeat(41))).hasMessage("The document breaks a limit");
    }

    @Test
    void theSizeOfTheDocumentIsLimitedInCharacters() throws IOException {
        assertThat(read("[" + " ".repeat(9_900) + "]")).isEqualTo(List.of());
        assertLimit("[" + " ".repeat(10_100) + "]");
    }

    @Test
    void anEncodingErrorOfTheReaderIsReportedAsItIsAndIsNeitherASyntaxNorALimitError() {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] bad = {'"', (byte) 0xC3, (byte) 0x28, '"'};
        assertThatThrownBy(() -> JsonReader.of(LIMITS).read(new InputStreamReader(new ByteArrayInputStream(bad), decoder)))
                .isInstanceOf(CharacterCodingException.class);
    }

    @Test
    void aReaderIsReusableAndAFailedReadLeavesNothingBehind() throws IOException {
        JsonReader reader = JsonReader.of(LIMITS);
        assertThatThrownBy(() -> reader.read(new StringReader("[1,"))).isInstanceOf(JsonReader.SyntaxException.class);
        assertThatThrownBy(() -> reader.read(new StringReader("[[[[[[1]]]]]]"))).isInstanceOf(JsonReader.LimitExceededException.class);
        assertThat(reader.read(new StringReader("[1]"))).isEqualTo(List.of(1));
        assertThat(reader.read(new StringReader("[1]"))).isEqualTo(List.of(1));
    }

    @Test
    void theLimitsMustBePositiveAndTheArgumentsGiven() {
        assertThat(new JsonReader.Limits(1, 1, 1, 1).maxValues()).isEqualTo(1);
        assertThatThrownBy(() -> new JsonReader.Limits(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonReader.Limits(1, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonReader.Limits(1, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonReader.Limits(1, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonReader.of(null)).isInstanceOf(NullPointerException.class).hasMessage("limits");
        assertThatThrownBy(() -> JsonReader.of(LIMITS).read(null)).isInstanceOf(NullPointerException.class).hasMessage("in");
    }

    @Test
    void theReaderOfTheDescriptorIsNotChanged() throws IOException {
        // the fixed limits and Jackson's own numbers and messages stay as they were for the files that use Json.read
        assertThat(Json.read(new ByteArrayInputStream("{\"a\": 1.5}".getBytes(StandardCharsets.UTF_8)))).isEqualTo(Map.of("a", 1.5));
        assertThatThrownBy(() -> Json.read(new ByteArrayInputStream("{\"a\": SECRETWORD}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class).hasMessageContaining("SECRETWORD");
    }
}

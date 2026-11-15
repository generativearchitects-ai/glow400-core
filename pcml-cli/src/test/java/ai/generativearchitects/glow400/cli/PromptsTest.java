package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How a parameter is described in a question, and how a shortened answer is matched. */
class PromptsTest {

    private static FieldDef field(WireType wire, Count count) {
        return new FieldDef("F", wire, Usage.INPUT_OUTPUT, count, Map.of(), new SourceLocation("a.pcml", 1, 1));
    }

    private static String describe(WireType wire) {
        return Prompts.describe(field(wire, new Count.None()));
    }

    @Test
    void everyWireTypeIsDescribedAsInThePcml() {
        assertThat(describe(new WireType.Char(new Length.Fixed(10), Optional.empty()))).isEqualTo("char(10)");
        assertThat(describe(new WireType.Char(new Length.Ref("LEN"), Optional.empty()))).isEqualTo("char(*LEN)");
        assertThat(describe(new WireType.VarChar(new Length.Fixed(20), Optional.empty(), Optional.empty()))).isEqualTo("varchar(20)");
        assertThat(describe(new WireType.Byte(new Length.Fixed(8)))).isEqualTo("byte(8)");
        assertThat(describe(new WireType.Int(4, Optional.empty()))).isEqualTo("int(4)");
        assertThat(describe(new WireType.Packed(7, 2))).isEqualTo("packed(7,2)");
        assertThat(describe(new WireType.Zoned(5, 0))).isEqualTo("zoned(5,0)");
        assertThat(describe(new WireType.Float(8))).isEqualTo("float(8)");
        assertThat(describe(new WireType.Struct("ROW"))).isEqualTo("struct ROW");
        assertThat(describe(new WireType.Date())).isEqualTo("date");
        assertThat(describe(new WireType.Time())).isEqualTo("time");
        assertThat(describe(new WireType.Timestamp())).isEqualTo("timestamp");
    }

    @Test
    void aCountIsShownAfterTheType() {
        assertThat(Prompts.describe(field(new WireType.Packed(7, 2), new Count.Fixed(3)))).isEqualTo("packed(7,2)[3]");
        assertThat(Prompts.describe(field(new WireType.Packed(7, 2), new Count.Ref("N")))).isEqualTo("packed(7,2)[*N]");
        assertThat(Prompts.describe(field(new WireType.Struct("ROW"), new Count.Fixed(10)))).isEqualTo("struct ROW[10]");
    }

    @Test
    void namesAreEscapedLikeInTheTextReport() {
        assertThat(Prompts.describe(field(new WireType.Struct("A\u001b[2J"), new Count.Ref("B\nC")))).isEqualTo("struct A\\u001b[2J[*B\\u000aC]");
        assertThat(describe(new WireType.Char(new Length.Ref("L\u0007"), Optional.empty()))).isEqualTo("char(*L\\u0007)");
    }

    private static final List<String> CHOICES = List.of("request", "response", "both", "internal");

    private static Optional<String> choose(String answer) {
        return Prompts.choose(answer, CHOICES, c -> c);
    }

    @Test
    void anAnswerIsInAnyCaseAndAnUnambiguousPrefix() {
        assertThat(choose("request")).contains("request");
        assertThat(choose("REQUEST")).contains("request");
        assertThat(choose("Req")).contains("request");
        assertThat(choose("resp")).contains("response");
        assertThat(choose("b")).contains("both");
        assertThat(choose("I")).contains("internal");
        assertThat(choose("  both  ")).contains("both");
    }

    @Test
    void anAnswerThatMatchesNoneOrSeveralIsNotAnAnswer() {
        assertThat(choose("r")).isEmpty();
        assertThat(choose("re")).isEmpty();
        assertThat(choose("x")).isEmpty();
        assertThat(choose("requests")).isEmpty();
        assertThat(choose("")).isEmpty();
        assertThat(choose("   ")).isEmpty();
    }
}

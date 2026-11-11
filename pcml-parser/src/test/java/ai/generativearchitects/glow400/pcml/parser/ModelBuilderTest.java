package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The small decisions {@code ModelBuilder} makes about attribute values. */
class ModelBuilderTest {

    @Test
    void aNumberIsOneOrMoreDecimalDigitsAndNothingElse() {
        for (String number : new String[] {"0", "9", "10", "007", "0123456789", "99999999999999999999"}) {
            assertThat(ModelBuilder.isNumber(number)).as(number).isTrue();
        }
        for (String other : new String[] {"", " ", "a", "1a", "a1", "1 ", " 1", "-1", "+1", "1.0", "1e3", "/", ":", "1/", "9:", "\u0661"}) {
            assertThat(ModelBuilder.isNumber(other)).as("'" + other + "'").isFalse();
        }
    }
}

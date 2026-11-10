package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** Merge rules 4 and 5. */
class NameRulesTest {

    static Stream<Arguments> names() {
        return Stream.of(
                // upper case only: lower-case everything and turn _x into X
                Arguments.of("ORDER_NUMBER", "orderNumber"),
                Arguments.of("P0ORDN", "p0ordn"),
                Arguments.of("A", "a"),
                Arguments.of("ABC", "abc"),
                Arguments.of("CUST_ID_NO", "custIdNo"),
                Arguments.of("A1_B2", "a1B2"),
                Arguments.of("X_1", "x1"),
                Arguments.of("_LEADING", "leading"),
                Arguments.of("TRAILING_", "trailing"),
                Arguments.of("DOUBLE__UNDERSCORE", "doubleUnderscore"),
                Arguments.of("_A_B_", "aB"),
                Arguments.of("P0#ORD$", "p0#ord$"),
                Arguments.of("123", "123"),
                Arguments.of("___", "___"),
                // contains a lower-case letter: keep it, lower-case the first character
                Arguments.of("OrderNumber", "orderNumber"),
                Arguments.of("orderNumber", "orderNumber"),
                Arguments.of("Cust_Name", "cust_Name"),
                Arguments.of("aBC", "aBC"),
                Arguments.of("X1y", "x1y"),
                Arguments.of("_Name", "_Name"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("names")
    void defaultExposedName(String rpg, String exposed) {
        assertThat(NameRules.exposedName(rpg)).isEqualTo(exposed);
    }

    @Test
    void defaultSemanticTypeFollowsTheWireType() {
        Length ten = new Length.Fixed(10);
        assertThat(NameRules.semanticType(new WireType.Char(ten, Optional.empty()))).contains(SemanticType.STRING);
        assertThat(NameRules.semanticType(new WireType.VarChar(ten, Optional.empty(), Optional.empty()))).contains(SemanticType.STRING);
        assertThat(NameRules.semanticType(new WireType.Byte(ten))).contains(SemanticType.BINARY);
        assertThat(NameRules.semanticType(new WireType.Int(4, Optional.empty()))).contains(SemanticType.INTEGER);
        assertThat(NameRules.semanticType(new WireType.Packed(9, 2))).contains(SemanticType.DECIMAL);
        assertThat(NameRules.semanticType(new WireType.Zoned(9, 2))).contains(SemanticType.DECIMAL);
        assertThat(NameRules.semanticType(new WireType.Float(8))).contains(SemanticType.DECIMAL);
        assertThat(NameRules.semanticType(new WireType.Date())).contains(SemanticType.DATE);
        assertThat(NameRules.semanticType(new WireType.Time())).contains(SemanticType.TIME);
        assertThat(NameRules.semanticType(new WireType.Timestamp())).contains(SemanticType.DATETIME);
        assertThat(NameRules.semanticType(new WireType.Struct("S"))).isEmpty();
    }
}

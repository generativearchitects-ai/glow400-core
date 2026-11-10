package ai.generativearchitects.glow400.pcml.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelInvariantsTest {

    private static final SourceLocation LOC = new SourceLocation("f.pcml", 1, 1);

    private static FieldDef field(String name) {
        return new FieldDef(name, new WireType.Date(), Usage.INHERIT, new Count.None(), Map.of(), LOC);
    }

    @Test
    void collectionsAreCopiedAndUnmodifiable() {
        List<FieldDef> fields = new ArrayList<>(List.of(field("A")));
        Map<String, String> attributes = new LinkedHashMap<>(Map.of("x", "1"));
        StructDef struct = new StructDef("S", fields, attributes, LOC);
        fields.add(field("B"));
        attributes.put("y", "2");
        assertThat(struct.fields()).hasSize(1);
        assertThat(struct.attributes()).containsOnlyKeys("x");
        assertThatThrownBy(() -> struct.fields().add(field("C"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> struct.attributes().put("z", "3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void structsKeepInsertionOrder() {
        Map<String, StructDef> structs = new LinkedHashMap<>();
        for (String name : List.of("Z", "A", "M", "B", "Y")) {
            structs.put(name, new StructDef(name, List.of(), Map.of(), LOC));
        }
        PcmlDocument doc = new PcmlDocument("6.0", structs, List.of(), Map.of());
        assertThat(doc.structs().keySet()).containsExactly("Z", "A", "M", "B", "Y");
    }

    @Test
    void attributeMapsKeepInsertionOrder() {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String key : List.of("z", "a", "m", "b", "y", "c", "x")) {
            attributes.put(key, "v");
        }
        assertThat(field("A").attributes()).isEmpty();
        assertThat(new FieldDef("A", new WireType.Date(), Usage.INPUT, new Count.None(), attributes, LOC).attributes().keySet())
                .containsExactly("z", "a", "m", "b", "y", "c", "x");
    }

    @Test
    void nullsAndBlanksAreRejected() {
        assertThatThrownBy(() -> new FieldDef(null, new WireType.Date(), Usage.INHERIT, new Count.None(), Map.of(), LOC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldDef(" ", new WireType.Date(), Usage.INHERIT, new Count.None(), Map.of(), LOC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgramDef("P", null, Optional.empty(), Optional.empty(), List.of(), Map.of(), LOC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StructDef("S", List.of(), Map.of("k", ""), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Count.Ref("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Length.Fixed(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceLocation("f", -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WireType.Char(null, Optional.empty())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordsHaveValueEquality() {
        assertThat(new Count.Fixed(3)).isEqualTo(new Count.Fixed(3)).isNotEqualTo(new Count.Ref("X"));
        assertThat(new WireType.Packed(9, 2)).isEqualTo(new WireType.Packed(9, 2));
        assertThat(field("A")).isEqualTo(field("A"));
    }

    @Test
    void zeroIsAllowedWhereNegativeIsNot() {
        assertThat(new SourceLocation("f", 0, 0)).hasToString("f:0:0");
        assertThat(new Count.Fixed(0).value()).isZero();
        assertThat(new Length.Fixed(0).value()).isZero();
        assertThatThrownBy(() -> new SourceLocation("f", 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Count.Fixed(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencesAndStructNamesMustNotBeBlank() {
        assertThatThrownBy(() -> new Length.Ref(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Count.Ref(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WireType.Struct(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Length.Ref(null)).isInstanceOf(NullPointerException.class);
        assertThat(new SourceLocation("a.pcml", 3, 7)).hasToString("a.pcml:3:7");
    }
}

package ai.generativearchitects.glow400.pcml.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiModelTest {

    private static final SourceLocation AT = new SourceLocation("api.yaml", 1, 1);
    private static final FieldDef FIELD = new FieldDef("P", new WireType.Char(new Length.Fixed(1), Optional.empty()), Usage.INPUT,
            new Count.None(), Map.of(), AT);
    private static final ProgramDef PROGRAM = new ProgramDef("P", Optional.empty(), Optional.empty(), Optional.empty(),
            List.of(), Map.of(), AT);

    private static FieldBinding binding(String path, String name, List<FieldBinding> children) {
        return new FieldBinding(path, FIELD, Direction.REQUEST, Optional.empty(), name, Optional.empty(), Optional.empty(),
                CodecSettings.none(), false, Optional.empty(), Optional.empty(), false, Optional.empty(), children, AT);
    }

    private static ApiOperation operation(String id, String version, String route, List<String> tags, List<FieldBinding> bindings) {
        return new ApiOperation(id, version, Optional.empty(), Optional.empty(), tags, PROGRAM, "a.pcml", "api.yaml", route,
                HttpMethod.GET, CallStrategy.AUTO, bindings, Optional.empty(), Optional.empty(), AT);
    }

    @Test
    void codecSettingsRejectNullsAndNoneMeansNoPaddingAndNoNullText() {
        assertThat(CodecSettings.none()).isEqualTo(new CodecSettings(Pad.NONE, ' ', "", Optional.empty()));
        assertThatThrownBy(() -> new CodecSettings(null, ' ', "", Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CodecSettings(Pad.LEFT, ' ', null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CodecSettings(Pad.LEFT, ' ', "", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void gatewayDefaultsAreStandardAndCopyTheirEmitters() {
        List<String> emitters = new ArrayList<>(List.of("a"));
        GatewayDefaults defaults = new GatewayDefaults(CodecSettings.none(), InputOutputPolicy.FAIL, emitters);
        emitters.add("b");
        assertThat(defaults.emitters()).containsExactly("a");
        assertThatThrownBy(() -> defaults.emitters().add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(GatewayDefaults.standard().charCodec()).isEqualTo(new CodecSettings(Pad.RIGHT, ' ', "", Optional.empty()));
        assertThat(GatewayDefaults.standard().inputOutput()).isEqualTo(InputOutputPolicy.BOTH);
        assertThat(GatewayDefaults.standard().emitters()).containsExactly("descriptor", "openapi", "mock");
        assertThatThrownBy(() -> new GatewayDefaults(null, InputOutputPolicy.BOTH, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GatewayDefaults(CodecSettings.none(), null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void trimCountFieldNeedsAPath() {
        assertThat(new Trim.CountField("N").path()).isEqualTo("N");
        assertThatThrownBy(() -> new Trim.CountField(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Trim.CountField(null)).isInstanceOf(NullPointerException.class);
        assertThat(new Trim.DropBlank()).isEqualTo(new Trim.DropBlank());
    }

    @Test
    void errorConditionsCarryTheirFieldAndValues() {
        ErrorCondition equals = new ErrorCondition.Equals("F", "v");
        ErrorCondition notEquals = new ErrorCondition.NotEquals("G", "w");
        List<String> values = new ArrayList<>(List.of("a", "b"));
        ErrorCondition in = new ErrorCondition.In("H", values);
        values.add("c");
        assertThat(equals.field()).isEqualTo("F");
        assertThat(notEquals.field()).isEqualTo("G");
        assertThat(in.field()).isEqualTo("H");
        assertThat(((ErrorCondition.In) in).values()).containsExactly("a", "b");
        assertThatThrownBy(() -> new ErrorCondition.Equals(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorCondition.Equals("F", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorCondition.NotEquals(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorCondition.NotEquals("F", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorCondition.In(null, List.of("a"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorCondition.In("H", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorMappingChecksStatusRangesAndCopiesTheStatusMap() {
        ErrorCondition when = new ErrorCondition.Equals("F", "v");
        Map<String, Integer> byCode = new LinkedHashMap<>();
        byCode.put("Z", 404);
        byCode.put("A", 503);
        ErrorMapping mapping = new ErrorMapping(when, Optional.of("C"), Optional.empty(), ErrorMapping.DEFAULT_HTTP_STATUS, byCode);
        byCode.put("X", 500);
        assertThat(ErrorMapping.DEFAULT_HTTP_STATUS).isEqualTo(422);
        assertThat(mapping.statusByCode()).containsExactly(Map.entry("Z", 404), Map.entry("A", 503));
        assertThatThrownBy(() -> mapping.statusByCode().put("Q", 400)).isInstanceOf(UnsupportedOperationException.class);
        new ErrorMapping(when, Optional.empty(), Optional.empty(), 400, Map.of());
        new ErrorMapping(when, Optional.empty(), Optional.empty(), 599, Map.of());
        for (int status : new int[] {399, 600, 0, 200}) {
            assertThatThrownBy(() -> new ErrorMapping(when, Optional.empty(), Optional.empty(), status, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(String.valueOf(status));
            assertThatThrownBy(() -> new ErrorMapping(when, Optional.empty(), Optional.empty(), 400, Map.of("C", status)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ErrorMapping(null, Optional.empty(), Optional.empty(), 400, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorMapping(when, null, Optional.empty(), 400, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorMapping(when, Optional.empty(), null, 400, Map.of())).isInstanceOf(NullPointerException.class);
        Map<String, Integer> nullKey = new LinkedHashMap<>();
        nullKey.put(null, 400);
        assertThatThrownBy(() -> new ErrorMapping(when, Optional.empty(), Optional.empty(), 400, nullKey)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cacheSettingsRejectNegativeTtl() {
        assertThat(new CacheSettings(0).ttlSeconds()).isZero();
        assertThat(new CacheSettings(60).ttlSeconds()).isEqualTo(60);
        assertThatThrownBy(() -> new CacheSettings(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldBindingsCopyTheirChildrenAndRejectBlankNames() {
        List<FieldBinding> children = new ArrayList<>(List.of(binding("P.X", "x", List.of())));
        FieldBinding parent = binding("P", "p", children);
        children.add(binding("P.Y", "y", List.of()));
        assertThat(parent.children()).hasSize(1);
        assertThatThrownBy(() -> parent.children().add(binding("P.Z", "z", List.of()))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> binding(" ", "p", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding("P", " ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding(null, "p", List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> binding("P", null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldBinding("P", FIELD, null, Optional.empty(), "p", Optional.empty(), Optional.empty(),
                CodecSettings.none(), false, Optional.empty(), Optional.empty(), false, Optional.empty(), List.of(), AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldBinding("P", FIELD, Direction.BOTH, Optional.empty(), "p", Optional.empty(), Optional.empty(),
                null, false, Optional.empty(), Optional.empty(), false, Optional.empty(), List.of(), AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldBinding("P", FIELD, Direction.BOTH, Optional.empty(), "p", Optional.empty(), Optional.empty(),
                CodecSettings.none(), false, Optional.empty(), Optional.empty(), false, Optional.empty(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBindingKeepsItsCallModelFieldAndRejectsNone() {
        assertThat(binding("P", "p", List.of()).field()).isSameAs(FIELD);
        assertThatThrownBy(() -> new FieldBinding("P", null, Direction.BOTH, Optional.empty(), "p", Optional.empty(), Optional.empty(),
                CodecSettings.none(), false, Optional.empty(), Optional.empty(), false, Optional.empty(), List.of(), AT))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("field");
    }

    @Test
    void operationsCopyTheirListsAndRejectBlankIdentifiers() {
        List<String> tags = new ArrayList<>(List.of("a"));
        List<FieldBinding> bindings = new ArrayList<>(List.of(binding("P", "p", List.of())));
        ApiOperation operation = operation("id", "1.0.0", "/r", tags, bindings);
        tags.add("b");
        bindings.clear();
        assertThat(operation.tags()).containsExactly("a");
        assertThat(operation.bindings()).hasSize(1);
        assertThatThrownBy(() -> operation.tags().add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> operation(" ", "1.0.0", "/r", List.of(), List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> operation("id", " ", "/r", List.of(), List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> operation("id", "1.0.0", " ", List.of(), List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("route");
        assertThatThrownBy(() -> operation(null, "1.0.0", "/r", List.of(), List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiOperation("id", "1.0.0", Optional.empty(), Optional.empty(), List.of(), null, "a", "b", "/r",
                HttpMethod.GET, CallStrategy.AUTO, List.of(), Optional.empty(), Optional.empty(), AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiOperation("id", "1.0.0", Optional.empty(), Optional.empty(), List.of(), PROGRAM, "a", "b", "/r",
                null, CallStrategy.AUTO, List.of(), Optional.empty(), Optional.empty(), AT)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void repositoryModelsCopyTheirOperations() {
        List<ApiOperation> operations = new ArrayList<>(List.of(operation("a", "1.0.0", "/a", List.of(), List.of())));
        ApiRepositoryModel model = new ApiRepositoryModel(GatewayDefaults.standard(), operations);
        operations.clear();
        assertThat(model.operations()).hasSize(1);
        assertThat(model.defaults()).isEqualTo(GatewayDefaults.standard());
        assertThatThrownBy(() -> new ApiRepositoryModel(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> model.operations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void codecSettingsCarryTheBooleanTexts() {
        CodecSettings withTexts = new CodecSettings(Pad.NONE, ' ', "", Optional.empty(), Optional.of("Y"), Optional.of("N"));
        assertThat(withTexts.trueValue()).contains("Y");
        assertThat(withTexts.falseValue()).contains("N");
        assertThat(CodecSettings.none().trueValue()).isEmpty();
        assertThat(CodecSettings.none().falseValue()).isEmpty();
        assertThat(new CodecSettings(Pad.LEFT, '0', "x", Optional.empty())).isEqualTo(
                new CodecSettings(Pad.LEFT, '0', "x", Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(CodecSettings.DEFAULT_TRUE_VALUE).isEqualTo("1");
        assertThat(CodecSettings.DEFAULT_FALSE_VALUE).isEqualTo("0");
        assertThatThrownBy(() -> new CodecSettings(Pad.NONE, ' ', "", Optional.empty(), null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CodecSettings(Pad.NONE, ' ', "", Optional.empty(), Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void gatewayDefaultsCarryTheLargeArrayThreshold() {
        assertThat(GatewayDefaults.DEFAULT_LARGE_ARRAY_THRESHOLD).isEqualTo(100);
        assertThat(new GatewayDefaults(CodecSettings.none(), InputOutputPolicy.BOTH, List.of()).largeArrayThreshold()).isEqualTo(100);
        assertThat(new GatewayDefaults(CodecSettings.none(), InputOutputPolicy.BOTH, List.of(), 1).largeArrayThreshold()).isEqualTo(1);
        for (int bad : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> new GatewayDefaults(CodecSettings.none(), InputOutputPolicy.BOTH, List.of(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

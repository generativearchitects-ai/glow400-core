package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code gateway.yaml}: all keys optional, absent keys take the standard defaults. */
class GatewayTest {

    private static GatewayDefaults defaults(Path dir, String gateway) {
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", gateway).load();
        assertThat(result.diagnostics()).isEmpty();
        return result.model().orElseThrow().defaults();
    }

    @Test
    void anEmptyFileACommentAndANullDocumentMeanTheStandardDefaults(@TempDir Path dir) {
        assertThat(defaults(dir.resolve("a"), "")).isEqualTo(GatewayDefaults.standard());
        assertThat(defaults(dir.resolve("b"), "# nothing here\n")).isEqualTo(GatewayDefaults.standard());
        assertThat(defaults(dir.resolve("c"), "~\n")).isEqualTo(GatewayDefaults.standard());
        assertThat(defaults(dir.resolve("d"), "{}\n")).isEqualTo(GatewayDefaults.standard());
        assertThat(defaults(dir.resolve("e"), "defaults: {}\n")).isEqualTo(GatewayDefaults.standard());
    }

    @Test
    void theStandardDefaultsAreTheOnesOfTheBrief() {
        GatewayDefaults standard = GatewayDefaults.standard();
        assertThat(standard.charCodec()).isEqualTo(new CodecSettings(Pad.RIGHT, ' ', "", Optional.empty()));
        assertThat(standard.inputOutput()).isEqualTo(InputOutputPolicy.BOTH);
        assertThat(standard.emitters()).containsExactly("descriptor", "openapi", "mock");
    }

    @Test
    void everyKeyCanBeSet(@TempDir Path dir) {
        GatewayDefaults result = defaults(dir, """
                defaults:
                  char:
                    pad: left
                    padChar: "0"
                    nullAs: "-"
                  inputoutput: fail
                emitters: [openapi, java, 3, true]
                """);
        assertThat(result.charCodec()).isEqualTo(new CodecSettings(Pad.LEFT, '0', "-", Optional.empty()));
        assertThat(result.inputOutput()).isEqualTo(InputOutputPolicy.FAIL);
        assertThat(result.emitters()).containsExactly("openapi", "java", "3", "true");
    }

    @Test
    void absentKeysKeepTheirStandardValues(@TempDir Path dir) {
        assertThat(defaults(dir.resolve("a"), "defaults:\n  char:\n    pad: left\n").charCodec())
                .isEqualTo(new CodecSettings(Pad.LEFT, ' ', "", Optional.empty()));
        assertThat(defaults(dir.resolve("b"), "defaults:\n  char:\n    padChar: \"_\"\n").charCodec())
                .isEqualTo(new CodecSettings(Pad.RIGHT, '_', "", Optional.empty()));
        assertThat(defaults(dir.resolve("c"), "defaults:\n  char:\n    nullAs: \"?\"\n").charCodec())
                .isEqualTo(new CodecSettings(Pad.RIGHT, ' ', "?", Optional.empty()));
        GatewayDefaults onlyPolicy = defaults(dir.resolve("d"), "defaults:\n  inputoutput: fail\n");
        assertThat(onlyPolicy.charCodec()).isEqualTo(GatewayDefaults.standard().charCodec());
        assertThat(onlyPolicy.emitters()).isEqualTo(GatewayDefaults.standard().emitters());
        assertThat(defaults(dir.resolve("e"), "emitters: []\n").emitters()).isEmpty();
        assertThat(defaults(dir.resolve("f"), "emitters: [mock]\n").inputOutput()).isEqualTo(InputOutputPolicy.BOTH);
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("an unknown key", "extra: 1\n", "OVL011", 1),
                Arguments.of("an unknown key in defaults", "defaults:\n  other: 1\n", "OVL011", 2),
                Arguments.of("an unknown key in char", "defaults:\n  char:\n    align: left\n", "OVL011", 3),
                Arguments.of("pad other than left or right", "defaults:\n  char:\n    pad: middle\n", "OVL012", 3),
                Arguments.of("pad none", "defaults:\n  char:\n    pad: none\n", "OVL012", 3),
                Arguments.of("padChar of two characters", "defaults:\n  char:\n    padChar: \"ab\"\n", "OVL012", 3),
                Arguments.of("empty padChar", "defaults:\n  char:\n    padChar: \"\"\n", "OVL012", 3),
                Arguments.of("nullAs that is not text", "defaults:\n  char:\n    nullAs: 5\n", "OVL012", 3),
                Arguments.of("an unknown policy", "defaults:\n  inputoutput: maybe\n", "OVL012", 2),
                Arguments.of("defaults that is not a mapping", "defaults: [a]\n", "OVL012", 1),
                Arguments.of("defaults left empty", "defaults:\n", "OVL012", 1),
                Arguments.of("char that is not a mapping", "defaults:\n  char: left\n", "OVL012", 2),
                Arguments.of("emitters that is not a list", "emitters: openapi\n", "OVL012", 1),
                Arguments.of("an emitter that is a list", "emitters: [[a]]\n", "OVL012", 1),
                Arguments.of("a list at the top", "- a\n", "OVL012", 1),
                Arguments.of("text at the top", "just text\n", "OVL012", 1),
                Arguments.of("invalid YAML", "defaults: [\n", "OVL010", 2),
                Arguments.of("a duplicate key", "emitters: []\nemitters: []\n", "OVL010", 2));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void aFileThatCannotBeInterpretedGivesNoModel(String why, String gateway, String code, int line, @TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", gateway).load();
        assertThat(result.model()).as(why).isEmpty();
        assertThat(result.diagnostics()).as(why).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.location().file()).isEqualTo("gateway.yaml");
        assertThat(d.location().line()).as(why + ": " + d).isEqualTo(line);
    }

    @Test
    void theOverlaysAreStillCheckedWhenGatewayYamlIsInvalid(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", "defaults: [a]\n")
                .api("A", Repos.Temp.overlay("").replace("version: 1.0.0", "version: bad")).load();
        assertThat(result.model()).isEmpty();
        assertThat(result.diagnostics()).extracting(d -> d.location().file()).containsExactly("apis/A/api.yaml", "gateway.yaml");
    }

    @Test
    void theDefaultsReachTheOperations(@TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("gateway.yaml", "defaults:\n  char:\n    pad: left\n    padChar: \"0\"\n")
                .api("A", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n")).load();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations().get(0).bindings().get(0).codec().padChar()).isEqualTo('0');
        assertThat(List.of(result.model().orElseThrow().defaults().emitters())).isNotEmpty();
    }
}

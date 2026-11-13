package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/** Every emitted document is read back with the YAML library of pcml-overlay and is what was written (C102). */
class YamlRoundTripTest {

    @Test
    void everyEmittedDocumentParsesBackToTheTreeThatWasWritten() {
        int documents = 0;
        for (String repository : Repositories.VALID) {
            ApiRepositoryModel model = Repositories.model(repository);
            List<EmittedFile> files = new OpenApiEmitter().emit(model).files();
            for (int i = 0; i < files.size(); i++) {
                ApiDefinition api = model.apis().get(i);
                Object parsed = new Load(LoadSettings.builder().build()).loadFromString(new String(files.get(i).content(), StandardCharsets.UTF_8));
                assertSame(OpenApiBuilder.document(api), parsed, repository + " " + api.name());
                documents++;
            }
        }
        assertThat(documents).isEqualTo(Repositories.VALID.size());
    }

    @SuppressWarnings("unchecked")
    private static void assertSame(Object written, Object parsed, String where) {
        switch (written) {
            case Map<?, ?> map -> {
                assertThat(parsed).as(where).isInstanceOf(Map.class);
                Map<Object, Object> actual = (Map<Object, Object>) parsed;
                assertThat(List.copyOf(actual.keySet())).as(where + " keys").isEqualTo(List.copyOf(map.keySet()));
                map.forEach((key, value) -> assertSame(value, actual.get(key), where + "/" + key));
            }
            case List<?> list -> {
                assertThat(parsed).as(where).isInstanceOf(List.class);
                List<Object> actual = (List<Object>) parsed;
                assertThat(actual).as(where + " size").hasSameSizeAs(list);
                for (int i = 0; i < list.size(); i++) {
                    assertSame(list.get(i), actual.get(i), where + "[" + i + "]");
                }
            }
            case Number number -> {
                assertThat(parsed).as(where).isInstanceOf(Number.class);
                assertThat(new BigDecimal(parsed.toString())).as(where).isEqualByComparingTo(new BigDecimal(number.toString()));
            }
            case null -> assertThat(parsed).as(where).isNull();
            default -> assertThat(parsed).as(where).isEqualTo(written);
        }
    }

    @Test
    void theComparisonSeesADifference() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertSame(Map.of("a", "yes"), Map.of("a", true), "x"))
                .isInstanceOf(AssertionError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertSame(List.of(1), List.of(2), "x")).isInstanceOf(AssertionError.class);
    }
}

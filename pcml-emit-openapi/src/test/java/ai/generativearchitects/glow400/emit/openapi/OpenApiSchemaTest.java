package ai.generativearchitects.glow400.emit.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Every document the emitter writes is a valid OpenAPI 3.1 document, by the official JSON Schema. */
class OpenApiSchemaTest {

    @Test
    void everyEmittedDocumentValidatesAgainstTheOfficialSchema() {
        for (String repository : Repositories.VALID) {
            for (EmittedFile file : new OpenApiEmitter().emit(Repositories.model(repository)).files()) {
                assertThat(OpenApiSchema.validate(OpenApiSchema.yaml(file.content()))).as(repository + "/" + file.path()).isEmpty();
            }
        }
    }

    @Test
    void theSchemaCheckCatchesAWrongDocument() {
        JsonNode broken = OpenApiSchema.json("{\"openapi\": \"3.1.0\", \"info\": {\"title\": \"t\"}, \"paths\": {}}");
        assertThat(OpenApiSchema.validate(broken)).isNotEmpty();
        JsonNode badSchemaObject = OpenApiSchema.json("{\"openapi\": \"3.1.0\", \"info\": {\"title\": \"t\", \"version\": \"1\"}, "
                + "\"paths\": {\"/a\": {\"get\": {\"responses\": {\"200\": {\"description\": \"d\", \"content\": {\"application/json\": "
                + "{\"schema\": {\"type\": \"nonsense\"}}}}}}}}}");
        assertThat(OpenApiSchema.validate(badSchemaObject)).isNotEmpty();
    }
}

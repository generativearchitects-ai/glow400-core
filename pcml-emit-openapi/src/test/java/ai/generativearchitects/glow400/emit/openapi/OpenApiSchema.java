package ai.generativearchitects.glow400.emit.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * The official OpenAPI 3.1 JSON Schema (with the Schema Object dialect), kept in test resources as published at
 * https://spec.openapis.org/oas/3.1/, for checking the documents the emitter writes.
 */
final class OpenApiSchema {

    private static final String BASE = "https://spec.openapis.org/oas/3.1/";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder -> builder
            .schemaMappers(mappers -> mappers.mappings(Map.of(
                    BASE + "schema-base/2022-10-07", "classpath:openapi-schema/schema-base.json",
                    BASE + "schema/2022-10-07", "classpath:openapi-schema/schema.json",
                    BASE + "dialect/base", "classpath:openapi-schema/dialect-base.json",
                    BASE + "meta/base", "classpath:openapi-schema/meta-base.json"))))
            .getSchema(OpenApiSchema.class.getResourceAsStream("/openapi-schema/schema-base.json"));

    private OpenApiSchema() {
    }

    static JsonNode yaml(byte[] document) {
        try {
            return YAML.readTree(new String(document, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What is wrong with the document according to the OpenAPI 3.1 schema; empty when it is valid. */
    static Set<ValidationMessage> validate(JsonNode document) {
        return SCHEMA.validate(document);
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A JSON Schema fragment of a document, for validating a value against it: the fragment with the document's components. */
    static JsonSchema schemaOf(JsonNode document, JsonNode fragment) {
        com.fasterxml.jackson.databind.node.ObjectNode root = JSON.createObjectNode();
        root.setAll((com.fasterxml.jackson.databind.node.ObjectNode) fragment.deepCopy());
        root.set("components", document.get("components"));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
    }
}

package ai.generativearchitects.glow400.emit.descriptor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JSON document into plain Java values: {@link Map} (in document order), {@link List}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}. Only the streaming API of Jackson is used, so nothing about the types
 * of the result can come from the input. The document size, the nesting depth and the length of a string are limited, a
 * repeated key is an error, and nothing may follow the document.
 */
final class JsonTree {

    /** The largest document accepted, in bytes. */
    static final long MAX_DOCUMENT_BYTES = 16L * 1024 * 1024;
    /** The deepest nesting accepted. */
    static final int MAX_DEPTH = 200;
    /** The longest string accepted, in characters. */
    static final int MAX_STRING_LENGTH = 1_000_000;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_DOCUMENT_BYTES)
                    .maxNestingDepth(MAX_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH)
                    .maxNumberLength(40)
                    .maxNameLength(1000)
                    .build())
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(com.fasterxml.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE)
            .build();

    private JsonTree() {
    }

    /**
     * @throws IOException if the input cannot be read, is not JSON, breaks a limit or has a repeated key; a
     *                     {@link com.fasterxml.jackson.core.JsonProcessingException} says where
     */
    static Object read(InputStream in) throws IOException {
        try (JsonParser parser = FACTORY.createParser(in)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new com.fasterxml.jackson.core.JsonParseException(parser, "The document is empty");
            }
            Object value = value(parser, first);
            if (parser.nextToken() != null) {
                throw new com.fasterxml.jackson.core.JsonParseException(parser, "Unexpected content after the end of the document");
            }
            return value;
        }
    }

    private static Object value(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    map.put(name, value(parser, parser.nextToken()));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_ARRAY; next = parser.nextToken()) {
                    list.add(value(parser, next));
                }
                yield list;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getNumberValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new com.fasterxml.jackson.core.JsonParseException(parser, "Unexpected token " + token);
        };
    }
}

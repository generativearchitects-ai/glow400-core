package ai.generativearchitects.slay400.emit.descriptor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JSON document into plain Java values: {@link Map} (in document order), {@link List}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}. Only the streaming API of Jackson is used, so nothing about the types
 * of the result can come from the input. The document size, the nesting depth, the number of values and the length of a
 * string are limited, a repeated key is an error, and nothing may follow the document.
 *
 * <p>The descriptor and the files that follow its rules are read from a stream with fixed limits and numbers as Jackson
 * gives them. {@link JsonReader} reads from characters with limits of the caller's choice and exact decimals.
 */
final class JsonTree {

    /** The largest document accepted, in bytes. */
    static final long MAX_DOCUMENT_BYTES = 16L * 1024 * 1024;
    /** The deepest nesting accepted. */
    static final int MAX_DEPTH = 200;
    /** The longest string accepted, in characters. */
    static final int MAX_STRING_LENGTH = 1_000_000;
    /** The longest number accepted, in characters; Jackson applies it to whole numbers, and this class to exact decimals. */
    static final int MAX_NUMBER_LENGTH = 40;

    private static final JsonTree DESCRIPTOR = new JsonTree(
            new JsonReader.Limits(MAX_DOCUMENT_BYTES, MAX_DEPTH, Integer.MAX_VALUE, MAX_STRING_LENGTH), false);

    private final JsonFactory factory;
    private final int maxValues;
    private final boolean exactDecimals;

    JsonTree(JsonReader.Limits limits, boolean exactDecimals) {
        this.factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(limits.maxDocumentLength())
                        .maxNestingDepth(limits.maxDepth())
                        .maxStringLength(limits.maxStringLength())
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .maxNameLength(1000)
                        .build())
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(com.fasterxml.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE)
                .build();
        this.maxValues = limits.maxValues();
        this.exactDecimals = exactDecimals;
    }

    /**
     * @throws IOException if the input cannot be read, is not JSON, breaks a limit or has a repeated key; a
     *                     {@link com.fasterxml.jackson.core.JsonProcessingException} says where
     */
    static Object read(InputStream in) throws IOException {
        return DESCRIPTOR.parse(DESCRIPTOR.factory.createParser(in));
    }

    /**
     * @throws JsonReader.LimitExceededException if a limit is exceeded
     * @throws JsonReader.SyntaxException         if the input is not JSON or has a repeated key
     * @throws IOException                        if the input cannot be read
     */
    Object read(Reader in) throws IOException {
        try {
            return parse(factory.createParser(in));
        } catch (StreamConstraintsException e) {
            throw new JsonReader.LimitExceededException("The document breaks a limit");
        } catch (JsonProcessingException e) {
            // Jackson's message quotes the input, so only the place is kept
            com.fasterxml.jackson.core.JsonLocation at = e.getLocation();
            throw new JsonReader.SyntaxException(at == null ? 0 : Math.max(0, at.getLineNr()), at == null ? 0 : Math.max(0, at.getColumnNr()));
        }
    }

    private Object parse(JsonParser opened) throws IOException {
        try (JsonParser parser = opened) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new com.fasterxml.jackson.core.JsonParseException(parser, "The document is empty");
            }
            Object value = value(parser, first, new int[1]);
            if (parser.nextToken() != null) {
                throw new com.fasterxml.jackson.core.JsonParseException(parser, "Unexpected content after the end of the document");
            }
            return value;
        }
    }

    /** An exact decimal; a number too long is refused before it is converted, which costs time in proportion to its square. */
    private static Object decimal(JsonParser parser) throws IOException {
        if (parser.getTextLength() > MAX_NUMBER_LENGTH) {
            throw new JsonReader.LimitExceededException("The document breaks a limit");
        }
        return parser.getDecimalValue();
    }

    private Object value(JsonParser parser, JsonToken token, int[] values) throws IOException {
        if (++values[0] > maxValues) {
            throw new JsonReader.LimitExceededException("The document has too many values");
        }
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    map.put(name, value(parser, parser.nextToken(), values));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_ARRAY; next = parser.nextToken()) {
                    list.add(value(parser, next, values));
                }
                yield list;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> exactDecimals ? decimal(parser) : parser.getNumberValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new com.fasterxml.jackson.core.JsonParseException(parser, "Unexpected token " + token);
        };
    }
}

package ai.generativearchitects.glow400.mock;

import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a {@code mock/<api>.json} back into the model records. The file is untrusted input: its size, its nesting and the
 * length of a string are bounded by the reader of the descriptor, a repeated key is an error, every key must be one this
 * version knows, and no type is taken from the input. Anything wrong gives one {@code MCK001} diagnostic and no file; the
 * reader never throws for bad input. Everything read gets one position, the file's.
 */
public final class MockReader {

    private MockReader() {
    }

    /** A problem in the document, with the place of it. */
    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bad(String path, String message) {
            super((path.isEmpty() ? "(document)" : path) + ": " + message, null, false, false);
        }
    }

    /**
     * Reads the document; the stream is left open.
     *
     * @param in       the document
     * @param fileName the name used in diagnostics and as the position of what is read
     */
    public static MockReadResult read(InputStream in, String fileName) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(fileName, "fileName");
        SourceLocation here = new SourceLocation(fileName, 1, 1);
        Object tree;
        try {
            tree = Json.read(in);
        } catch (JsonProcessingException e) {
            com.fasterxml.jackson.core.JsonLocation at = e.getLocation();
            SourceLocation where = at == null || at.getLineNr() < 0 ? here : new SourceLocation(fileName, at.getLineNr(), Math.max(0, at.getColumnNr()));
            return failure("The document is not valid JSON: " + e.getOriginalMessage(), where);
        } catch (IOException e) {
            return failure("The document cannot be read: " + e.getClass().getSimpleName(), here);
        }
        try {
            return new MockReadResult(Optional.of(file(tree, here)), List.of());
        } catch (Bad bad) {
            return failure(bad.getMessage(), here);
        }
    }

    private static MockReadResult failure(String message, SourceLocation at) {
        return new MockReadResult(Optional.empty(), List.of(new Diagnostic("MCK001", Severity.ERROR, message, at)));
    }

    // ------------------------------------------------------------------ structure

    private static MockFile file(Object tree, SourceLocation here) {
        Map<String, Object> root = object(tree, "");
        Set<String> taken = new HashSet<>();
        Object version = take(root, "mockSchemaVersion", "", taken);
        if (!(version instanceof Number number) || !MockJson.whole(number)) {
            throw new Bad("mockSchemaVersion", "expected a whole number");
        }
        if (!number.toString().equals(String.valueOf(MockJson.SCHEMA_VERSION))) {
            throw new Bad("mockSchemaVersion", "unsupported mock schema version " + number + "; this reader supports version " + MockJson.SCHEMA_VERSION);
        }
        String api = text(take(root, "api", "", taken), "api");
        if (api.isBlank()) {
            throw new Bad("api", "must not be empty");
        }
        List<Object> items = list(take(root, "operations", "", taken), "operations");
        List<MockDefinition> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String at = "operations[" + i + "]";
            Map<String, Object> operation = object(items.get(i), at);
            Set<String> own = new HashSet<>();
            String id = text(take(operation, "id", at, own), at + ".id");
            if (id.isBlank()) {
                throw new Bad(at + ".id", "must not be empty");
            }
            if (!ids.add(id)) {
                throw new Bad(at + ".id", "another operation has this id");
            }
            List<Object> caseItems = list(take(operation, "cases", at, own), at + ".cases");
            if (caseItems.size() > MockDefinition.MAX_CASES) {
                throw new Bad(at + ".cases", "at most " + MockDefinition.MAX_CASES + " cases");
            }
            List<MockCase> cases = new ArrayList<>();
            for (int c = 0; c < caseItems.size(); c++) {
                cases.add(mockCase(caseItems.get(c), at + ".cases[" + c + "]", here));
            }
            end(operation, at, own);
            definitions.add(new MockDefinition(id, cases, here));
        }
        end(root, "", taken);
        return new MockFile(api, definitions);
    }

    private static MockCase mockCase(Object node, String at, SourceLocation here) {
        Map<String, Object> map = object(node, at);
        Set<String> taken = new HashSet<>();
        Optional<String> name = Optional.empty();
        if (map.containsKey("name")) {
            String text = text(take(map, "name", at, taken), at + ".name");
            if (text.isBlank()) {
                throw new Bad(at + ".name", "must not be empty");
            }
            name = Optional.of(text);
        }
        Map<String, MockValue> when = map.containsKey("when") ? values(take(map, "when", at, taken), at + ".when", here) : Map.of();
        Map<String, MockValue> returns = map.containsKey("returns") ? values(take(map, "returns", at, taken), at + ".returns", here) : Map.of();
        Optional<Integer> delay = Optional.empty();
        if (map.containsKey("delayMs")) {
            Object value = take(map, "delayMs", at, taken);
            if (!(value instanceof Number n) || !MockJson.whole(n) || n.longValue() < 0 || n.longValue() > MockCase.MAX_DELAY_MS || n instanceof BigInteger) {
                throw new Bad(at + ".delayMs", "expected a whole number from 0 to " + MockCase.MAX_DELAY_MS);
            }
            delay = Optional.of(n.intValue());
        }
        Optional<MockFailure> fails = Optional.empty();
        if (map.containsKey("fails")) {
            String text = text(take(map, "fails", at, taken), at + ".fails");
            fails = switch (text) {
                case "unavailable" -> Optional.of(MockFailure.UNAVAILABLE);
                case "timeout" -> Optional.of(MockFailure.TIMEOUT);
                default -> throw new Bad(at + ".fails", "expected one of [unavailable, timeout]");
            };
        }
        if (fails.isPresent() && !returns.isEmpty()) {
            throw new Bad(at, "a case with 'fails' has no 'returns'");
        }
        end(map, at, taken);
        return new MockCase(name, when, returns, delay, fails, here);
    }

    private static Map<String, MockValue> values(Object node, String at, SourceLocation here) {
        Map<String, Object> map = object(node, at);
        Map<String, MockValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            values.put(entry.getKey(), value(entry.getValue(), at + "." + entry.getKey(), here));
        }
        return values;
    }

    private static MockValue value(Object node, String at, SourceLocation here) {
        if (node instanceof String text) {
            return new MockValue.Text(text, here);
        }
        if (node instanceof Boolean flag) {
            return new MockValue.Bool(flag, here);
        }
        if (node instanceof Number number) {
            return MockJson.whole(number) ? new MockValue.Whole(new BigInteger(number.toString()), here) : new MockValue.Real(number.toString(), here);
        }
        if (node instanceof List<?> list) {
            List<MockValue> elements = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                elements.add(value(list.get(i), at + "[" + i + "]", here));
            }
            return new MockValue.Array(elements, here);
        }
        if (node instanceof Map<?, ?>) {
            return new MockValue.Struct(values(node, at, here), here);
        }
        throw new Bad(at, "a value must not be null");
    }

    // ------------------------------------------------------------------ typed access

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object node, String at) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new Bad(at, "expected an object");
        }
        return (Map<String, Object>) map;
    }

    private static List<Object> list(Object node, String at) {
        if (!(node instanceof List<?> list)) {
            throw new Bad(at, "expected an array");
        }
        return new ArrayList<>(list);
    }

    private static String text(Object node, String at) {
        if (!(node instanceof String text)) {
            throw new Bad(at, "expected a string");
        }
        return text;
    }

    private static Object take(Map<String, Object> map, String key, String at, Set<String> taken) {
        if (!map.containsKey(key)) {
            throw new Bad(at, "missing key '" + key + "'");
        }
        taken.add(key);
        return map.get(key);
    }

    private static void end(Map<String, Object> map, String at, Set<String> taken) {
        for (String key : map.keySet()) {
            if (!taken.contains(key)) {
                throw new Bad(at, "unknown key '" + key + "'");
            }
        }
    }
}

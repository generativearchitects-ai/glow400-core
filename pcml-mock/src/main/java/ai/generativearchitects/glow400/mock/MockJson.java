package ai.generativearchitects.glow400.mock;

import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The tree of {@code mock/<api>.json}: what the emitter writes and the reader reads. */
final class MockJson {

    /** The version of the file this code writes and reads. */
    static final int SCHEMA_VERSION = 1;

    private MockJson() {
    }

    static Map<String, Object> tree(String api, List<MockDefinition> definitions) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mockSchemaVersion", SCHEMA_VERSION);
        root.put("api", api);
        List<Object> operations = new ArrayList<>();
        for (MockDefinition definition : definitions) {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("id", definition.operationId());
            List<Object> cases = new ArrayList<>();
            definition.cases().forEach(c -> cases.add(tree(c)));
            operation.put("cases", cases);
            operations.add(operation);
        }
        root.put("operations", operations);
        return root;
    }

    private static Map<String, Object> tree(MockCase mockCase) {
        Map<String, Object> out = new LinkedHashMap<>();
        mockCase.name().ifPresent(name -> out.put("name", name));
        if (!mockCase.when().isEmpty()) {
            out.put("when", values(mockCase.when()));
        }
        if (!mockCase.returns().isEmpty()) {
            out.put("returns", values(mockCase.returns()));
        }
        mockCase.delayMs().ifPresent(delay -> out.put("delayMs", delay));
        mockCase.fails().ifPresent(fails -> out.put("fails", fails.name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    private static Map<String, Object> values(Map<String, MockValue> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((name, value) -> out.put(name, value(value)));
        return out;
    }

    private static Object value(MockValue value) {
        return switch (value) {
            case MockValue.Text text -> text.value();
            case MockValue.Whole whole -> whole.value().bitLength() < 64 ? (Object) whole.value().longValueExact() : (Object) whole.value();
            case MockValue.Bool bool -> bool.value();
            case MockValue.Struct struct -> values(struct.members());
            case MockValue.Array array -> array.elements().stream().map(MockJson::value).toList();
            case MockValue.Real real -> throw new IllegalArgumentException("A number with a fraction is not a valid mock value, so it is not written");
        };
    }

    /** Whether a number written in JSON is a whole number. */
    static boolean whole(Number number) {
        return number instanceof Integer || number instanceof Long || number instanceof BigInteger || number instanceof Short || number instanceof Byte;
    }
}

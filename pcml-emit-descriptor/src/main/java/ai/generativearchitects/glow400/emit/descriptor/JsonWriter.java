package ai.generativearchitects.glow400.emit.descriptor;

import java.util.List;
import java.util.Map;

/**
 * Writes a tree of {@link Map} (in iteration order), {@link List}, {@link String}, {@link Integer}, {@link Long},
 * {@link Boolean} and {@code null} as JSON: two-space indentation, LF line endings, a final newline, and nothing that
 * depends on the machine or the moment.
 */
final class JsonWriter {

    private final StringBuilder out = new StringBuilder();

    private JsonWriter() {
    }

    /** The JSON text of the tree, ending with a newline. */
    static String write(Object tree) {
        JsonWriter writer = new JsonWriter();
        writer.value(tree, 0);
        writer.out.append('\n');
        return writer.out.toString();
    }

    private void value(Object value, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String text -> string(text);
            case Boolean flag -> out.append(flag);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case Map<?, ?> map -> object(map, depth);
            case List<?> list -> array(list, depth);
            default -> throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        }
    }

    private void object(Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(depth + 1);
            string((String) entry.getKey());
            out.append(": ");
            value(entry.getValue(), depth + 1);
            out.append(++i < map.size() ? ",\n" : "\n");
        }
        indent(depth);
        out.append('}');
    }

    private void array(List<?> list, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(depth + 1);
            value(list.get(i), depth + 1);
            out.append(i + 1 < list.size() ? ",\n" : "\n");
        }
        indent(depth);
        out.append(']');
    }

    private void indent(int depth) {
        out.append("  ".repeat(depth));
    }

    private void string(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    boolean pairStart = Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
                    if (pairStart) {
                        out.append(c).append(text.charAt(++i));
                    } else if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029 || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c)); // lone surrogates cannot be written as UTF-8
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

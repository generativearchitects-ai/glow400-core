package ai.generativearchitects.glow400.emit.openapi;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes a tree of {@link Map} (in iteration order), {@link List}, {@link String}, whole and decimal numbers,
 * {@link Boolean} and {@code null} as block-style YAML: two-space indentation, LF line endings, a final newline.
 *
 * <p>A string is written plain only when it is a plain word that no YAML version reads as anything else; every other
 * string is double-quoted with JSON escapes, which YAML reads the same way. So the text round-trips in YAML 1.1 and 1.2.
 */
final class YamlWriter {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    /** Words that YAML 1.1 reads as booleans or null, and the spellings of 1.2. */
    private static final Set<String> RESERVED = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    private final StringBuilder out = new StringBuilder();

    private YamlWriter() {
    }

    /** The YAML text of the tree, ending with a newline. */
    static String write(Map<String, Object> root) {
        YamlWriter writer = new YamlWriter();
        writer.mapping(root, 0, false);
        return writer.out.toString();
    }

    private void mapping(Map<?, ?> map, int indent, boolean firstInline) {
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(first && firstInline)) {
                out.append(" ".repeat(indent));
            }
            first = false;
            out.append(scalar((String) entry.getKey())).append(':');
            value(entry.getValue(), indent);
        }
    }

    private void sequence(List<?> list, int indent) {
        for (Object item : list) {
            out.append(" ".repeat(indent)).append('-');
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                out.append(' ');
                mapping(map, indent + 2, true);
            } else if (item instanceof List<?>) {
                throw new IllegalArgumentException("A list inside a list is not supported");
            } else {
                inline(item);
            }
        }
    }

    /** What follows {@code key:} in a mapping whose keys are at {@code indent}. */
    private void value(Object value, int indent) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            out.append('\n');
            mapping(map, indent + 2, false);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            out.append('\n');
            sequence(list, indent + 2);
        } else {
            inline(value);
        }
    }

    /** A scalar or an empty collection, written after a space and followed by a newline. */
    private void inline(Object value) {
        out.append(' ');
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> out.append("{}");
            case List<?> list -> out.append("[]");
            case Boolean flag -> out.append(flag);
            case BigDecimal number -> out.append(number.toPlainString());
            case BigInteger number -> out.append(number);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case String text -> out.append(scalar(text));
            default -> throw new IllegalArgumentException("Not a YAML value: " + value.getClass().getName());
        }
        out.append('\n');
    }

    private static String scalar(String text) {
        if (PLAIN.matcher(text).matches() && !RESERVED.contains(text.toLowerCase(Locale.ROOT))) {
            return text;
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    boolean pair = Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
                    if (pair) {
                        quoted.append(c).append(text.charAt(++i));
                    } else if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029 || Character.isSurrogate(c)) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}

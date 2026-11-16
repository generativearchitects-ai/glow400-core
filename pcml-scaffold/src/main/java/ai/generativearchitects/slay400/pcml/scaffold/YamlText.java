package ai.generativearchitects.slay400.pcml.scaffold;

import java.util.Set;
import java.util.regex.Pattern;

/** Writing a value into YAML so that the loader reads back exactly the same text. */
final class YamlText {

    /** Plain text that no YAML reader takes for anything but a string: it starts with a letter or an underscore. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*([.-][A-Za-z0-9_]+)*");
    /** The words of the YAML core schema that are not strings, in any of the spellings it accepts. */
    private static final Set<String> RESERVED = Set.of("null", "true", "false");

    private YamlText() {
    }

    /** The value as a plain scalar if that is safe, otherwise as a double-quoted one. */
    static String scalar(String value) {
        if (PLAIN.matcher(value).matches() && !RESERVED.contains(value.toLowerCase(java.util.Locale.ROOT))) {
            return value;
        }
        StringBuilder out = new StringBuilder("\"");
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (cp < 0x20 || cp == 0x7f || cp == 0x85 || cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF
                            || Character.getType(cp) == Character.SURROGATE) {
                        out.append(String.format("\\u%04x", cp));
                    } else {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.append('"').toString();
    }
}

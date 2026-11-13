package ai.generativearchitects.glow400.emit.descriptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A JSON object being read: every key must be asked for, and none may be left over. Each accessor says what it
 * expects; a missing key, a value of another type and an unknown key are all {@code DSC003}.
 */
final class Obj {

    private final Map<String, Object> map;
    private final String path;
    private final Set<String> taken = new HashSet<>();

    private Obj(Map<String, Object> map, String path) {
        this.map = map;
        this.path = path;
    }

    @SuppressWarnings("unchecked")
    static Obj of(Object node, String path) {
        if (!(node instanceof Map<?, ?> map)) {
            throw Fail.structure(path, "expected an object");
        }
        return new Obj((Map<String, Object>) map, path);
    }

    String path() {
        return path;
    }

    /** The place of a key, for messages. */
    String at(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private Object take(String key) {
        if (!map.containsKey(key)) {
            throw Fail.structure(path, "missing key '" + key + "'");
        }
        taken.add(key);
        return map.get(key);
    }

    boolean isNull(String key) {
        return take(key) == null;
    }

    String string(String key) {
        Object value = take(key);
        if (!(value instanceof String text)) {
            throw Fail.structure(at(key), "expected a string");
        }
        return text;
    }

    /** A string or {@code null}. */
    String nullableString(String key) {
        Object value = take(key);
        if (value != null && !(value instanceof String)) {
            throw Fail.structure(at(key), "expected a string or null");
        }
        return (String) value;
    }

    int integer(String key) {
        return toInt(take(key), at(key), "a whole number");
    }

    /** A whole number or {@code null}. */
    Integer nullableInteger(String key) {
        Object value = take(key);
        return value == null ? null : toInt(value, at(key), "a whole number or null");
    }

    static int toInt(Object value, String where, String expected) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long number = ((Number) value).longValue();
            if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                return (int) number;
            }
        }
        throw Fail.structure(where, "expected " + expected + " that fits in 32 bits");
    }

    boolean bool(String key) {
        Object value = take(key);
        if (!(value instanceof Boolean flag)) {
            throw Fail.structure(at(key), "expected true or false");
        }
        return flag;
    }

    List<Object> array(String key) {
        Object value = take(key);
        if (!(value instanceof List<?> list)) {
            throw Fail.structure(at(key), "expected an array");
        }
        return new ArrayList<>(list);
    }

    /** An array or {@code null}. */
    List<Object> nullableArray(String key) {
        Object value = take(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw Fail.structure(at(key), "expected an array or null");
        }
        return new ArrayList<>(list);
    }

    List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        List<Object> items = array(key);
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof String text)) {
                throw Fail.structure(at(key) + "[" + i + "]", "expected a string");
            }
            out.add(text);
        }
        return out;
    }

    Obj object(String key) {
        return of(take(key), at(key));
    }

    /** An object or {@code null}. */
    Obj nullableObject(String key) {
        Object value = take(key);
        return value == null ? null : of(value, at(key));
    }

    /** A map of text to text. */
    Map<String, String> stringMap(String key) {
        Obj entries = object(key);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String name : entries.map.keySet()) {
            out.put(name, entries.string(name));
        }
        entries.end();
        return out;
    }

    /** A map of text to whole number. */
    Map<String, Integer> integerMap(String key) {
        Obj entries = object(key);
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String name : entries.map.keySet()) {
            out.put(name, entries.integer(name));
        }
        entries.end();
        return out;
    }

    /** The raw value of a key, for a place that is read by shape (a count or a length). */
    Object raw(String key) {
        return take(key);
    }

    /** A value of an enumeration, written in lower case; {@code names} gives the accepted spellings in order. */
    <E extends Enum<E>> E choice(String key, Class<E> type, java.util.function.Function<E, String> spelling) {
        String text = string(key);
        return parse(text, type, spelling, at(key));
    }

    /** Like {@link #choice} for a key that may be {@code null}. */
    <E extends Enum<E>> E nullableChoice(String key, Class<E> type, java.util.function.Function<E, String> spelling) {
        String text = nullableString(key);
        return text == null ? null : parse(text, type, spelling, at(key));
    }

    private static <E extends Enum<E>> E parse(String text, Class<E> type, java.util.function.Function<E, String> spelling, String where) {
        List<String> accepted = new ArrayList<>();
        for (E candidate : type.getEnumConstants()) {
            if (spelling.apply(candidate).equals(text)) {
                return candidate;
            }
            accepted.add(spelling.apply(candidate));
        }
        throw Fail.structure(where, "expected one of " + accepted);
    }

    /** Fails if the object has a key nobody asked for. */
    void end() {
        for (String key : map.keySet()) {
            if (!taken.contains(key)) {
                throw Fail.structure(path, "unknown key '" + key + "'");
            }
        }
    }
}

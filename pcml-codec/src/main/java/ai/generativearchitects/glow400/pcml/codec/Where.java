package ai.generativearchitects.glow400.pcml.codec;

import java.util.ArrayList;
import java.util.List;

/** Where a value is: its path in PCML names and in API names. */
record Where(String fieldPath, String exposedPath) {

    static final Where ROOT = new Where("", "");

    Where member(String field, String exposed) {
        return new Where(join(fieldPath, field), join(exposedPath, exposed));
    }

    Where element(int index) {
        return new Where(fieldPath + "[" + index + "]", exposedPath + "[" + index + "]");
    }

    /** A field of a struct that the struct does not have: it has no PCML path, only the path it was given at. */
    Where unknown(String exposed) {
        return new Where(fieldPath, join(exposedPath, exposed));
    }

    private static String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "." + name;
    }
}

/** Collects the problems found while encoding or decoding. */
final class Problems {

    private final List<ValueProblem> found = new ArrayList<>();

    void add(String code, Where where, String message) {
        found.add(new ValueProblem(code, where.fieldPath(), where.exposedPath(), message));
    }

    boolean any() {
        return !found.isEmpty();
    }

    int count() {
        return found.size();
    }

    List<ValueProblem> list() {
        return List.copyOf(found);
    }
}

package ai.generativearchitects.slay400.pcml.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a value is: its path in PCML names and in API names. Built as a lazy chain, one cheap link per array
 * element or struct member on the way down; the text itself ({@link #fieldPath()}, {@link #exposedPath()}) is only
 * put together when something actually asks for it, which is only when a problem is reported for that path
 * (kickoff 14, Part D: no path text for the declared size of an array nobody ever reports a problem for).
 */
final class Where {

    static final Where ROOT = new Where(null, Kind.ROOT, null, null, 0);

    private enum Kind { ROOT, LITERAL, MEMBER, ELEMENT, UNKNOWN }

    private final Where parent;
    private final Kind kind;
    private final String field;
    private final String exposed;
    private final int index;

    private Where(Where parent, Kind kind, String field, String exposed, int index) {
        this.parent = parent;
        this.kind = kind;
        this.field = field;
        this.exposed = exposed;
        this.index = index;
    }

    /**
     * A field path already known as text, such as a dotted overlay path read from {@code error}; there is no
     * matching exposed name at this point, so {@link #exposedPath()} stays empty, same as {@link #ROOT}'s.
     */
    static Where literal(String fieldPath) {
        return new Where(null, Kind.LITERAL, fieldPath, null, 0);
    }

    Where member(String field, String exposed) {
        return new Where(this, Kind.MEMBER, field, exposed, 0);
    }

    Where element(int index) {
        return new Where(this, Kind.ELEMENT, null, null, index);
    }

    /** A field of a struct that the struct does not have: it has no PCML path, only the path it was given at. */
    Where unknown(String exposed) {
        return new Where(this, Kind.UNKNOWN, null, exposed, 0);
    }

    String fieldPath() {
        StringBuilder out = new StringBuilder();
        buildField(out);
        return out.toString();
    }

    String exposedPath() {
        StringBuilder out = new StringBuilder();
        buildExposed(out);
        return out.toString();
    }

    private void buildField(StringBuilder out) {
        if (kind == Kind.ROOT) {
            return;
        }
        if (kind == Kind.LITERAL) {
            out.append(field);
            return;
        }
        parent.buildField(out);
        switch (kind) {
            case ELEMENT -> out.append('[').append(index).append(']');
            case MEMBER -> {
                if (out.length() > 0) {
                    out.append('.');
                }
                out.append(field);
            }
            case UNKNOWN -> { }
            default -> throw new AssertionError(kind);
        }
    }

    private void buildExposed(StringBuilder out) {
        if (kind == Kind.ROOT || kind == Kind.LITERAL) {
            return;
        }
        parent.buildExposed(out);
        switch (kind) {
            case ELEMENT -> out.append('[').append(index).append(']');
            case MEMBER, UNKNOWN -> {
                if (out.length() > 0) {
                    out.append('.');
                }
                out.append(exposed);
            }
            default -> throw new AssertionError(kind);
        }
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

    List<ValueProblem> list() {
        return List.copyOf(found);
    }
}

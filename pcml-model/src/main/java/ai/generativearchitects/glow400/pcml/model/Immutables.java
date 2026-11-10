package ai.generativearchitects.glow400.pcml.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Defensive copies that preserve insertion order and reject nulls. */
final class Immutables {

    private Immutables() {
    }

    static <V> Map<String, V> orderedCopy(Map<String, V> source, String what) {
        Objects.requireNonNull(source, what);
        Map<String, V> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, what + " key"),
                Objects.requireNonNull(value, what + " value")));
        return Collections.unmodifiableMap(copy);
    }
}

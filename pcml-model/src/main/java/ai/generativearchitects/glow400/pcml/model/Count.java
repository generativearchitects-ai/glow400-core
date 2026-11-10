package ai.generativearchitects.glow400.pcml.model;

import java.util.Objects;

/**
 * The PCML {@code count} attribute: how many times a field is repeated.
 */
public sealed interface Count {

    /**
     * No {@code count} attribute: the field is a single value.
     */
    record None() implements Count {
    }

    /**
     * A numeric {@code count}: a fixed number of elements.
     *
     * @param value number of elements; not negative
     */
    record Fixed(int value) implements Count {
        /**
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public Fixed {
            if (value < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }
    }

    /**
     * A {@code count} that names another field, whose value gives the number of elements at run time.
     * The reference is kept as written in the PCML.
     *
     * @param field name or dotted path of the referenced field; never blank
     */
    record Ref(String field) implements Count {
        /**
         * @throws NullPointerException     if {@code field} is {@code null}
         * @throws IllegalArgumentException if {@code field} is blank
         */
        public Ref {
            Objects.requireNonNull(field, "field");
            if (field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
        }
    }
}

package ai.generativearchitects.glow400.pcml.model;

import java.util.Objects;

/**
 * The PCML {@code length} attribute of a {@code char}, {@code byte} or {@code varchar} field:
 * a number, or the name of another field that holds the length.
 */
public sealed interface Length {

    /**
     * A numeric {@code length}.
     *
     * @param value length in characters or bytes; not negative
     */
    record Fixed(int value) implements Length {
        /**
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public Fixed {
            if (value < 0) {
                throw new IllegalArgumentException("length must not be negative");
            }
        }
    }

    /**
     * A {@code length} that names another field. The reference is kept as written in the PCML.
     *
     * @param field name or dotted path of the referenced field; never blank
     */
    record Ref(String field) implements Length {
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

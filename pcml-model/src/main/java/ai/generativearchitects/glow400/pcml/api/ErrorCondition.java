package ai.generativearchitects.glow400.pcml.api;

import java.util.List;
import java.util.Objects;

/** The {@code when} part of the overlay's {@code error} block: when does the program report a business error. */
public sealed interface ErrorCondition {

    /** Path of the output field that is tested. */
    String field();

    /**
     * The field equals a value.
     *
     * @param field path of the field
     * @param value expected text
     */
    record Equals(String field, String value) implements ErrorCondition {
        /** @throws NullPointerException if an argument is {@code null} */
        public Equals {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * The field differs from a value.
     *
     * @param field path of the field
     * @param value text that means "no error"
     */
    record NotEquals(String field, String value) implements ErrorCondition {
        /** @throws NullPointerException if an argument is {@code null} */
        public NotEquals {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * The field is one of several values.
     *
     * @param field  path of the field
     * @param values accepted texts; not empty
     */
    record In(String field, List<String> values) implements ErrorCondition {
        /**
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code values} is empty
         */
        public In {
            Objects.requireNonNull(field, "field");
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("values must not be empty");
            }
        }
    }
}

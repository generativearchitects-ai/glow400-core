package ai.generativearchitects.glow400.pcml.api;

import java.util.Objects;

/** How an output array is shortened: the {@code trim} setting of the overlay. */
public sealed interface Trim {

    /**
     * Keep as many elements as another field says.
     *
     * @param path path of the scalar field that holds the count, relative to the program parameters
     */
    record CountField(String path) implements Trim {
        /**
         * @throws NullPointerException     if {@code path} is {@code null}
         * @throws IllegalArgumentException if {@code path} is blank
         */
        public CountField {
            Objects.requireNonNull(path, "path");
            if (path.isBlank()) {
                throw new IllegalArgumentException("path must not be blank");
            }
        }
    }

    /** Drop trailing elements that are entirely blank. */
    record DropBlank() implements Trim {
    }
}

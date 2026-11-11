package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.Objects;

/**
 * A value read from an overlay file together with the position it was read from, so that later steps can
 * report problems at the exact line and column.
 *
 * @param value the value; never {@code null}
 * @param at    position of the entry in the overlay file
 * @param <T>   type of the value
 */
public record Located<T>(T value, SourceLocation at) {

    /** @throws NullPointerException if an argument is {@code null} */
    public Located {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(at, "at");
    }
}

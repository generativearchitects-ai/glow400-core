package ai.generativearchitects.glow400.pcml.model;

import java.util.Objects;

/**
 * Position of a PCML element in its source file: the point where the element's start tag begins.
 *
 * <p>Lines and columns are 1-based. The value {@code 0} means "unknown" (for example when an
 * XML parser cannot tell where an error occurred).
 *
 * @param file   name of the source, as given to the parser; never {@code null}
 * @param line   1-based line, or 0 if unknown
 * @param column 1-based column, or 0 if unknown
 */
public record SourceLocation(String file, int line, int column) {

    /**
     * Creates a location.
     *
     * @throws NullPointerException     if {@code file} is {@code null}
     * @throws IllegalArgumentException if {@code line} or {@code column} is negative
     */
    public SourceLocation {
        Objects.requireNonNull(file, "file");
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("line and column must not be negative");
        }
    }

    @Override
    public String toString() {
        return file + ":" + line + ":" + column;
    }
}

package ai.generativearchitects.glow400.pcml.api;

import java.util.List;
import java.util.Objects;

/**
 * An API: one overlay file, that is one folder under {@code apis/}, with one {@code version} and one or more operations.
 *
 * @param name        the folder name, lower-cased; matches {@code [a-z0-9][a-z0-9._-]*}
 * @param title       the overlay's {@code title}, or the name when it has none
 * @param version     the overlay's {@code version}
 * @param overlayPath the overlay file, relative to the repository root, with {@code /} separators
 * @param operations  the operations of the file, in file order; not empty
 */
public record ApiDefinition(String name, String title, String version, String overlayPath, List<ApiOperation> operations) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if a text is blank or there is no operation
     */
    public ApiDefinition {
        requireNotBlank(name, "name");
        requireNotBlank(title, "title");
        requireNotBlank(version, "version");
        Objects.requireNonNull(overlayPath, "overlayPath");
        operations = List.copyOf(operations);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations must not be empty");
        }
    }

    private static void requireNotBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

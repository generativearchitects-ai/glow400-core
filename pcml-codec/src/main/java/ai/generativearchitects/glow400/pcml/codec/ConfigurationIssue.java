package ai.generativearchitects.glow400.pcml.codec;

import java.util.Objects;

/**
 * Something in an operation's configuration that makes it impossible to encode or decode, found by
 * {@link OperationCodec#check}. The message names the field and the rule, never a value.
 *
 * @param fieldPath path of the binding the issue is about, or empty if it concerns the operation as a whole
 * @param message   what is wrong
 */
public record ConfigurationIssue(String fieldPath, String message) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ConfigurationIssue {
        Objects.requireNonNull(fieldPath, "fieldPath");
        Objects.requireNonNull(message, "message");
    }
}

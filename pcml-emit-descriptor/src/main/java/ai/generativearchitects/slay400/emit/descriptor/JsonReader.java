package ai.generativearchitects.slay400.emit.descriptor;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/**
 * Reads untrusted JSON with limits that the caller sets, for a document that arrives over a network: an HTTP request body.
 * It follows the rules of {@link Json#read}: only the streaming API of Jackson is used, so nothing about the types of the
 * result can come from the input; a repeated key is an error; nothing may follow the document. The tree is made of
 * {@link java.util.Map} (in document order), {@link java.util.List}, {@link String}, {@link Boolean}, {@code null} and
 * numbers, and a number is a {@link java.math.BigInteger}, {@link Integer} or {@link Long} when it has no fraction and no
 * exponent, and an exact {@link java.math.BigDecimal} otherwise, so that no digit is lost.
 *
 * <p>The input is characters, so that the caller decides the encoding and how an encoding error is reported. A reader is
 * immutable and can be shared by threads.
 */
public final class JsonReader {

    /**
     * What a document may be.
     *
     * @param maxDocumentLength the most characters in the document
     * @param maxDepth          the deepest nesting of objects and arrays
     * @param maxValues         the most values (scalars, objects and arrays) in the document
     * @param maxStringLength   the most characters in one string
     */
    public record Limits(long maxDocumentLength, int maxDepth, int maxValues, int maxStringLength) {

        /** @throws IllegalArgumentException if a limit is not positive */
        public Limits {
            if (maxDocumentLength < 1 || maxDepth < 1 || maxValues < 1 || maxStringLength < 1) {
                throw new IllegalArgumentException("every limit must be positive");
            }
        }
    }

    /** A limit was exceeded: the document is too large, too deep, has too many values, or a string or a number is too long. */
    public static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        LimitExceededException(String message) {
            super(message, null);
        }
    }

    /**
     * The document is not JSON, has a repeated key, or has content after the end. The message is fixed and never contains the
     * input (Jackson's own messages quote it); the place is given as numbers.
     */
    public static final class SyntaxException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int line;
        private final int column;

        SyntaxException(int line, int column) {
            super("The document is not valid JSON", null);
            this.line = line;
            this.column = column;
        }

        /** The line of the problem, counted from 1; 0 if it is not known. */
        public int line() {
            return line;
        }

        /** The column of the problem, counted from 1; 0 if it is not known. */
        public int column() {
            return column;
        }
    }

    private final JsonTree tree;

    private JsonReader(JsonTree tree) {
        this.tree = tree;
    }

    /**
     * A reader with these limits.
     *
     * @throws NullPointerException if {@code limits} is {@code null}
     */
    public static JsonReader of(Limits limits) {
        return new JsonReader(new JsonTree(Objects.requireNonNull(limits, "limits"), true));
    }

    /**
     * Reads one JSON document. The reader is left open.
     *
     * @throws LimitExceededException if a limit is exceeded
     * @throws SyntaxException        if the input is not JSON, has a repeated key, or has content after the document
     * @throws IOException            if the input cannot be read, for example because of an encoding error that the reader
     *                                reports
     */
    public Object read(Reader in) throws IOException {
        Objects.requireNonNull(in, "in");
        return tree.read(in);
    }
}

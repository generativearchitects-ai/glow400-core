package ai.generativearchitects.glow400.emit.descriptor;

import java.io.IOException;
import java.io.InputStream;

/**
 * The JSON reading and writing of the descriptor, for other files that follow the same rules, such as the manifest of a
 * bundle. Trees are made of {@link java.util.Map} (in document order), {@link java.util.List}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}.
 */
public final class Json {

    private Json() {
    }

    /**
     * The JSON text of a tree: two-space indentation, LF line endings, a final newline, keys in the order of the maps. Maps,
     * lists, strings, {@code Integer}, {@code Long}, {@code Boolean} and {@code null} are accepted.
     *
     * @throws IllegalArgumentException for any other value
     */
    public static String write(Object tree) {
        return JsonWriter.write(tree);
    }

    /**
     * Reads one JSON document with the limits of the descriptor: its size, its nesting depth and the length of a string are
     * bounded, a repeated key is an error, and nothing may follow the document. The stream is left open.
     *
     * @throws IOException if the input cannot be read, is not JSON, breaks a limit or has a repeated key
     */
    public static Object read(InputStream in) throws IOException {
        return JsonTree.read(in);
    }
}

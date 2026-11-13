package ai.generativearchitects.glow400.pcml.api;

import java.util.Objects;

/**
 * One file written by an {@link Emitter}.
 *
 * @param path    relative to the output root, with {@code /} separators, no leading slash and no {@code ..}
 * @param content the bytes
 */
public record EmittedFile(String path, byte[] content) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the path is empty, absolute, has a backslash or a {@code ..} segment
     */
    public EmittedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || java.util.Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("path must be relative with '/' separators and no '..': " + path);
        }
        content = content.clone();
    }

    /** A copy of the content, since arrays are mutable. */
    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EmittedFile file && path.equals(file.path) && java.util.Arrays.equals(content, file.content);
    }

    @Override
    public int hashCode() {
        return 31 * path.hashCode() + java.util.Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return "EmittedFile[" + path + ", " + content.length + " bytes]";
    }
}

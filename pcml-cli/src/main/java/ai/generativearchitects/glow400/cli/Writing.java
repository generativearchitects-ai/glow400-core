package ai.generativearchitects.glow400.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where files may be written, and why a write failed, in words that name no content and no machine. */
final class Writing {

    private Writing() {
    }

    /**
     * The path as it is on disk, for comparisons: the real path of the deepest part that exists, with the part that does not
     * exist yet added unchanged. Symbolic links on the way are followed; a link that leads nowhere is an
     * {@link java.nio.file.NoSuchFileException}, since writing through it would create a file at a place nobody looked at.
     */
    static Path physical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent(); // the root of the file system is always there, so this ends
        }
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    /** A short reason for an I/O failure. */
    static String reason(IOException e) {
        return ai.generativearchitects.glow400.pcml.diagnostics.IoReason.of(e);
    }
}

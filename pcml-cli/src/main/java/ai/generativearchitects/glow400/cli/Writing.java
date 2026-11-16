package ai.generativearchitects.glow400.cli;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/** Where files may be written, and why a write failed, in words that name no content and no machine. */
final class Writing {

    /** What the name of a temporary file starts with. Neutral on purpose: it carries no product name. */
    static final String TEMP_PREFIX = ".write-";

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

    /** The temporary file written before it is moved over {@code target}: a neutral name, in the same folder, never the same twice. */
    static Path temporaryFor(Path target) {
        return target.resolveSibling(TEMP_PREFIX + UUID.randomUUID() + ".tmp");
    }

    /**
     * Writes a file that does not exist yet: to a temporary file in the same folder, which is then moved into place, so that no
     * reader sees half a file. Nothing is overwritten.
     *
     * @return {@code false} if something was already there; the file is then left alone
     */
    static boolean createNew(Path target, byte[] content) throws IOException {
        Path temp = temporaryFor(target);
        try {
            Files.write(temp, content, StandardOpenOption.CREATE_NEW);
            Files.move(temp, target); // without REPLACE_EXISTING: fails if the target has appeared in the meantime
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Replaces the content of a file that exists, through a temporary file and a move; the permissions of the file are kept. */
    static void replace(Path target, byte[] content) throws IOException {
        Path temp = temporaryFor(target);
        try {
            Files.write(temp, content, StandardOpenOption.CREATE_NEW);
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
                Files.setPosixFilePermissions(temp, permissions);
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system: the permissions are what the system gives a new file
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

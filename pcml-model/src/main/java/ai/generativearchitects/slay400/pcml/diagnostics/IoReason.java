package ai.generativearchitects.slay400.pcml.diagnostics;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.Locale;

/**
 * Why an I/O operation failed, in a few words that name no path and no content. The message of an {@link IOException}
 * usually holds the absolute path of the build machine, which a diagnostic must not carry.
 */
public final class IoReason {

    private IoReason() {
    }

    /** A short reason for the failure. */
    public static String of(IOException e) {
        if (e instanceof AccessDeniedException) {
            return "access denied";
        }
        if (e instanceof NoSuchFileException) {
            return "no such file or folder";
        }
        if (e instanceof NotDirectoryException) {
            return "not a folder";
        }
        if (e instanceof DirectoryNotEmptyException) {
            return "folder is not empty";
        }
        if (e instanceof FileAlreadyExistsException) {
            return "already exists";
        }
        if (e instanceof FileSystemException fse && fse.getReason() != null) {
            return fse.getReason().toLowerCase(Locale.ROOT);
        }
        return e.getClass().getSimpleName();
    }
}

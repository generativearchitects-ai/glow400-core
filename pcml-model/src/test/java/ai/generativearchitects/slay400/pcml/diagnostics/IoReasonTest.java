package ai.generativearchitects.slay400.pcml.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import org.junit.jupiter.api.Test;

/** The words for an I/O failure: the kind of failure, never a path. */
class IoReasonTest {

    @Test
    void theReasonNamesTheKindOfFailureAndNothingElse() {
        assertThat(IoReason.of(new AccessDeniedException("/secret/path"))).isEqualTo("access denied");
        assertThat(IoReason.of(new NoSuchFileException("/secret/path"))).isEqualTo("no such file or folder");
        assertThat(IoReason.of(new NotDirectoryException("/secret/path"))).isEqualTo("not a folder");
        assertThat(IoReason.of(new DirectoryNotEmptyException("/secret/path"))).isEqualTo("folder is not empty");
        assertThat(IoReason.of(new FileAlreadyExistsException("/secret/path"))).isEqualTo("already exists");
        assertThat(IoReason.of(new FileSystemException("/secret/path", null, "Read-only file system"))).isEqualTo("read-only file system");
        assertThat(IoReason.of(new FileSystemException("/secret/path"))).isEqualTo("FileSystemException");
        assertThat(IoReason.of(new IOException("boom: /secret/path"))).isEqualTo("IOException");
    }
}

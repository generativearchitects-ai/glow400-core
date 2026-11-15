package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The words for an I/O failure, and where a path really is. */
class WritingTest {

    @Test
    void theReasonNamesTheKindOfFailureAndNothingElse() {
        assertThat(Writing.reason(new AccessDeniedException("/secret/path"))).isEqualTo("access denied");
        assertThat(Writing.reason(new NoSuchFileException("/secret/path"))).isEqualTo("no such file or folder");
        assertThat(Writing.reason(new NotDirectoryException("/secret/path"))).isEqualTo("not a folder");
        assertThat(Writing.reason(new DirectoryNotEmptyException("/secret/path"))).isEqualTo("folder is not empty");
        assertThat(Writing.reason(new FileAlreadyExistsException("/secret/path"))).isEqualTo("already exists");
        assertThat(Writing.reason(new FileSystemException("/secret/path", null, "Read-only file system"))).isEqualTo("read-only file system");
        assertThat(Writing.reason(new FileSystemException("/secret/path"))).isEqualTo("FileSystemException");
        assertThat(Writing.reason(new IOException("boom: /secret/path"))).isEqualTo("IOException");
    }

    @Test
    void aPhysicalPathFollowsLinksAndKeepsWhatDoesNotExistYet(@TempDir Path dir) throws IOException {
        Path real = Files.createDirectories(dir.resolve("real")).toRealPath();
        Files.createSymbolicLink(dir.resolve("link"), real);
        assertThat(Writing.physical(dir.resolve("link/new/file.txt"))).isEqualTo(real.resolve("new/file.txt"));
        assertThat(Writing.physical(dir.resolve("real/./x/../y"))).isEqualTo(real.resolve("y"));
        Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("nowhere"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Writing.physical(dir.resolve("dangling/x"))).isInstanceOf(NoSuchFileException.class);
    }
}

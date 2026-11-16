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

    @Test
    void theTemporaryFileHasANeutralNameInTheSameFolderAndNeverTheSameTwice(@TempDir Path dir) {
        Path target = dir.resolve("sub/api.yaml");
        Path one = Writing.temporaryFor(target);
        Path two = Writing.temporaryFor(target);
        assertThat(Writing.TEMP_PREFIX).isEqualTo(".write-").doesNotContainIgnoringCase("glow");
        assertThat(one.getParent()).isEqualTo(target.getParent());
        assertThat(one.getFileName().toString()).startsWith(".write-").endsWith(".tmp").doesNotContainIgnoringCase("glow");
        assertThat(one).isNotEqualTo(two);
        assertThat(one.getFileName().toString().length()).isGreaterThan(".write-.tmp".length() + 30);
    }

    @Test
    void aNewFileIsWrittenThroughATemporaryFileThatIsNotLeftBehind(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("new.yaml");
        assertThat(Writing.createNew(target, "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isTrue();
        assertThat(Files.readString(target)).isEqualTo("abc");
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("new.yaml");
        }
    }

    @Test
    void aNewFileNeverReplacesWhatIsThere(@TempDir Path dir) throws IOException {
        Path target = Files.writeString(dir.resolve("a.yaml"), "mine");
        assertThat(Writing.createNew(target, "other".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(Files.readString(target)).isEqualTo("mine");
        Files.createSymbolicLink(dir.resolve("link.yaml"), dir.resolve("nowhere.yaml"));
        assertThat(Writing.createNew(dir.resolve("link.yaml"), new byte[0])).isFalse();
        assertThat(dir.resolve("nowhere.yaml")).doesNotExist();
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactlyInAnyOrder("a.yaml", "link.yaml");
        }
    }

    @Test
    void aNewFileThatCannotBeWrittenLeavesNothingBehind(@TempDir Path dir) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Writing.createNew(dir.resolve("no/such/folder/a.yaml"), new byte[0]))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aFileIsReplacedThroughATemporaryFileAndKeepsItsPermissions(@TempDir Path dir) throws IOException {
        Path target = Files.writeString(dir.resolve("a.yaml"), "old");
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        Writing.replace(target, "new".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("a.yaml");
        }
    }

    @Test
    void aFileThatCannotBeReplacedIsLeftAsItWasAndNoTemporaryFileRemains(@TempDir Path dir) throws IOException {
        Path target = Files.createDirectories(dir.resolve("folder"));
        Files.writeString(target.resolve("inside"), "x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Writing.replace(target, "new".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("folder");
        }
    }
}

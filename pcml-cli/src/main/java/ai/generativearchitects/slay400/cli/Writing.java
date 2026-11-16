package ai.generativearchitects.slay400.cli;

import java.io.IOException;
import java.nio.file.Path;

/** Where files may be written, and why a write failed: the helpers of the tasks, under the names the commands use. */
final class Writing {

    /** What the name of a temporary file starts with. */
    static final String TEMP_PREFIX = ai.generativearchitects.slay400.tasks.Writing.TEMP_PREFIX;

    private Writing() {
    }

    static Path physical(Path path) throws IOException {
        return ai.generativearchitects.slay400.tasks.Writing.physical(path);
    }

    static String reason(IOException e) {
        return ai.generativearchitects.slay400.tasks.Writing.reason(e);
    }

    static Path temporaryFor(Path target) {
        return ai.generativearchitects.slay400.tasks.Writing.temporaryFor(target);
    }

    static boolean createNew(Path target, byte[] content) throws IOException {
        return ai.generativearchitects.slay400.tasks.Writing.createNew(target, content);
    }

    static void replace(Path target, byte[] content) throws IOException {
        ai.generativearchitects.slay400.tasks.Writing.replace(target, content);
    }
}

package ai.generativearchitects.slay400.tasks;

import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.EmitResult;
import ai.generativearchitects.slay400.pcml.api.EmittedFile;
import ai.generativearchitects.slay400.pcml.api.Emitter;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.util.List;

/** Emitters that only the tests register, to reach the paths a real emitter never takes. */
final class TestEmitters {

    private TestEmitters() {
    }

    /** Writes a path the descriptor emitter writes too. */
    public static final class Clash implements Emitter {
        @Override
        public String name() {
            return "clash";
        }

        @Override
        public EmitResult emit(ApiRepositoryModel model) {
            return new EmitResult(List.of(new EmittedFile("descriptor.json", new byte[] {1})), List.of());
        }
    }

    /** Reports an error, and still offers a file. */
    public static final class Broken implements Emitter {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public EmitResult emit(ApiRepositoryModel model) {
            return new EmitResult(List.of(new EmittedFile("broken.txt", new byte[] {1})),
                    List.of(new Diagnostic("EMT001", Severity.ERROR, "The broken emitter always fails", new SourceLocation("apis/X/api.yaml", 3, 1))));
        }
    }

    /** Writes below a folder that a test makes a symbolic link. */
    public static final class Linked implements Emitter {
        @Override
        public String name() {
            return "linked";
        }

        @Override
        public EmitResult emit(ApiRepositoryModel model) {
            return new EmitResult(List.of(new EmittedFile("linked/inside.txt", new byte[] {1})), List.of());
        }
    }
}

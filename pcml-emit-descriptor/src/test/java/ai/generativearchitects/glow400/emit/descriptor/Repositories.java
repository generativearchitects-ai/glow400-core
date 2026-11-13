package ai.generativearchitects.glow400.emit.descriptor;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import ai.generativearchitects.glow400.pcml.validation.Validator;
import java.util.ArrayList;
import java.util.List;

/** The valid fixture repositories of kickoff 02, loaded and validated. */
final class Repositories {

    /** Repositories that load and validate without an error. */
    static final List<String> VALID = List.of("action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink",
            "inputoutput", "minimal", "operations-list", "structs");

    private Repositories() {
    }

    static ApiRepositoryModel model(String name) {
        RepositoryResult result = OverlayLoader.loadRepository(Fixtures.path(name));
        List<Diagnostic> all = new ArrayList<>(result.diagnostics());
        result.model().ifPresent(model -> all.addAll(Validator.validate(model)));
        if (all.stream().anyMatch(d -> d.severity() == ai.generativearchitects.glow400.pcml.diagnostics.Severity.ERROR)) {
            throw new AssertionError("The fixture " + name + " has errors: " + all);
        }
        return result.model().orElseThrow();
    }
}

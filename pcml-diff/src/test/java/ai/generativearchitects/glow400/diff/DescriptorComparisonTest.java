package ai.generativearchitects.glow400.diff;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.emit.descriptor.DescriptorEmitter;
import ai.generativearchitects.glow400.emit.descriptor.DescriptorReader;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A repository compared with itself, and with its own descriptor, has nothing to report. */
class DescriptorComparisonTest {

    private static final List<String> VALID = List.of("action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink",
            "inputoutput", "minimal", "operations-list", "structs");

    @Test
    void aModelComparedWithItselfIsNoneForEveryApi() {
        for (String repository : VALID) {
            ApiRepositoryModel model = Fixtures.model(repository);
            DiffResult result = ApiDiff.compare(model, model);
            assertThat(result.apis()).as(repository).hasSameSizeAs(model.apis());
            assertThat(result.apis()).allSatisfy(set -> {
                assertThat(set.impact()).isEqualTo(Impact.NONE);
                assertThat(set.changes()).isEmpty();
            });
            assertThat(result.diagnostics()).as(repository).isEmpty();
        }
    }

    @Test
    void aModelReadFromItsDescriptorComparedWithTheModelItCameFromIsNoneForEveryApi() {
        for (String repository : VALID) {
            ApiRepositoryModel current = Fixtures.model(repository);
            byte[] descriptor = new DescriptorEmitter().emit(current).files().get(0).content();
            ApiRepositoryModel released = DescriptorReader.read(new ByteArrayInputStream(descriptor)).model().orElseThrow();
            DiffResult result = ApiDiff.compare(released, current);
            assertThat(result.apis()).as(repository).hasSameSizeAs(current.apis());
            assertThat(result.apis()).allSatisfy(set -> {
                assertThat(set.impact()).as(repository + " " + set.api()).isEqualTo(Impact.NONE);
                assertThat(set.changes()).isEmpty();
            });
            assertThat(result.diagnostics()).as(repository).isEmpty();
            // and the other way round
            assertThat(ApiDiff.compare(current, released).diagnostics()).isEmpty();
        }
    }
}

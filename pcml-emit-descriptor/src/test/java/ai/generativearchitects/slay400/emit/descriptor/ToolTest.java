package ai.generativearchitects.slay400.emit.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The tool that names itself in descriptors, bundles and reports. */
class ToolTest {

    @Test
    void theToolHasANameAVersionAndARepository() {
        assertThat(Tool.NAME).isEqualTo("slay400");
        assertThat(Tool.version()).isNotBlank().doesNotContain("${").doesNotContain(" ");
        assertThat(Tool.REPOSITORY_URL).startsWith("https://").endsWith("slay400-core");
    }

    @Test
    void theDescriptorNamesTheToolFromThisOnePlace() {
        var model = Repositories.model("minimal");
        String json = new String(new DescriptorEmitter().emit(model).files().get(0).content(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"generator\": {\n    \"name\": \"" + Tool.NAME + "\",\n    \"version\": \"" + Tool.version() + "\"\n  }");
    }
}

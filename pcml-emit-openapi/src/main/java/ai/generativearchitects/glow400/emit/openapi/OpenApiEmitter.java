package ai.generativearchitects.glow400.emit.openapi;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmitResult;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code openapi} emitter: one OpenAPI 3.1 document per API, {@code openapi/<api name>.yaml}, served under
 * {@code /{tenant}/{environment}}. The same model always gives the same bytes. Internal, injected and hidden fields never
 * appear in a document.
 */
public final class OpenApiEmitter implements Emitter {

    @Override
    public String name() {
        return "openapi";
    }

    @Override
    public EmitResult emit(ApiRepositoryModel model) {
        List<EmittedFile> files = new ArrayList<>();
        for (ApiDefinition api : model.apis()) {
            String yaml = YamlWriter.write(OpenApiBuilder.document(api));
            files.add(new EmittedFile("openapi/" + api.name() + ".yaml", yaml.getBytes(StandardCharsets.UTF_8)));
        }
        return new EmitResult(files, List.of());
    }
}

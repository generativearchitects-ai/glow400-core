package ai.generativearchitects.slay400.mock;

import ai.generativearchitects.slay400.emit.descriptor.Json;
import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.EmitResult;
import ai.generativearchitects.slay400.pcml.api.EmittedFile;
import ai.generativearchitects.slay400.pcml.api.Emitter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code mock} emitter: one {@code mock/<api>.json} for every API that has at least one mock file, with the cases of each
 * mocked operation in file order and {@code mockSchemaVersion} 1. An API without a mock file gets no file. The same model
 * always gives the same bytes. {@link MockReader} reads the file back.
 */
public final class MockEmitter implements Emitter {

    /** The folder of the files this emitter writes. */
    public static final String FOLDER = "mock/";

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public EmitResult emit(ApiRepositoryModel model) {
        List<EmittedFile> files = new ArrayList<>();
        for (ApiDefinition api : model.apis()) {
            if (!api.mocks().isEmpty()) {
                String json = Json.write(MockJson.tree(api.name(), api.mocks()));
                files.add(new EmittedFile(FOLDER + api.name() + ".json", json.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new EmitResult(files, List.of());
    }
}

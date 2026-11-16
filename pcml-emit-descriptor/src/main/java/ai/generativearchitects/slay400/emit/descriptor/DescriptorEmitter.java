package ai.generativearchitects.slay400.emit.descriptor;

import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.EmitResult;
import ai.generativearchitects.slay400.pcml.api.EmittedFile;
import ai.generativearchitects.slay400.pcml.api.Emitter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The {@code descriptor} emitter: one {@code descriptor.json} for the repository, with everything the gateway needs to
 * serve its APIs. The same model always gives the same bytes. The file holds no environment data and no secret:
 * injection expressions are written as expressions.
 */
public final class DescriptorEmitter implements Emitter {

    /** The path of the file this emitter writes. */
    public static final String FILE = "descriptor.json";

    @Override
    public String name() {
        return "descriptor";
    }

    @Override
    public EmitResult emit(ApiRepositoryModel model) {
        String json = JsonWriter.write(DescriptorWriter.tree(model, Tool.version()));
        return new EmitResult(List.of(new EmittedFile(FILE, json.getBytes(StandardCharsets.UTF_8))), List.of());
    }
}

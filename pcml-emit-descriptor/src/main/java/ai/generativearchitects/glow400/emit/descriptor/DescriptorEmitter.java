package ai.generativearchitects.glow400.emit.descriptor;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.EmitResult;
import ai.generativearchitects.glow400.pcml.api.EmittedFile;
import ai.generativearchitects.glow400.pcml.api.Emitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * The {@code descriptor} emitter: one {@code descriptor.json} for the repository, with everything the gateway needs to
 * serve its APIs. The same model always gives the same bytes. The file holds no environment data and no secret:
 * injection expressions are written as expressions.
 */
public final class DescriptorEmitter implements Emitter {

    /** The path of the file this emitter writes. */
    public static final String FILE = "descriptor.json";

    private static final String VERSION = version();

    @Override
    public String name() {
        return "descriptor";
    }

    @Override
    public EmitResult emit(ApiRepositoryModel model) {
        String json = JsonWriter.write(DescriptorWriter.tree(model, VERSION));
        return new EmitResult(List.of(new EmittedFile(FILE, json.getBytes(StandardCharsets.UTF_8))), List.of());
    }

    private static String version() {
        Properties properties = new Properties();
        try (InputStream in = DescriptorEmitter.class.getResourceAsStream("generator.properties")) {
            if (in == null) {
                throw new IllegalStateException("generator.properties is missing from the class path");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }
}

package ai.generativearchitects.glow400.pcml.api;

/**
 * An output of the generator, such as the descriptor or an OpenAPI document. Emitters are discovered with
 * {@link java.util.ServiceLoader}, so that emitters outside this repository plug in the same way.
 *
 * <p>An emitter is pure: it reads no file, no clock, no network and no randomness, and the same model always gives the
 * same bytes. It is only run on a model that has no error from loading and validation, and may assume that.
 */
public interface Emitter {

    /** The name used in {@code gateway.yaml} {@code defaults.emitters}, such as {@code descriptor} or {@code openapi}. */
    String name();

    /** The files for the model, with paths relative to the output root, and any diagnostics. */
    EmitResult emit(ApiRepositoryModel model);
}

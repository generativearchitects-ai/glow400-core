package ai.generativearchitects.glow400.emit.descriptor;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Optional;

/**
 * What {@link DescriptorReader} made of a document: the model when the document is a valid descriptor, and the
 * diagnostics ({@code DSC001}, {@code DSC002}, {@code DSC003}) when it is not.
 *
 * @param model       the model; empty when there is a diagnostic
 * @param diagnostics what is wrong with the document
 */
public record DescriptorResult(Optional<ApiRepositoryModel> model, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public DescriptorResult {
        java.util.Objects.requireNonNull(model, "model");
        diagnostics = List.copyOf(diagnostics);
    }
}

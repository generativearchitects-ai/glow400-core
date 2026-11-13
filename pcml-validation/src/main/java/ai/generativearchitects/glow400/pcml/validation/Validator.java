package ai.generativearchitects.glow400.pcml.validation;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.codec.ConfigurationIssue;
import ai.generativearchitects.glow400.pcml.codec.OperationCodec;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Static validation of an API model: whether each operation can be encoded and decoded at all, and two advisory warnings.
 *
 * <ul>
 *   <li>{@code OVL003} (error): the operation cannot be encoded or decoded as configured. Every condition is found by the
 *       codec's own rules ({@link OperationCodec#check}), so the validator and the codec cannot disagree: an operation
 *       without an OVL003 error never fails in {@link OperationCodec#of}.</li>
 *   <li>{@code OVL005} (warning): a response array with a fixed count of {@code defaults.largeArrayThreshold} or more and
 *       no {@code trim}.</li>
 *   <li>{@code OVL006} (warning): an operation without an {@code error} mapping.</li>
 * </ul>
 *
 * <p>Diagnostics are positioned at the binding's overlay position, or at the operation when the binding was derived from
 * the PCML alone, and are ordered by file, line and column.
 */
public final class Validator {

    private Validator() {
    }

    /**
     * Validates every operation of a model.
     *
     * @param model the API model, as loaded from a customer repository
     * @return the diagnostics, empty if there is nothing to report
     */
    public static List<Diagnostic> validate(ApiRepositoryModel model) {
        Objects.requireNonNull(model, "model");
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ApiOperation operation : model.operations()) {
            for (ConfigurationIssue issue : OperationCodec.check(operation)) {
                diagnostics.add(new Diagnostic("OVL003", Severity.ERROR, describe(operation, issue), positionOf(operation, issue.fieldPath())));
            }
            for (FieldBinding binding : operation.bindings()) {
                if (isLargeResponseArrayWithoutTrim(binding, model.defaults().largeArrayThreshold())) {
                    diagnostics.add(new Diagnostic("OVL005", Severity.WARNING,
                            "Operation " + operation.id() + ", field " + binding.fieldPath() + ": the response array has "
                                    + ((Count.Fixed) binding.field().count()).value() + " elements and no trim, so every call returns all of them; "
                                    + "set 'trim' (at least " + model.defaults().largeArrayThreshold() + " elements is large)",
                            sourceOf(operation, binding)));
                }
            }
            if (operation.errorMapping().isEmpty()) {
                diagnostics.add(new Diagnostic("OVL006", Severity.WARNING,
                        "Operation " + operation.id() + " has no error mapping: a business error reported by the program is returned as a success",
                        operation.source()));
            }
        }
        diagnostics.sort(Comparator
                .comparing((Diagnostic d) -> d.location().file())
                .thenComparingInt(d -> d.location().line())
                .thenComparingInt(d -> d.location().column()));
        return diagnostics;
    }

    private static boolean isLargeResponseArrayWithoutTrim(FieldBinding binding, int threshold) {
        boolean response = binding.direction() == Direction.RESPONSE || binding.direction() == Direction.BOTH;
        return response && binding.trim().isEmpty()
                && binding.field().count() instanceof Count.Fixed fixed && fixed.value() >= threshold;
    }

    private static String describe(ApiOperation operation, ConfigurationIssue issue) {
        return "Operation " + operation.id() + (issue.fieldPath().isEmpty() ? "" : ", field " + issue.fieldPath()) + ": " + issue.message();
    }

    private static SourceLocation positionOf(ApiOperation operation, String fieldPath) {
        return find(operation.bindings(), fieldPath).map(binding -> sourceOf(operation, binding)).orElse(operation.source());
    }

    /** The overlay position of a binding, or the operation's when the binding was derived from the PCML alone. */
    private static SourceLocation sourceOf(ApiOperation operation, FieldBinding binding) {
        return binding.source().file().equals(operation.overlayPath()) ? binding.source() : operation.source();
    }

    private static Optional<FieldBinding> find(List<FieldBinding> bindings, String path) {
        for (FieldBinding binding : bindings) {
            if (binding.fieldPath().equals(path)) {
                return Optional.of(binding);
            }
            if (path.startsWith(binding.fieldPath() + ".")) {
                return find(binding.children(), path);
            }
        }
        return Optional.empty();
    }
}

package ai.generativearchitects.slay400.pcml.validation;

import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.api.MockDefinition;
import ai.generativearchitects.slay400.pcml.codec.ConfigurationIssue;
import ai.generativearchitects.slay400.pcml.codec.MockCodec;
import ai.generativearchitects.slay400.pcml.codec.MockProblem;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
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
 *       no {@code trim}, at any nesting level.</li>
 *   <li>{@code OVL006} (warning): an operation without an {@code error} mapping.</li>
 *   <li>{@code OVL022} (error): a response whose guaranteed size (fixed counts with no {@code trim} anywhere on the way)
 *       is already more than {@code defaults.maxResponseValues}, so it could never be served (kickoff 14, decision 113).</li>
 *   <li>{@code MCK003} (error): a name in a mock that is not a parameter or member of the program, or is on the wrong side.</li>
 *   <li>{@code MCK004} (error): a value in a mock that the codec cannot carry for its field.</li>
 *   <li>{@code MCK005} (warning): a case of a mock that can never match.</li>
 *   <li>{@code MCK006} (warning): a count field that a mock gives and that is not the number of elements it gives for the list.</li>
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
                collectLargeArrayWarnings(operation, binding, model.defaults().largeArrayThreshold(), diagnostics);
            }
            long guaranteed = 0;
            for (FieldBinding binding : operation.bindings()) {
                guaranteed += guaranteedResponseValueCount(binding);
            }
            if (guaranteed > model.defaults().maxResponseValues()) {
                diagnostics.add(new Diagnostic("OVL022", Severity.ERROR,
                        "Operation " + operation.id() + ": the response always has at least " + guaranteed
                                + " values, more than the limit of " + model.defaults().maxResponseValues()
                                + "; set 'trim' on a large array on the way, or raise 'defaults.maxResponseValues'",
                        operation.source()));
            }
            if (operation.errorMapping().isEmpty()) {
                diagnostics.add(new Diagnostic("OVL006", Severity.WARNING,
                        "Operation " + operation.id() + " has no error mapping: a business error reported by the program is returned as a success",
                        operation.source()));
            }
        }
        for (ApiDefinition api : model.apis()) {
            for (MockDefinition mock : api.mocks()) {
                ApiOperation operation = api.operations().stream().filter(o -> o.id().equals(mock.operationId())).findFirst().orElseThrow();
                if (!OperationCodec.check(operation).isEmpty()) {
                    continue; // the operation is reported (OVL003); what a mock may say about it cannot be judged
                }
                for (MockProblem problem : MockCodec.of(operation).check(mock)) {
                    diagnostics.add(new Diagnostic(problem.code(), problem.severity(), problem.message(), problem.at()));
                }
            }
        }
        diagnostics.sort(Comparator
                .comparing((Diagnostic d) -> d.location().file())
                .thenComparingInt(d -> d.location().line())
                .thenComparingInt(d -> d.location().column()));
        return diagnostics;
    }

    /**
     * {@code OVL005}, at any nesting level: a struct member can be a large response array in its own right. Reported
     * at the binding's own position, the member's or its parameter's, whether that is in the overlay or the PCML --
     * unlike {@link #sourceOf}, which some other diagnostics use to fall back to the operation for a binding derived
     * from the PCML alone: here the member itself is a far more useful position to report than the operation header,
     * and, unlike the top-level parameters {@code sourceOf} was written for, nothing else already reports where a
     * struct member with no overlay settings of its own is (review of PR #20, "smaller" #1).
     */
    private static void collectLargeArrayWarnings(ApiOperation operation, FieldBinding binding, int threshold,
                                                   List<Diagnostic> diagnostics) {
        if (isLargeResponseArrayWithoutTrim(binding, threshold)) {
            diagnostics.add(new Diagnostic("OVL005", Severity.WARNING,
                    "Operation " + operation.id() + ", field " + binding.fieldPath() + ": the response array has "
                            + ((Count.Fixed) binding.field().count()).value() + " elements and no trim, so every call returns all of them; "
                            + "set 'trim' (at least " + threshold + " elements is large)",
                    binding.source()));
        }
        for (FieldBinding child : binding.children()) {
            collectLargeArrayWarnings(operation, child, threshold, diagnostics);
        }
    }

    private static boolean isLargeResponseArrayWithoutTrim(FieldBinding binding, int threshold) {
        boolean response = binding.direction() == Direction.RESPONSE || binding.direction() == Direction.BOTH;
        return response && binding.trim().isEmpty()
                && binding.field().count() instanceof Count.Fixed fixed && fixed.value() >= threshold;
    }

    /**
     * {@code OVL022}: the number of scalar values a response is guaranteed to hold on every call, assuming every array
     * that carries a {@code trim} anywhere on the way down to it shrinks to nothing (the opposite, most favourable
     * case for the limit). A {@code Count.Ref} array is bounded by another field at run time, so it is excluded the
     * same way {@link #isLargeResponseArrayWithoutTrim} excludes it from {@code OVL005}.
     */
    private static long guaranteedResponseValueCount(FieldBinding binding) {
        boolean response = binding.direction() == Direction.RESPONSE || binding.direction() == Direction.BOTH;
        // A hidden field is skipped while decoding (FieldPlan.decodeElement), so it never costs a value either.
        if (!response || binding.hidden() || binding.trim().isPresent() || binding.field().count() instanceof Count.Ref) {
            return 0;
        }
        long multiplier = binding.field().count() instanceof Count.Fixed fixed ? fixed.value() : 1;
        if (binding.children().isEmpty()) {
            return multiplier;
        }
        long perElement = 0;
        for (FieldBinding child : binding.children()) {
            perElement += guaranteedResponseValueCount(child);
        }
        return multiplier * perElement;
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

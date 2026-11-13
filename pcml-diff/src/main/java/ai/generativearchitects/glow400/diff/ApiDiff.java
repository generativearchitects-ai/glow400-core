package ai.generativearchitects.glow400.diff;

import ai.generativearchitects.glow400.contract.ApiSurface;
import ai.generativearchitects.glow400.contract.OperationSurface;
import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Compares the released version of a repository with the current one, API by API: what changed for the consumers, whether
 * it breaks them, and whether the version number was raised enough. The comparison uses the
 * {@link ApiSurface surface} of each API, which is exactly what the OpenAPI document shows, plus what a consumer cannot
 * see, to tell an internal change from none.
 */
public final class ApiDiff {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    private ApiDiff() {
    }

    /**
     * Compares two repositories.
     *
     * @param released the repository as released, normally read from its descriptor
     * @param current  the repository as it is now, normally loaded from its overlay files
     * @return the changes of each API in both, and {@code API002} to {@code API006}
     */
    public static DiffResult compare(ApiRepositoryModel released, ApiRepositoryModel current) {
        Objects.requireNonNull(released, "released");
        Objects.requireNonNull(current, "current");
        Map<String, ApiDefinition> before = new LinkedHashMap<>();
        released.apis().forEach(api -> before.put(api.name(), api));
        List<ApiChangeSet> sets = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ApiDefinition now : current.apis()) {
            seen.add(now.name());
            ApiDefinition was = before.get(now.name());
            if (was == null) {
                diagnostics.add(new Diagnostic("API006", Severity.INFO, "API " + now.name() + " is new in version " + now.version(),
                        new SourceLocation(now.overlayPath(), 1, 1)));
                continue;
            }
            Compared compared = api(was, now);
            sets.add(compared.set());
            diagnostics.addAll(Versions.diagnostics(was, now, compared));
        }
        for (ApiDefinition gone : released.apis()) {
            if (!seen.contains(gone.name())) {
                diagnostics.add(new Diagnostic("API005", Severity.WARNING, "API " + gone.name() + " is in the released repository and not in the current one",
                        new SourceLocation("gateway.yaml", 1, 1)));
            }
        }
        diagnostics.sort(Comparator.comparing((Diagnostic d) -> d.location().file()).thenComparingInt(d -> d.location().line())
                .thenComparingInt(d -> d.location().column()).thenComparing(Diagnostic::code));
        return new DiffResult(sets, diagnostics);
    }

    /** The changes of one API, each with its position in the current repository. */
    record Compared(ApiChangeSet set, List<Change> changes, List<SourceLocation> positions, SourceLocation fallback) {
    }

    private static Compared api(ApiDefinition was, ApiDefinition now) {
        ApiSurface before = ApiSurface.of(was);
        ApiSurface after = ApiSurface.of(now);
        SourceLocation top = new SourceLocation(now.overlayPath(), 1, 1);
        List<Change> changes = new ArrayList<>();
        List<SourceLocation> positions = new ArrayList<>();
        if (!before.title().equals(after.title())) {
            changes.add(new Change(Impact.DOCUMENTATION, "", "title", "The title of the API changed"));
            positions.add(top);
        }
        Map<String, Integer> pairs = pair(before, after);
        Set<Integer> matchedAfter = new TreeSet<>(pairs.values());
        for (int i = 0; i < before.operations().size(); i++) {
            OperationSurface was1 = before.operations().get(i);
            if (!pairs.containsKey(was1.id())) {
                changes.add(new Change(Impact.BREAKING, was1.id(), "", "Operation " + was1.id() + " was removed"));
                positions.add(top);
            }
        }
        for (int j = 0; j < after.operations().size(); j++) {
            OperationSurface now1 = after.operations().get(j);
            if (!matchedAfter.contains(j)) {
                changes.add(new Change(Impact.COMPATIBLE, now1.id(), "", "Operation " + now1.id() + " was added"));
                positions.add(now.operations().get(j).source());
            }
        }
        for (Map.Entry<String, Integer> pair : pairs.entrySet()) {
            int i = indexOf(before, pair.getKey());
            int j = pair.getValue();
            operation(was.operations().get(i), now.operations().get(j), before.operations().get(i), after.operations().get(j), changes, positions);
        }
        Impact impact = changes.stream().map(Change::impact).max(Comparator.naturalOrder()).orElse(Impact.NONE);
        ApiChangeSet set = new ApiChangeSet(now.name(), Optional.of(was.version()), Optional.of(now.version()), impact, changes);
        return new Compared(set, changes, positions, top);
    }

    private static int indexOf(ApiSurface surface, String id) {
        for (int i = 0; i < surface.operations().size(); i++) {
            if (surface.operations().get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("No operation " + id);
    }

    /**
     * Operations by id, in the order of the released API. An operation unmatched on both sides with the same method and the
     * same route, placeholder names ignored, is one operation whose id changed: it is paired under its released id.
     */
    private static Map<String, Integer> pair(ApiSurface before, ApiSurface after) {
        Map<String, Integer> pairs = new LinkedHashMap<>();
        Set<Integer> usedAfter = new LinkedHashSet<>();
        for (OperationSurface was : before.operations()) {
            for (int j = 0; j < after.operations().size(); j++) {
                if (after.operations().get(j).id().equals(was.id())) {
                    pairs.put(was.id(), j);
                    usedAfter.add(j);
                }
            }
        }
        for (OperationSurface was : before.operations()) {
            if (pairs.containsKey(was.id())) {
                continue;
            }
            for (int j = 0; j < after.operations().size(); j++) {
                OperationSurface now = after.operations().get(j);
                if (!usedAfter.contains(j) && now.method() == was.method() && shape(now.route()).equals(shape(was.route()))) {
                    pairs.put(was.id(), j);
                    usedAfter.add(j);
                    break;
                }
            }
        }
        return pairs;
    }

    private static String shape(String route) {
        return PLACEHOLDER.matcher(route).replaceAll("{}");
    }

    // ------------------------------------------------------------------ one operation

    private static void operation(ApiOperation wasOp, ApiOperation nowOp, OperationSurface was, OperationSurface now, List<Change> changes,
                                  List<SourceLocation> positions) {
        String id = now.id();
        SourceLocation at = nowOp.source();
        if (!was.id().equals(now.id())) {
            add(changes, positions, Impact.BREAKING, id, "id", "The id of operation " + was.id() + " changed to " + now.id(), at);
        }
        if (!was.route().equals(now.route())) {
            add(changes, positions, Impact.BREAKING, id, "route", "The route of operation " + id + " changed", at);
        }
        if (was.method() != now.method()) {
            add(changes, positions, Impact.BREAKING, id, "method", "The method of operation " + id + " changed from " + was.method() + " to " + now.method(), at);
        }
        if (!was.summary().equals(now.summary()) || !was.description().equals(now.description()) || !was.tags().equals(now.tags())) {
            add(changes, positions, Impact.DOCUMENTATION, id, "documentation", "The summary, description or tags of operation " + id + " changed", at);
        }
        field(changes, positions, FieldComparison.compare(id, true, was.request(), now.request()), nowOp);
        if (was.hasContent() && !now.hasContent()) {
            add(changes, positions, Impact.BREAKING, id, "response", "Operation " + id + " no longer returns content", at);
        } else if (!was.hasContent() && now.hasContent()) {
            add(changes, positions, Impact.COMPATIBLE, id, "response", "Operation " + id + " now returns content", at);
        } else {
            field(changes, positions, FieldComparison.compare(id, false, was.response(), now.response()), nowOp);
        }
        errors(was, now, changes, positions, at);
        Map<String, String> before = InternalAspects.of(wasOp);
        Map<String, String> after = InternalAspects.of(nowOp);
        before.forEach((aspect, text) -> {
            if (!text.equals(after.get(aspect))) {
                add(changes, positions, Impact.INTERNAL, id, aspect, "The " + aspect + " of operation " + id + " changed, which consumers cannot see", at);
            }
        });
    }

    private static void errors(OperationSurface was, OperationSurface now, List<Change> changes, List<SourceLocation> positions, SourceLocation at) {
        String id = now.id();
        boolean defaultChanged = was.defaultErrorStatus().isPresent() && now.defaultErrorStatus().isPresent()
                && !was.defaultErrorStatus().equals(now.defaultErrorStatus());
        if (defaultChanged) {
            add(changes, positions, Impact.BREAKING, id, "error", "The default error status of operation " + id + " changed from "
                    + was.defaultErrorStatus().get() + " to " + now.defaultErrorStatus().get(), at);
        }
        for (int status : was.errorStatuses()) {
            boolean replacedDefault = defaultChanged && was.defaultErrorStatus().get() == status;
            if (!now.errorStatuses().contains(status) && !replacedDefault) {
                add(changes, positions, Impact.BREAKING, id, "error." + status, "Operation " + id + " no longer returns the error status " + status, at);
            }
        }
        for (int status : now.errorStatuses()) {
            boolean newDefault = defaultChanged && now.defaultErrorStatus().get() == status;
            if (!was.errorStatuses().contains(status) && !newDefault) {
                add(changes, positions, Impact.COMPATIBLE, id, "error." + status, "Operation " + id + " now also returns the error status " + status, at);
            }
        }
    }

    private static void add(List<Change> changes, List<SourceLocation> positions, Impact impact, String id, String path, String description,
                            SourceLocation at) {
        changes.add(new Change(impact, id, path, description));
        positions.add(at);
    }

    /** Adds the changes of one side, each at the position of its field in the current operation, or at the operation. */
    private static void field(List<Change> changes, List<SourceLocation> positions, FieldComparison.Result result, ApiOperation current) {
        for (int i = 0; i < result.changes().size(); i++) {
            changes.add(result.changes().get(i));
            positions.add(locate(current, result.places().get(i)));
        }
    }

    /** The overlay position of the field in the current operation, or the operation's when the field is not there or has none. */
    private static SourceLocation locate(ApiOperation operation, List<String> names) {
        List<FieldBinding> scope = operation.bindings();
        FieldBinding found = null;
        for (String name : names) {
            found = null;
            for (FieldBinding binding : scope) {
                if (binding.exposedName().equals(name) && !binding.hidden()) {
                    found = binding;
                    break;
                }
            }
            if (found == null) {
                return operation.source();
            }
            scope = found.children();
        }
        return found != null && found.source().file().equals(operation.overlayPath()) ? found.source() : operation.source();
    }
}

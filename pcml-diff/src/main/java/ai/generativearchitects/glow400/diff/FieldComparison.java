package ai.generativearchitects.glow400.diff;

import ai.generativearchitects.glow400.contract.FieldSurface;
import ai.generativearchitects.glow400.contract.Limits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/** The changes between two versions of the fields of one scope, on one side of an operation. */
final class FieldComparison {

    private final String operationId;
    private final boolean request;
    private final List<Change> changes = new ArrayList<>();
    /** The exposed path of each change, in the same order, for finding its position in the current repository. */
    private final List<List<String>> places = new ArrayList<>();

    private FieldComparison(String operationId, boolean request) {
        this.operationId = operationId;
        this.request = request;
    }

    /** What changed in the fields of one side; the places say which field each change is about. */
    record Result(List<Change> changes, List<List<String>> places) {
    }

    static Result compare(String operationId, boolean request, List<FieldSurface> released, List<FieldSurface> current) {
        FieldComparison comparison = new FieldComparison(operationId, request);
        comparison.scope(released, current, List.of());
        return new Result(comparison.changes, comparison.places);
    }

    private void scope(List<FieldSurface> released, List<FieldSurface> current, List<String> parents) {
        Map<String, FieldSurface> before = byName(released);
        Map<String, FieldSurface> after = byName(current);
        for (FieldSurface gone : released) {
            if (!after.containsKey(gone.exposedName())) {
                add(Impact.BREAKING, parents, gone.exposedName(), (request ? "Request" : "Response") + " field " + path(parents, gone.exposedName()) + " was removed");
            }
        }
        for (FieldSurface added : current) {
            if (!before.containsKey(added.exposedName())) {
                boolean breaking = request && added.required();
                add(breaking ? Impact.BREAKING : Impact.COMPATIBLE, parents, added.exposedName(),
                        (request ? "Request" : "Response") + " field " + path(parents, added.exposedName()) + " was added"
                                + (request ? (added.required() ? ", and it is required" : ", and it is optional") : ""));
            }
        }
        for (FieldSurface now : current) {
            FieldSurface was = before.get(now.exposedName());
            if (was != null) {
                field(was, now, parents);
            }
        }
    }

    private void field(FieldSurface was, FieldSurface now, List<String> parents) {
        String name = now.exposedName();
        String where = path(parents, name);
        if (!Objects.equals(was.description(), now.description()) || !Objects.equals(was.example(), now.example())) {
            add(Impact.DOCUMENTATION, parents, name, "The description or example of " + where + " changed");
        }
        if (request) {
            if (!Objects.equals(was.location(), now.location())) {
                add(Impact.BREAKING, parents, name, "Request field " + where + " moved from " + was.location().map(Enum::name).orElse("?").toLowerCase()
                        + " to " + now.location().map(Enum::name).orElse("?").toLowerCase());
            }
            if (!was.required() && now.required()) {
                add(Impact.BREAKING, parents, name, "Request field " + where + " became required");
            } else if (was.required() && !now.required()) {
                add(Impact.COMPATIBLE, parents, name, "Request field " + where + " became optional");
            }
        } else if (was.required() && !now.required()) {
            add(Impact.BREAKING, parents, name, "Response field " + where + " is no longer always present");
        } else if (!was.required() && now.required()) {
            add(Impact.COMPATIBLE, parents, name, "Response field " + where + " is now always present");
        }
        if (was.object() != now.object() || was.array() != now.array()) {
            add(Impact.BREAKING, parents, name, (request ? "Request" : "Response") + " field " + where + " changed from " + shape(was) + " to " + shape(now));
            return;
        }
        if (!was.object()) {
            if (!was.type().equals(now.type())) {
                add(Impact.BREAKING, parents, name, (request ? "Request" : "Response") + " field " + where + " changed type from "
                        + was.type().map(Enum::name).orElse("?").toLowerCase() + " to " + now.type().map(Enum::name).orElse("?").toLowerCase());
                return;
            }
            limits(was.limits(), now.limits(), parents, name, where);
            return;
        }
        limits(was.limits(), now.limits(), parents, name, where);
        List<String> inside = new ArrayList<>(parents);
        inside.add(name);
        scope(was.children(), now.children(), inside);
    }

    private static String shape(FieldSurface field) {
        String element = field.object() ? "object" : "scalar";
        return field.array() ? "an array of " + element + "s" : "a " + element;
    }

    // ------------------------------------------------------------------ limits

    private void limits(Limits was, Limits now, List<String> parents, String name, String where) {
        if (!Objects.equals(was.scale(), now.scale())) {
            add(Impact.BREAKING, parents, name, "The decimal scale of " + where + " changed from " + text(was.scale()) + " to " + text(now.scale()));
        }
        List<String> tighter = new ArrayList<>();
        List<String> looser = new ArrayList<>();
        upper("maxLength", was.maxLength(), now.maxLength(), tighter, looser);
        upper("digits", was.digits(), now.digits(), tighter, looser);
        upper("maxItems", was.maxItems(), now.maxItems(), tighter, looser);
        upper("maxBytes", was.maxBytes(), now.maxBytes(), tighter, looser);
        lower("minBytes", was.minBytes(), now.minBytes(), tighter, looser);
        upperBig("maximum", was.maximum(), now.maximum(), tighter, looser);
        lowerBig("minimum", was.minimum(), now.minimum(), tighter, looser);
        String side = request ? "request" : "response";
        if (request && !tighter.isEmpty()) {
            add(Impact.BREAKING, parents, name, "The limits of " + side + " field " + where + " got tighter: " + String.join(", ", tighter));
        } else if (!tighter.isEmpty() || !looser.isEmpty()) {
            List<String> all = new ArrayList<>(tighter);
            all.addAll(looser);
            add(Impact.COMPATIBLE, parents, name, "The limits of " + side + " field " + where + " changed: " + String.join(", ", all)
                    + (request ? " (looser)" : ""));
        }
    }

    private static String text(Optional<Integer> value) {
        return value.map(String::valueOf).orElse("none");
    }

    /** A maximum: lower is tighter; no limit is the loosest. */
    private static void upper(String limit, Optional<Integer> was, Optional<Integer> now, List<String> tighter, List<String> looser) {
        compare(limit, was, now, (a, b) -> a.compareTo(b) > 0, tighter, looser, true);
    }

    /** A minimum: higher is tighter; no limit is the loosest. */
    private static void lower(String limit, Optional<Integer> was, Optional<Integer> now, List<String> tighter, List<String> looser) {
        compare(limit, was, now, (a, b) -> a.compareTo(b) > 0, tighter, looser, false);
    }

    private static void upperBig(String limit, Optional<BigInteger> was, Optional<BigInteger> now, List<String> tighter, List<String> looser) {
        compare(limit, was, now, (a, b) -> a.compareTo(b) > 0, tighter, looser, true);
    }

    private static void lowerBig(String limit, Optional<BigInteger> was, Optional<BigInteger> now, List<String> tighter, List<String> looser) {
        compare(limit, was, now, (a, b) -> a.compareTo(b) > 0, tighter, looser, false);
    }

    /**
     * Notes a limit that moved. {@code greater} says whether its first argument is the larger; {@code maximum} says whether
     * a smaller value is the tighter one (a maximum) or a larger one (a minimum).
     */
    private static <T> void compare(String limit, Optional<T> was, Optional<T> now, BiPredicate<T, T> greater, List<String> tighter,
                                    List<String> looser, boolean maximum) {
        if (was.equals(now)) {
            return;
        }
        boolean tightens;
        if (was.isEmpty()) {
            tightens = true;
        } else if (now.isEmpty()) {
            tightens = false;
        } else {
            boolean nowGreater = greater.test(now.get(), was.get());
            tightens = maximum != nowGreater;
        }
        (tightens ? tighter : looser).add(limit);
    }

    // ------------------------------------------------------------------ helpers

    private void add(Impact impact, List<String> parents, String name, String description) {
        List<String> place = new ArrayList<>(parents);
        place.add(name);
        changes.add(new Change(impact, operationId, (request ? "request." : "response.") + String.join(".", place), description));
        places.add(place);
    }

    private static String path(List<String> parents, String name) {
        return parents.isEmpty() ? name : String.join(".", parents) + "." + name;
    }

    private static Map<String, FieldSurface> byName(List<FieldSurface> fields) {
        Map<String, FieldSurface> map = new LinkedHashMap<>();
        fields.forEach(field -> map.put(field.exposedName(), field));
        return map;
    }
}

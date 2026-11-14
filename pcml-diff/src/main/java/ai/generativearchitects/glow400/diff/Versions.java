package ai.generativearchitects.glow400.diff;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** The version rule and the diagnostics of an API that is in both repositories. */
final class Versions {

    private Versions() {
    }

    /** API002, API003 and API004 for one API. */
    static List<Diagnostic> diagnostics(ApiDefinition was, ApiDefinition now, ApiDiff.Compared compared) {
        List<Diagnostic> out = new ArrayList<>();
        Version before = Version.parse(was.version());
        Version after = Version.parse(now.version());
        ApiChangeSet set = compared.set();
        String name = now.name();
        if (after.compareTo(before) < 0) {
            out.add(new Diagnostic("API004", Severity.ERROR, "The version of API " + name + " is " + now.version()
                    + ", which is lower than the released version " + was.version(), compared.fallback()));
        } else if (before.isPreRelease()) {
            // Decision 61: against a released pre-release any change is allowed, as long as the version is higher.
            boolean changed = set.impact() == Impact.BREAKING || set.impact() == Impact.COMPATIBLE;
            if (changed && after.compareTo(before) == 0) {
                int first = firstOf(compared, set.impact());
                out.add(new Diagnostic("API004", Severity.ERROR, "API " + name + " has changes but its version is still the released pre-release "
                        + was.version() + "; it must be higher", compared.positions().get(first)));
            }
        } else if (set.impact() == Impact.BREAKING && !enough(before, after, true)) {
            int first = firstOf(compared, Impact.BREAKING);
            out.add(new Diagnostic("API002", Severity.ERROR, "API " + name + " has breaking changes but its version goes from " + was.version()
                    + " to " + now.version() + "; the " + (before.isStable() ? "major" : "minor") + " version must increase. Breaking changes: "
                    + compared.changes().stream().filter(c -> c.impact() == Impact.BREAKING).map(Versions::line).collect(Collectors.joining("; ")),
                    compared.positions().get(first)));
        } else if (set.impact() == Impact.COMPATIBLE && !enough(before, after, false)) {
            int first = firstOf(compared, Impact.COMPATIBLE);
            out.add(new Diagnostic("API004", Severity.ERROR, "API " + name + " has compatible changes but its version goes from " + was.version()
                    + " to " + now.version() + "; the " + (before.isStable() ? "minor or major" : "patch, minor or major") + " version must increase",
                    compared.positions().get(first)));
        }
        if (!compared.changes().isEmpty()) {
            out.add(new Diagnostic("API003", Severity.INFO, summary(name, was.version(), now.version(), set), compared.fallback()));
        }
        return out;
    }

    /** Whether the version moved enough for the impact, by the table of decision 57. */
    private static boolean enough(Version before, Version after, boolean breaking) {
        if (breaking) {
            return before.isStable() ? after.major().compareTo(before.major()) > 0 : after.compareMajorMinor(before) > 0;
        }
        return before.isStable() ? after.compareMajorMinor(before) > 0 : after.compareNumbers(before) > 0;
    }

    private static int firstOf(ApiDiff.Compared compared, Impact impact) {
        for (int i = 0; i < compared.changes().size(); i++) {
            if (compared.changes().get(i).impact() == impact) {
                return i;
            }
        }
        throw new IllegalStateException("No change with impact " + impact);
    }

    private static String line(Change change) {
        return (change.operationId().isEmpty() ? "" : change.operationId() + " ") + change.description();
    }

    private static String summary(String name, String was, String now, ApiChangeSet set) {
        List<String> parts = new ArrayList<>();
        for (Impact impact : List.of(Impact.BREAKING, Impact.COMPATIBLE, Impact.INTERNAL, Impact.DOCUMENTATION)) {
            long count = set.changes().stream().filter(c -> c.impact() == impact).count();
            if (count > 0) {
                parts.add(count + " " + impact.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return "API " + name + ": " + set.impact().name().toLowerCase(java.util.Locale.ROOT) + " (" + String.join(", ", parts) + "), version "
                + was + " to " + now;
    }
}

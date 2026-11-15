package ai.generativearchitects.glow400.report;

import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.emit.descriptor.Tool;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The report for pull-request annotations: SARIF 2.1.0, one run, with the rules that occur and one result for each
 * diagnostic. Nothing else: no invocations, no fingerprints, no code snippets.
 */
public final class SarifReport {

    /** The version of the SARIF format. */
    public static final String SARIF_VERSION = "2.1.0";

    private static final String SOURCE_ROOT = "%SRCROOT%";

    private SarifReport() {
    }

    /** The report as SARIF, with the layout of {@link Json#write}. */
    public static String render(Report report) {
        List<Diagnostic> sorted = report.sorted();
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", Tool.NAME);
        driver.put("version", Tool.version());
        driver.put("informationUri", Tool.REPOSITORY_URL);
        List<Object> rules = new ArrayList<>();
        new TreeSet<>(sorted.stream().map(Diagnostic::code).toList()).forEach(code -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", code);
            rules.add(rule);
        });
        driver.put("rules", rules);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("driver", driver);

        List<Object> results = new ArrayList<>();
        for (Diagnostic d : sorted) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", d.code());
            result.put("level", level(d.severity()));
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("text", d.message());
            result.put("message", message);
            result.put("locations", List.of(location(report, d)));
            results.add(result);
        }
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", tool);
        run.put("results", results);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", SARIF_VERSION);
        root.put("runs", List.of(run));
        return Json.write(root);
    }

    private static String level(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }

    /** A location: the path relative to the repository root with {@code %SRCROOT%}, or the path as given; a region is 1-based. */
    private static Map<String, Object> location(Report report, Diagnostic d) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        java.util.Optional<String> relative = report.relative(d.location().file());
        artifact.put("uri", uri(relative.orElse(d.location().file())));
        if (relative.isPresent()) {
            artifact.put("uriBaseId", SOURCE_ROOT);
        }
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("startLine", Math.max(1, d.location().line()));
        region.put("startColumn", Math.max(1, d.location().column()));
        Map<String, Object> physical = new LinkedHashMap<>();
        physical.put("artifactLocation", artifact);
        physical.put("region", region);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("physicalLocation", physical);
        return location;
    }

    /**
     * A path as a URI reference: everything but letters, digits and {@code - . _ ~ /} is percent-encoded, as UTF-8. A relative
     * path whose first segment holds a colon begins with {@code ./}, so that it is not read as a scheme.
     */
    static String uri(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean plain = c < 0x80 && (Character.isLetterOrDigit(c) || "-._~/".indexOf(c) >= 0);
            if (plain) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        String encoded = out.toString();
        int firstSlash = encoded.indexOf('/');
        String firstSegment = firstSlash < 0 ? encoded : encoded.substring(0, firstSlash);
        return !encoded.startsWith("/") && firstSegment.contains("%3A") ? "./" + encoded : encoded;
    }
}

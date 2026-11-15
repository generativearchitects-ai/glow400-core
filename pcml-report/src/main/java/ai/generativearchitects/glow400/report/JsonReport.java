package ai.generativearchitects.glow400.report;

import ai.generativearchitects.glow400.emit.descriptor.Json;
import ai.generativearchitects.glow400.emit.descriptor.Tool;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The report for scripts: one JSON document, {@code reportSchemaVersion} 1. */
public final class JsonReport {

    /** The version of the report format. */
    public static final int SCHEMA_VERSION = 1;

    private JsonReport() {
    }

    /** The report as JSON, with the layout of {@link Json#write}. */
    public static String render(Report report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportSchemaVersion", SCHEMA_VERSION);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", Tool.NAME);
        tool.put("version", Tool.version());
        root.put("tool", tool);
        root.put("command", report.command());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("errors", report.count(Severity.ERROR));
        summary.put("warnings", report.count(Severity.WARNING));
        summary.put("notes", report.count(Severity.INFO));
        root.put("summary", summary);
        List<Object> diagnostics = new ArrayList<>();
        for (Diagnostic d : report.sorted()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", d.code());
            entry.put("severity", d.severity() == Severity.INFO ? "note" : d.severity().name().toLowerCase(Locale.ROOT));
            entry.put("message", d.message());
            entry.put("file", report.display(d.location().file()));
            entry.put("line", d.location().line());
            entry.put("column", d.location().column());
            diagnostics.add(entry);
        }
        root.put("diagnostics", diagnostics);
        return Json.write(root);
    }
}

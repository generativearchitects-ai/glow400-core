package ai.generativearchitects.glow400.report;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.Locale;

/**
 * The report for people: one line per diagnostic, {@code file:line:column: severity CODE: message}, then one summary line. The lowest
 * severity is written {@code note} here, as in the summary, the JSON report and SARIF.
 * Messages and names can come from files a pull request controls, so everything that is not a printable character is
 * written as an escape: no message can move the cursor, start another line or forge one.
 */
public final class TextReport {

    private TextReport() {
    }

    /** The report as text, every line ending with a line feed. */
    public static String render(Report report) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic d : report.sorted()) {
            out.append(escape(report.display(d.location().file()))).append(':').append(d.location().line()).append(':')
                    .append(d.location().column()).append(": ").append(d.severity() == Severity.INFO ? "note" : d.severity().name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(escape(d.code())).append(": ").append(escape(d.message())).append('\n');
        }
        long errors = report.count(Severity.ERROR);
        long warnings = report.count(Severity.WARNING);
        long notes = report.count(Severity.INFO);
        out.append(errors).append(errors == 1 ? " error, " : " errors, ").append(warnings).append(warnings == 1 ? " warning, " : " warnings, ")
                .append(notes).append(notes == 1 ? " note" : " notes").append('\n');
        return out.toString();
    }

    /**
     * Printable characters as they are; control characters, line and paragraph separators, format characters (such as the
     * ones that reverse the direction of text), unassigned code points and lone surrogates as {@code \}{@code uXXXX}
     * ({@code \}{@code u{X}} above the basic plane).
     */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (printable(cp)) {
                out.appendCodePoint(cp);
            } else if (cp <= 0xFFFF) {
                out.append(String.format("\\u%04x", cp));
            } else {
                out.append("\\u{").append(Integer.toHexString(cp)).append('}');
            }
        });
        return out.toString();
    }

    private static boolean printable(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT, Character.UNASSIGNED,
                 Character.SURROGATE, Character.PRIVATE_USE -> false;
            default -> true;
        };
    }
}

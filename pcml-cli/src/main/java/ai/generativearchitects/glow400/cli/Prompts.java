package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import ai.generativearchitects.glow400.report.TextReport;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** What {@code annotate} says and how it reads an answer: the descriptions, the matching of shortened answers. */
final class Prompts {

    private Prompts() {
    }

    /** A parameter as the questions show it: the wire type and the count, as in the PCML, escaped. */
    static String describe(FieldDef field) {
        String type = switch (field.wire()) {
            case WireType.Char c -> "char(" + length(c.length()) + ")";
            case WireType.VarChar v -> "varchar(" + length(v.length()) + ")";
            case WireType.Byte b -> "byte(" + length(b.length()) + ")";
            case WireType.Int i -> "int(" + i.lengthBytes() + ")";
            case WireType.Packed p -> "packed(" + p.length() + "," + p.precision() + ")";
            case WireType.Zoned z -> "zoned(" + z.length() + "," + z.precision() + ")";
            case WireType.Float f -> "float(" + f.lengthBytes() + ")";
            case WireType.Struct s -> "struct " + TextReport.escape(s.structName());
            case WireType.Date d -> "date";
            case WireType.Time t -> "time";
            case WireType.Timestamp t -> "timestamp";
        };
        return type + switch (field.count()) {
            case Count.None n -> "";
            case Count.Fixed f -> "[" + f.value() + "]";
            case Count.Ref r -> "[*" + TextReport.escape(r.field()) + "]";
        };
    }

    private static String length(Length length) {
        return switch (length) {
            case Length.Fixed f -> String.valueOf(f.value());
            case Length.Ref r -> "*" + TextReport.escape(r.field());
        };
    }

    /**
     * The one choice an answer stands for: the answer in any case, or the beginning of exactly one choice.
     *
     * @return the choice, or empty if the answer matches none or more than one
     */
    static <T> Optional<T> choose(String answer, List<T> choices, Function<T, String> spelling) {
        String wanted = answer.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<T> matches = choices.stream().filter(c -> spelling.apply(c).startsWith(wanted)).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}

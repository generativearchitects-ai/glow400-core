package ai.generativearchitects.slay400.contract;

import java.util.Objects;

/**
 * The written forms of the values that travel as text in an API: whole numbers in a path, query or header, and decimals,
 * dates, times and date-times wherever they appear. The OpenAPI emitter publishes these forms as patterns, and the HTTP
 * binding reads text with the same patterns, so that a document and a reader cannot disagree about what a client may send.
 *
 * <p>The patterns are written for JSON Schema ({@code ^} and {@code $}, ECMA 262 syntax) and mean the same in
 * {@link java.util.regex.Pattern} when used with {@code matches()}. A form says how a value is written, not whether it
 * fits its field except for decimals, whose form includes the digits of the field: lengths and ranges are checked by the
 * codec.
 */
public final class TextForms {

    /** A whole number: an optional minus sign and digits. The document gives it the type {@code integer}, with no pattern. */
    public static final String INTEGER = "^-?\\d+$";

    /** A date, {@code yyyy-MM-dd}. The document gives it {@code format: date}, with no pattern. */
    public static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    /** A time of day without a time zone: {@code HH:mm:ss} and an optional fraction of 1 to 9 digits. */
    public static final String TIME = "^\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$";

    /** A date and a time of day without a time zone: a date, {@code T}, and a time. */
    public static final String DATETIME = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$";

    private TextForms() {
    }

    /**
     * The form of a decimal, which travels as text so that no caller loses precision: up to the field's scale for packed and
     * zoned (trailing zeros beyond it allowed, as the codec compares numbers), unsigned for a decimal held as text, and any
     * fraction for a float.
     *
     * @param limits the limits of the decimal field
     * @throws NullPointerException if {@code limits} is {@code null}
     */
    public static String decimal(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        if (limits.scale().isPresent()) {
            return fixedScale(limits.digits().orElseThrow(), limits.scale().get());
        }
        return limits.minimum().isPresent() ? "^\\d+(\\.\\d+)?$" : "^[-+]?\\d+(\\.\\d+)?$";
    }

    private static String fixedScale(int digits, int scale) {
        int integerDigits = digits - scale;
        String integer = integerDigits <= 0 ? "0" : "\\d{1," + integerDigits + "}";
        return "^-?" + integer + (scale > 0 ? "(\\.\\d{1," + scale + "}0*)?" : "") + "$";
    }
}

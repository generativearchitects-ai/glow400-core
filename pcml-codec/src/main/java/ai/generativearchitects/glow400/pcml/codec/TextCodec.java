package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Char and varchar fields. The text is built by the semantic type, checked against the field's length (counted in
 * characters, see {@link ByteWidth}) and padded; decoding removes the padding and reads the text by semantic type.
 */
final class TextCodec implements ScalarCodec {

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    /** The field length in characters, or -1 if it is given by another field. */
    private final int length;
    private final SemanticType semantic;
    private final Pad pad;
    private final char padChar;
    private final String nullAs;
    private final DatePattern pattern;
    private final String trueValue;
    private final String falseValue;

    TextCodec(int length, SemanticType semantic, Pad pad, char padChar, String nullAs, DatePattern pattern,
              String trueValue, String falseValue) {
        this.length = length;
        this.semantic = semantic;
        this.pad = pad;
        this.padChar = padChar;
        this.nullAs = nullAs;
        this.pattern = pattern;
        this.trueValue = trueValue;
        this.falseValue = falseValue;
    }

    @Override
    public Optional<WireValue> absent() {
        return Optional.of(new WireValue.Text(padded(nullAs)));
    }

    @Override
    public WireValue blank() {
        return new WireValue.Text(padded(nullAs));
    }

    @Override
    public boolean supportsInjection() {
        return true;
    }

    // ------------------------------------------------------------------ encoding

    @Override
    public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
        String text;
        switch (semantic) {
            case STRING -> {
                if (!(value instanceof ApiValue.Text t)) {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                text = t.value();
            }
            case INTEGER -> {
                if (!(value instanceof ApiValue.Integer i)) {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                if (i.value().signum() < 0) {
                    problems.add("VAL003", where, "A negative number cannot be stored in a text field");
                    return Optional.empty();
                }
                text = i.value().toString();
            }
            case DECIMAL -> {
                BigDecimal number;
                if (value instanceof ApiValue.Decimal d) {
                    number = d.value();
                } else if (value instanceof ApiValue.Integer i) {
                    number = new BigDecimal(i.value());
                } else {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                if (number.signum() < 0) {
                    problems.add("VAL003", where, "A negative number cannot be stored in a text field");
                    return Optional.empty();
                }
                text = number.toPlainString();
            }
            case BOOLEAN -> {
                if (!(value instanceof ApiValue.Bool b)) {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                text = b.value() ? trueValue : falseValue;
            }
            case DATE -> {
                if (!(value instanceof ApiValue.Date d)) {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                Optional<String> formatted = pattern.format(d.value());
                if (formatted.isEmpty()) {
                    doesNotFit(where, problems);
                    return Optional.empty();
                }
                text = formatted.get();
            }
            case TIME -> {
                if (!(value instanceof ApiValue.Time t)) {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                Optional<String> formatted = pattern.format(t.value());
                if (formatted.isEmpty()) {
                    doesNotFit(where, problems);
                    return Optional.empty();
                }
                text = formatted.get();
            }
            case DATETIME -> {
                if (!(value instanceof ApiValue.DateTime t)) {
                    wrongKind(where, problems);
                    return Optional.empty();
                }
                Optional<String> formatted = pattern.format(t.value());
                if (formatted.isEmpty()) {
                    doesNotFit(where, problems);
                    return Optional.empty();
                }
                text = formatted.get();
            }
            default -> {
                wrongKind(where, problems);
                return Optional.empty();
            }
        }
        return finish(text, where, problems);
    }

    @Override
    public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
        return finish(text, where, problems);
    }

    private void wrongKind(Where where, Problems problems) {
        problems.add("VAL002", where, "The value is not a " + semantic.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void doesNotFit(Where where, Problems problems) {
        problems.add("VAL003", where, "The value cannot be written in the field's format without losing part of it");
    }

    /** Checks the length and pads. */
    private Optional<WireValue> finish(String text, Where where, Problems problems) {
        if (length >= 0 && text.codePointCount(0, text.length()) > length) {
            problems.add("VAL003", where, "The value is too long: at most " + length + " characters");
            return Optional.empty();
        }
        return Optional.of(new WireValue.Text(padded(text)));
    }

    private String padded(String text) {
        if (pad == Pad.NONE) {
            return text;
        }
        // A length that names another field is -1: nothing is missing then, and neither when the text fills the field.
        String fill = String.valueOf(padChar).repeat(Math.max(0, length - text.codePointCount(0, text.length())));
        return pad == Pad.LEFT ? fill + text : text + fill;
    }

    // ------------------------------------------------------------------ decoding

    @Override
    public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
        if (!(value instanceof WireValue.Text wire)) {
            problems.add("VAL101", where, "A text value was expected");
            return Optional.empty();
        }
        String raw = wire.value();
        String text = unpadded(raw);
        switch (semantic) {
            case STRING -> {
                return Optional.of(new ApiValue.Text(text));
            }
            case INTEGER -> {
                if (isNoValue(text)) {
                    return Optional.of(new ApiValue.Absent());
                }
                if (!DIGITS.matcher(text).matches()) {
                    unreadable(where, problems);
                    return Optional.empty();
                }
                return Optional.of(new ApiValue.Integer(new BigInteger(text)));
            }
            case DECIMAL -> {
                if (isNoValue(text)) {
                    return Optional.of(new ApiValue.Absent());
                }
                // Left padding with '0' also removes the 0 before the decimal point: "000.5" is read as 0.5.
                String number = text.startsWith(".") ? "0" + text : text;
                if (!DECIMAL.matcher(number).matches()) {
                    unreadable(where, problems);
                    return Optional.empty();
                }
                return Optional.of(new ApiValue.Decimal(new BigDecimal(number)));
            }
            case BOOLEAN -> {
                if (text.equals(trueValue)) {
                    return Optional.of(new ApiValue.Bool(true));
                }
                if (text.equals(falseValue) || text.isBlank()) {
                    return Optional.of(new ApiValue.Bool(false));
                }
                unreadable(where, problems);
                return Optional.empty();
            }
            case DATE, TIME, DATETIME -> {
                if (noValue(raw)) {
                    return Optional.of(new ApiValue.Absent());
                }
                Optional<ApiValue> parsed = switch (semantic) {
                    case DATE -> pattern.parseDate(text).map(ApiValue.Date::new);
                    case TIME -> pattern.parseTime(text).map(ApiValue.Time::new);
                    default -> pattern.parseDateTime(text).map(ApiValue.DateTime::new);
                };
                if (parsed.isEmpty()) {
                    unreadable(where, problems);
                }
                return parsed;
            }
            default -> throw new IllegalStateException("A text field cannot hold " + semantic);
        }
    }

    private void unreadable(Where where, Problems problems) {
        problems.add("VAL102", where, "The text cannot be read as " + semantic.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Blank, or the text that stands for "no value" when there is one. */
    private boolean isNoValue(String text) {
        return text.isBlank() || (!nullAs.isBlank() && text.equals(nullAs));
    }

    /** The text of a returned field with its padding removed, without reading it as a value of its semantic type. */
    String unpaddedText(String raw) {
        return unpadded(raw);
    }

    /** Removes the padding: trailing for {@code pad: right}, leading for {@code pad: left}, none when not padded. */
    private String unpadded(String raw) {
        if (pad == Pad.NONE) {
            return raw;
        }
        if (pad == Pad.RIGHT) {
            int end = raw.length();
            while (end > 0 && raw.charAt(end - 1) == padChar) {
                end--;
            }
            return raw.substring(0, end);
        }
        int start = 0;
        while (start < raw.length() && raw.charAt(start) == padChar) {
            start++;
        }
        boolean numeric = semantic == SemanticType.INTEGER || semantic == SemanticType.DECIMAL;
        if (start == raw.length() && numeric && !raw.isEmpty()) {
            return raw.substring(raw.length() - 1); // an all-padding number keeps one character: "0000" is "0"
        }
        return raw.substring(start);
    }

    /**
     * Whether a raw date-like field means "no value". Entirely blank does for all three types; entirely zeros, which is how
     * RPG programs write "no date", does for DATE and DATETIME, where it is not a valid value. For TIME all zeros is midnight.
     * The text is the raw field, before padding is removed; an empty field is blank.
     */
    private boolean noValue(String raw) {
        if (raw.isBlank()) {
            return true;
        }
        return semantic != SemanticType.TIME && raw.chars().allMatch(c -> c == '0');
    }

    @Override
    public boolean isBlank(WireValue value) {
        return value instanceof WireValue.Blank
                || value instanceof WireValue.Text t && (t.value().isBlank() || t.value().equals(padded("")));
    }
}

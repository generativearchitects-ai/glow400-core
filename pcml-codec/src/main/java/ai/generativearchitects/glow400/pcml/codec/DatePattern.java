package ai.generativearchitects.glow400.pcml.codec;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A date or time pattern with fixed-width parts only: {@code yyyy}, {@code MM}, {@code dd}, {@code HH}, {@code mm},
 * {@code ss} and {@code S} repeated 1 to 9 times, each at most once. Every other character that is not a letter is a
 * literal, and a single-quoted text such as {@code 'T'} is a literal too ({@code ''} is an apostrophe). Because every
 * part has a fixed width, the length of the text a pattern produces is known without looking at a value.
 */
final class DatePattern {

    private enum Kind { YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, FRACTION, LITERAL }

    private record Part(Kind kind, int width, String literal) {
    }

    /** Result of {@link #parse}: the pattern, or why it cannot be used. */
    record Parsed(Optional<DatePattern> pattern, Optional<String> error) {
    }

    private final List<Part> parts;
    private final int length;

    private DatePattern(List<Part> parts) {
        this.parts = parts;
        this.length = parts.stream().mapToInt(p -> p.kind == Kind.LITERAL ? p.literal.length() : p.width).sum();
    }

    static Parsed parse(String text) {
        List<Part> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                parts.add(new Part(Kind.LITERAL, 0, "'")); // two quotes outside a quoted text are one apostrophe
                i += 2;
            } else if (c == '\'') {
                StringBuilder literal = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < text.length()) {
                    if (text.charAt(i) == '\'') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                            literal.append('\'');
                            i += 2;
                        } else {
                            closed = true;
                            i++;
                            break;
                        }
                    } else {
                        literal.append(text.charAt(i++));
                    }
                }
                if (!closed) {
                    return failed("a quote is not closed");
                }
                if (!literal.isEmpty()) {
                    parts.add(new Part(Kind.LITERAL, 0, literal.toString()));
                }
            } else if (Character.isLetter(c)) {
                int run = 1;
                while (i + run < text.length() && text.charAt(i + run) == c) {
                    run++;
                }
                Part part = letter(c, run);
                if (part == null) {
                    return failed("'" + c + "' repeated " + run + " time" + (run == 1 ? "" : "s") + " is not supported");
                }
                if (part.kind != Kind.LITERAL && parts.stream().anyMatch(p -> p.kind == part.kind)) {
                    return failed("'" + c + "' appears twice");
                }
                parts.add(part);
                i += run;
            } else {
                parts.add(new Part(Kind.LITERAL, 0, String.valueOf(c)));
                i++;
            }
        }
        if (parts.isEmpty()) {
            return failed("it is empty");
        }
        return new Parsed(Optional.of(new DatePattern(parts)), Optional.empty());
    }

    private static Parsed failed(String reason) {
        return new Parsed(Optional.empty(), Optional.of(reason));
    }

    private static Part letter(char c, int run) {
        return switch (c) {
            case 'y' -> run == 4 ? new Part(Kind.YEAR, 4, null) : null;
            case 'M' -> run == 2 ? new Part(Kind.MONTH, 2, null) : null;
            case 'd' -> run == 2 ? new Part(Kind.DAY, 2, null) : null;
            case 'H' -> run == 2 ? new Part(Kind.HOUR, 2, null) : null;
            case 'm' -> run == 2 ? new Part(Kind.MINUTE, 2, null) : null;
            case 's' -> run == 2 ? new Part(Kind.SECOND, 2, null) : null;
            case 'S' -> run <= 9 ? new Part(Kind.FRACTION, run, null) : null;
            default -> null;
        };
    }

    /** The number of characters every text of this pattern has. */
    int outputLength() {
        return length;
    }

    private boolean has(Kind kind) {
        return parts.stream().anyMatch(p -> p.kind == kind);
    }

    boolean hasDate() {
        return has(Kind.YEAR) && has(Kind.MONTH) && has(Kind.DAY);
    }

    private boolean hasAnyDate() {
        return has(Kind.YEAR) || has(Kind.MONTH) || has(Kind.DAY);
    }

    boolean hasTime() {
        return has(Kind.HOUR) && has(Kind.MINUTE) && has(Kind.SECOND);
    }

    private boolean hasAnyTime() {
        return has(Kind.HOUR) || has(Kind.MINUTE) || has(Kind.SECOND) || has(Kind.FRACTION);
    }

    /** Why the pattern does not suit a DATE field, or empty if it does: exactly year, month and day. */
    Optional<String> problemForDate() {
        return hasDate() && !hasAnyTime() ? Optional.empty() : Optional.of("a date pattern needs year, month and day, and no time parts");
    }

    /** Why the pattern does not suit a TIME field, or empty if it does: hour, minute and second, fraction optional. */
    Optional<String> problemForTime() {
        return hasTime() && !hasAnyDate() ? Optional.empty() : Optional.of("a time pattern needs hour, minute and second, and no date parts");
    }

    /** Why the pattern does not suit a DATETIME field, or empty if it does: year to second, fraction optional. */
    Optional<String> problemForDateTime() {
        return hasDate() && hasTime() ? Optional.empty() : Optional.of("a date-time pattern needs year, month, day, hour, minute and second");
    }

    // ------------------------------------------------------------------ formatting

    /** The text for a date, or empty if the year does not fit four digits. */
    Optional<String> format(LocalDate date) {
        return format(date, null);
    }

    /** The text for a time, or empty if it has more fraction digits than the pattern holds. */
    Optional<String> format(LocalTime time) {
        return format(null, time);
    }

    /** The text for a date-time, or empty if a part does not fit. */
    Optional<String> format(LocalDateTime dateTime) {
        return format(dateTime.toLocalDate(), dateTime.toLocalTime());
    }

    private Optional<String> format(LocalDate date, LocalTime time) {
        StringBuilder out = new StringBuilder(length);
        for (Part part : parts) {
            switch (part.kind) {
                case LITERAL -> out.append(part.literal);
                case YEAR -> {
                    if (date.getYear() < 0 || date.getYear() > 9999) {
                        return Optional.empty();
                    }
                    digits(out, date.getYear(), 4);
                }
                case MONTH -> digits(out, date.getMonthValue(), 2);
                case DAY -> digits(out, date.getDayOfMonth(), 2);
                case HOUR -> digits(out, time.getHour(), 2);
                case MINUTE -> digits(out, time.getMinute(), 2);
                case SECOND -> digits(out, time.getSecond(), 2);
                case FRACTION -> {
                    String nanos = String.format("%09d", time.getNano());
                    if (!nanos.substring(part.width).chars().allMatch(ch -> ch == '0')) {
                        return Optional.empty();
                    }
                    out.append(nanos, 0, part.width);
                }
            }
        }
        if (time != null && !has(Kind.FRACTION) && time.getNano() != 0) {
            return Optional.empty();
        }
        return Optional.of(out.toString());
    }

    private static void digits(StringBuilder out, int value, int width) {
        String text = Integer.toString(value);
        out.append("0".repeat(width - text.length())).append(text);
    }

    // ------------------------------------------------------------------ parsing

    private record Fields(int year, int month, int day, int hour, int minute, int second, int nano) {
    }

    private Optional<Fields> fields(String text) {
        if (text.length() != length) {
            return Optional.empty();
        }
        int year = 0;
        int month = 1;
        int day = 1;
        int hour = 0;
        int minute = 0;
        int second = 0;
        int nano = 0;
        int at = 0;
        for (Part part : parts) {
            if (part.kind == Kind.LITERAL) {
                if (!text.startsWith(part.literal, at)) {
                    return Optional.empty();
                }
                at += part.literal.length();
                continue;
            }
            int value = 0;
            for (int k = 0; k < part.width; k++) {
                char ch = text.charAt(at + k);
                if (ch < '0' || ch > '9') {
                    return Optional.empty();
                }
                value = value * 10 + (ch - '0');
            }
            switch (part.kind) {
                case YEAR -> year = value;
                case MONTH -> month = value;
                case DAY -> day = value;
                case HOUR -> hour = value;
                case MINUTE -> minute = value;
                case SECOND -> second = value;
                default -> nano = value * (int) Math.pow(10, 9 - part.width);
            }
            at += part.width;
        }
        return Optional.of(new Fields(year, month, day, hour, minute, second, nano));
    }

    Optional<LocalDate> parseDate(String text) {
        return fields(text).flatMap(f -> {
            try {
                return Optional.of(LocalDate.of(f.year, f.month, f.day));
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        });
    }

    Optional<LocalTime> parseTime(String text) {
        return fields(text).flatMap(f -> {
            try {
                return Optional.of(LocalTime.of(f.hour, f.minute, f.second, f.nano));
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        });
    }

    Optional<LocalDateTime> parseDateTime(String text) {
        return fields(text).flatMap(f -> {
            try {
                return Optional.of(LocalDateTime.of(f.year, f.month, f.day, f.hour, f.minute, f.second, f.nano));
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        });
    }
}

package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.SemanticType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the {@code example} text of an overlay as the API value a client would send: integers and decimals in plain
 * digits, {@code true} or {@code false}, ISO-8601 dates, times and date-times, Base64 for binary, text as it is.
 */
final class ExampleParser {

    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    private ExampleParser() {
    }

    static Optional<ApiValue> parse(String text, SemanticType semantic) {
        try {
            return switch (semantic) {
                case STRING -> Optional.of(new ApiValue.Text(text));
                case INTEGER -> INTEGER.matcher(text).matches() ? Optional.of(new ApiValue.Integer(new BigInteger(text))) : Optional.empty();
                case DECIMAL -> DECIMAL.matcher(text).matches() ? Optional.of(new ApiValue.Decimal(new BigDecimal(text))) : Optional.empty();
                case BOOLEAN -> text.equals("true") ? Optional.of(new ApiValue.Bool(true))
                        : text.equals("false") ? Optional.of(new ApiValue.Bool(false)) : Optional.empty();
                case DATE -> Optional.of(new ApiValue.Date(LocalDate.parse(text)));
                case TIME -> Optional.of(new ApiValue.Time(LocalTime.parse(text)));
                case DATETIME -> Optional.of(new ApiValue.DateTime(LocalDateTime.parse(text)));
                case BINARY -> Optional.of(new ApiValue.Binary(Base64.getDecoder().decode(text)));
            };
        } catch (DateTimeException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

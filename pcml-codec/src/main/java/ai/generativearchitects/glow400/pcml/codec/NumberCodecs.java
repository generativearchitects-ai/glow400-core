package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.SemanticType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/** Codecs for the numeric wire types: {@code int}, {@code packed} and {@code zoned}, {@code float}. */
final class NumberCodecs {

    private static final Pattern INTEGER_TEXT = Pattern.compile("-?[0-9]+");
    private static final Pattern DECIMAL_TEXT = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    private NumberCodecs() {
    }

    private static void wrongKind(Where where, Problems problems, String expected) {
        problems.add("VAL002", where, "The value is not " + expected);
    }

    // ------------------------------------------------------------------ int

    /** An {@code int} field: 2, 4 or 8 bytes, signed or unsigned by its {@code precision}. */
    static final class IntCodec implements ScalarCodec {
        private final BigInteger min;
        private final BigInteger max;

        IntCodec(int lengthBytes, boolean unsigned) {
            int bits = lengthBytes * 8;
            this.min = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
            this.max = unsigned ? BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE) : BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE);
        }

        @Override
        public Optional<WireValue> absent() {
            return Optional.of(blank());
        }

        @Override
        public WireValue blank() {
            return new WireValue.Integer(BigInteger.ZERO);
        }

        @Override
        public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
            if (!(value instanceof ApiValue.Integer i)) {
                wrongKind(where, problems, "a whole number");
                return Optional.empty();
            }
            return fit(i.value(), where, problems);
        }

        private Optional<WireValue> fit(BigInteger number, Where where, Problems problems) {
            if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
                problems.add("VAL003", where, "The number is outside the range of the field: " + min + " to " + max);
                return Optional.empty();
            }
            return Optional.of(new WireValue.Integer(number));
        }

        @Override
        public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
            if (!INTEGER_TEXT.matcher(text).matches()) {
                problems.add("VAL004", where, "The injected text is not a whole number");
                return Optional.empty();
            }
            return fit(new BigInteger(text), where, problems);
        }

        @Override
        public boolean supportsInjection() {
            return true;
        }

        @Override
        public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
            if (!(value instanceof WireValue.Integer i)) {
                problems.add("VAL101", where, "A whole number was expected");
                return Optional.empty();
            }
            return Optional.of(new ApiValue.Integer(i.value()));
        }

        @Override
        public boolean isBlank(WireValue value) {
            return value instanceof WireValue.Blank || value instanceof WireValue.Integer i && i.value().signum() == 0;
        }
    }

    // ------------------------------------------------------------------ packed and zoned

    /** A {@code packed} or {@code zoned} field: {@code length} digits of which {@code scale} are decimals. */
    static final class DecimalCodec implements ScalarCodec {
        private final int length;
        private final int scale;
        private final SemanticType semantic;

        DecimalCodec(int length, int scale, SemanticType semantic) {
            this.length = length;
            this.scale = scale;
            this.semantic = semantic;
        }

        @Override
        public Optional<WireValue> absent() {
            return Optional.of(blank());
        }

        @Override
        public WireValue blank() {
            return new WireValue.Decimal(BigDecimal.ZERO.setScale(scale));
        }

        @Override
        public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
            BigDecimal number;
            if (semantic == SemanticType.INTEGER && value instanceof ApiValue.Integer i) {
                number = new BigDecimal(i.value());
            } else if (semantic == SemanticType.DECIMAL && value instanceof ApiValue.Decimal d) {
                number = d.value();
            } else if (semantic == SemanticType.DECIMAL && value instanceof ApiValue.Integer i) {
                number = new BigDecimal(i.value());
            } else {
                wrongKind(where, problems, semantic == SemanticType.INTEGER ? "a whole number" : "a number");
                return Optional.empty();
            }
            return fit(number, where, problems);
        }

        private Optional<WireValue> fit(BigDecimal number, Where where, Problems problems) {
            BigDecimal stripped = number.stripTrailingZeros();
            int decimals = Math.max(stripped.scale(), 0);
            if (decimals > scale) {
                problems.add("VAL003", where, "The number has more decimal places than the field holds: at most " + scale);
                return Optional.empty();
            }
            int integerDigits = number.signum() == 0 ? 0 : stripped.precision() - stripped.scale();
            if (integerDigits > length - scale) {
                problems.add("VAL003", where, "The number has too many digits: at most " + (length - scale) + " before the decimal point");
                return Optional.empty();
            }
            return Optional.of(new WireValue.Decimal(number.setScale(scale, RoundingMode.UNNECESSARY)));
        }

        @Override
        public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
            if (!DECIMAL_TEXT.matcher(text).matches()) {
                problems.add("VAL004", where, "The injected text is not a number");
                return Optional.empty();
            }
            return fit(new BigDecimal(text), where, problems);
        }

        @Override
        public boolean supportsInjection() {
            return true;
        }

        @Override
        public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
            if (!(value instanceof WireValue.Decimal d)) {
                problems.add("VAL101", where, "A decimal number was expected");
                return Optional.empty();
            }
            if (semantic == SemanticType.INTEGER) {
                try {
                    return Optional.of(new ApiValue.Integer(d.value().toBigIntegerExact()));
                } catch (ArithmeticException e) {
                    problems.add("VAL102", where, "The number cannot be read as a whole number");
                    return Optional.empty();
                }
            }
            return Optional.of(new ApiValue.Decimal(d.value()));
        }

        @Override
        public boolean isBlank(WireValue value) {
            return value instanceof WireValue.Blank || value instanceof WireValue.Decimal d && d.value().signum() == 0;
        }
    }

    // ------------------------------------------------------------------ float

    /** A {@code float} field of 4 or 8 bytes. */
    static final class FloatCodec implements ScalarCodec {
        private final boolean single;

        FloatCodec(int lengthBytes) {
            this.single = lengthBytes == 4;
        }

        @Override
        public Optional<WireValue> absent() {
            return Optional.of(blank());
        }

        @Override
        public WireValue blank() {
            return new WireValue.Float(0.0);
        }

        @Override
        public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
            BigDecimal number;
            if (value instanceof ApiValue.Decimal d) {
                number = d.value();
            } else if (value instanceof ApiValue.Integer i) {
                number = new BigDecimal(i.value());
            } else {
                wrongKind(where, problems, "a number");
                return Optional.empty();
            }
            return fit(number, where, problems);
        }

        private Optional<WireValue> fit(BigDecimal number, Where where, Problems problems) {
            double d = number.doubleValue();
            if (Double.isInfinite(d) || single && Float.isInfinite((float) d)) {
                problems.add("VAL003", where, "The number is too large for the field");
                return Optional.empty();
            }
            return Optional.of(new WireValue.Float(d));
        }

        @Override
        public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
            if (!DECIMAL_TEXT.matcher(text).matches()) {
                problems.add("VAL004", where, "The injected text is not a number");
                return Optional.empty();
            }
            return fit(new BigDecimal(text), where, problems);
        }

        @Override
        public boolean supportsInjection() {
            return true;
        }

        @Override
        public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
            if (!(value instanceof WireValue.Float f)) {
                problems.add("VAL101", where, "A floating point number was expected");
                return Optional.empty();
            }
            if (Double.isNaN(f.value()) || Double.isInfinite(f.value())) {
                problems.add("VAL102", where, "The number is not finite");
                return Optional.empty();
            }
            BigDecimal exact = single ? new BigDecimal(Float.toString((float) f.value())) : BigDecimal.valueOf(f.value());
            return Optional.of(new ApiValue.Decimal(exact));
        }

        @Override
        public boolean isBlank(WireValue value) {
            return value instanceof WireValue.Blank || value instanceof WireValue.Float f && f.value() == 0.0;
        }
    }
}

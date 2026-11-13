package ai.generativearchitects.glow400.pcml.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Seeded random values for the property tests of the codec: one generator per kind of value and per field shape.
 *
 * <p>A generator belongs to one case of one property. Its randomness comes from the seed and the case index alone, so a
 * failure that reports both can be reproduced exactly. The first cases of every property draw the boundary values of each
 * generator in turn (empty, exactly at the limit, one over, zero, maximum digits, first and last valid date), and the
 * remaining cases are random. There is no shrinking.
 */
final class Gen {

    static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final String LETTERS_AND_SPACES = LETTERS + "   ";

    private final Random random;
    private final int caseIndex;

    Gen(long seed, int caseIndex) {
        this.random = new Random(seed ^ (caseIndex * 0x9E3779B97F4A7C15L));
        this.caseIndex = caseIndex;
    }

    int caseIndex() {
        return caseIndex;
    }

    /** The boundary value for this case if there is one left, otherwise a random value. */
    <T> T boundaryOrElse(List<T> boundaries, Supplier<T> random) {
        return caseIndex < boundaries.size() ? boundaries.get(caseIndex) : random.get();
    }

    // ---- shapes

    <T> T pick(List<T> choices) {
        return choices.get(random.nextInt(choices.size()));
    }

    int intIn(int min, int max) {
        return boundaryOrElse(List.of(min, max, Math.max(min, Math.min(max, 0)), Math.min(max, min + 1)), () -> min + random.nextInt(max - min + 1));
    }

    boolean bool() {
        return boundaryOrElse(List.of(false, true), random::nextBoolean);
    }

    // ---- text

    /** A text of {@code min} to {@code max} characters from an alphabet. */
    String text(String alphabet, int min, int max) {
        return boundaryOrElse(List.of(repeat(alphabet, min), repeat(alphabet, max)), () -> randomText(alphabet, min + random.nextInt(max - min + 1)));
    }

    /**
     * A text around a field's limit: empty, exactly the limit, one over the limit, then any length up to the limit plus
     * {@code extra}.
     */
    String textAround(String alphabet, int limit, int extra) {
        return boundaryOrElse(List.of("", repeat(alphabet, limit), repeat(alphabet, limit + 1)), () -> randomText(alphabet, random.nextInt(limit + extra + 1)));
    }

    private String randomText(String alphabet, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private static String repeat(String alphabet, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(i % alphabet.length()));
        }
        return text.toString();
    }

    // ---- numbers

    /** A whole number from 0 up to {@code digits} digits: 0, 1, the largest one, then random. */
    BigInteger digits(int digits) {
        BigInteger largest = BigInteger.TEN.pow(digits).subtract(BigInteger.ONE);
        return boundaryOrElse(List.of(BigInteger.ZERO, BigInteger.ONE, largest, BigInteger.TEN.pow(digits - 1)),
                () -> new BigInteger(largest.bitLength() + 1, random).mod(largest.add(BigInteger.ONE)));
    }

    /** A whole number of up to {@code digits} digits, negative or not. */
    BigInteger signedDigits(int digits) {
        BigInteger value = digits(digits);
        return caseIndex >= 4 && random.nextBoolean() ? value.negate() : value;
    }

    /** A decimal of up to {@code digits} digits with the given scale. */
    BigDecimal decimal(int digits, int scale) {
        return new BigDecimal(digits(digits), scale);
    }

    long longValue() {
        return boundaryOrElse(List.of(0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, 1L), random::nextLong);
    }

    /** A finite double. */
    double finiteDouble() {
        return boundaryOrElse(List.of(0.0, 1.0, -1.0, Double.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE), () -> {
            double d;
            do {
                d = Double.longBitsToDouble(random.nextLong());
            } while (Double.isNaN(d) || Double.isInfinite(d));
            return d;
        });
    }

    /** A finite float. */
    float finiteFloat() {
        return boundaryOrElse(List.of(0.0f, 1.0f, -1.0f, Float.MAX_VALUE, Float.MIN_VALUE, -Float.MAX_VALUE), () -> {
            float f;
            do {
                f = Float.intBitsToFloat(random.nextInt());
            } while (Float.isNaN(f) || Float.isInfinite(f));
            return f;
        });
    }

    // ---- dates and times

    /** A date from 0000-01-01 to 9999-12-31, the years a four-digit year can hold. */
    LocalDate date() {
        long first = LocalDate.of(0, 1, 1).toEpochDay();
        long last = LocalDate.of(9999, 12, 31).toEpochDay();
        return boundaryOrElse(List.of(LocalDate.of(0, 1, 1), LocalDate.of(9999, 12, 31), LocalDate.of(2024, 2, 29), LocalDate.of(2026, 1, 1)),
                () -> LocalDate.ofEpochDay(first + (long) (random.nextDouble() * (last - first + 1))));
    }

    /** A time of day in whole milliseconds. */
    LocalTime time() {
        return boundaryOrElse(List.of(LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_000_000), LocalTime.NOON, LocalTime.of(0, 0, 0, 1_000_000)),
                () -> LocalTime.ofNanoOfDay(random.nextInt(24 * 3600 * 1000) * 1_000_000L));
    }

    LocalDateTime dateTime() {
        return LocalDateTime.of(date(), time());
    }

    // ---- bytes

    byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        if (caseIndex == 0) {
            java.util.Arrays.fill(bytes, (byte) 0);
        } else if (caseIndex == 1) {
            java.util.Arrays.fill(bytes, (byte) 0xFF);
        }
        return bytes;
    }
}

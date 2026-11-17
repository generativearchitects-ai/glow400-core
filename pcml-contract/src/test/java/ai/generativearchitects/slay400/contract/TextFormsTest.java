package ai.generativearchitects.slay400.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The written forms, and the decimal form by the limits of a field. */
class TextFormsTest {

    private static boolean matches(String form, String text) {
        return Pattern.compile(form).matcher(text).matches();
    }

    private static Limits packed(int digits, int scale) {
        return new Limits(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(digits), Optional.of(scale),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void aWholeNumberIsAnOptionalMinusAndDigitsAndNothingElse() {
        assertThat(matches(TextForms.INTEGER, "0")).isTrue();
        assertThat(matches(TextForms.INTEGER, "-42")).isTrue();
        assertThat(matches(TextForms.INTEGER, "007")).isTrue();
        for (String bad : new String[] {"", "-", "+1", "1.0", "1e3", " 1", "1 ", "1\n", "١", "--1", "1-"}) {
            assertThat(matches(TextForms.INTEGER, bad)).as("[" + bad + "]").isFalse();
        }
    }

    @Test
    void aDateHasAFourDigitYearAndTwoDigitMonthAndDay() {
        assertThat(matches(TextForms.DATE, "2026-11-17")).isTrue();
        for (String bad : new String[] {"", "26-11-17", "2026-1-17", "2026-11-7", "20261117", "+2026-11-17", "2026-11-17T10:20:30", "2026-11-17\n"}) {
            assertThat(matches(TextForms.DATE, bad)).as("[" + bad + "]").isFalse();
        }
    }

    @Test
    void aTimeNeedsSecondsAndHasAnOptionalFractionOfOneToNineDigits() {
        for (String good : new String[] {"10:20:30", "00:00:00", "23:59:59.1", "10:20:30.123456789"}) {
            assertThat(matches(TextForms.TIME, good)).as(good).isTrue();
        }
        for (String bad : new String[] {"", "10:20", "1:20:30", "10:20:30.", "10:20:30.1234567890", "10:20:30Z", "10:20:30+01:00", "10:20:30\n"}) {
            assertThat(matches(TextForms.TIME, bad)).as("[" + bad + "]").isFalse();
        }
    }

    @Test
    void aDateTimeIsADateATAndATimeWithoutAZone() {
        for (String good : new String[] {"2026-11-17T10:20:30", "2026-11-17T10:20:30.5", "2026-11-17T10:20:30.123456789"}) {
            assertThat(matches(TextForms.DATETIME, good)).as(good).isTrue();
        }
        for (String bad : new String[] {"", "2026-11-17", "2026-11-17 10:20:30", "2026-11-17T10:20", "2026-11-17T10:20:30Z", "2026-11-17t10:20:30",
                "2026-11-17T10:20:30.1234567890", "2026-11-17T10:20:30\n"}) {
            assertThat(matches(TextForms.DATETIME, bad)).as("[" + bad + "]").isFalse();
        }
    }

    @Test
    void aPackedOrZonedDecimalAllowsUpToItsScaleAndTrailingZerosBeyondIt() {
        String form = TextForms.decimal(packed(9, 2));
        assertThat(form).isEqualTo("^-?\\d{1,7}(\\.\\d{1,2}0*)?$");
        for (String good : new String[] {"0", "-0", "1234567", "-1234567", "1.5", "1.55", "1.500", "1.5500", "0.05", "007.5"}) {
            assertThat(matches(form, good)).as(good).isTrue();
        }
        for (String bad : new String[] {"", "12345678", "1.", ".5", "1.555", "+1.5", "1,5", "1.5 ", "1.50\n", "1e2"}) {
            assertThat(matches(form, bad)).as("[" + bad + "]").isFalse();
        }
    }

    @Test
    void aDecimalWithoutDecimalsHasNoFractionAndOneWithNoIntegerDigitsHasTheIntegerPartZero() {
        assertThat(TextForms.decimal(packed(5, 0))).isEqualTo("^-?\\d{1,5}$");
        assertThat(matches(TextForms.decimal(packed(5, 0)), "12345")).isTrue();
        assertThat(matches(TextForms.decimal(packed(5, 0)), "1.0")).isFalse();
        assertThat(TextForms.decimal(packed(3, 3))).isEqualTo("^-?0(\\.\\d{1,3}0*)?$");
        assertThat(matches(TextForms.decimal(packed(3, 3)), "0.123")).isTrue();
        assertThat(matches(TextForms.decimal(packed(3, 3)), "1.123")).isFalse();
    }

    @Test
    void aDecimalHeldAsTextIsUnsignedAndAFloatMayCarryASign() {
        Limits text = new Limits(Optional.empty(), Optional.of(BigInteger.ZERO), Optional.empty(), Optional.of(8), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(TextForms.decimal(text)).isEqualTo("^\\d+(\\.\\d+)?$");
        assertThat(matches(TextForms.decimal(text), "12.5")).isTrue();
        assertThat(matches(TextForms.decimal(text), "-1")).isFalse();
        assertThat(matches(TextForms.decimal(text), "+1")).isFalse();
        assertThat(TextForms.decimal(Limits.none())).isEqualTo("^[-+]?\\d+(\\.\\d+)?$");
        assertThat(matches(TextForms.decimal(Limits.none()), "-1.5")).isTrue();
        assertThat(matches(TextForms.decimal(Limits.none()), "+1.5")).isTrue();
        assertThat(matches(TextForms.decimal(Limits.none()), "1.")).isFalse();
    }

    @Test
    void theLimitsAreRequired() {
        assertThatThrownBy(() -> TextForms.decimal(null)).isInstanceOf(NullPointerException.class).hasMessage("limits");
    }
}

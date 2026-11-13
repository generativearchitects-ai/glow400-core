package ai.generativearchitects.glow400.pcml.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The generator is reproducible, starts with the boundary values, and a failure names its seed and case. */
class GenTest {

    @Test
    void aCaseIsReproducibleFromTheSeedAndTheCaseIndex() {
        for (int index : new int[] {0, 7, 499}) {
            Gen a = new Gen(42L, index);
            Gen b = new Gen(42L, index);
            assertThat(a.text(Gen.LETTERS, 0, 20)).isEqualTo(b.text(Gen.LETTERS, 0, 20));
            assertThat(a.digits(12)).isEqualTo(b.digits(12));
            assertThat(a.date()).isEqualTo(b.date());
            assertThat(a.time()).isEqualTo(b.time());
            assertThat(a.longValue()).isEqualTo(b.longValue());
            assertThat(a.bytes(6)).isEqualTo(b.bytes(6));
        }
        assertThat(new Gen(1L, 100).digits(30)).isNotEqualTo(new Gen(2L, 100).digits(30));
        assertThat(new Gen(1L, 100).digits(30)).isNotEqualTo(new Gen(1L, 101).digits(30));
        assertThat(new Gen(5L, 3).caseIndex()).isEqualTo(3);
    }

    @Test
    void theFirstCasesAreTheBoundaryValues() {
        List<BigInteger> digits = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        List<LocalTime> times = new ArrayList<>();
        List<String> around = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Gen g = new Gen(99L, i);
            digits.add(g.digits(5));
            dates.add(g.date());
            times.add(g.time());
            around.add(g.textAround("ab", 3, 4));
        }
        assertThat(digits).containsExactly(BigInteger.ZERO, BigInteger.ONE, new BigInteger("99999"), new BigInteger("10000"));
        assertThat(dates).contains(LocalDate.of(0, 1, 1), LocalDate.of(9999, 12, 31), LocalDate.of(2024, 2, 29));
        assertThat(times).contains(LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_000_000));
        assertThat(around.subList(0, 3)).containsExactly("", "aba", "abab");
    }

    @Test
    void valuesStayInsideTheirRanges() {
        for (int i = 0; i < 600; i++) {
            Gen g = new Gen(7L, i);
            assertThat(g.text("abc", 2, 5).length()).isBetween(2, 5);
            assertThat(g.textAround("abc", 4, 3).length()).isBetween(0, 7);
            assertThat(g.digits(4)).isBetween(BigInteger.ZERO, new BigInteger("9999"));
            assertThat(g.signedDigits(4).abs()).isLessThanOrEqualTo(new BigInteger("9999"));
            assertThat(g.decimal(5, 2).unscaledValue()).isBetween(BigInteger.ZERO, new BigInteger("99999"));
            assertThat(g.decimal(5, 2).scale()).isEqualTo(2);
            assertThat(g.intIn(3, 9)).isBetween(3, 9);
            assertThat(g.date()).isBetween(LocalDate.of(0, 1, 1), LocalDate.of(9999, 12, 31));
            assertThat(g.time().getNano() % 1_000_000).isZero();
            assertThat(g.dateTime()).isNotNull();
            assertThat(Double.isFinite(g.finiteDouble())).isTrue();
            assertThat(Float.isFinite(g.finiteFloat())).isTrue();
            assertThat(g.bytes(5)).hasSize(5);
            assertThat(g.pick(List.of("x", "y", "z"))).isIn("x", "y", "z");
        }
        assertThat(new Gen(1L, 0).bytes(3)).containsOnly((byte) 0);
        assertThat(new Gen(1L, 1).bytes(3)).containsOnly((byte) 0xFF);
        assertThat(new Gen(1L, 0).bool()).isFalse();
        assertThat(new Gen(1L, 1).bool()).isTrue();
        assertThat(new Gen(1L, 0).intIn(2, 8)).isEqualTo(2);
        assertThat(new Gen(1L, 1).intIn(2, 8)).isEqualTo(8);
        assertThat(new Gen(1L, 0).longValue()).isZero();
        assertThat(new Gen(1L, 0).text("ab", 0, 5)).isEmpty();
        assertThat(new Gen(1L, 1).text("ab", 0, 5)).isEqualTo("ababa");
    }

    @Test
    void propertiesRunTheRequestedNumberOfCasesEachWithItsOwnGenerator() {
        List<Integer> seen = new ArrayList<>();
        Properties.check("count", 123L, 40, g -> seen.add(g.caseIndex()));
        assertThat(seen).hasSize(40).first().isEqualTo(0);
        assertThat(seen.get(39)).isEqualTo(39);
        assertThat(Properties.DEFAULT_CASES).isEqualTo(500);
    }

    @Test
    void aFailureNamesTheSeedAndTheCaseIndexSoThatItCanBeReproduced() {
        assertThatThrownBy(() -> Properties.check("always fails at case 3", 777L, 10, g -> {
            if (g.caseIndex() == 3) {
                throw new IllegalStateException("boom");
            }
        })).isInstanceOf(AssertionError.class)
                .hasMessageContaining("seed 777").hasMessageContaining("case 3").hasMessageContaining("-Dglow400.seed=777")
                .hasMessageContaining("always fails at case 3").hasMessageContaining("boom").hasCauseInstanceOf(IllegalStateException.class);
    }
}

package ai.generativearchitects.glow400.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Semantic version 2.0.0: parsing and precedence. */
class VersionTest {

    private static int compare(String a, String b) {
        return Integer.signum(Version.parse(a).compareTo(Version.parse(b)));
    }

    @Test
    void numbersAreComparedAsNumbersNotAsText() {
        assertThat(compare("1.2.3", "1.2.3")).isZero();
        assertThat(compare("1.10.0", "1.9.0")).isEqualTo(1);
        assertThat(compare("2.0.0", "10.0.0")).isEqualTo(-1);
        assertThat(compare("1.0.10", "1.0.9")).isEqualTo(1);
        assertThat(compare("0.9.0", "1.0.0")).isEqualTo(-1);
        assertThat(compare("99999999999999999999.0.0", "99999999999999999998.0.0")).isEqualTo(1);
    }

    @Test
    void aPreReleaseIsLowerThanItsReleaseAndIdentifiersFollowTheSpecification() {
        List<String> ascending = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");
        for (int i = 0; i < ascending.size(); i++) {
            for (int j = 0; j < ascending.size(); j++) {
                assertThat(compare(ascending.get(i), ascending.get(j))).as(ascending.get(i) + " vs " + ascending.get(j)).isEqualTo(Integer.compare(i, j));
            }
        }
    }

    @Test
    void buildMetadataDoesNotCount() {
        assertThat(compare("1.0.0+build.1", "1.0.0+build.2")).isZero();
        assertThat(compare("1.0.0-rc.1+x", "1.0.0-rc.1")).isZero();
        assertThat(Version.parse("1.2.3+meta").toString()).isEqualTo("1.2.3");
        assertThat(Version.parse("1.2.3-rc.1").toString()).isEqualTo("1.2.3-rc.1");
    }

    @Test
    void stabilityStartsAtOneZeroZero() {
        assertThat(Version.parse("1.0.0").isStable()).isTrue();
        assertThat(Version.parse("0.9.9").isStable()).isFalse();
        assertThat(Version.parse("12.0.0-rc.1").isStable()).isTrue();
    }

    @Test
    void majorMinorAndNumbersComparisons() {
        Version a = Version.parse("1.2.3");
        assertThat(a.compareMajorMinor(Version.parse("1.2.9"))).isZero();
        assertThat(a.compareMajorMinor(Version.parse("1.3.0"))).isNegative();
        assertThat(a.compareMajorMinor(Version.parse("0.9.0"))).isPositive();
        assertThat(a.compareNumbers(Version.parse("1.2.4"))).isNegative();
        assertThat(a.compareNumbers(Version.parse("1.2.3-rc"))).isZero();
    }

    @Test
    void textThatIsNotAVersionIsZero() {
        assertThat(Version.parse("one").toString()).isEqualTo("0.0.0");
        assertThat(Version.parse("1.0").toString()).isEqualTo("0.0.0");
        assertThat(Version.parse("01.0.0").toString()).isEqualTo("0.0.0");
    }
}

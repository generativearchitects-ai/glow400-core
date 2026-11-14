package ai.generativearchitects.glow400.diff;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A semantic version 2.0.0: three numbers and an optional pre-release; build metadata does not count in comparisons. */
record Version(BigInteger major, BigInteger minor, BigInteger patch, String preRelease) implements Comparable<Version> {

    private static final Pattern PATTERN = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

    /** The version of a text; {@code 0.0.0} when it is not a semantic version, which the loader has already refused. */
    static Version parse(String text) {
        Matcher m = PATTERN.matcher(text);
        if (!m.matches()) {
            return new Version(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, "");
        }
        return new Version(new BigInteger(m.group(1)), new BigInteger(m.group(2)), new BigInteger(m.group(3)), m.group(4) == null ? "" : m.group(4));
    }

    /** Whether the version has a pre-release part, such as {@code 2.0.0-rc.1}. */
    boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    /** Whether the version is 1.0.0 or above, where the stable rules apply. */
    boolean isStable() {
        return major.signum() > 0;
    }

    /** The version without its pre-release and build, as compared by the major and minor rules. */
    int compareMajorMinor(Version other) {
        int byMajor = major.compareTo(other.major);
        return byMajor != 0 ? byMajor : minor.compareTo(other.minor);
    }

    int compareNumbers(Version other) {
        int byMajorMinor = compareMajorMinor(other);
        return byMajorMinor != 0 ? byMajorMinor : patch.compareTo(other.patch);
    }

    /** Semantic version precedence: the numbers, then a version with a pre-release is lower than the one without. */
    @Override
    public int compareTo(Version other) {
        int numbers = compareNumbers(other);
        if (numbers != 0) {
            return numbers;
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return preRelease.isEmpty() == other.preRelease.isEmpty() ? 0 : preRelease.isEmpty() ? 1 : -1;
        }
        String[] mine = preRelease.split("\\.");
        String[] theirs = other.preRelease.split("\\.");
        for (int i = 0; i < Math.min(mine.length, theirs.length); i++) {
            int c = compareIdentifier(mine[i], theirs[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(mine.length, theirs.length);
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNumeric = a.chars().allMatch(Character::isDigit);
        boolean bNumeric = b.chars().allMatch(Character::isDigit);
        if (aNumeric && bNumeric) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (aNumeric != bNumeric) {
            return aNumeric ? -1 : 1;
        }
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
